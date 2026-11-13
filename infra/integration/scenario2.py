"""S2 조회 중심 혼합 부하: 70% 조회 / 20% 좌석 탐색 / 10% 예매.

python infra/integration/scenario2.py --rate 25 --duration 60
rate는 초당 시작하는 사용자 여정 수다. 단계마다 새 회차/계정을 준비한다.
--seat-api split은 좌석 조회를 layout 1번 + status 조회로 바꾼다(#271). 기본 legacy는 기존 좌석 조회를 쓴다.
--seat-api zone은 구역 목록 → 구역 layout → 구역 status로 조회한다(#397). --venue-layout sections가 필요하다.
"""
import argparse
from datetime import datetime
import json
import math
from pathlib import Path
import subprocess
import threading
import time
import urllib.parse
import urllib.request

from scenario1 import Scenario, Failure, require, KST, ROOT, PASSWORD, VENUE_LAYOUTS, DEFAULT_SECTION_SEATS, SECTION_SEATS_PER_ROW
import foreign
import layout_probe

CPU_SERVICES=('reservation-service','gateway-service','caddy')

def cpu_summary(data,start,end):
    """Prometheus range 결과에서 측정 구간의 서비스별 CPU 코어 평균·최대. 같은 서비스의 컨테이너는 시각별로 더한다."""
    by_service={}
    for series in data.get('data',{}).get('result',[]):
        service=series['metric'].get('service')
        if service not in CPU_SERVICES:continue
        points=by_service.setdefault(service,{})
        for ts,v in series['values']:
            if start<=float(ts)<=end:points[ts]=points.get(ts,0)+float(v)
    return {s:{'avg':sum(p.values())/len(p),'max':max(p.values()),'samples':len(p)} for s,p in by_service.items() if p}

def layout_measurements(value,metrics):
    """k6 요약에서 layout 응답 지표만 모은다. 본문을 받아 두지 않으므로 크기는 Content-Length가 있을 때만 있다."""
    return {'reads':value('seat_layout_reads','count'),'gzip_responses':value('seat_layout_gzip','count'),
            'content_length_missing':value('seat_layout_length_missing','count'),
            'content_length_bytes':metrics.get('seat_layout_content_length',{}).get('values',{})}

def encoding_as_requested(accept_encoding,layout):
    """gzip을 요청했으면 모든 layout이 gzip, identity면 하나도 gzip이 아니어야 한다. 조건을 잘못 걸고 잰 회차를 거른다."""
    if accept_encoding=='gzip':return layout['reads']>0 and layout['gzip_responses']==layout['reads']
    if accept_encoding=='identity':return layout['gzip_responses']==0
    return True

def zone_fixture(seats,buyers):
    """구역 모드(#397)의 준비값. seats는 기존 좌석 조회 응답의 seats다.
    - 반환: [{section, seat_count}]. 순서는 구역의 가장 작은 좌석 id 순으로 /sections 응답 순서와 같다.
      k6(JavaScript)에서 숫자 같은 구역 이름("1")의 객체 키 순서가 바뀌지 않게 목록으로 넘긴다
    - 구매자마다 section(배정 좌석의 구역)과 section_index(구역 layout 안의 순서 = 구역 status 비트 위치)를 넣는다.
      구역 layout은 그 구역 좌석의 id 오름차순이다(docs/api.md 좌석 절)"""
    by_section={};position={}
    for seat in sorted(seats,key=lambda s:s['scheduleSeatId']):
        group=by_section.setdefault(seat['section'],[])
        position[seat['scheduleSeatId']]=(seat['section'],len(group))
        group.append(seat['scheduleSeatId'])
    for b in buyers: b['section'],b['section_index']=position[b['schedule_seat_id']]
    return [{'section':name,'seat_count':len(ids)} for name,ids in by_section.items()]

def option_error(a):
    """좌석 조회 옵션 조합 검사. 틀리면 이유를, 맞으면 None을 준다."""
    if a.layout_body=='skip' and a.seat_api not in ('split','zone'):return '--layout-body skip needs --seat-api split or zone'
    if a.seat_api=='zone' and a.venue_layout!='sections':return '--seat-api zone needs --venue-layout sections'
    if a.seat_api!='zone' and (a.zone_visits!=1 or a.status_reads is not None):return '--zone-visits/--status-reads need --seat-api zone'
    if a.section_seats!=DEFAULT_SECTION_SEATS and a.venue_layout!='sections':return '--section-seats needs --venue-layout sections'
    if not (SECTION_SEATS_PER_ROW<=a.section_seats<=5000 and a.section_seats%SECTION_SEATS_PER_ROW==0):
        return f'section-seats {SECTION_SEATS_PER_ROW}..5000, multiple of {SECTION_SEATS_PER_ROW}'
    if not 1<=a.zone_visits<=20:return 'zone-visits 1..20'
    if a.status_reads is not None and not 1<=a.status_reads<=20:return 'status-reads 1..20'
    return None

class MixedScenario(Scenario):
    # 부하 스크립트. scenario5(#313)는 같은 실행기(load)에 다른 스크립트를 넘긴다. 줄 머리(S2_EVIDENCE·S2_WINDOW)와 환경 변수는 같이 쓴다
    k6_script='/scripts/scenario2.js'

    def progress(self):
        return f'booking evidence {len(self.report["journeys"])}; confirmed '+str(sum(r['outcome']=='confirmed' for r in self.report['journeys']))

    def preflight(self):
        super().preflight()
        if self.report['scenario'] in (2,3):
            config_path=self.out/'fakepg-confirm-config.json'
            config=json.loads(config_path.read_text(encoding='utf-8'))
            config['latencyMs']=300
            self.request('PUT','/fake/admin/faults/TOSS/CONFIRM',config,raw=True)
            applied=self.request('GET','/fake/admin/faults/TOSS/CONFIRM',raw=True)
            require(applied.get('latencyMs')==300,'Fake PG CONFIRM latency must be 300ms')
            config_path.write_text(json.dumps(applied,indent=2)+'\n',encoding='utf-8')
            self.report['workload']['fake_pg_confirm_latency_ms']=300
            self.save()
            print('[prepare] Fake PG TOSS CONFIRM latency: 300ms',flush=True)

    def __init__(self,args,scenario=2,buyer_count=None):
        self.args=args
        # 공연장 좌석 배치(#397). scenario3·4는 이 옵션이 없어 기존 배치(A구역 1열)로 둔다
        self.venue_layout=getattr(args,'venue_layout','single')
        self.section_seats=getattr(args,'section_seats',DEFAULT_SECTION_SEATS)
        # arrival executor의 경계 시각 차이를 위한 두 계정 여유. 좌석은 매번 같은 인자로 고정한다.
        buyers=math.ceil(args.rate*args.duration/10)+2 if buyer_count is None else buyer_count
        require(args.seats>=buyers,f'Need at least {buyers} seats. Keep --seats identical when comparing rates.')
        super().__init__(buyers,180,scenario=scenario,allow_foreign=getattr(args,'allow_foreign',False))
        self.report.update(workload={'rate_journeys_per_second':args.rate,'duration_seconds':args.duration,
            'warmup_seconds':args.warmup,'mix_percent':{'browse':70,'seat':20,'booking':10},
            'seat_count':args.seats,'venue_layout':self.venue_layout,
            'section_seats':self.section_seats if self.venue_layout=='sections' else None,'viewer_account_pool':args.viewers,'preallocated_vus':args.vus,'max_vus':args.max_vus,
            'think_seconds':[.3,1.0],'seat_reads_per_viewer':[3,5],
            'booking_seat_selection':'disjoint assigned seats; no intentional seat competition',
            # scenario3·4는 이 옵션이 없을 수 있어 legacy로 둔다
            'seat_api':getattr(args,'seat_api','legacy'),
            # zone(#397) 여정: 여정마다 보는 구역 수, 구역마다 받는 status 수(None이면 split과 같은 횟수: 좌석 탐색 3~5, 예매 1)
            'zone_visits':getattr(args,'zone_visits',1),'status_reads':getattr(args,'status_reads',None),
            # 좌석 경계 측정 조건(#287). scenario3·4는 이 옵션이 없어 기존 동작(parse·default)으로 둔다
            'layout_body':getattr(args,'layout_body','parse'),'accept_encoding':getattr(args,'accept_encoding','default'),
            'authentication':('browse anonymous; authenticated accounts login in k6 setup; tokens reused during measurement'
                if scenario in (2,3) else 'login per authenticated journey')},
            limitations=['One popular schedule; TOSS only; no coupon',
                'PASS gates errors/completion/dropped/DB consistency; no agreed latency SLO',
                'Preparation, warmup and post-run financial checks are excluded from measured phase',
                'Sequential stages must keep seats/viewer pool/CPU/DB/PG settings identical'])
        self.report['journeys']=[]
        self.process=None
        self.container_name='ticket-integration-s2-load'
        self.metrics_path=ROOT/'script/integration/scenario2-metrics.prom'

    def save(self):
        if not hasattr(self,'out'): return
        (self.out/'result.json').write_text(json.dumps(self.report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        metrics=[f'integration_scenario2_target_journeys_per_second {self.args.rate}',
                 f'integration_scenario2_run_info{{test_id="{self.run_id}"}} 1',
                 f'integration_scenario2_verified {int(self.report["verdict"]=="PASS")}',
                 f'integration_scenario2_updated_timestamp_seconds {time.time()}']
        for outcome in ('confirmed','failed','business_rejected'):
            metrics.append(f'integration_scenario2_booking_evidence_total{{kind="{outcome}"}} '+str(sum(r.get('outcome')==outcome for r in self.report['journeys'])))
        tmp=self.metrics_path.with_suffix('.tmp');tmp.write_text('\n'.join(metrics)+'\n',encoding='utf-8');tmp.replace(self.metrics_path)

    def prepare(self):
        self.stage='prepare';self.fixtures(seat_count=self.args.seats)
        buyer_accounts=self.accounts
        viewers=[]
        for i in range(self.args.viewers):
            email=f's2-view-{self.run_id}-{i}@integration.local'
            uid=self.signup(email);viewers.append({'email':email,'user_id':uid})
        token=self.login(buyer_accounts[0][0])
        sid=self.report['fixtures']['schedule_id']
        seats=self.request('GET',f'/api/seats/schedules/{sid}',token=token)
        available=sorted((s['scheduleSeatId'] for s in seats['seats'] if s['selectable']))
        require(len(available)==self.args.seats,'Prepared seat count mismatch')
        buyers=[{'email':email,'user_id':uid,'schedule_seat_id':available[i]} for i,(email,uid) in enumerate(buyer_accounts)]
        layout=self.prepare_layout(sid,token,buyers)
        if self.report['workload']['seat_api']=='zone':layout.update(self.prepare_zone(sid,token,seats,buyers))
        meta={'run_id':self.run_id,'rate':self.args.rate,'duration':self.args.duration,'warmup':self.args.warmup,
              'vus':self.args.vus,'max_vus':self.args.max_vus,'password':PASSWORD,'prelogin':self.report['scenario'] in (2,3),
              'performance_id':self.report['fixtures']['performance_id'],'schedule_id':sid,
              'seat_api':self.report['workload']['seat_api'],
              'layout_body':self.report['workload']['layout_body'],'accept_encoding':self.report['workload']['accept_encoding'],**layout}
        # 공개 로컬 시험 비밀번호와 시험 ID만 저장. JWT/PG 인증값은 k6 메모리에만 둔다.
        (self.out/'input.json').write_text(json.dumps({'meta':meta,'buyers':buyers,'viewers':viewers}),encoding='utf-8')
        self.report['prepared_at']=datetime.now(KST).isoformat();self.save()

    def boundary_conditions(self):
        """#287 경계 측정 옵션을 쓴 회차인지. 기본값(parse·default)이면 준비·결과를 전과 같게 둔다."""
        w=self.report['workload']
        return w['layout_body']!='parse' or w['accept_encoding']!='default'

    def prepare_layout(self,sid,token,buyers):
        """측정 전에 layout을 identity·gzip으로 한 번씩 받아 크기·헤더를 남기고, k6가 본문을 읽지 않아도 검증할 값을 만든다(#287).
        - config_version·seat_count: status 응답 검증 기준
        - seat_index: 배정 좌석의 status 비트 위치(layout 순서)
        layout은 강한 ETag라 Tomcat 압축이 걸리지 않는다(TS-9). gzip을 요청해도 압축본이 오는지는 기록만 하고,
        측정 중 요청과 다르면 checks.layout_encoding_as_requested가 실패한다. 압축 측정은 #287 2번(사전 압축)에서 한다."""
        if not self.boundary_conditions(): return {}
        result,seats=layout_probe.probe(self.http,sid,token,repeat=1)
        (self.out/'layout-cost.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        index=layout_probe.seat_indexes(seats)
        for b in buyers: b['seat_index']=index[b['schedule_seat_id']]
        self.report['layout']={'seat_count':result['seat_count'],'config_version':result['config_version'],
            'encodings':result['encodings']}
        self.save()
        print(f"[prepare] layout {result['seat_count']} seats: "+', '.join(
              f"{k} requested -> {'/'.join(v['content_encoding'])} {v['wire_bytes']} bytes" for k,v in result['encodings'].items()),flush=True)
        return {'config_version':result['config_version'],'seat_count':result['seat_count']}

    def prepare_zone(self,sid,token,seats,buyers):
        """구역 모드(#397) 준비값을 만들어 k6에 넘긴다. 구역 목록·구역 layout 응답 검증 기준과 구매자의 배정 구역이다."""
        sections=zone_fixture(seats['seats'],buyers)
        w=self.report['workload']
        require(w['zone_visits']<=len(sections),f"--zone-visits {w['zone_visits']} exceeds {len(sections)} sections")
        self.report['zone']={'sections':len(sections),'seat_count_per_section':sorted({s['seat_count'] for s in sections})}
        if self.boundary_conditions():
            # 경계 측정 조건이면 첫 구역 layout의 크기·헤더도 남긴다. 전체 layout(layout-cost.json)과 비교한다
            result,_=layout_probe.probe(self.http,sid,token,repeat=1,section=sections[0]['section'])
            (self.out/'layout-cost-section.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
            self.report['zone']['layout']={'section':result['section'],'seat_count':result['seat_count'],'encodings':result['encodings']}
        if w['accept_encoding']=='gzip':
            self.report['zone']['gzip_primed_seconds']=self.prime_section_gzip(sid,token,[s['section'] for s in sections])
        self.save()
        print(f"[prepare] zone: {len(sections)} sections, visits {w['zone_visits']}, status reads {w['status_reads'] or 'split default'}",flush=True)
        return {'config_version':seats['configVersion'],'sections':sections,
                'zone_visits':w['zone_visits'],'status_reads':w['status_reads']}

    def prime_section_gzip(self,sid,token,names,timeout=30,clock=time.monotonic,sleep=time.sleep):
        """gzip 측정 전에 모든 구역 layout의 압축본을 만들어 둔다(#397). 걸린 초를 돌려준다.
        서버는 (회차, configVersion, 구역) 본문을 처음 요청받을 때 원본을 주고 압축은 뒤에서 한다(#287 사전 압축).
        split은 준비 단계 layout_probe가 전체 layout을 한 번 받아 압축이 끝난 채로 측정했다. 구역은 20개라 짧은 측정에서는
        구역마다 첫 요청이 원본으로 나가 checks.layout_encoding_as_requested가 실패한다(11/13 short).
        운영에서도 구역마다 첫 요청 뒤에는 압축본이 나가므로, split과 같게 압축이 끝난 상태에서 잰다."""
        start=clock();waiting=list(names)
        while waiting:
            waiting=[n for n in waiting if layout_probe.fetch(self.http,sid,token,'gzip',n)['content_encoding']!='gzip']
            if not waiting:break
            require(clock()-start<timeout,f'section layout gzip not ready in {timeout}s: {len(waiting)} sections')
            sleep(.5)
        seconds=clock()-start
        print(f'[prepare] zone: gzip layout ready for {len(names)} sections ({seconds:.1f}s)',flush=True)
        return seconds

    def db_size(self):
        return self.db("SELECT coalesce(json_agg(x),'[]') FROM (SELECT schemaname,relname,n_live_tup estimated_live_rows,pg_total_relation_size(relid) total_bytes FROM pg_stat_user_tables WHERE schemaname IN ('user_schema','performance_schema','reservation_schema','payment_schema','fake_pg_schema') ORDER BY schemaname,relname) x")

    def load(self):
        self.stage='measure'
        folder=self.out.name
        loadenv=self.env.copy()
        loadenv.update(S2_INPUT=f'/results/{folder}/input.json',S2_SUMMARY=f'/results/{folder}/k6-summary.json',
                       K6_PROMETHEUS_RW_STALE_MARKERS='true')
        args=self.cmd+['run','--rm','--no-deps','-T','--name',self.container_name,
            '-e','S2_INPUT','-e','S2_SUMMARY','-e','K6_PROMETHEUS_RW_STALE_MARKERS','k6','run','--quiet','--out','experimental-prometheus-rw',self.k6_script]
        self.report['load_started_at']=datetime.now(KST).isoformat()
        self.report['load_start_epoch']=time.time()
        self.report['measure_start_epoch']=time.time()+(self.args.warmup+5 if self.args.warmup else 0)
        self.report['measure_end_epoch']=self.report['measure_start_epoch']+self.args.duration
        self.save()
        print(f'[load] {self.args.rate} journeys/s, {self.args.duration}s, warmup {self.args.warmup}s; k6 log: {self.out / "k6.log"}',flush=True)
        self.process=subprocess.Popen(args,env=loadenv,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,
            text=True,encoding='utf-8',errors='replace')
        def output():
            with (self.out/'k6.log').open('w',encoding='utf-8') as log:
                for line in self.process.stdout:
                    log.write(line);log.flush()
                    if 'S2_EVIDENCE ' in line or 'S2_WINDOW ' in line or 'S4_REQUEST ' in line:
                        # k6의 logfmt msg는 JSON 문자열로 이스케이프되어 있다.
                        try:
                            if 'msg=' in line:
                                message=json.JSONDecoder().raw_decode(line.split('msg=',1)[1].lstrip())[0]
                            else: message=line
                            if 'S4_REQUEST ' in message:
                                if hasattr(self,'on_request'):
                                    self.on_request(json.JSONDecoder().raw_decode(message.split('S4_REQUEST ',1)[1])[0])
                            elif 'S2_WINDOW ' in message:
                                window=json.JSONDecoder().raw_decode(message.split('S2_WINDOW ',1)[1])[0]
                                self.report.setdefault('measurement_windows',{})[window['scenario']]=window
                            else:
                                payload=json.JSONDecoder().raw_decode(message.split('S2_EVIDENCE ',1)[1])[0]
                                self.report['journeys'].append(payload)
                        except (ValueError,IndexError,TypeError):
                            self.report.setdefault('evidence_parse_errors',0)
                            self.report['evidence_parse_errors']+=1
        reader=threading.Thread(target=output,daemon=True);reader.start()
        last=time.monotonic()
        while self.process.poll() is None:
            time.sleep(1)
            if time.monotonic()-last>=15:
                self.save();last=time.monotonic()
                print('[load] '+self.progress(),flush=True)
        reader.join(timeout=10)
        require(not reader.is_alive(),'k6 output reader did not finish')
        self.report['k6_exit_code']=self.process.returncode
        self.report['load_end_epoch']=time.time()
        windows=list(self.report.get('measurement_windows',{}).values())
        if windows:
            self.report['measure_start_epoch']=min(w['start'] for w in windows)
            self.report['measure_end_epoch']=max(w['end'] for w in windows)
        self.report['load_finished_at']=datetime.now(KST).isoformat();self.save()

    def review(self):
        self.stage='review'
        summary_path=self.out/'k6-summary.json'
        require(summary_path.exists(),'k6 summary missing; see k6.log (image/startup/script issue)')
        summary=json.loads(summary_path.read_text(encoding='utf-8'))
        m=summary['metrics']
        def value(name,key,default=0): return m.get(name,{}).get('values',{}).get(key,default)
        started={kind:value(kind+'_started','count') for kind in ('browse','seat','booking')}
        dropped=value('dropped_iterations','count')
        expected=self.args.rate*self.args.duration
        completed_in_window=sum(r.get('outcome')=='confirmed' and self.report['measure_start_epoch']<=r.get('completed_epoch',0)<self.report['measure_end_epoch'] for r in self.report['journeys'])
        self.report['measurements']={
            'expected_journeys':expected,'actual_started':started,'actual_start_rate':sum(started.values())/self.args.duration,
            'dropped_iterations':dropped,'booking_confirmed':value('booking_confirmed','count'),
            'booking_completed_during_measure':completed_in_window,
            'completion_tps_during_measure':completed_in_window/self.args.duration,
            'booking_completed_during_drain':value('booking_confirmed','count')-completed_in_window,
            'http_total_including_warmup':value('http_reqs','count'),
            'system_error_rate_measure':value('system_errors{phase:measure}','rate',None),
            'journey_success_rate_measure':value('journey_success{phase:measure}','rate',None),
            'booking_duration_ms':m.get('booking_completion_duration',{}).get('values',{}),
            'http_duration_ms_measure':m.get('measured_http_duration',{}).get('values',{}),
            'profile_duration_ms_measure':{kind:m.get(kind+'_duration',{}).get('values',{}) for kind in ('browse','seat','booking_journey')},
            'journey_duration_ms_including_warmup':m.get('journey_duration',{}).get('values',{})}
        self.report['checks']['arrival_rate_achieved']=dropped==0 and abs(sum(started.values())-expected)<=3
        self.report['checks']['measurement_windows_recorded']=len(self.report.get('measurement_windows',{}))==3
        self.report['checks']['mix_achieved']=all(abs(started[k]-expected*w)<=2 for k,w in [('browse',.7),('seat',.2),('booking',.1)])
        self.report['checks']['k6_thresholds']=self.report['k6_exit_code']==0
        self.report['checks']['booking_evidence_complete']=len(self.report['journeys'])==started['booking'] and not self.report.get('evidence_parse_errors')
        if self.report['workload']['seat_api']=='zone':
            # 여정 옵션대로 요청이 나갔는지 확인용(#397)
            self.report['measurements']['zone']={'sections_reads':value('seat_sections_reads','count'),
                'layout_reads':value('seat_layout_reads','count'),'status_reads':value('seat_reads','count')}
        if self.boundary_conditions():
            self.report['measurements']['layout']=layout_measurements(value,m)
            self.report['checks']['layout_encoding_as_requested']=encoding_as_requested(self.report['workload']['accept_encoding'],self.report['measurements']['layout'])
        sid=int(self.report['fixtures']['schedule_id'])
        # 해당 회차에 한정한다. 반복 금융 검사 전체 이력을 스캔하지 않는다.
        sql=f"""SELECT json_build_object('rows',(SELECT coalesce(json_agg(x),'[]') FROM (
          SELECT r.reservation_id,r.user_id,r.order_id,r.status,r.payment_amount,(r.pg_auth_token IS NULL) auth_cleared,
           (SELECT count(*) FROM reservation_schema.p_tickets t WHERE t.reservation_id=r.reservation_id AND t.ticket_code IS NOT NULL AND t.price=r.payment_amount AND t.deleted_at IS NULL) issued_tickets,
           (SELECT count(*) FROM reservation_schema.p_schedule_seats s WHERE s.reservation_id=r.reservation_id AND s.status='SOLD') sold_seats,
           (SELECT min(s.id) FROM reservation_schema.p_schedule_seats s WHERE s.reservation_id=r.reservation_id AND s.status='SOLD') sold_seat_id,
           (SELECT count(*) FROM payment_schema.p_payments p WHERE p.reservation_id=r.reservation_id AND p.deleted_at IS NULL) payment_rows,
           (SELECT count(*) FROM payment_schema.p_payments p WHERE p.reservation_id=r.reservation_id AND p.order_id=r.order_id AND p.user_id=r.user_id AND p.schedule_id=r.schedule_id AND p.performance_id=r.performance_id AND p.seller_id=r.seller_id AND p.status='DONE' AND p.amount=r.payment_amount AND p.pg_provider='TOSS' AND p.deleted_at IS NULL) correct_payments,
           (SELECT count(*) FROM fake_pg_schema.pg_transaction g WHERE g.order_id=r.order_id AND g.provider='TOSS' AND g.status='DONE' AND g.amount=r.payment_amount AND coalesce(g.canceled_amount,0)=0) correct_pg
           FROM reservation_schema.p_reservations r WHERE r.schedule_id={sid} AND r.deleted_at IS NULL) x),
           'seats',(SELECT json_build_object('total',count(*),'sold',count(*) FILTER(WHERE status='SOLD'),'held',count(*) FILTER(WHERE status='HOLD')) FROM reservation_schema.p_schedule_seats WHERE schedule_id={sid}),
           'orphan_payments',(SELECT count(*) FROM payment_schema.p_payments p WHERE p.schedule_id={sid} AND p.deleted_at IS NULL AND NOT EXISTS(SELECT 1 FROM reservation_schema.p_reservations r WHERE r.reservation_id=p.reservation_id AND r.order_id=p.order_id)),
           'orphan_pg',(SELECT count(*) FROM fake_pg_schema.pg_transaction g WHERE g.provider='TOSS' AND g.order_id IN(SELECT p.order_id FROM payment_schema.p_payments p WHERE p.schedule_id={sid}) AND g.status='DONE' AND NOT EXISTS(SELECT 1 FROM payment_schema.p_payments p WHERE p.order_id=g.order_id AND p.status='DONE')));"""
        evidence=self.db(sql.rstrip(';'))
        (self.out/'financial-verification.json').write_text(json.dumps(evidence,indent=2)+'\n',encoding='utf-8')
        by_id={r['reservation_id']:r for r in evidence['rows']}
        confirmed=[r for r in self.report['journeys'] if r['outcome']=='confirmed']
        bad=[]
        for e in confirmed:
            row=by_id.get(e.get('reservation_id'))
            if not (row and row['user_id']==e['user_id'] and row['order_id']==e.get('order_id') and row['status']=='CONFIRMED'
                and row['auth_cleared'] and row['sold_seat_id']==e.get('schedule_seat_id')
                and row['payment_amount']==e.get('amount') and all(row[k]==1 for k in ('issued_tickets','sold_seats','payment_rows','correct_payments','correct_pg'))):
                bad.append(e.get('reservation_id'))
        remaining=[r['reservation_id'] for r in evidence['rows'] if r['status']!='CONFIRMED']
        self.report['financial_summary']={'confirmed_evidence':len(confirmed),'db_confirmed':sum(r['status']=='CONFIRMED' for r in evidence['rows']),
            'mismatched_reservations':bad,'unfinished_reservations':remaining,'orphan_payments':evidence['orphan_payments'],
            'orphan_pg':evidence['orphan_pg'],'seats':evidence['seats']}
        self.report['checks']['financial_consistency']=not bad and not remaining and evidence['orphan_payments']==0 and evidence['orphan_pg']==0 and \
            len(confirmed)==len(evidence['rows'])==evidence['seats']['sold'] and len(confirmed)==value('booking_confirmed','count') and \
            evidence['seats']['total']==self.args.seats and evidence['seats']['held']==0
        self.finish_foreign()
        self.report['verdict']='PASS' if all(self.report['checks'].values()) else 'FAIL'

    def prometheus(self):
        start=self.report.get('load_start_epoch')
        if not start:return
        end=self.report.get('load_end_epoch',time.time())
        queries={
          'targets':'up','cpu_cores':'rate(integration_container_cpu_usage_seconds_total[30s])',
          'cpu_throttled_seconds':'rate(integration_container_throttled_seconds_total[30s])',
          'hikari_pending':'hikaricp_connections_pending','hikari_active':'hikaricp_connections_active',
          'tomcat_busy':'tomcat_threads_busy_threads','pg_waits':'integration_pg_sessions',
          'windows_commit':'integration_windows_perf_bytes','vm_swap':'integration_vm_memory_bytes{kind=~"Swap.*"}',
          'memory_events':'integration_container_memory_events_total{kind=~"oom|oom_kill"}',
          'vm_pressure':'integration_vm_pressure_ratio','kafka_lag':'kafka_consumergroup_lag',
          'redis_blocked':'redis_blocked_clients',
          'gc_pause_seconds':'rate(jvm_gc_pause_seconds_sum[30s])','gc_allocated_bytes':'rate(jvm_gc_memory_allocated_bytes_total[30s])',
          'http_p99':'histogram_quantile(0.99,sum by(le,application,uri)(rate(http_server_requests_seconds_bucket[30s])))',
          'k6_requests':'rate(k6_http_reqs_total{phase="measure"}[30s])',
          'k6_confirmed':'rate(k6_booking_confirmed_total[30s])',
          # 스냅샷 생성 시간(#397: 구역 나누기 포함)과 요청 스레드 대기 시간. 회차 태그가 없어 측정 중 모든 회차의 합이다
          'snapshot_load_p99':'histogram_quantile(0.99,sum by(le)(rate(reservation_seat_snapshot_load_seconds_bucket{result="success"}[30s])))',
          'snapshot_get_p99':'histogram_quantile(0.99,sum by(le)(rate(reservation_seat_snapshot_get_seconds_bucket[30s])))'}
        queries['k6_requests']=f'rate(k6_http_reqs_total{{phase="measure",test_id="{self.run_id}"}}[30s])'
        queries['k6_confirmed']=f'rate(k6_booking_confirmed_total{{test_id="{self.run_id}"}}[30s])'
        gaps=[]
        for name,query in queries.items():
            try:
                url='http://127.0.0.1:19090/api/v1/query_range?'+urllib.parse.urlencode({'query':query,'start':start,'end':end,'step':5})
                with self.http.open(url,timeout=5) as r: data=json.load(r)
                (self.out/('prometheus-'+name+'.json')).write_text(json.dumps(data),encoding='utf-8')
                if name=='cpu_cores' and self.report.get('measure_start_epoch'):
                    # 압축은 CPU를 쓰므로 측정 구간의 서버 CPU를 결과에 바로 남긴다(#287). 원값은 prometheus-cpu_cores.json
                    self.report['server_cpu_cores_measure']=cpu_summary(data,self.report['measure_start_epoch'],self.report['measure_end_epoch'])
                if not data.get('data',{}).get('result'):gaps.append(name)
            except Exception:gaps.append(name)
        self.report['observation_gaps']=gaps
        self.report['observation_notes']={'kafka_lag':'Empty series may mean no active consumer group; this booking flow is synchronous, not a Kafka completion test',
            'host_metrics':'Start/restart observe.py before a measured stage. Missing series must not be interpreted as zero.'}

    def execute(self):
        try:
            self.save();self.preflight();self.report['database_before']=self.db_size()
            self.prepare();self.load();self.review();self.report['database_after']=self.db_size()
        except KeyboardInterrupt:
            # Ctrl+C로 Docker CLI만 끝내고 부하 컨테이너가 남는 일을 막는다.
            self.report.update(verdict='INTERRUPTED',error='Operator interrupted; fixtures retained')
            if self.process and self.process.poll() is None:
                subprocess.run(['docker','stop','--time','10',self.container_name],capture_output=True,timeout=20)
                try:self.process.wait(timeout=15)
                except subprocess.TimeoutExpired:self.process.terminate()
        except Exception as e:
            self.report.update(verdict='FAIL',error=str(e) if isinstance(e,Failure) else type(e).__name__,failed_stage=self.stage)
        finally:
            if self.process and self.process.poll() is None:
                try:
                    subprocess.run(['docker','stop','--time','10',self.container_name],capture_output=True,timeout=20)
                    self.process.wait(timeout=15)
                except (OSError,subprocess.SubprocessError):
                    self.report['cleanup_warning']='Check ticket-integration-s2-load status manually'
            self.prometheus()
            self.report['finished_at']=datetime.now(KST).isoformat();self.save()
            doc=ROOT/'backend/payment-service/docs-local/performance-test-progress.md'
            if doc.exists():
                rel=self.out.relative_to(ROOT).as_posix()
                if rel not in doc.read_text(encoding='utf-8'):
                    with doc.open('a',encoding='utf-8') as f:
                        f.write(f'\n- 통합 S2 `{self.run_id}`: {self.report["verdict"]}, {self.args.rate}여정/s × {self.args.duration}초, 70/20/10 혼합. 수치·오류·DB 대조·관측 공백은 `{rel}/result.json`. 준비/예열/금융 검사는 측정 구간과 분리, 시험 데이터 보존, 자원 설정 변경 없음.\n')
        print('Verdict: '+self.report['verdict']+'; results: '+str(self.out),flush=True)
        if 'error' in self.report:print(str(self.report['error']),flush=True)
        if self.report.get('measurements'):print(json.dumps(self.report['measurements'],indent=2),flush=True)
        if self.report.get('observation_gaps'): print('Observation gaps: '+', '.join(self.report['observation_gaps']),flush=True)
        return 0 if self.report['verdict']=='PASS' else 1

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rate',type=int,default=25,help='user journeys started per second')
    p.add_argument('--duration',type=int,default=60,help='measured seconds, excludes warmup and drain')
    p.add_argument('--warmup',type=int,default=20,help='browse-only warmup seconds')
    p.add_argument('--seats',type=int,default=2000,help='keep identical across compared rates')
    p.add_argument('--viewers',type=int,default=50,help='fixed account pool for seat-only visitors')
    p.add_argument('--vus',type=int,help='default max(200, rate*6), split 70/20/10')
    p.add_argument('--max-vus',type=int,help='default max(1000, vus, rate*7)')
    p.add_argument('--seat-api',choices=['legacy','split','zone'],default='legacy',
                   help='legacy: GET seats map; split: layout once per journey + status reads (#271); '
                        'zone: sections, then layout/status per section (#397)')
    p.add_argument('--zone-visits',type=int,default=1,
                   help='zone only: sections visited per seat/booking journey; booking visits its assigned section last')
    p.add_argument('--status-reads',type=int,
                   help='zone only: status reads per visited section; default same as split (seat 3..5, booking 1)')
    p.add_argument('--venue-layout',choices=VENUE_LAYOUTS,default='single',
                   help='single: all seats in section A row 1 (as before); sections: 25 seats/row, --section-seats per section A, B, ... (#397)')
    p.add_argument('--section-seats',type=int,default=DEFAULT_SECTION_SEATS,
                   help='sections only: seats per section, multiple of 25 (default 1000 = #287 browser fixture)')
    p.add_argument('--allow-foreign',action='store_true',help=foreign.ALLOW_HELP)
    p.add_argument('--layout-body',choices=['parse','skip'],default='parse',
                   help='skip: k6 does not keep the layout body (split or zone, #287 boundary)')
    p.add_argument('--accept-encoding',choices=['default','identity','gzip'],default='default',
                   help='Accept-Encoding sent by k6; default sends none as before (#287)')
    a=p.parse_args()
    if option_error(a):p.error(option_error(a))
    if a.vus is None:a.vus=max(200,a.rate*6)
    if a.max_vus is None:a.max_vus=max(1000,a.vus,a.rate*7)
    if not (1<=a.rate<=400 and 10<=a.duration<=600 and 0<=a.warmup<=120 and 1<=a.viewers<=500 and 3<=a.vus<=a.max_vus<=3000 and 1<=a.seats<=20000):
        p.error('rate 1..400, duration 10..600, warmup 0..120, viewers 1..500, 3<=vus<=max-vus<=3000, seats 1..20000')
    try: scenario=MixedScenario(a)
    except Failure as e:p.error(str(e))
    raise SystemExit(scenario.execute())
