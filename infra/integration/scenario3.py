"""S3: B 정상 예매 기준선과 A 혼합 부하 + B 정상 예매를 비교한다.

python infra/integration/scenario3.py --rate 25 --duration 300 --seats 15000
--rate는 A의 사용자 여정/s. B는 기본 1예매 여정/s다.
"""
import argparse
from datetime import datetime
import json
import math
from pathlib import Path
import subprocess
import time
import uuid

from scenario1 import Failure, require, KST, ROOT
from scenario2 import MixedScenario

class Phase(MixedScenario):
    def __init__(self,args,kind,comparison_id):
        count=math.ceil(args.probe_rate*args.duration)+2 if kind!='mixed' else None
        super().__init__(args,scenario=3,buyer_count=count)
        self.kind=kind
        self.comparison_id=comparison_id
        self.container_name='ticket-integration-s3-load'
        self.report.update(comparison_phase=kind,comparison_id=comparison_id)
        self.report['workload'].update(mode='mixed_probe' if kind=='mixed' else 'probe_only',
            a_rate=args.rate if kind=='mixed' else 0,b_rate=args.probe_rate,
            mix_percent={'browse':70,'seat':20,'booking':10} if kind=='mixed' else {'booking':100})
        self.report['limitations']=['Phase execution gate is not an isolation latency SLO',
            'B has unique buyers and disjoint seats; all fixtures are prepared before baseline measurement']

    def save(self):
        if hasattr(self,'out'):
            (self.out/'result.json').write_text(json.dumps(self.report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

    def prepare(self):
        super().prepare()
        path=self.out/'input.json';data=json.loads(path.read_text(encoding='utf-8'))
        data['meta'].update(mode='mixed_probe' if self.kind=='mixed' else 'probe',
            probe_rate=self.args.probe_rate,probe_vus=self.args.probe_vus,probe_max_vus=self.args.probe_max_vus,
            comparison_phase=self.kind,comparison_id=self.comparison_id)
        path.write_text(json.dumps(data),encoding='utf-8')

    def attach_probe(self,fixture):
        path=self.out/'input.json';data=json.loads(path.read_text(encoding='utf-8'))
        b=json.loads((fixture.out/'input.json').read_text(encoding='utf-8'))
        data.update(probe=b['meta'],probe_buyers=b['buyers'])
        path.write_text(json.dumps(data),encoding='utf-8')
        self.report['probe_fixture']=fixture.report['fixtures']
        self.save()

    def audit(self,cohort,fixture,seats):
        sid=int(fixture['schedule_id'])
        sql=f"""SELECT json_build_object('rows',(SELECT coalesce(json_agg(x),'[]') FROM (
          SELECT r.reservation_id,r.user_id,r.order_id,r.status,r.payment_amount,
            (r.status='CONFIRMED' AND r.pg_auth_token IS NULL
             AND (SELECT count(*) FROM reservation_schema.p_tickets t WHERE t.reservation_id=r.reservation_id AND t.deleted_at IS NULL)=1
             AND (SELECT count(*) FROM reservation_schema.p_tickets t WHERE t.reservation_id=r.reservation_id AND t.deleted_at IS NULL AND t.ticket_code IS NOT NULL AND t.price=r.payment_amount)=1
             AND (SELECT count(*) FROM reservation_schema.p_schedule_seats s WHERE s.reservation_id=r.reservation_id AND s.status='SOLD' AND s.hold_expires_at IS NULL)=1
             AND (SELECT count(*) FROM payment_schema.p_payments p WHERE p.reservation_id=r.reservation_id AND p.deleted_at IS NULL)=1
             AND (SELECT count(*) FROM payment_schema.p_payments p WHERE p.reservation_id=r.reservation_id AND p.deleted_at IS NULL AND p.order_id=r.order_id AND p.user_id=r.user_id AND p.schedule_id=r.schedule_id AND p.performance_id=r.performance_id AND p.seller_id=r.seller_id AND p.amount=r.payment_amount AND p.status='DONE' AND p.pg_provider='TOSS')=1
             AND (SELECT count(*) FROM fake_pg_schema.pg_transaction g WHERE g.order_id=r.order_id AND g.provider='TOSS' AND g.status='DONE' AND g.amount=r.payment_amount AND coalesce(g.canceled_amount,0)=0)=1) state_ok,
            (SELECT min(s.id) FROM reservation_schema.p_schedule_seats s WHERE s.reservation_id=r.reservation_id AND s.status='SOLD') sold_seat_id
          FROM reservation_schema.p_reservations r WHERE r.schedule_id={sid} AND r.deleted_at IS NULL) x),
          'seats',(SELECT json_build_object('total',count(*),'sold',count(*) FILTER(WHERE status='SOLD'),'held',count(*) FILTER(WHERE status='HOLD')) FROM reservation_schema.p_schedule_seats WHERE schedule_id={sid}),
          'orphan_payments',(SELECT count(*) FROM payment_schema.p_payments p WHERE p.schedule_id={sid} AND p.deleted_at IS NULL AND NOT EXISTS(SELECT 1 FROM reservation_schema.p_reservations r WHERE r.reservation_id=p.reservation_id AND r.order_id=p.order_id)));"""
        db=self.db(sql.rstrip(';'))
        (self.out/f'financial-{cohort}.json').write_text(json.dumps(db,indent=2)+'\n',encoding='utf-8')
        evidence=[r for r in self.report['journeys'] if r.get('cohort')==cohort and r['outcome']=='confirmed']
        by_id={r['reservation_id']:r for r in db['rows']}
        ok=len(evidence)==len(db['rows'])==db['seats']['sold'] and db['seats']['total']==seats and db['seats']['held']==0 and db['orphan_payments']==0
        for row in evidence:
            r=by_id.get(row.get('reservation_id'))
            ok=ok and bool(r and r['state_ok'] and r['user_id']==row['user_id'] and r['order_id']==row.get('order_id')
                and r['sold_seat_id']==row.get('schedule_seat_id') and r['payment_amount']==row.get('amount'))
        return bool(ok)

    def review(self):
        self.stage='review'
        path=self.out/'k6-summary.json';require(path.exists(),'k6 summary missing; see k6.log')
        m=json.loads(path.read_text(encoding='utf-8'))['metrics']
        def value(name,key,default=0):return m.get(name,{}).get('values',{}).get(key,default)
        probe_start=value('probe_started','count')
        a_started={k:value(k+'_started','count') for k in ('browse','seat','booking')}
        confirmed=[r for r in self.report['journeys'] if r.get('cohort')=='B' and r['outcome']=='confirmed']
        bwindow=self.report.get('measurement_windows',{}).get('probe',{})
        completed=sum(bwindow.get('start',0)<=r.get('completed_epoch',0)<bwindow.get('end',0) for r in confirmed)
        self.report['measurements']={'probe_started':probe_start,'probe_confirmed':value('probe_confirmed','count'),
            'probe_success_rate':value('probe_success','rate',None),'probe_duration_ms':m.get('probe_duration',{}).get('values',{}),
            'probe_http_duration_ms':m.get('probe_http_duration',{}).get('values',{}),
            'probe_completion_tps_during_measure':completed/self.args.duration,
            'probe_completed_during_measure':completed,'probe_completed_during_drain':len(confirmed)-completed,
            'a_started':a_started,'a_actual_start_rate':sum(a_started.values())/self.args.duration,
            'a_confirmed':value('booking_confirmed','count'),'dropped_iterations':value('dropped_iterations','count'),
            'system_error_rate_measure':value('system_errors{phase:measure}','rate',None)}
        checks=self.report['checks']
        checks.update(k6_thresholds=self.report.get('k6_exit_code')==0,
            no_generator_drops=value('dropped_iterations','count')==0,
            probe_arrivals=abs(probe_start-self.args.probe_rate*self.args.duration)<=1,
            probe_evidence=len([r for r in self.report['journeys'] if r.get('cohort')=='B'])==probe_start and not self.report.get('evidence_parse_errors'),
            probe_completed=len(confirmed)==value('probe_confirmed','count'),
            windows=len(self.report.get('measurement_windows',{}))==(4 if self.kind=='mixed' else 1))
        if self.kind=='mixed':
            checks['a_arrivals']=all(abs(a_started[k]-self.args.rate*self.args.duration*w)<=2 for k,w in [('browse',.7),('seat',.2),('booking',.1)])
            checks['a_evidence']=len([r for r in self.report['journeys'] if r.get('cohort')=='A'])==a_started['booking']
            checks['financial_A']=self.audit('A',self.report['fixtures'],self.args.seats)
            checks['financial_B']=self.audit('B',self.report['probe_fixture'],self.args.probe_seats)
        else:
            checks['no_a_traffic']=sum(a_started.values())==0
            checks['financial_B']=self.audit('B',self.report['fixtures'],self.args.seats)
        self.report['verdict']='PASS' if all(checks.values()) else 'FAIL'
        self.save()

class Comparison:
    def __init__(self,args):
        self.args=args
        self.run_id=datetime.now(KST).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]
        self.out=ROOT/'script/integration/results'/('scenario3-comparison-'+self.run_id)
        self.out.mkdir(parents=True,exist_ok=False)
        self.report={'scenario':3,'run_id':self.run_id,'verdict':'RUNNING','started_at':datetime.now(KST).isoformat(),
            'conditions':vars(args),'phases':{},'limitations':[
                'Before/after comparison is observational, not automatic proof of a particular root cause',
                'B baseline and B mixed use fresh but identically sized fixtures; all fixtures are prepared before either measurement',
                'PASS means execution/data checks passed, not a latency isolation SLO',
                'A rate should be chosen using scenario2 results; default 25 is only a starting point',
                'One k6 container generates A and B; generator saturation/drops invalidate attribution',
                'Small samples are unsuitable for a strong p99 conclusion']}
        self.active=None
        self.metrics=ROOT/'script/integration/scenario3-metrics.prom'

    def save(self):
        (self.out/'result.json').write_text(json.dumps(self.report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        lines=[f'integration_scenario3_valid {int(self.report["verdict"]=="PASS")}',
            f'integration_scenario3_updated_timestamp_seconds {time.time()}',
            f'integration_scenario3_run_info{{comparison_id="{self.run_id}"}} 1']
        for key,v in self.report.get('comparison',{}).items():
            if isinstance(v,dict) and v.get('ratio') is not None:
                lines.append(f'integration_scenario3_probe_latency_ratio{{quantile="{key}"}} {v["ratio"]}')
        tmp=self.metrics.with_suffix('.tmp');tmp.write_text('\n'.join(lines)+'\n',encoding='utf-8');tmp.replace(self.metrics)

    def execute(self):
        phases=[]
        try:
            self.save()
            bargs=argparse.Namespace(**vars(self.args));bargs.rate=0;bargs.seats=self.args.probe_seats;bargs.viewers=1
            baseline=Phase(bargs,'baseline',self.run_id)
            mixed=Phase(self.args,'mixed',self.run_id)
            probe_fixture=Phase(bargs,'probe_fixture',self.run_id)
            phases=[baseline,mixed,probe_fixture]
            print('[prepare] All A/B fixtures are created before measurement',flush=True)
            for p in phases:
                self.active=p;p.preflight();p.prepare()
                self.report['phases'][p.kind]={'run_id':p.run_id,'results':p.out.relative_to(ROOT).as_posix()}
                self.save()
            mixed.attach_probe(probe_fixture)
            self.report['database_before']=baseline.db_size()
            for p in (baseline,mixed):
                self.active=p
                print('[phase] '+p.kind+': '+('B only' if p.kind=='baseline' else f'A {self.args.rate}/s + B {self.args.probe_rate}/s'),flush=True)
                p.load();p.review()
                p.report['finished_at']=datetime.now(KST).isoformat();p.save()
                self.report['phases'][p.kind].update(verdict=p.report['verdict'],measurements=p.report['measurements'])
                self.save()
                if p is baseline:
                    require(p.report['verdict']=='PASS','B baseline invalid; mixed load not started')
                    print(f'[cooldown] {self.args.cooldown}s',flush=True)
                    time.sleep(self.args.cooldown)
            self.report['database_after']=baseline.db_size()
            before=baseline.report['measurements'];after=mixed.report['measurements']
            comparison={}
            for key in ('avg','p(95)','p(99)'):
                a=before['probe_duration_ms'].get(key);b=after['probe_duration_ms'].get(key)
                comparison[key]={'baseline_ms':a,'mixed_ms':b,'delta_ms':b-a if a is not None and b is not None else None,
                    'ratio':b/a if a and b is not None else None}
            comparison['probe_success_rate']={'baseline':before['probe_success_rate'],'mixed':after['probe_success_rate']}
            comparison['probe_completion_tps']={'baseline':before['probe_completion_tps_during_measure'],'mixed':after['probe_completion_tps_during_measure']}
            self.report['comparison']=comparison
            self.report['checks']={'same_resource_config':baseline.report['resources']==mixed.report['resources'],
                'baseline_valid':baseline.report['verdict']=='PASS','mixed_valid':mixed.report['verdict']=='PASS'}
            self.report['verdict']='PASS' if all(self.report['checks'].values()) else 'FAIL'
        except KeyboardInterrupt:self.report.update(verdict='INTERRUPTED',error='Operator interrupted; data retained')
        except Exception as e:self.report.update(verdict='FAIL',error=str(e) if isinstance(e,Failure) else type(e).__name__)
        finally:
            for p in phases:
                if p.process and p.process.poll() is None:
                    try:
                        subprocess.run(['docker','stop','--time','10',p.container_name],capture_output=True,timeout=20)
                        p.process.wait(timeout=15)
                    except (OSError,subprocess.SubprocessError):self.report['cleanup_warning']='Check ticket-integration-s3-load'
                if p.report.get('load_start_epoch'):
                    p.prometheus();p.save()
                    self.report['phases'][p.kind]['observation_gaps']=p.report.get('observation_gaps',[])
            self.report['finished_at']=datetime.now(KST).isoformat();self.save()
            doc=ROOT/'backend/payment-service/docs-local/performance-test-progress.md'
            if doc.exists():
                rel=self.out.relative_to(ROOT).as_posix()
                if rel not in doc.read_text(encoding='utf-8'):
                    with doc.open('a',encoding='utf-8') as f:
                        f.write(f'\n- 통합 S3 `{self.run_id}`: {self.report["verdict"]}, B {self.args.probe_rate}예매/s 기준선 → A {self.args.rate}혼합여정/s+B, 구간당 {self.args.duration}초. B 지연/완료율 비교·DB 대조·관측 공백은 `{rel}/result.json`. 데이터 준비는 두 측정 전 완료, 설정 변경/기존 데이터 삭제 없음.\n')
        print('Verdict: '+self.report['verdict']+'; results: '+str(self.out),flush=True)
        if self.report.get('comparison'):print(json.dumps(self.report['comparison'],indent=2),flush=True)
        if self.report.get('error'):print(self.report['error'],flush=True)
        return 0 if self.report['verdict']=='PASS' else 1

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rate',type=int,default=25,help='A mixed user journeys/s; use scenario2 near-saturation result')
    p.add_argument('--duration',type=int,default=300,help='seconds per phase; two measured phases')
    p.add_argument('--warmup',type=int,default=20)
    p.add_argument('--cooldown',type=int,default=20)
    p.add_argument('--seats',type=int,default=15000,help='A seats, keep identical across compared A rates')
    p.add_argument('--probe-rate',type=int,default=1,help='B full booking journeys/s')
    p.add_argument('--probe-seats',type=int,default=1000,help='identical B seat count for baseline/mixed')
    p.add_argument('--viewers',type=int,default=50)
    p.add_argument('--vus',type=int)
    p.add_argument('--max-vus',type=int)
    p.add_argument('--probe-vus',type=int,default=20)
    p.add_argument('--probe-max-vus',type=int,default=100)
    a=p.parse_args()
    if a.vus is None:a.vus=max(200,a.rate*6)
    if a.max_vus is None:a.max_vus=max(1000,a.vus,a.rate*7)
    if not (1<=a.rate<=400 and 10<=a.duration<=600 and 0<=a.warmup<=120 and 0<=a.cooldown<=60 and
        1<=a.probe_rate<=5 and 1<=a.viewers<=500 and 3<=a.vus<=a.max_vus<=3000 and
        1<=a.probe_vus<=a.probe_max_vus<=500 and a.seats<=20000 and a.probe_seats<=20000):
        p.error('Invalid rate/duration/warmup/cooldown/VU/seat options')
    if a.seats<math.ceil(a.rate*a.duration/10)+2 or a.probe_seats<a.probe_rate*a.duration+2:
        p.error('Not enough A or B seats for planned unique bookings')
    raise SystemExit(Comparison(a).execute())
