"""S4: 공유 PostgreSQL 일시 중단과 전체 서비스 복구. Kafka 장애를 사용하지 않는다.

python infra/integration/scenario4.py --rate 5 --baseline 60 --outage 25 --recovery 180
--dry-run은 대상/계획만 확인하며 데이터 생성·DB 중지를 하지 않는다.
"""
import argparse
from collections import Counter, defaultdict
from datetime import datetime
import json
import math
import subprocess
import threading
import time

from scenario1 import Failure, require, ROOT, KST
from scenario2 import MixedScenario

SERVICES=('user','performance','seat','booking','payment','fakepg')
APPS=('gateway-service','user-service','performance-service','reservation-service','payment-service','fake-pg')

def validate_database_target(target):
    require(target.get('project')=='ticket-integration' and target.get('service')=='postgres',
            'Refusing DB fault: target must be ticket-integration/postgres')
    require(target.get('running') is True,'Integration PostgreSQL must already be running')
    require(bool(target.get('id')),'Missing exact database container ID')
    return target['id']

def percentile(values,p):
    if not values:return None
    a=sorted(values);index=(len(a)-1)*p;lo=math.floor(index);hi=math.ceil(index)
    return a[lo]+(a[hi]-a[lo])*(index-lo)

class DatabaseFault(MixedScenario):
    def __init__(self,args):
        self.lock=threading.RLock();self.db_lock=threading.Lock();self.cancel=threading.Event()
        self.requests=[];self.controller=None;self.restore_needed=False;self.db_id=None;self.phase=0
        super().__init__(args,scenario=4)
        self.container_name='ticket-integration-s4-load'
        self.metrics_path=ROOT/'script/integration/scenario4-metrics.prom'
        self.report.update(timeline={k:None for k in ('measure_start','stop_begin','stop_complete','start_begin','start_complete','query_ready')},
            recovery={},fault_error=None,
            fault_plan={'baseline_seconds':args.baseline,'outage_seconds_after_stop':args.outage,
                'recovery_observation_seconds':args.recovery,'docker_stop_grace_seconds':5,
                'method':'docker stop/start same container; graceful stop, not abrupt crash/WAL corruption test'},
            recovery_criterion={'window_seconds':args.recovery_window,'minimum_success_rate':args.recovery_success_rate,
                'probe_p95_ms_limit':args.recovery_p95_ms,'scope':'continuous preauthenticated API/health probes; pools/threads archived separately'},
            limitations=['DB outage errors are expected and are not gated by the normal S2 error threshold',
                'API probes via Gateway can fail at Gateway/user validation before the target service; failures do not prove a process crashed',
                'Login-per-journey traffic and preauthenticated diagnostic probes are reported separately',
                'Payment/FakePG probes use internal health; other probes use Gateway API paths',
                'Abandoned unpaid HOLD/READY records are retained until normal expiry; they are not called financial completion',
                'Metrics export is selected series, not a complete Prometheus snapshot'])

    def save(self):
        if not hasattr(self,'out'):return
        with self.lock:
            self.report['request_evidence_count']=len(self.requests)
            (self.out/'result.json').write_text(json.dumps(self.report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
            lines=[f'integration_scenario4_phase {self.phase}',f'integration_scenario4_verified {int(self.report["verdict"]=="PASS")}',
                   f'integration_scenario4_updated_timestamp_seconds {time.time()}']
            for service,record in self.report.get('recovery',{}).items():
                if record.get('seconds_after_query_ready') is not None:
                    lines.append(f'integration_scenario4_recovery_seconds{{service="{service}"}} {record["seconds_after_query_ready"]}')
            tmp=self.metrics_path.with_suffix('.tmp');tmp.write_text('\n'.join(lines)+'\n',encoding='utf-8');tmp.replace(self.metrics_path)

    def docker(self,*args,timeout=20):
        p=subprocess.run(['docker',*args],capture_output=True,text=True,timeout=timeout)
        require(p.returncode==0,'Docker operation failed: '+args[0])
        return p.stdout

    def target(self):
        fmt='{"id":{{json .Id}},"project":{{json (index .Config.Labels "com.docker.compose.project")}},"service":{{json (index .Config.Labels "com.docker.compose.service")}},"running":{{.State.Running}},"mounts":{{json .Mounts}}}'
        d=json.loads(self.docker('inspect','--format',fmt,'ticket-integration-postgres-1'))
        self.db_id=validate_database_target(d);self.report['database_target']=d
        return d

    def app_states(self):
        fmt='{"name":{{json .Name}},"started_at":{{json .State.StartedAt}},"restarts":{{.RestartCount}},"running":{{.State.Running}}}'
        output=self.docker('inspect','--format',fmt,*['ticket-integration-'+s+'-1' for s in APPS])
        return [json.loads(line) for line in output.splitlines()]

    def prepare(self):
        super().prepare()
        file=self.out/'input.json';data=json.loads(file.read_text(encoding='utf-8'))
        data['meta'].update(mode='db_fault')
        file.write_text(json.dumps(data),encoding='utf-8')

    def on_request(self,row):
        with self.lock:
            self.requests.append(row)
            with (self.out/'request-evidence.jsonl').open('a',encoding='utf-8') as f:f.write(json.dumps(row)+'\n')

    def restore_database(self):
        with self.db_lock:
            if self.restore_needed:
                self.report['timeline']['start_begin']=time.time();self.phase=4;self.save()
                self.docker('start',self.db_id)
                self.report['timeline']['start_complete']=time.time()
                self.restore_needed=False;self.save()

    def sql_ready(self):
        try:
            out=self.docker('exec',self.db_id,'psql','-U',self.env['POSTGRES_USER'],'-d','ticket','-XAt',
                            '-v','ON_ERROR_STOP=1','-c','SELECT 1',timeout=4)
            return out.strip()=='1'
        except (Failure,subprocess.SubprocessError):return False

    def baseline_healthy(self):
        with self.lock:rows=list(self.requests)
        for service in SERVICES:
            recent=[r for r in rows if r['source']=='probe' and r['service']==service][-3:]
            if len(recent)<3 or not all(r['ok'] for r in recent):return False
        return True

    def fault_controller(self):
        try:
            while not self.cancel.wait(.1):
                windows=self.report.get('measurement_windows',{})
                main=[v['start'] for k,v in windows.items() if k in ('browse','seat','booking')]
                if main:break
            else:return
            start=min(main);self.report['timeline']['measure_start']=start;self.phase=1;self.save()
            if self.cancel.wait(max(0,start+self.args.baseline-time.time())):return
            require(self.baseline_healthy(),'Baseline probes not healthy; DB fault was not injected')
            print('[fault] Stopping integration PostgreSQL; volume retained',flush=True)
            with self.db_lock:
                # stop 응답을 잃어도 finally에서 복구하도록 호출 전에 표시한다.
                self.restore_needed=True;self.phase=2
                self.report['timeline']['stop_begin']=time.time();self.save()
                self.docker('stop','--time','5',self.db_id)
                self.report['timeline']['stop_complete']=time.time();self.phase=3;self.save()
            if self.cancel.wait(self.args.outage):return
            print('[fault] Starting the same PostgreSQL container',flush=True)
            self.restore_database()
            deadline=time.monotonic()+self.args.recovery
            while not self.cancel.is_set() and time.monotonic()<deadline:
                if self.sql_ready():
                    self.report['timeline']['query_ready']=time.time();self.save()
                    print('[recovery] SELECT 1 succeeded; API recovery continues under load',flush=True)
                    return
                self.cancel.wait(1)
            raise Failure('PostgreSQL did not become query-ready within the observation limit')
        except Exception as e:
            with self.lock:self.report['fault_error']=str(e) if isinstance(e,Failure) else type(e).__name__
        finally:
            try:self.restore_database()
            except Exception as e:
                self.report['restore_error']=type(e).__name__
                print('[CRITICAL] Check integration PostgreSQL running state; automatic start failed',flush=True)
            if self.report.get('fault_error') and self.process and self.process.poll() is None:
                try:self.docker('stop','--time','10',self.container_name)
                except (Failure,subprocess.SubprocessError):pass
            self.save()

    def period(self,epoch):
        t=self.report['timeline']
        if t['stop_begin'] is None or epoch<t['stop_begin']:return 'baseline'
        if t['query_ready'] is None or epoch<t['query_ready']:return 'outage_and_startup'
        return 'recovery'

    def recovery_review(self):
        ready=self.report['timeline']['query_ready']
        groups=defaultdict(list)
        for row in self.requests:
            if row['start_epoch']<(self.report['timeline']['measure_start'] or float('inf')):continue
            groups[(self.period(row['start_epoch']),row['source'],row['service'])].append(row)
        self.report['request_summary']=[{'period':period,'source':source,'service':service,'count':len(rows),
            'success_rate':sum(r['ok'] for r in rows)/len(rows),
            'status_counts':dict(Counter(str(r['status']) for r in rows)),
            'p95_ms':percentile([1000*(r['end_epoch']-r['start_epoch']) for r in rows],.95),
            'p99_ms':percentile([1000*(r['end_epoch']-r['start_epoch']) for r in rows],.99)}
            for (period,source,service),rows in groups.items()]
        for service in SERVICES:
            rows=sorted((r for r in self.requests if r['source']=='probe' and r['service']==service and ready is not None and r['start_epoch']>=ready and r['end_epoch']<=ready+self.args.recovery),key=lambda r:r['end_epoch'])
            recovered=None
            for i,last in enumerate(rows):
                window=[r for r in rows[:i+1] if r['start_epoch']>=last['end_epoch']-self.args.recovery_window]
                if len(window)<max(3,self.args.recovery_window-1):continue
                if last['end_epoch']-window[0]['start_epoch']<self.args.recovery_window-1:continue
                p95=percentile([1000*(r['end_epoch']-r['start_epoch']) for r in window],.95)
                if sum(r['ok'] for r in window)/len(window)>=self.args.recovery_success_rate and p95<=self.args.recovery_p95_ms:
                    recovered=last['end_epoch'];break
            self.report['recovery'][service]={'recovered_epoch':recovered,
                'seconds_after_query_ready':recovered-ready if recovered is not None else None,
                'probe_samples_after_ready':len(rows)}
        self.report['checks']['all_probes_recovered']=all(r['recovered_epoch'] is not None for r in self.report['recovery'].values())

    def audit(self):
        self.phase=5;self.save();sid=int(self.report['fixtures']['schedule_id'])
        deadline=time.monotonic()+self.args.settle
        while True:
            pending=self.db(f"SELECT (SELECT count(*) FROM reservation_schema.p_reservations WHERE schedule_id={sid} AND status IN ('PAYMENT_IN_PROGRESS','CANCELING'))+(SELECT count(*) FROM payment_schema.p_payments WHERE schedule_id={sid} AND status='IN_PROGRESS')")
            if pending==0 or time.monotonic()>=deadline:break
            time.sleep(2)
        sql=f"""SELECT json_build_object(
          'reservations',(SELECT coalesce(json_agg(x),'[]') FROM (SELECT reservation_id,user_id,order_id,status,payment_amount,(pg_auth_token IS NULL) auth_cleared FROM reservation_schema.p_reservations WHERE schedule_id={sid} AND deleted_at IS NULL) x),
          'tickets',(SELECT coalesce(json_agg(x),'[]') FROM (SELECT t.reservation_id,t.schedule_seat_id,t.price,(t.ticket_code IS NOT NULL) issued FROM reservation_schema.p_tickets t JOIN reservation_schema.p_reservations r USING(reservation_id) WHERE r.schedule_id={sid} AND t.deleted_at IS NULL) x),
          'seats',(SELECT coalesce(json_agg(x),'[]') FROM (SELECT id,status,reservation_id,(hold_expires_at IS NOT NULL) has_expiry FROM reservation_schema.p_schedule_seats WHERE schedule_id={sid}) x),
          'payments',(SELECT coalesce(json_agg(x),'[]') FROM (SELECT id,reservation_id,user_id,seller_id,performance_id,schedule_id,order_id,status,amount,pg_provider FROM payment_schema.p_payments WHERE schedule_id={sid} AND deleted_at IS NULL) x),
          'pg',(SELECT coalesce(json_agg(x),'[]') FROM (SELECT g.order_id,g.status,g.amount,g.canceled_amount FROM fake_pg_schema.pg_transaction g WHERE g.provider='TOSS' AND g.order_id IN(SELECT order_id FROM payment_schema.p_payments WHERE schedule_id={sid})) x));"""
        data=self.db(sql.rstrip(';'))
        (self.out/'financial-verification.json').write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
        violations=[];unfinished=[];unpaid=[];resolutions=[]
        by_user={r['user_id']:r for r in data['reservations']}
        by_res={r['reservation_id']:r for r in data['reservations']}
        for r in data['reservations']:
            rid=r['reservation_id'];tickets=[t for t in data['tickets'] if t['reservation_id']==rid]
            owned=[s for s in data['seats'] if s['reservation_id']==rid]
            payments=[p for p in data['payments'] if p['reservation_id']==rid]
            if len(tickets)!=1 or len(payments)>1:violations.append({'reservation_id':rid,'reason':'ticket/payment count'})
            if r['status']=='CONFIRMED':
                ok=len(tickets)==len(payments)==len(owned)==1 and tickets[0]['issued'] and r['auth_cleared']
                if ok:
                    p=payments[0];pg=[g for g in data['pg'] if g['order_id']==p['order_id']]
                    ok=owned[0]['status']=='SOLD' and not owned[0]['has_expiry'] and owned[0]['id']==tickets[0]['schedule_seat_id'] and \
                       p['status']=='DONE' and p['order_id']==r['order_id'] and p['amount']==r['payment_amount']==tickets[0]['price'] and \
                       p['user_id']==r['user_id'] and p['seller_id']==self.report['fixtures']['seller_id'] and \
                       p['performance_id']==self.report['fixtures']['performance_id'] and len(pg)==1 and pg[0]['status']=='DONE' and \
                       pg[0]['amount']==p['amount'] and not pg[0].get('canceled_amount')
                if not ok:violations.append({'reservation_id':rid,'reason':'confirmed state mismatch'})
            elif r['status']=='HOLD':
                if len(owned)!=1 or owned[0]['status']!='HOLD' or not owned[0]['has_expiry'] or any(t['issued'] for t in tickets) or (len(tickets)==1 and owned[0]['id']!=tickets[0]['schedule_seat_id']):
                    violations.append({'reservation_id':rid,'reason':'hold state mismatch'})
                unpaid.append(rid)
            elif r['status'] in ('PAYMENT_IN_PROGRESS','CANCELING'):unfinished.append(rid)
            elif owned:violations.append({'reservation_id':rid,'reason':'closed reservation still owns seat'})
        for p in data['payments']:
            r=by_res.get(p['reservation_id']);pg=[g for g in data['pg'] if g['order_id']==p['order_id']]
            if r is None:violations.append({'order_id':p['order_id'],'reason':'orphan payment'})
            if p['status']=='IN_PROGRESS':unfinished.append({'order_id':p['order_id'],'state':'payment IN_PROGRESS'})
            if r and (p['user_id']!=r['user_id'] or p['seller_id']!=self.report['fixtures']['seller_id'] or
                p['performance_id']!=self.report['fixtures']['performance_id'] or p['pg_provider']!='TOSS'):
                violations.append({'order_id':p['order_id'],'reason':'payment identity mismatch'})
            if p['status']=='DONE' and (r is None or r['status']!='CONFIRMED'):
                violations.append({'order_id':p['order_id'],'reason':'paid without confirmed reservation'})
            if any(g['status']=='DONE' for g in pg) and p['status']!='DONE':
                violations.append({'order_id':p['order_id'],'reason':'PG charged but payment not DONE'})
            if p['status']=='CANCELED' and not (len(pg)==1 and pg[0]['status']=='CANCELED' and (pg[0]['canceled_amount'] or pg[0]['amount'])==p['amount']):
                violations.append({'order_id':p['order_id'],'reason':'cancel mismatch'})
        for e in self.report['journeys']:
            r=by_user.get(e.get('user_id'))
            if e['outcome']=='confirmed' and (r is None or r['status']!='CONFIRMED' or r['order_id']!=e.get('order_id')):
                violations.append({'user_id':e.get('user_id'),'reason':'acknowledged success missing'})
            if r and e.get('schedule_seat_id') not in [t['schedule_seat_id'] for t in data['tickets'] if t['reservation_id']==r['reservation_id']]:
                violations.append({'user_id':e.get('user_id'),'reason':'assigned seat mismatch'})
            resolutions.append({'user_id':e.get('user_id'),'client_outcome':e['outcome'],
                'client_classification':e.get('client_classification','success' if e['outcome']=='confirmed' else 'explicit_failure'),
                'failed_stage':e.get('stage'),'reservation_id':r['reservation_id'] if r else None,
                'database_status':r['status'] if r else 'NOT_CREATED'})
        self.report['financial_review']={'violations':violations,'unfinished':unfinished,'unpaid_hold_waiting_expiry':unpaid,
            'client_resolutions':resolutions,'note':'No forced expiry/refund/data edits are performed by the test'}
        self.report['checks']['financial_consistency']=not violations and not unfinished

    def review(self):
        path=self.out/'k6-summary.json';require(path.exists(),'k6 summary missing; see k6.log')
        metrics=json.loads(path.read_text(encoding='utf-8'))['metrics']
        count=lambda name:metrics.get(name,{}).get('values',{}).get('count',0)
        expected=self.args.rate*self.args.duration
        actual=sum(count(k+'_started') for k in ('browse','seat','booking'))
        self.report['measurements']={'expected_journeys':expected,'actual_started':actual,
            'dropped_iterations':count('dropped_iterations'),'booking_started':count('booking_started'),
            'client_booking_outcomes':dict(Counter(e['outcome'] for e in self.report['journeys']))}
        self.report['checks'].update(generator_valid=self.report.get('k6_exit_code')==0 and count('dropped_iterations')==0 and abs(actual-expected)<=3,
            booking_evidence_complete=len(self.report['journeys'])==count('booking_started') and not self.report.get('evidence_parse_errors'),
            fault_injected_and_db_ready=all(self.report['timeline'][k] is not None for k in ('stop_begin','stop_complete','start_begin','start_complete','query_ready')) and not self.report['fault_error'])
        self.recovery_review();self.audit()
        after=self.app_states();self.report['app_states_after']=after
        self.report['checks']['no_application_restart']=after==self.report['app_states_before']
        current=json.loads(self.docker('inspect','--format','{{json .Mounts}}',self.db_id))
        self.report['checks']['database_mounts_unchanged']=current==self.report['database_target']['mounts']
        self.report['verdict']='PASS' if all(self.report['checks'].values()) else 'FAIL'

    def execute(self):
        try:
            self.save();self.preflight();self.target()
            other=self.docker('ps','-q','--filter','label=com.docker.compose.project=ticket-integration','--filter','label=com.docker.compose.service=k6')
            require(not other.strip(),'Another integration k6 run is active; do not inject a shared DB fault')
            if self.args.dry_run:
                self.report['verdict']='DRY_RUN';print('Target validated; no fixtures or DB stop/start performed',flush=True)
                return 0
            self.prepare();self.report['app_states_before']=self.app_states()
            self.controller=threading.Thread(target=self.fault_controller,daemon=True);self.controller.start()
            self.load();self.cancel.set();self.controller.join(timeout=25)
            require(not self.controller.is_alive(),'DB fault controller did not finish')
            self.restore_database();require(self.sql_ready(),'Integration DB is not query-ready after load')
            self.review()
        except KeyboardInterrupt:self.report.update(verdict='INTERRUPTED',error='Interrupted; DB restoration runs in finally')
        except Exception as e:self.report.update(verdict='FAIL',error=str(e) if isinstance(e,Failure) else type(e).__name__)
        finally:
            self.cancel.set()
            if self.controller:self.controller.join(timeout=25)
            try:self.restore_database()
            except Exception as e:self.report.update(verdict='RESTORE_REQUIRED',restore_error=type(e).__name__)
            if self.process and self.process.poll() is None:
                try:
                    self.docker('stop','--time','10',self.container_name)
                    self.process.wait(timeout=15)
                except (Failure,subprocess.SubprocessError):self.report['load_cleanup_warning']=True
            self.prometheus();self.phase=0 if self.report['verdict']=='DRY_RUN' else (6 if self.report['verdict']=='PASS' else 7);self.save()
            self.report['finished_at']=datetime.now(KST).isoformat();self.save()
            if not self.args.dry_run:
                doc=ROOT/'backend/payment-service/docs-local/performance-test-progress.md'
                if doc.exists():
                    rel=self.out.relative_to(ROOT).as_posix()
                    if rel not in doc.read_text(encoding='utf-8'):
                        with doc.open('a',encoding='utf-8') as f:f.write(f'\n- 통합 S4 `{self.run_id}`: {self.report["verdict"]}, {self.args.rate}여정/s, DB 중지 {self.args.outage}초. 타임라인·경로별 복구·결과 불명/미완료·DB 대조는 `{rel}/result.json`. 기존 볼륨 유지, 앱 재시작 없음 여부는 checks 확인.\n')
            print('Verdict: '+self.report['verdict']+'; results: '+str(self.out),flush=True)
            if self.report.get('error'):print(self.report['error'],flush=True)
            if self.report.get('recovery'):print(json.dumps(self.report['recovery'],indent=2),flush=True)
        return 0 if self.report['verdict']=='PASS' else 1

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rate',type=int,default=5)
    p.add_argument('--baseline',type=int,default=60)
    p.add_argument('--outage',type=int,default=25)
    p.add_argument('--recovery',type=int,default=180)
    p.add_argument('--recovery-window',type=int,default=15)
    p.add_argument('--recovery-success-rate',type=float,default=.95)
    p.add_argument('--recovery-p95-ms',type=int,default=1000)
    p.add_argument('--settle',type=int,default=90)
    p.add_argument('--warmup',type=int,default=20)
    p.add_argument('--seats',type=int,default=2000)
    p.add_argument('--viewers',type=int,default=20)
    p.add_argument('--vus',type=int,default=200)
    p.add_argument('--max-vus',type=int,default=600)
    p.add_argument('--dry-run',action='store_true')
    a=p.parse_args();a.duration=a.baseline+5+a.outage+a.recovery
    if not(1<=a.rate<=25 and 5<=a.baseline<=120 and 1<=a.outage<=60 and 15<=a.recovery<=300 and
        3<=a.recovery_window<=a.recovery and .5<=a.recovery_success_rate<=1 and a.recovery_p95_ms>0 and
        0<=a.settle<=180 and 0<=a.warmup<=60 and 1<=a.viewers<=100 and 3<=a.vus<=a.max_vus<=3000):p.error('Invalid fault/recovery/workload options')
    if not math.ceil(a.rate*a.duration/10)+2<=a.seats<=20000:p.error('Insufficient seats for planned unique bookings')
    raise SystemExit(DatabaseFault(a).execute())
