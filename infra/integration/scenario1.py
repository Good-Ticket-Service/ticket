"""시나리오 1: 공개 API로 준비하고 정상 예매 흐름을 순차 검증한다.

실행: python infra/integration/scenario1.py --users 10
외부 라이브러리 불필요. ticket-integration만 사용하며 기존 데이터를 지우지 않는다.
JWT/PG 인증값/비밀번호/티켓 코드는 결과·로그에 기록하지 않는다.
"""
import argparse
from datetime import datetime, timedelta, timezone
import json
import math
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

from compose import command, ROOT

BASE = 'http://127.0.0.1:18080'
PG = 'http://127.0.0.1:18090'
PASSWORD = 'Integrate!2026'  # 공개 로컬 시험 계정 전용, 가입 정책 8~15자
KST = timezone(timedelta(hours=9))

class Failure(Exception):
    pass

def require(condition, message):
    if not condition:
        raise Failure(message)

class Scenario:
    def __init__(self, users, poll_timeout, scenario=1):
        self.users = users
        self.poll_timeout = poll_timeout
        self.run_id = datetime.now(KST).strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:6]
        self.out = ROOT/'script'/'integration'/'results'/(f'scenario{scenario}-'+self.run_id)
        self.out.mkdir(parents=True, exist_ok=False)
        self.report = {'run_id':self.run_id, 'scenario':scenario, 'requested_users':users,
                       'started_at':datetime.now(KST).isoformat(), 'verdict':'RUNNING',
                       'fixtures':{}, 'journeys':[], 'checks':{}, 'limitations':[
                           'TOSS/no coupon only; sequential functional test, not capacity benchmark',
                           'No payment-completion Kafka event exists in this synchronous flow',
                           'PG checkout is simulated through the local Fake PG API']}
        self.http_count = 0
        self.durations = []
        self.stage = 'preflight'
        self.cmd, self.env, sha = command(['ps'])
        self.report['source_sha'] = sha
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.metrics_path = ROOT/'script'/'integration'/'scenario1-metrics.prom'

    def save(self):
        (self.out/'result.json').write_text(json.dumps(self.report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        rows=self.report['journeys']
        lines=[f'integration_scenario1_http_requests_total {self.http_count}',
               f'integration_scenario1_journey_total{{kind="started"}} {len(rows)}',
               f'integration_scenario1_journey_total{{kind="api_confirmed"}} {sum(r.get("api_confirmed",False) for r in rows)}',
               f'integration_scenario1_journey_total{{kind="failed"}} {sum("error" in r for r in rows)}',
               f'integration_scenario1_verified {int(self.report["verdict"]=="PASS")}',
               f'integration_scenario1_updated_timestamp_seconds {time.time()}']
        for bound in (.1,.25,.5,1,2,5,10,30,60,120,float('inf')):
            le='+Inf' if math.isinf(bound) else str(bound)
            lines.append(f'integration_scenario1_journey_duration_seconds_bucket{{le="{le}"}} {sum(d<=bound for d in self.durations)}')
        lines += [f'integration_scenario1_journey_duration_seconds_sum {sum(self.durations)}',
                  f'integration_scenario1_journey_duration_seconds_count {len(self.durations)}']
        tmp=self.metrics_path.with_suffix('.tmp')
        tmp.write_text('\n'.join(lines)+'\n',encoding='utf-8')
        tmp.replace(self.metrics_path)

    def request(self, method, path, payload=None, token=None, expected=(200,), raw=False, headers=None):
        base=PG if raw else BASE
        h={'Accept':'application/json', 'X-Request-Id':'s1-'+self.run_id+'-'+uuid.uuid4().hex[:8]}
        if token: h['Authorization']='Bearer '+token
        if headers: h.update(headers)
        data=None if payload is None else json.dumps(payload).encode()
        if data is not None: h['Content-Type']='application/json'
        start=time.perf_counter();status=0; code=None
        try:
            response=self.http.open(urllib.request.Request(base+path,data=data,headers=h,method=method),timeout=70)
            status=response.status
            body=json.loads(response.read())
        except urllib.error.HTTPError as e:
            status=e.code
            try: body=json.loads(e.read())
            except (ValueError,UnicodeDecodeError): body={}
            code=body.get('code') if isinstance(body,dict) else None
            raise Failure(f'{self.stage}: {method} {path} -> HTTP {status}, code={code}') from None
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as e:
            raise Failure(f'{self.stage}: {method} {path} -> {type(e).__name__}; request was not retried') from None
        finally:
            self.http_count+=1
            with (self.out/'requests.jsonl').open('a',encoding='utf-8') as f:
                f.write(json.dumps({'at':datetime.now(KST).isoformat(),'stage':self.stage,'method':method,
                    'path':path,'status':status,'duration_ms':round((time.perf_counter()-start)*1000,2),
                    'code':code,'request_id':h['X-Request-Id']})+'\n')
        require(status in expected,f'{self.stage}: unexpected HTTP {status}')
        if raw: return body
        require(isinstance(body,dict) and body.get('status')==status and 'data' in body,
                f'{self.stage}: ApiResponse contract mismatch')
        return body['data']

    def login(self,email,password=PASSWORD):
        d=self.request('POST','/api/auth/login',{'email':email,'password':password})
        require(bool(d.get('accessToken')),'login response missing token')
        return d['accessToken']

    def signup(self,email,seller=False):
        d=self.request('POST','/api/auth/'+('seller/signup' if seller else 'signup'),
            {'email':email,'password':PASSWORD,'name':'Integration Test','phone':'010-1234-5678'},expected=(201,))
        require(d.get('role')==('SELLER' if seller else 'USER'),'signup role mismatch')
        return d['userId']

    def preflight(self):
        # 변경 작업 전에 Compose 대상과 DB 접근, 양쪽 큐 설정을 확인한다.
        # k6는 load 프로필이라 프로필을 지정해야 resources에 cpuset·mem_limit가 함께 기록된다
        p=subprocess.run(self.cmd+['--profile','load','config','--format','json'],env=self.env,capture_output=True,text=True,timeout=20)
        require(p.returncode==0,'Compose configuration failed')
        config=json.loads(p.stdout)
        for service in ('gateway-service','reservation-service'):
            require(str(config['services'][service]['environment'].get('QUEUE_ENABLED')).lower()=='false', 'QUEUE_ENABLED must be false')
        self.report['resources']={s:{k:v for k,v in c.items() if k in ('cpuset','cpus','mem_limit')}
                                  for s,c in config['services'].items()}
        # compose ps는 credential/environment를 출력하지 않는다.
        p=subprocess.run(self.cmd+['ps','--status','running','--services'],env=self.env,capture_output=True,text=True,timeout=20)
        require(p.returncode==0,'Docker access failed; start Docker Desktop / check permissions')
        running=set(p.stdout.split())
        require({'caddy','gateway-service','user-service','performance-service','reservation-service','payment-service','fake-pg','postgres'}<=running,
                'Required integration services are not running')
        self.db('SELECT 1',json_result=False)
        # health는 업무 ApiResponse 래퍼가 아니다.
        with self.http.open(BASE+'/actuator/health',timeout=10) as r:
            require(json.load(r).get('status')=='UP','Gateway health is not UP')
        try:
            with self.http.open('http://127.0.0.1:19100/metrics',timeout=2) as r:
                self.report['collector_reachable']=r.status==200
        except (OSError,urllib.error.URLError):
            self.report['collector_reachable']=False
            print('[warning] Collector is not running: start python infra/integration/observe.py for host/S1 dashboard metrics',flush=True)
        faults=self.request('GET','/fake/admin/faults/TOSS/CONFIRM',raw=True)
        (self.out/'fakepg-confirm-config.json').write_text(json.dumps(faults,indent=2)+'\n',encoding='utf-8')
        for key in ('serverErrorRate','failRate','responseLossRate','delayedApprovalRate','tamperRate'):
            require(faults.get(key,0)==0,'Fake PG fault active: '+key+'; not reset automatically')

    def db(self,sql,json_result=True):
        p=subprocess.run(self.cmd+['exec','-T','postgres','psql','-U',self.env['POSTGRES_USER'],'-d','ticket',
            '-X','-qAt','-v','ON_ERROR_STOP=1'],env=self.env,
            input="BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET LOCAL statement_timeout='10s';\n"+sql+';\nCOMMIT;\n',
            capture_output=True,text=True,timeout=25)
        require(p.returncode==0,'Read-only DB verification failed; check schema/pg permissions')
        return json.loads(p.stdout) if json_result else p.stdout

    def fixtures(self, seat_count=None):
        seat_count=self.users if seat_count is None else seat_count
        label='S'+str(self.report['scenario'])
        self.stage='prepare';print('[prepare] Creating isolated users, venue, performance and schedule',flush=True)
        master=self.login(self.env['MASTER_EMAIL'],self.env['MASTER_PASSWORD'])
        seller_email=label.lower()+'-seller-'+self.run_id+'@integration.local'
        seller_id=self.signup(seller_email,True);seller=self.login(seller_email)
        venue=self.request('POST','/api/admin/venues',{'name':label+' '+self.run_id,'address':'Integration local','region':'SEOUL'},master,expected=(201,))
        fixture=self.report['fixtures'];fixture.update(venue_id=venue['id'],seller_id=seller_id)
        self.save()
        grades=self.request('POST',f'/api/admin/venues/{venue["id"]}/seat-grades',{'grades':[{'name':'R'}]},master,
                            expected=(201,),headers={'If-Match':str(venue['version'])})
        grade=grades['grades'][0]['id']
        self.request('POST',f'/api/admin/venues/{venue["id"]}/seats',{'seats':[
            {'section':'A','rowLabel':'1','seatNumber':i+1,'venueSeatGradeId':grade} for i in range(seat_count)]},master,
            expected=(201,),headers={'If-Match':str(grades['venueVersion'])})
        perf=self.request('POST','/api/seller/performances',{'venueId':venue['id'],'title':label+' '+self.run_id,
            'genre':'CONCERT','ageRating':'ALL','runningTime':60,
            'description':'Integration scenario 1 fixture',
            'posterUrl':'https://example.com/integration-poster.png'},seller,expected=(201,))
        fixture['performance_id']=perf['id'];self.save()
        prices=self.request('PUT',f'/api/seller/performances/{perf["id"]}/grade-prices',
            {'gradePrices':[{'venueSeatGradeId':grade,'price':10000}]},seller,headers={'If-Match':str(perf['version'])})
        now=datetime.now(KST).replace(microsecond=0)
        schedule=self.request('POST',f'/api/seller/performances/{perf["id"]}/schedules',
            {'startAt':(now+timedelta(days=7)).isoformat(),'bookingOpenAt':(now-timedelta(hours=1)).isoformat(),
             'bookingCloseAt':(now+timedelta(days=6)).isoformat()},seller,expected=(201,))
        fixture['schedule_id']=schedule['id'];self.save()
        deadline=time.monotonic()+self.poll_timeout
        while schedule.get('seatSetupStatus')!='READY':
            require(schedule.get('seatSetupStatus')!='FAILED','performance -> seat preparation FAILED')
            require(time.monotonic()<deadline,'performance -> seat preparation timed out')
            time.sleep(1)
            schedule=self.request('GET',f'/api/seller/schedules/{schedule["id"]}',token=seller)
        self.request('POST',f'/api/seller/performances/{perf["id"]}/publication',token=seller,
                     headers={'If-Match':str(prices['performanceVersion'])})
        fixture['seat_setup_status']='READY'
        self.accounts=[]
        for i in range(self.users):
            email=f'{label.lower()}-{self.run_id}-{i}@integration.local'
            uid=self.signup(email);self.accounts.append((email,uid))
            if (i+1)%50==0: print(f'[prepare] accounts {i+1}/{self.users}',flush=True)
        fixture['user_ids']=[uid for _,uid in self.accounts];self.save()

    def journey(self,index,email,uid):
        self.stage='user-'+str(index+1)
        row={'index':index+1,'user_id':uid,'api_confirmed':False}
        self.report['journeys'].append(row);self.save()
        start=time.perf_counter()
        try:
            token=self.login(email)
            me=self.request('GET','/api/users/me',token=token)
            require(me['userId']==uid,'JWT user identity mismatch')
            f=self.report['fixtures'];pid=f['performance_id'];sid=f['schedule_id']
            listing=self.request('GET',f'/api/performances?keyword=S1%20{self.run_id}&size=100',token=token)
            require(any(x['id']==pid for x in listing['items']),'Published performance missing from list')
            detail=self.request('GET',f'/api/performances/{pid}',token=token)
            require(detail['id']==pid,'Performance detail mismatch')
            schedules=self.request('GET',f'/api/performances/{pid}/schedules',token=token)
            require(any(x['id']==sid for x in schedules['schedules']),'Schedule missing from public listing')
            schedule=self.request('GET',f'/api/schedules/{sid}',token=token)
            require(schedule['seatSetupStatus']=='READY','Public schedule is not READY')
            seats=self.request('GET',f'/api/seats/schedules/{sid}',token=token)
            available=sorted((s for s in seats['seats'] if s['selectable']),key=lambda s:s['scheduleSeatId'])
            require(bool(available),'No selectable fixture seat')
            seat=available[0];row['schedule_seat_id']=seat['scheduleSeatId']
            hold=self.request('POST',f'/api/seats/schedules/{sid}/holds',{'scheduleSeatId':seat['scheduleSeatId']},token,expected=(200,201))
            row['reservation_id']=hold['reservationId'];self.save()
            require(hold['status']=='HOLD' and hold['scheduleSeatId']==seat['scheduleSeatId'],'Seat hold mismatch')
            rid=hold['reservationId'];path=f'/api/reservations/{rid}'
            prepared=self.request('POST',path+'/payments/checkout',{'pgProvider':'TOSS'},token)
            row.update(order_id=prepared['orderId'],amount=prepared['amount']);self.save()
            require(prepared['amount']==seat['basePrice']==10000,'Checkout price mismatch')
            auth=self.request('POST','/fake/toss/checkout',{'orderId':prepared['orderId'],'amount':prepared['amount']},raw=True)
            require(auth['orderId']==prepared['orderId'] and auth['amount']==prepared['amount'],'Fake PG checkout mismatch')
            confirmed=self.request('POST',path+'/payments/confirm',{'orderId':prepared['orderId'],'pgAuthToken':auth['paymentKey']},token)
            deadline=time.monotonic()+self.poll_timeout
            while confirmed['status']=='PAYMENT_IN_PROGRESS':
                require(time.monotonic()<deadline,'Reservation confirmation timed out')
                time.sleep(1);confirmed=self.request('GET',path,token=token)
            require(confirmed['status']=='CONFIRMED','Reservation ended in '+confirmed['status'])
            final=self.request('GET',path,token=token)
            require(final['status']=='CONFIRMED' and final['orderId']==prepared['orderId'] and final['scheduleId']==sid,
                    'Reservation final state/order mismatch')
            require(final['paymentAmount']==prepared['amount'] and final['scheduleSeatIds']==[seat['scheduleSeatId']],
                    'Reservation amount/seat mismatch')
            require(len(final['tickets'])==1 and bool(final['tickets'][0]['ticketCode']),'Confirmed ticket missing')
            after=self.request('GET',f'/api/seats/schedules/{sid}',token=token)
            require(not any(s['scheduleSeatId']==seat['scheduleSeatId'] and s['selectable'] for s in after['seats']),
                    'Confirmed seat is still selectable')
            row.update(api_confirmed=True,api_final_status=final['status'],ticket_count=1)
        except Failure as e:
            row['error']=str(e)
        finally:
            row['duration_ms']=round((time.perf_counter()-start)*1000,2)
            self.durations.append(row['duration_ms']/1000);self.save()
        print(f'[{index+1}/{self.users}] '+('API PASS' if row['api_confirmed'] else 'FAIL')+
              f' reservation={row.get("reservation_id")} elapsed={row["duration_ms"]}ms',flush=True)

    def verify(self):
        self.stage='financial-verification'
        sid=int(self.report['fixtures']['schedule_id'])
        # 스키마 간 읽기는 시험 관측용이다. 업무 서비스의 경계를 변경하지 않는다.
        sql=f"""SELECT json_build_object(
          'reservations',(SELECT coalesce(json_agg(x),'[]') FROM (
            SELECT r.reservation_id,r.user_id,r.schedule_id,r.performance_id,r.seller_id,r.order_id,r.status,
                   r.payment_amount,(r.pg_auth_token IS NULL) auth_cleared,
              (SELECT count(*) FROM reservation_schema.p_tickets t WHERE t.reservation_id=r.reservation_id AND t.deleted_at IS NULL) tickets,
              (SELECT count(*) FROM reservation_schema.p_tickets t WHERE t.reservation_id=r.reservation_id AND t.ticket_code IS NOT NULL AND t.deleted_at IS NULL) issued_tickets,
              (SELECT count(*) FROM reservation_schema.p_schedule_seats s WHERE s.reservation_id=r.reservation_id AND s.status='SOLD' AND s.hold_expires_at IS NULL) sold_seats,
              (SELECT count(*) FROM payment_schema.p_payments p WHERE p.reservation_id=r.reservation_id AND p.deleted_at IS NULL) payments,
              (SELECT count(*) FROM payment_schema.p_payments p WHERE p.reservation_id=r.reservation_id AND p.order_id=r.order_id AND p.user_id=r.user_id AND p.seller_id=r.seller_id AND p.performance_id=r.performance_id AND p.schedule_id=r.schedule_id AND p.status='DONE' AND p.amount=r.payment_amount AND p.approved_at IS NOT NULL AND p.pg_provider='TOSS' AND p.deleted_at IS NULL) correct_payments,
              (SELECT count(*) FROM fake_pg_schema.pg_transaction g WHERE g.order_id=r.order_id AND g.provider='TOSS' AND g.status='DONE' AND g.amount=r.payment_amount AND coalesce(g.canceled_amount,0)=0) correct_pg
            FROM reservation_schema.p_reservations r WHERE r.schedule_id={sid} AND r.deleted_at IS NULL ORDER BY r.reservation_id) x),
          'seats',(SELECT json_build_object('total',count(*),'sold',count(*) FILTER(WHERE status='SOLD'),'hold',count(*) FILTER(WHERE status='HOLD')) FROM reservation_schema.p_schedule_seats WHERE schedule_id={sid}));"""
        evidence=self.db(sql.rstrip(';'))
        (self.out/'financial-verification.json').write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf-8')
        rows=evidence['reservations'];expected=self.report['journeys']
        ok=len(rows)==self.users and all(r.get('api_confirmed') for r in expected)
        mapped={r['reservation_id']:r for r in rows}
        for row in expected:
            db=mapped.get(row.get('reservation_id'))
            row['db_verified']=bool(db and db['user_id']==row['user_id'] and db['order_id']==row.get('order_id')
                and db['status']=='CONFIRMED' and db['auth_cleared']
                and all(db[k]==1 for k in ('tickets','issued_tickets','sold_seats','payments','correct_payments','correct_pg')))
            ok=ok and row['db_verified']
        ok=ok and evidence['seats']=={'total':self.users,'sold':self.users,'hold':0}
        self.report['checks']['financial_consistency']=bool(ok)
        return ok

    def execute(self):
        try:
            self.save();self.preflight();self.fixtures()
            for i,(email,uid) in enumerate(self.accounts):
                self.journey(i,email,uid)
                if not self.report['journeys'][-1]['api_confirmed']:
                    # 정상 경로에서 실패하면 재시도로 숨기지 않고 신규 쓰기를 중단한다.
                    break
                time.sleep(.5)
            self.report['verdict']='PASS' if self.verify() else 'FAIL'
        except KeyboardInterrupt:
            self.report.update(verdict='INTERRUPTED',error='Operator interrupted; created data retained')
        except Exception as e:
            self.report.update(verdict='FAIL',error=str(e) if isinstance(e,Failure) else type(e).__name__,failed_stage=self.stage)
        finally:
            self.report['finished_at']=datetime.now(KST).isoformat();self.save()
            doc=ROOT/'backend/payment-service/docs-local/performance-test-progress.md'
            if doc.exists():
                relative=self.out.relative_to(ROOT).as_posix()
                if relative not in doc.read_text(encoding='utf-8'):
                    with doc.open('a',encoding='utf-8') as f:
                        f.write(f'\n- 통합 S1 `{self.run_id}`: {self.report["verdict"]}, 요청 {self.users}명, API 확정 {sum(r.get("api_confirmed",False) for r in self.report["journeys"])}건, DB 대조 {self.report["checks"].get("financial_consistency","미실행")}. 근거 `{relative}/result.json`. 신규 데이터 보존, 기존 환경/설정 변경 없음.\n')
        print('Verdict: '+self.report['verdict']+'; results: '+str(self.out),flush=True)
        if 'error' in self.report: print(str(self.report['error']),flush=True)
        return 0 if self.report['verdict']=='PASS' else 1

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--users',type=int,default=10,choices=range(1,21),metavar='1..20')
    parser.add_argument('--poll-timeout',type=int,default=90)
    args=parser.parse_args()
    raise SystemExit(Scenario(args.users,args.poll_timeout).execute())
