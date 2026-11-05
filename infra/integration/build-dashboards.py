"""통합 관측 대시보드 생성. 실행해 JSON을 갱신한다."""
import json
from pathlib import Path

OUT = Path(__file__).parent / 'grafana' / 'dashboards'
OUT.mkdir(parents=True, exist_ok=True)
DS = {'type': 'prometheus', 'uid': 'prometheus'}

def dashboard(uid, title, rows):
    panels = []
    y = 0
    for heading, charts in rows:
        panels.append({'id': len(panels)+1, 'type': 'row', 'title': heading,
                       'gridPos': {'x': 0, 'y': y, 'w': 24, 'h': 1}, 'collapsed': False})
        y += 1
        for n, (name, expr, unit) in enumerate(charts):
            panels.append({'id': len(panels)+1, 'type': 'timeseries', 'title': name,
                'description': 'No data는 미수집/미발생일 수 있다. 0으로 대체하지 않는다. 시간 범위와 수집 상태를 확인한다.',
                'datasource': DS, 'gridPos': {'x': (n % 2)*12, 'y': y+(n//2)*8, 'w': 12, 'h': 8},
                'targets': [{'refId': chr(65+i), 'expr': e, 'legendFormat': '{{__name__}} {{comparison_phase}} {{cohort}} {{application}} {{service}} {{uri}} {{topic}} {{wait_event}} {{kind}} {{source}} {{name}} {{state}}'} for i,e in enumerate(expr.split(' or '))],
                'fieldConfig': {'defaults': {'unit': unit, 'custom': {'spanNulls': False}}, 'overrides': []},
                'options': {'tooltip': {'mode': 'multi'}, 'legend': {'displayMode': 'table', 'placement': 'bottom', 'calcs': ['lastNotNull', 'max']}}})
        y += ((len(charts)+1)//2)*8
    data = {'uid': uid, 'title': title, 'schemaVersion': 39, 'version': 1, 'editable': True,
        'tags': ['integration'], 'timezone': 'browser', 'refresh': '5s', 'time': {'from': 'now-15m', 'to': 'now'},
        'links': [{'title': t, 'url': '/d/'+u, 'type': 'link', 'keepTime': True} for u,t in [
            ('integration-overview','전체 현황'),('integration-services','서비스 병목'),('integration-infra','호스트·컨테이너'),('integration-data','DB·Redis·Kafka')]],
        'templating': {'list': [{'name':'service','type':'query','datasource':DS,
            'query':'label_values(http_server_requests_seconds_count, application)', 'refresh':1,
            'includeAll':True,'allValue':'.*','multi':True,'current':{'text':'All','value':'$__all'}},
            {'name':'test_id','type':'query','datasource':DS,'query':'label_values(k6_http_reqs_total, test_id)',
             'refresh':1,'includeAll':True,'allValue':'.*','multi':True,'current':{'text':'All','value':'$__all'}},
            {'name':'comparison_id','type':'query','datasource':DS,'query':'label_values(k6_probe_started_total, comparison_id)',
             'refresh':1,'includeAll':True,'allValue':'.*','multi':True,'current':{'text':'All','value':'$__all'}}]},
        'annotations': {'list': [{'name':'시험 단계','type':'dashboard','builtIn':1,'enable':True,'hide':False}]}, 'panels':panels}
    (OUT/(uid+'.json')).write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')

f = 'application=~"$service"'
def per_second(kind, counter):
    # rate()는 지표 이름을 지우므로, 한 패널에 겹친 카운터를 범례에서 가르려고 kind 라벨을 붙인다.
    return 'label_replace(rate('+counter+'{'+f+'}[$__rate_interval]),"kind","'+kind+'","","")'
def q(p):
    return 'histogram_quantile('+str(p)+', sum by (le,application,uri) (rate(http_server_requests_seconds_bucket{'+f+',uri!~"/actuator.*"}[$__rate_interval])))'

dashboard('integration-overview', '통합시험 01 · 전체 현황', [
 ('S4 공유 DB 장애 · 상태 0준비 1정상 2중지중 3중단 4복구 5대조 6통과 7실패', [
  ('실제 장애·복구 단계','integration_scenario4_phase','short'),
  ('S4 실행·복구·정합성 최종 판정','integration_scenario4_verified','short'),
  ('경로별 probe 성공률 · booking 404는 의도한 정상 응답','k6_s4_probe_success_rate','percentunit'),
  ('경로별 probe p95/p99','k6_s4_probe_duration_p95 or k6_s4_probe_duration_p99','s'),
  ('DB 쿼리 복구 이후 경로별 정상화 시간','integration_scenario4_recovery_seconds','s'),
  ('S4 마지막 결과 갱신 후 경과','time()-integration_scenario4_updated_timestamp_seconds','s')]),
 ('S3 영향 전파 · B 기준선(baseline)과 혼합(mixed) 비교 · PASS는 실행/정합성 판정', [
  ('B 예매 여정 p95/p99 · 사용자 생각 시간 포함','k6_probe_duration_p95{comparison_id=~"$comparison_id"} or k6_probe_duration_p99{comparison_id=~"$comparison_id"}','s'),
  ('B HTTP p95/p99','k6_probe_http_duration_p95{comparison_id=~"$comparison_id"} or k6_probe_http_duration_p99{comparison_id=~"$comparison_id"}','s'),
  ('B 여정 성공률','k6_probe_success_rate{comparison_id=~"$comparison_id"}','percentunit'),
  ('B 예매 완료/s','rate(k6_probe_confirmed_total{comparison_id=~"$comparison_id"}[$__rate_interval])','ops'),
  ('B 지연 배율 · 혼합/기준선','integration_scenario3_probe_latency_ratio','short'),
  ('S3 실행·DB 대조 통과 여부','integration_scenario3_valid','short')]),
 ('S2 혼합 부하 · 측정 phase=measure, 목표는 사용자 여정/s', [
  ('S2 목표 유입/s','integration_scenario2_target_journeys_per_second','ops'),
  ('S2 실제 여정 시작/s','sum by(journey)(rate(k6_journey_total{kind="started",phase="measure",test_id=~"$test_id"}[$__rate_interval]))','ops'),
  ('S2 예매 완료/s','rate(k6_booking_confirmed_total{phase="measure",test_id=~"$test_id"}[$__rate_interval])','ops'),
  ('S2 시스템 오류율','k6_system_errors_rate{phase="measure",test_id=~"$test_id"}','percentunit'),
  ('S2 실제 HTTP RPS','sum(rate(k6_http_reqs_total{phase="measure",test_id=~"$test_id"}[$__rate_interval]))','reqps'),
  ('S2 실패 원인/s','sum by(journey,code)(rate(k6_journey_failures_total{phase="measure",test_id=~"$test_id"}[$__rate_interval]))','ops'),
  ('S2 DB 최종 대조 · 실행중/실패=0','integration_scenario2_verified','short')]),
 ('S1 정상 흐름 · Python 순차 기능 시험, k6 부하 시험과 구분', [
  ('S1 시작·API확정·실패 누적','integration_scenario1_journey_total','short'),
  ('S1 DB 대조 최종 통과 · 실행중/실패=0','integration_scenario1_verified','short'),
  ('S1 마지막 결과 갱신 후 경과','time()-integration_scenario1_updated_timestamp_seconds','s'),
  ('S1 전체 흐름 시간 분포 · 소수 표본, 실패 소요시간 포함','histogram_quantile(0.95,integration_scenario1_journey_duration_seconds_bucket) or histogram_quantile(0.99,integration_scenario1_journey_duration_seconds_bucket)','s')]),
 ('실제 유입과 업무 완료 · 사용자 정의 지표는 여정 스크립트 연결 후 표시', [
  ('HTTP RPS', 'sum(rate(k6_http_reqs_total[$__rate_interval]))', 'reqps'),
  ('시작·완료 여정/s', 'sum by(kind) (rate(k6_journey_total[$__rate_interval]))','ops'),
  ('k6 HTTP p95/p99', 'k6_http_req_duration_p95 or k6_http_req_duration_p99','s'),
  ('전체 예매 완료 p95/p99', 'k6_booking_completion_duration_p95 or k6_booking_completion_duration_p99','s'),
  ('발생기 누락/s', 'sum(rate(k6_dropped_iterations_total[$__rate_interval]))','ops'),
  ('활성·최대 VU', 'k6_vus or k6_vus_max','short')]),
 ('서비스 비교 · 아래 서비스 병목 화면으로 이동', [
  ('서비스 RPS', 'sum by(application) (rate(http_server_requests_seconds_count{'+f+',uri!~"/actuator.*"}[$__rate_interval]))','reqps'),
  ('서비스/API p99',q(.99),'s'),
  ('5xx/s', 'sum by(application) (rate(http_server_requests_seconds_count{'+f+',status=~"5.."}[$__rate_interval]))','ops'),
  ('4xx/s · 업무 실패와 시스템 오류는 응답 code로 추가 분류 필요', 'sum by(application,status) (rate(http_server_requests_seconds_count{'+f+',status=~"4.."}[$__rate_interval]))','ops')]),
 ('수집 상태 · 0은 장애, No data는 수집 여부 확인', [
  ('수집 대상 UP','up','short'),
  ('세부 수집 성공 · 0이면 권한/명령/대상 확인','integration_source_up or integration_cgroup_collection_up','short'),
  ('수집 시간','scrape_duration_seconds','s'),
  ('컨테이너 OOM 상태','integration_container_oom_killed','short'),
  ('수집기 마지막 성공 후 경과','time()-integration_collection_success_timestamp_seconds','s')])])

dashboard('integration-services','통합시험 02 · 서비스 병목',[
 ('요청과 CPU',[('API p95',q(.95),'s'),('API p99',q(.99),'s'),
  ('JVM CPU 사용률','process_cpu_usage{'+f+'}','percentunit'),
  ('컨테이너 CPU 사용 코어','rate(integration_container_cpu_usage_seconds_total[$__rate_interval])','short')]),
 ('요청 스레드·DB 풀',[
  ('Tomcat Busy/최대','tomcat_threads_busy_threads{'+f+'} or tomcat_threads_config_max_threads{'+f+'}','short'),
  ('Hikari Active/Pending/최대','hikaricp_connections_active{'+f+'} or hikaricp_connections_pending{'+f+'} or hikaricp_connections_max{'+f+'}','short'),
  ('Hikari 획득 평균','rate(hikaricp_connections_acquire_seconds_sum{'+f+'}[$__rate_interval])/rate(hikaricp_connections_acquire_seconds_count{'+f+'}[$__rate_interval])','s'),
  ('Hikari 점유 평균','rate(hikaricp_connections_usage_seconds_sum{'+f+'}[$__rate_interval])/rate(hikaricp_connections_usage_seconds_count{'+f+'}[$__rate_interval])','s'),
  ('Hikari acquire p95/p99','histogram_quantile(0.95,sum by(le,application)(rate(hikaricp_connections_acquire_seconds_bucket{'+f+'}[$__rate_interval]))) or histogram_quantile(0.99,sum by(le,application)(rate(hikaricp_connections_acquire_seconds_bucket{'+f+'}[$__rate_interval])))','s'),
  ('Hikari 점유 p95/p99','histogram_quantile(0.95,sum by(le,application)(rate(hikaricp_connections_usage_seconds_bucket{'+f+'}[$__rate_interval]))) or histogram_quantile(0.99,sum by(le,application)(rate(hikaricp_connections_usage_seconds_bucket{'+f+'}[$__rate_interval])))','s'),
  ('Hikari 타임아웃/s','rate(hikaricp_connections_timeout_total{'+f+'}[$__rate_interval])','ops')]),
 ('JVM·Gateway · Gateway 연결 풀 지표는 노출 설정 확인',[
  ('힙 사용/최대','sum by(application)(jvm_memory_used_bytes{'+f+',area="heap"}) or sum by(application)(jvm_memory_max_bytes{'+f+',area="heap"})','bytes'),
  ('GC 소요 시간/s','sum by(application)(rate(jvm_gc_pause_seconds_sum{'+f+'}[$__rate_interval]))','s'),
  ('Gateway 연결 풀 대기','reactor_netty_connection_provider_pending_connections','short'),
  ('Gateway 연결 활성','reactor_netty_connection_provider_active_connections','short')]),
 ('예매 업무 · reservation-service만 표시',[
  ('결제 진행 중·상한 초과 · 상한 초과가 0보다 크면 좌석이 묶인 예약','reservation_payment_in_progress{'+f+'} or reservation_payment_stuck{'+f+'}','short'),
  ('선점 종료/s',' or '.join(per_second(k,c) for k,c in [('만료','reservation_expired_total'),('해제','reservation_released_total'),('취소','reservation_canceled_total')]),'ops'),
  ('결제됐지만 예매 못 한 건/s',' or '.join(per_second(k,c) for k,c in [('늦은 결제 성공','reservation_payment_late_success_total'),('확정 못 해 환불','reservation_payment_unconfirmed_refunded_total')]),'ops'),
  ('복구 재전송/s',' or '.join(per_second(k,c) for k,c in [('승인 재전송','reservation_payment_confirm_resent_total'),('환불 재전송','reservation_cancel_refund_resent_total'),('모르는 환불 주문','reservation_cancel_unknown_order_total')]),'ops'),
  ('회차 취소 메시지 · 누적 DLT와 전달 실패/s',
   'reservation_schedule_cancel_dead_letter_total{'+f+'} or reservation_schedule_cancel_confirmed_after_completed{'+f+'} or '+per_second('전달 실패','reservation_schedule_cancel_delivery_failed_total'),'short'),
  ('결제 서킷 · 1인 상태가 현재 상태','resilience4j_circuitbreaker_state{'+f+',state=~"closed|open|half_open"}','short')])])

dashboard('integration-infra','통합시험 03 · Windows·Docker VM·컨테이너',[
 ('Windows · 호스트 수집기를 실행해야 표시',[
  ('물리 메모리 전체/사용 가능','integration_windows_memory_bytes','bytes'),
  ('Commit·한도·페이지파일','integration_windows_perf_bytes','bytes'),
  ('CPU 사용률','integration_windows_cpu_ratio','percentunit'),
  ('페이지 읽기·쓰기/s · 단순 page fault와 구분','integration_windows_paging_pages_per_second','ops'),
  ('디스크 지연','integration_windows_disk_latency_seconds','s'),
  ('수집기 실행 비용','integration_collection_duration_seconds','s')]),
 ('Docker Linux VM · 컨테이너별 메모리와 구분',[
  ('VM 메모리·swap 전체/여유','integration_vm_memory_bytes','bytes'),
  ('VM swap 입출력 pages/s','rate(integration_vm_swap_pages_total[$__rate_interval])','ops'),
  ('CPU·메모리·I/O pressure avg10','integration_vm_pressure_ratio','percentunit'),
  ('VM 디스크 여유','integration_vm_disk_available_bytes','bytes')]),
 ('컨테이너 · quota 스로틀링이 없어도 cpuset 경합 가능',[
  ('메모리 사용/제한','integration_container_memory_bytes','bytes'),
  ('CPU 코어 사용량','rate(integration_container_cpu_usage_seconds_total[$__rate_interval])','short'),
  ('throttled 기간 비율','rate(integration_container_nr_throttled_total[$__rate_interval])/rate(integration_container_nr_periods_total[$__rate_interval])','percentunit'),
  ('스로틀링 초/s','rate(integration_container_throttled_seconds_total[$__rate_interval])','s'),
  ('OOM·OOM kill 누적','integration_container_memory_events_total{kind=~"oom|oom_kill"}','short'),
  ('블록 I/O bytes/s','rate(integration_container_io_bytes_total[$__rate_interval])','Bps'),
  ('네트워크 bytes/s','rate(integration_container_network_bytes_total[$__rate_interval])','Bps'),
  ('Running·재시작·OOMKilled','integration_container_running or integration_container_restarts_total or integration_container_oom_killed','short')])])

dashboard('integration-data','통합시험 04 · DB·Redis·Kafka',[
 ('PostgreSQL · 진단 수집기 필요',[
  ('서비스 계정별 세션·대기','integration_pg_sessions','short'),
  ('가장 오래 열린 트랜잭션','integration_pg_oldest_transaction_seconds','s'),
  ('차단 세션 수','integration_pg_blocked_sessions','short'),
  ('SQL 누적 실행 시간 증가/s · p99가 아님','rate(integration_pg_sql_exec_seconds_total[$__rate_interval])','s'),
  ('서비스 계정별 SQL 호출/s','rate(integration_pg_sql_calls_total[$__rate_interval])','ops'),
  ('WAL 생성 bytes/s','rate(integration_pg_wal_bytes_total[$__rate_interval])','Bps')]),
 ('Redis',[
  ('사용·최대 메모리','redis_memory_used_bytes or redis_memory_max_bytes','bytes'),
  ('연결·blocked clients','redis_connected_clients or redis_blocked_clients','short'),
  ('eviction/s','rate(redis_evicted_keys_total[$__rate_interval])','ops'),
  ('명령 평균 시간','rate(redis_commands_duration_seconds_total[$__rate_interval])/rate(redis_commands_processed_total[$__rate_interval])','s')]),
 ('Kafka · lag는 실행 중인 소비 그룹 기준',[
  ('파티션별 소비 지연','kafka_consumergroup_lag','short'),
  ('소비 offset 증가/s','rate(kafka_consumergroup_current_offset[$__rate_interval])','ops'),
  ('DLT 로그 끝 offset 증가/s · 재시작/retention 해석 주의','deriv(kafka_topic_partition_current_offset{topic=~".*[dD][lL][tT].*"}[1m])','ops'),
  ('Spring Kafka 처리 시간 평균','rate(spring_kafka_listener_seconds_sum[$__rate_interval])/rate(spring_kafka_listener_seconds_count[$__rate_interval])','s')])])
print('Generated 4 integration dashboards')
