"""S5 좌석 선점 경쟁(#313): 인기 좌석 몇 개에 선점을 몰고, 성공하면 잠시 쥐었다가 해제해 다른 사용자가 다시 선점하게 한다.

python infra/integration/scenario5.py --burst 250 --duration 60 --hot-seats 1
python infra/integration/scenario5.py --rate 250 --duration 60 --hot-seats 10
python infra/integration/scenario5.py --burst 250 --hot-seats 1 --base-rate 250 --seats 2000
python infra/integration/scenario5.py --rate 0 --base-rate 250 --seats 2000
--burst N은 매 초 정각에 N명이 동시에 선점한다(오픈 순간의 몰림). --rate는 초당 rate회를 고르게 보낸다.
고른 부하는 같은 좌석 잠금이 거의 겹치지 않아(1석 250/s도 잠금 대기 0) 잠금 방식 비교에는 burst를 쓴다.
조회·결제는 하지 않는다. 회차마다 새 공연·회차·계정을 준비한다.
--base-rate R은 인기 좌석과 별도로 다른 좌석 선점을 초당 R회 고르게 함께 보낸다(#390). 인기 좌석 경쟁이 다른 좌석 선점을
늦추는지 본다. 매번 다른 좌석·다른 사용자라 서로 경쟁하지 않는다. --rate 0이면 인기 좌석 부하 없이 다른 좌석 선점만 보낸다(기준).
잠금 방식(wait|nowait)은 compose-inputs.example의 INTEGRATION_SEAT_LOCK_MODE로 바꾸고 compose.py up -d로 reservation을 다시 만든다.
"""
import argparse
from datetime import datetime
import json
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from scenario1 import Failure, require, KST, ROOT, PASSWORD, BASE, Scenario
from scenario2 import MixedScenario
import foreign

# SeatHoldService.LOCK_BUSY_MESSAGE(#365). 잠금 실패 409는 코드가 SEAT_ALREADY_TAKEN으로 같고 메시지만 다르다.
# 문구가 바뀌면 lock_busy가 taken으로 세어진다. wait 모드 check(lock_busy 0)로는 드러나지 않으므로 함께 고친다
LOCK_BUSY_MESSAGE = '다른 요청이 이 좌석을 처리 중입니다. 잠시 후 다시 시도해 주세요.'
OUTCOMES = ('created', 'existing', 'taken', 'lock_busy', 'purchase_limit', 'error')
SAMPLER_APP = 's5-lock-sampler'
# burst의 wave 간격. 매 wave에 burst명이 같은 순간에 보낸다
WAVE_MS = 1000
# wave 경계보다 이만큼 늦게 보낸 시도가 이 비율을 넘으면 동시성이 의도보다 낮아진 것이다(k6 scenario5.js LATE_MS=100ms)
BURST_LATE_LIMIT = 0.05
# 0.1초 표본기. 수집기(15초)로는 60초 측정에 표본이 4개뿐이라 잠금 대기 세션을 따로 센다. 두 모드에 같은 부담이다.
# 1초 간격은 burst wave(매 초 처음 약 0.5초)와 위상이 맞지 않으면 매번 조용한 순간만 찍었다(11/12 burst 짧은 시험, 모두 0)
LOCK_SAMPLE_SQL = ("SELECT json_build_object('t',extract(epoch FROM clock_timestamp()),"
                   "'lock',count(*) FILTER(WHERE wait_event_type='Lock'),"
                   "'blocked',count(*) FILTER(WHERE cardinality(pg_blocking_pids(pid))>0),"
                   "'active',count(*) FILTER(WHERE state='active')) "
                   "FROM pg_stat_activity WHERE backend_type='client backend' AND pid<>pg_backend_pid() "
                   "AND application_name<>'" + SAMPLER_APP + "'")
SAMPLER_MAX_SECONDS = 3600
SAMPLE_INTERVAL = 0.1
# 0.2초 CPU 표본기(#390). 수집기 CPU는 15초 간격이라 매초 burst 순간에 예매 서비스 코어가 다 찼는지 보이지 않는다.
# 예매 서비스 컨테이너 안에서 cgroup cpu.stat 첫 줄(usage_usec)을 읽는다. 표본마다 sleep 프로세스 하나가 그 컨테이너 CPU를 조금 쓴다(모든 조건에 같음).
# 중지 파일이 생기면 끝나고, 정리가 실패해도 SAMPLER_MAX_SECONDS 뒤에는 끝난다
CPU_SAMPLE_INTERVAL = 0.2
CPU_SAMPLER_SERVICE = 'reservation-service'
CPU_SAMPLER_STOP = '/tmp/s5-cpu-sampler-stop'
CPU_SAMPLER_SCRIPT = (f'rm -f {CPU_SAMPLER_STOP}; n=0; while [ $n -lt {int(SAMPLER_MAX_SECONDS/CPU_SAMPLE_INTERVAL)} ] '
                      f'&& [ ! -e {CPU_SAMPLER_STOP} ]; do read -r k v < /sys/fs/cgroup/cpu.stat; '
                      f'echo "$EPOCHREALTIME $v"; sleep {CPU_SAMPLE_INTERVAL}; n=$((n+1)); done')
# 이 비율 이상 쓰면 그 구간은 코어가 다 찬 것으로 센다
CPU_SATURATED = 0.9
# reservation만 본다. application 라벨은 Eureka 이름(대문자)일 수 있어 대소문자를 가리지 않는다
RESERVATION = 'application=~"(?i)reservation-service"'
SERVER_QUERIES = {
    'hikari_pending': f'hikaricp_connections_pending{{{RESERVATION}}}',
    'hikari_active': f'hikaricp_connections_active{{{RESERVATION}}}',
    'tomcat_busy': f'tomcat_threads_busy_threads{{{RESERVATION}}}',
    # Lock 대기 세션이 하나도 없으면 시계열 자체가 없다. 수집이 된 시각에는 0으로 채운다(빈칸과 0을 구분)
    'pg_lock_waits': 'sum(integration_pg_sessions{wait_type="Lock"}) or 0*sum(integration_pg_sessions)',
    'pg_blocked': 'integration_pg_blocked_sessions',
    # 위 gauge는 5초마다 그 순간 값이라 1초 안에 끝나는 몰림을 놓친다. 아래는 구간 최댓값·평균이라 몰림이 남는다
    'hikari_acquire_max': f'max(hikaricp_connections_acquire_seconds_max{{{RESERVATION}}})',
    'hikari_acquire_mean': (f'sum(rate(hikaricp_connections_acquire_seconds_sum{{{RESERVATION}}}[30s]))'
                            f'/sum(rate(hikaricp_connections_acquire_seconds_count{{{RESERVATION}}}[30s]))'),
    'hold_http_max': f'max(http_server_requests_seconds_max{{{RESERVATION},method="POST",uri=~".*/holds"}})',
    # GC로 멈춘 시간 비율(30초 평균)과 구간 최댓값(#390). 해제처럼 판매 검증을 부르지 않는 요청도 느려져서 서버 전체가 멈췄는지 본다
    'gc_pause_ratio': f'sum(rate(jvm_gc_pause_seconds_sum{{{RESERVATION}}}[30s]))',
    'gc_pause_max': f'max(jvm_gc_pause_seconds_max{{{RESERVATION}}})',
}

# 다른 좌석 선점(--base-rate)의 좌석은 이 시간(초) 안에 다시 쓰지 않는다. 앞 선점이 해제(hold_ms 뒤)되기 전에 같은 좌석을
# 다시 고르면 다른 좌석 선점끼리 경쟁해 taken이 나온다. hold_ms(기본 0.5초)와 응답 지연을 넉넉히 덮는 값이다
BASE_SEAT_REUSE_SECONDS = 2

def base_seat_shortage(base_rate, base_seats):
    """다른 좌석 선점에 쓸 좌석이 모자라면 필요한 좌석 수를, 충분하면 None을 돌려준다."""
    need = base_rate*BASE_SEAT_REUSE_SECONDS
    return need if base_rate and base_seats < need else None

def base_measurements(value, trend, base_rate, duration):
    """다른 좌석 선점(--base-rate)의 k6 요약. 인기 좌석 지표와 따로 둔다."""
    counts = {o: value('base_hold_'+o) for o in OUTCOMES}
    return {'rate': base_rate, 'expected_attempts': base_rate*duration, 'attempts': value('base_hold_attempts'), 'outcomes': counts,
            'hold_duration_ms': trend('base_hold_duration'),
            'release_ok': value('base_release_ok'), 'release_failed': value('base_release_failed')}

def base_checks(base, dropped):
    """다른 좌석 선점이 의도대로 나갔는지. 측정 구간 밖 반복은 보내지 않으므로 시도 수는 1초 분량까지 차이를 허용한다."""
    counts = base['outcomes']
    return {'base_arrival_rate_achieved': dropped == 0 and base['attempts'] > 0 and abs(base['attempts']-base['expected_attempts']) <= base['rate'],
            # 매번 다른 좌석·다른 사용자라 모두 201이어야 한다. 아니면 좌석이 모자라 서로 겹친 것이다(--seats를 늘린다)
            'base_all_created': base['attempts'] > 0 and counts['created'] == base['attempts'],
            'base_all_released': base['release_failed'] == 0 and base['release_ok'] == counts['created']}

def classify(status, body):
    """k6 scenario5.js의 classify와 같은 규칙. 시험 실행 없이 분류를 확인하려고 Python에도 둔다."""
    if status == 201: return 'created'
    if status == 200: return 'existing'
    if status == 409 and body.get('code') == 'SEAT_ALREADY_TAKEN':
        return 'lock_busy' if body.get('message') == LOCK_BUSY_MESSAGE else 'taken'
    if status == 409 and body.get('code') == 'PURCHASE_LIMIT_EXCEEDED': return 'purchase_limit'
    return 'error'

def seat_timeline(reservations, hot_seats):
    """좌석마다 예약을 만든 순서로 놓고, 앞 예약이 끝난(RELEASED·EXPIRED) 뒤에 다음 예약이 생겼는지 본다.
    선점은 좌석 행을 잠근 뒤 시각을 읽고, 해제는 같은 잠금 안에서 updated_at을 쓴 뒤 커밋한다. 그래서 겹치지 않았다면
    다음 created ≥ 앞 updated다. 같은 JVM 시계라 비교할 수 있다."""
    by_seat = {seat: [] for seat in hot_seats}
    for r in reservations:
        if r['seat'] in by_seat: by_seat[r['seat']].append(r)
    overlaps, active = [], {}
    for seat, rows in by_seat.items():
        rows.sort(key=lambda r: (r['created'], r['reservation_id']))
        for prev, nxt in zip(rows, rows[1:]):
            if prev['status'] not in ('RELEASED', 'EXPIRED') or nxt['created'] < prev['updated']:
                overlaps.append({'seat': seat, 'previous': prev['reservation_id'], 'next': nxt['reservation_id']})
        active[seat] = [r['reservation_id'] for r in rows if r['status'] == 'HOLD']
    return {'holds_per_seat': {str(s): len(rows) for s, rows in by_seat.items()}, 'overlaps': overlaps,
            'active_holds': {str(s): ids for s, ids in active.items() if ids}}

def series_summary(data, start, end):
    """Prometheus range 결과를 시각별로 더해 측정 구간의 평균·최대를 낸다. 표본이 없으면 None(0과 구분)."""
    points = {}
    for series in data.get('data', {}).get('result', []):
        for ts, v in series['values']:
            if start <= float(ts) <= end: points[ts] = points.get(ts, 0) + float(v)
    if not points: return None
    return {'avg': sum(points.values()) / len(points), 'max': max(points.values()), 'samples': len(points)}

def sample_summary(samples, start, end):
    """0.1초 표본기 결과의 측정 구간 평균·최대와 0보다 큰 표본의 비율(몰림이 얼마나 자주 잡혔는지)."""
    rows = [s for s in samples if start <= s['t'] <= end]
    if not rows: return None
    return {key: {'avg': sum(r[key] for r in rows) / len(rows), 'max': max(r[key] for r in rows),
                  'nonzero_ratio': sum(r[key] > 0 for r in rows) / len(rows)}
            for key in ('lock', 'blocked', 'active')} | {'samples': len(rows)}

def cpu_count(cpuset):
    """'2-3,6' 같은 cpuset 문자열의 코어 수. 비었으면 None."""
    if not cpuset: return None
    n = 0
    for part in str(cpuset).split(','):
        lo, _, hi = part.strip().partition('-')
        n += int(hi or lo) - int(lo) + 1
    return n

def parse_cpu_line(line):
    """CPU 표본기 한 줄('<epoch> <usage_usec>')을 읽는다. 형식이 아니면 None."""
    fields = line.split()
    if len(fields) != 2: return None
    try: return {'t': float(fields[0]), 'usage_usec': int(fields[1])}
    except ValueError: return None

def cpu_summary(samples, start, end, cpus):
    """0.2초 CPU 표본(누적 usage_usec)을 이웃 표본 사이 사용 코어 수로 바꿔 측정 구간의 평균·p95·최대와 코어가 다 찬 구간 비율을 낸다."""
    rows = sorted((s for s in samples if start <= s['t'] <= end), key=lambda s: s['t'])
    cores = [(b['usage_usec']-a['usage_usec'])/1e6/(b['t']-a['t']) for a, b in zip(rows, rows[1:]) if b['t'] > a['t']]
    if not cores: return None
    ordered = sorted(cores)
    out = {'avg': sum(cores)/len(cores), 'p95': ordered[min(len(ordered)-1, int(len(ordered)*0.95))], 'max': ordered[-1],
           'intervals': len(cores), 'cpus': cpus}
    if cpus: out['saturated_ratio'] = sum(c >= cpus*CPU_SATURATED for c in cores)/len(cores)
    return out

def collector_sources(text):
    """수집기 /metrics에서 integration_source_up{source=...} 값을 읽는다."""
    found = {}
    for line in text.splitlines():
        if line.startswith('integration_source_up{source="'):
            name = line.split('"')[1]
            found[name] = float(line.rsplit(' ', 1)[1]) == 1
    return found

class HoldContentionScenario(MixedScenario):
    k6_script = '/scripts/scenario5.js'

    def __init__(self, args):
        self.args = args
        # 계정은 인기 좌석용(args.users) 뒤에 다른 좌석용(args.base_users)을 붙여 만든다. 두 부하가 같은 사용자를 쓰지 않는다
        Scenario.__init__(self, args.users+args.base_users, 180, scenario=5, allow_foreign=args.allow_foreign)
        self.hot_enabled = bool(args.burst or args.rate)
        self.lock_mode = self.env.get('INTEGRATION_SEAT_LOCK_MODE', 'wait')
        self.report.update(workload={'load_mode': 'burst' if args.burst else 'rate', 'burst': args.burst,
            'wave_ms': WAVE_MS if args.burst else None, 'attempts_per_second': args.rate, 'duration_seconds': args.duration,
            'hot_seats': args.hot_seats, 'seat_count': args.seats, 'users': args.users, 'hold_ms': args.hold_ms,
            'preallocated_vus': args.vus, 'max_vus': args.max_vus, 'seat_lock_mode': self.lock_mode,
            'hot_load': self.hot_enabled, 'base_rate': args.base_rate, 'base_users': args.base_users,
            'base_vus': args.base_vus, 'base_max_vus': args.base_max_vus,
            'seat_selection': 'attempt i -> users[i % users], hot_seats[i % hot_seats] (burst: i = wave*burst + slot); winners release after hold_ms',
            'base_selection': 'base attempt j -> users[users + j % base_users], base_seats[j % base_seats] (all seats except hot and check seat); release after hold_ms',
            'authentication': 'accounts login in k6 setup; tokens reused during measurement'},
            limitations=['Hold and release only; no seat reads, no payment',
                'Gateway path (Caddy -> Gateway -> reservation); sale validation calls performance-service',
                'Lock mode is a container setting; compare runs with the same build and conditions'])
        self.process = None
        self.sampler = None
        self.cpu_sampler = None
        self.container_name = 'ticket-integration-s5-load'
        self.metrics_path = ROOT/'script/integration/scenario5-metrics.prom'

    def progress(self):
        rows = self.report['journeys']
        return f'wins {len(rows)}; released ' + str(sum(r.get('released', False) for r in rows))

    def save(self):
        if not hasattr(self, 'out'): return
        (self.out/'result.json').write_text(json.dumps(self.report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        metrics = [f'integration_scenario5_target_attempts_per_second {self.args.rate}',
                   f'integration_scenario5_run_info{{test_id="{self.run_id}",lock_mode="{self.lock_mode}"}} 1',
                   f'integration_scenario5_verified {int(self.report["verdict"]=="PASS")}',
                   f'integration_scenario5_wins_total {len(self.report["journeys"])}',
                   f'integration_scenario5_updated_timestamp_seconds {time.time()}']
        tmp = self.metrics_path.with_suffix('.tmp'); tmp.write_text('\n'.join(metrics)+'\n', encoding='utf-8'); tmp.replace(self.metrics_path)

    # ---------- 준비 ----------
    def preflight(self):
        super().preflight()
        self.check_lock_mode()
        self.check_pg_diagnostics()

    def check_lock_mode(self):
        """compose가 넘긴 값만 보면, 설정이 없는 빌드(#365 전)에서 nowait 측정이 실제로는 wait로 돌아도 통과한다.
        그래서 소스에 설정이 있는지와 컨테이너에 전달된 값을 함께 본다. 컨테이너 환경 변수는 이 한 줄만 읽는다."""
        source = Path(self.env['INTEGRATION_SOURCE'])
        config = source/'backend/config-repo/reservation-service.yml'
        supports = config.exists() and 'lock-mode:' in config.read_text(encoding='utf-8')
        p = subprocess.run(self.cmd+['ps', '-q', 'reservation-service'], env=self.env, capture_output=True, text=True, timeout=20)
        cid = p.stdout.split()[0] if p.returncode == 0 and p.stdout.split() else None
        applied = None
        if cid:
            fmt = '{{range .Config.Env}}{{if eq (index (split . "=") 0) "SEAT_HOLD_LOCK_MODE"}}{{.}}{{end}}{{end}}'
            q = subprocess.run(['docker', 'inspect', '--format', fmt, cid], capture_output=True, text=True, timeout=20)
            value = q.stdout.strip()
            applied = value.split('=', 1)[1] if q.returncode == 0 and '=' in value else None
        self.report['seat_lock'] = {'expected': self.lock_mode, 'source_supports_lock_mode': supports, 'container_env': applied}
        self.report['checks']['lock_mode_applied'] = supports and applied == self.lock_mode
        if not self.report['checks']['lock_mode_applied']:
            print(f'[warning] lock mode not confirmed: source supports={supports}, container={applied}, expected={self.lock_mode}', flush=True)
        self.save()

    def check_pg_diagnostics(self):
        """DB 잠금 대기 세션이 핵심 지표다. 수집기가 postgres-diagnostics를 못 모으면 측정 전에 멈춘다(TS-12)."""
        try:
            with self.http.open('http://127.0.0.1:19100/metrics', timeout=5) as r:
                sources = collector_sources(r.read().decode('utf-8', 'replace'))
        except (OSError, urllib.error.URLError):
            sources = {}
        self.report['collector_sources'] = {k: sources.get(k) for k in ('postgres-diagnostics', 'postgres-statements')}
        ok = sources.get('postgres-diagnostics') is True
        self.report['checks']['pg_diagnostics_up'] = ok
        if sources.get('postgres-statements') is False:
            print('[warning] postgres-statements=0: pg_stat_statements missing (pg_inside.py unavailable)', flush=True)
        self.save()
        if not ok and not self.args.allow_missing_pg_diagnostics:
            raise Failure('Collector postgres-diagnostics is not 1 (TS-12). Start observe.py and fix the DB, '
                          'or rerun with --allow-missing-pg-diagnostics (recorded as FAIL)')

    def call(self, method, path, token, payload=None):
        """기능 확인용. 오류 응답도 예외 없이 (status, body)로 돌려준다."""
        data = None if payload is None else json.dumps(payload).encode()
        h = {'Accept': 'application/json', 'Authorization': 'Bearer '+token}
        if data is not None: h['Content-Type'] = 'application/json'
        try:
            with self.http.open(urllib.request.Request(BASE+path, data=data, headers=h, method=method), timeout=30) as r:
                raw = r.read(); status = r.status
        except urllib.error.HTTPError as e:
            raw = e.read(); status = e.code
        try: body = json.loads(raw) if raw else {}
        except ValueError: body = {}
        return status, body

    def functional_check(self, sid, seat):
        """Checkpoint C: 좌석 준비로 만든 좌석으로 선점·재요청·경쟁·남의 해제·해제·재해제·재선점이 계약대로인지(api.md 좌석 절)."""
        self.stage = 'functional-check'
        accounts = []
        for who in ('a', 'b'):
            email = f's5-check-{who}-{self.run_id}@integration.local'
            accounts.append((self.signup(email), self.login(email)))
        (a_id, a), (b_id, b) = accounts
        holds = f'/api/seats/schedules/{sid}/holds'
        steps, ctx = [], {}
        def step(name, method, path, token, expect, code=None, payload=None, after=None):
            status, body = self.call(method, path, token, payload)
            ok = status == expect and (code is None or body.get('code') == code)
            if ok and after:
                try: ok = after(body)
                except (KeyError, TypeError): ok = False
            steps.append({'step': name, 'status': status, 'code': body.get('code'), 'expected': [expect, code], 'ok': bool(ok)})
            return body
        body = step('A hold', 'POST', holds, a, 201, payload={'scheduleSeatId': seat},
                    after=lambda b: b['data']['status'] == 'HOLD' and b['data']['scheduleSeatId'] == seat)
        rid = (body.get('data') or {}).get('reservationId')
        step('A hold again (same seat)', 'POST', holds, a, 200, payload={'scheduleSeatId': seat},
             after=lambda b: b['data']['reservationId'] == rid)
        step('B hold (taken)', 'POST', holds, b, 409, 'SEAT_ALREADY_TAKEN', payload={'scheduleSeatId': seat})
        step("B releases A's hold", 'DELETE', f'{holds}/{rid}', b, 403, 'FORBIDDEN')
        step('A release', 'DELETE', f'{holds}/{rid}', a, 204)
        step('A release again', 'DELETE', f'{holds}/{rid}', a, 204)
        body = step('B hold after release', 'POST', holds, b, 201, payload={'scheduleSeatId': seat})
        rid_b = (body.get('data') or {}).get('reservationId')
        step('B release', 'DELETE', f'{holds}/{rid_b}', b, 204)
        self.report['functional_check'] = {'schedule_seat_id': seat, 'user_ids': [a_id, b_id],
                                           'reservation_ids': [rid, rid_b], 'steps': steps}
        (self.out/'functional-check.json').write_text(json.dumps(self.report['functional_check'], indent=2)+'\n', encoding='utf-8')
        ok = all(s['ok'] for s in steps)
        self.report['checks']['functional_check'] = ok
        self.save()
        print('[prepare] functional check (Checkpoint C): '+('PASS' if ok else 'FAIL'), flush=True)
        require(ok, 'Hold/release functional check failed; see functional-check.json')

    def prepare(self):
        self.stage = 'prepare'; self.fixtures(seat_count=self.args.seats)
        sid = self.report['fixtures']['schedule_id']
        token = self.login(self.accounts[0][0])
        seats = self.request('GET', f'/api/seats/schedules/{sid}', token=token)
        available = sorted(s['scheduleSeatId'] for s in seats['seats'] if s['selectable'])
        require(len(available) == self.args.seats, 'Prepared seat count mismatch')
        hot = available[:self.args.hot_seats]
        self.report['fixtures']['hot_seat_ids'] = hot
        # 다른 좌석 선점은 인기 좌석과 기능 확인 좌석(마지막)을 뺀 나머지를 돌려 쓴다
        base = available[self.args.hot_seats:-1] if self.args.base_rate else []
        shortage = base_seat_shortage(self.args.base_rate, len(base))
        require(shortage is None, f'--base-rate needs at least {shortage} other seats; raise --seats')
        self.report['fixtures']['base_seat_count'] = len(base)
        self.functional_check(sid, available[-1])
        self.stage = 'prepare'
        meta = {'run_id': self.run_id, 'mode': 'burst' if self.args.burst else 'rate', 'burst': self.args.burst,
                'wave_ms': WAVE_MS, 'rate': self.args.rate, 'duration': self.args.duration,
                'vus': self.args.vus, 'max_vus': self.args.max_vus, 'password': PASSWORD, 'schedule_id': sid,
                'hot_seats': hot, 'hold_ms': self.args.hold_ms, 'lock_mode': self.lock_mode,
                'lock_busy_message': LOCK_BUSY_MESSAGE, 'hot_enabled': self.hot_enabled, 'hot_users': self.args.users,
                'base_rate': self.args.base_rate, 'base_users': self.args.base_users, 'base_seats': base,
                'base_vus': self.args.base_vus, 'base_max_vus': self.args.base_max_vus}
        users = [{'email': e, 'user_id': u} for e, u in self.accounts]
        # 공개 로컬 시험 비밀번호와 시험 ID만 저장. JWT는 k6 메모리에만 둔다
        (self.out/'input.json').write_text(json.dumps({'meta': meta, 'users': users}), encoding='utf-8')
        self.report['prepared_at'] = datetime.now(KST).isoformat(); self.save()

    # ---------- 0.1초 잠금 대기 표본기 ----------
    def start_sampler(self):
        path = self.out/'pg-lock-samples.jsonl'
        conn = f'dbname=ticket application_name={SAMPLER_APP}'
        cmd = self.cmd+['exec', '-T', 'postgres', 'psql', '-U', self.env['POSTGRES_USER'], '-X', '-qAt', conn]
        try:
            self.sampler = subprocess.Popen(cmd, env=self.env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.DEVNULL, text=True, encoding='utf-8', errors='replace')
            # 반복 횟수에 상한을 둔다. 정리가 실패해도 psql이 컨테이너에 끝없이 남지 않게 한다
            self.sampler.stdin.write(LOCK_SAMPLE_SQL+f' \\watch i={SAMPLE_INTERVAL} c={int(SAMPLER_MAX_SECONDS/SAMPLE_INTERVAL)}\n'); self.sampler.stdin.close()
        except OSError:
            self.report['lock_sampler'] = {'error': 'start failed'}; self.sampler = None; return
        def read():
            with path.open('w', encoding='utf-8') as f:
                for line in self.sampler.stdout:
                    line = line.strip()
                    if line.startswith('{'): f.write(line+'\n'); f.flush()
        self.sampler_reader = threading.Thread(target=read, daemon=True); self.sampler_reader.start()

    def stop_sampler(self):
        if not self.sampler: return
        # docker exec 클라이언트만 끝내면 컨테이너 안의 psql이 남을 수 있어 DB에서 그 연결을 끝낸다
        sql = f"SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name='{SAMPLER_APP}'"
        try:
            subprocess.run(self.cmd+['exec', '-T', 'postgres', 'psql', '-U', self.env['POSTGRES_USER'], '-d', 'ticket', '-X', '-qAt', '-c', sql],
                           env=self.env, capture_output=True, text=True, timeout=20)
            self.sampler.wait(timeout=15)
        except (OSError, subprocess.SubprocessError):
            self.sampler.kill()
        self.sampler_reader.join(timeout=5)
        self.sampler = None

    # ---------- 0.2초 예매 서비스 CPU 표본기 ----------
    def start_cpu_sampler(self):
        path = self.out/'cpu-samples.jsonl'
        cmd = self.cmd+['exec', '-T', CPU_SAMPLER_SERVICE, 'bash', '-c', CPU_SAMPLER_SCRIPT]
        try:
            self.cpu_sampler = subprocess.Popen(cmd, env=self.env, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                                stderr=subprocess.DEVNULL, text=True, encoding='utf-8', errors='replace')
        except OSError:
            self.report['cpu_sampler'] = {'error': 'start failed'}; self.cpu_sampler = None; return
        def read():
            with path.open('w', encoding='utf-8') as f:
                for line in self.cpu_sampler.stdout:
                    row = parse_cpu_line(line)
                    if row: f.write(json.dumps(row)+'\n'); f.flush()
        self.cpu_sampler_reader = threading.Thread(target=read, daemon=True); self.cpu_sampler_reader.start()

    def stop_cpu_sampler(self):
        if not self.cpu_sampler: return
        # docker exec 클라이언트만 끝내면 컨테이너 안의 반복이 남을 수 있어 중지 파일을 만든다
        try:
            subprocess.run(self.cmd+['exec', '-T', CPU_SAMPLER_SERVICE, 'touch', CPU_SAMPLER_STOP],
                           env=self.env, capture_output=True, text=True, timeout=20)
            self.cpu_sampler.wait(timeout=15)
        except (OSError, subprocess.SubprocessError):
            self.cpu_sampler.kill()
        self.cpu_sampler_reader.join(timeout=5)
        self.cpu_sampler = None

    def load(self):
        self.start_sampler(); self.start_cpu_sampler()
        try: super().load()
        finally: self.stop_cpu_sampler(); self.stop_sampler()

    # ---------- 판정 ----------
    def review(self):
        self.stage = 'review'
        summary_path = self.out/'k6-summary.json'
        require(summary_path.exists(), 'k6 summary missing; see k6.log (image/startup/script issue)')
        m = json.loads(summary_path.read_text(encoding='utf-8'))['metrics']
        def value(name, key='count', default=0): return m.get(name, {}).get('values', {}).get(key, default)
        def trend(name): return m.get(name, {}).get('values', {})
        attempts = value('hold_attempts')
        counts = {o: value('hold_'+o) for o in OUTCOMES}
        expected = self.args.rate*self.args.duration if self.hot_enabled else 0
        conflicts = counts['taken']+counts['lock_busy']+counts['purchase_limit']
        self.report['measurements'] = {
            'expected_attempts': expected, 'attempts': attempts, 'dropped_iterations': value('dropped_iterations'),
            'outcomes': counts,
            'ratio_409': conflicts/attempts if attempts else None,
            'ratio_lock_busy': counts['lock_busy']/attempts if attempts else None,
            'ratio_taken': counts['taken']/attempts if attempts else None,
            'hold_duration_ms': trend('hold_duration'),
            'hold_duration_ms_by_outcome': {o: trend('hold_duration_'+o) for o in OUTCOMES if trend('hold_duration_'+o)},
            'release_duration_ms': trend('release_duration'),
            'release_ok': value('release_ok'), 'release_failed': value('release_failed')}
        checks = self.report['checks']
        if self.args.base_rate:
            base = base_measurements(value, trend, self.args.base_rate, self.args.duration)
            self.report['measurements']['base'] = base
            checks.update(base_checks(base, value('dropped_iterations')))
        if self.hot_enabled and self.args.burst:
            # wave마다 burst명이 모두 보냈고, 경계보다 크게 늦은 시도가 적어야 의도한 동시성이다
            late = value('burst_late')
            self.report['measurements'].update(burst_late=late, burst_late_ratio=late/attempts if attempts else None,
                                               burst_skew_ms=trend('burst_skew'))
            checks['arrival_rate_achieved'] = attempts == expected and attempts > 0 and late/attempts <= BURST_LATE_LIMIT
        elif self.hot_enabled:
            checks['arrival_rate_achieved'] = value('dropped_iterations') == 0 and abs(attempts-expected) <= 3
        windows = self.report.get('measurement_windows', {})
        checks['measurement_windows_recorded'] = ('hold' in windows or not self.hot_enabled) and ('base' in windows or not self.args.base_rate)
        checks['k6_thresholds'] = self.report['k6_exit_code'] == 0
        checks['no_unexpected_responses'] = counts['error'] == 0
        # 같은 사용자가 앞 선점을 쥔 채 다시 뽑혔다(200 또는 PURCHASE_LIMIT). 지연이 users/rate초를 넘은 것이라 --users를 늘린다
        checks['no_user_overlap'] = counts['existing'] == 0 and counts['purchase_limit'] == 0
        checks['all_released'] = value('release_failed') == 0 and all(r.get('released') for r in self.report['journeys'])
        # wait 모드에는 NOWAIT 잠금 실패가 없어야 한다. 있으면 모드가 섞였거나 lock_timeout이 걸린 것이다
        checks['lock_busy_matches_mode'] = counts['lock_busy'] == 0 if self.lock_mode == 'wait' else True
        self.verify_db()
        self.finish_foreign()

    def verify_db(self):
        self.stage = 'db-verification'
        sid = int(self.report['fixtures']['schedule_id'])
        hot = self.report['fixtures']['hot_seat_ids']
        check_seat = self.report['functional_check']['schedule_seat_id']
        ids = ','.join(str(int(s)) for s in hot+[check_seat])
        evidence = self.db(f"""SELECT json_build_object(
          'reservations',(SELECT coalesce(json_agg(x),'[]') FROM (
            SELECT r.reservation_id,r.user_id,r.status,t.schedule_seat_id seat,
              extract(epoch FROM r.created_at) created,extract(epoch FROM r.updated_at) updated
            FROM reservation_schema.p_reservations r JOIN reservation_schema.p_tickets t ON t.reservation_id=r.reservation_id
            WHERE r.schedule_id={sid} AND r.deleted_at IS NULL AND t.schedule_seat_id IN ({ids})) x),
          'base_statuses',(SELECT coalesce(json_object_agg(status,n),'{{}}') FROM (
            SELECT r.status,count(*) n FROM reservation_schema.p_reservations r JOIN reservation_schema.p_tickets t ON t.reservation_id=r.reservation_id
            WHERE r.schedule_id={sid} AND r.deleted_at IS NULL AND t.schedule_seat_id NOT IN ({ids}) GROUP BY r.status) x),
          'seats',(SELECT coalesce(json_agg(x),'[]') FROM (SELECT id,status,reservation_id FROM reservation_schema.p_schedule_seats
            WHERE schedule_id={sid} AND id IN ({ids})) x))""")
        (self.out/'seat-verification.json').write_text(json.dumps(evidence, indent=2)+'\n', encoding='utf-8')
        rows = evidence['reservations']
        timeline = seat_timeline(rows, hot)
        won = {r.get('reservation_id') for r in self.report['journeys']}
        db_hot = {r['reservation_id'] for r in rows if r['seat'] in hot}
        seats = {s['id']: s for s in evidence['seats']}
        check_rows = [r for r in rows if r['seat'] == check_seat]
        self.report['seat_summary'] = {**timeline, 'wins_evidence': len(won), 'db_reservations_hot': len(db_hot),
            'db_without_evidence': sorted(db_hot-won), 'evidence_without_db': sorted(i for i in won-db_hot if i is not None)}
        checks = self.report['checks']
        checks['no_overlap'] = not timeline['overlaps'] and not timeline['active_holds']
        checks['evidence_matches_db'] = won == db_hot
        # 모든 인기 좌석이 해제 뒤 다른 예약으로 다시 선점됐다(해제→재선점 경로)
        if self.hot_enabled:
            checks['released_seats_reheld'] = all(n >= 2 for n in timeline['holds_per_seat'].values())
        if self.args.base_rate:
            # 다른 좌석 선점은 모두 해제돼야 하고, DB 예약 수가 k6가 받은 201 수와 같아야 한다
            statuses = evidence['base_statuses']
            self.report['seat_summary']['base_statuses'] = statuses
            created = self.report['measurements']['base']['outcomes']['created']
            checks['base_db_released'] = set(statuses) <= {'RELEASED'} and statuses.get('RELEASED', 0) == created
        checks['final_seats_available'] = all(seats.get(s, {}).get('status') == 'AVAILABLE' for s in hot)
        checks['functional_check_db'] = seats.get(check_seat, {}).get('status') == 'AVAILABLE' and \
            len(check_rows) == 2 and all(r['status'] == 'RELEASED' for r in check_rows)
        self.save()

    def cleanup(self):
        """측정에서 해제하지 못한 선점을 API로 해제한다. 남기면 10분 뒤 만료 스케줄러가 다음 측정 중에 돈다."""
        sid = self.report['fixtures'].get('schedule_id')
        if not sid: return
        left = self.db(f"""SELECT coalesce(json_agg(x),'[]') FROM (SELECT reservation_id,user_id FROM reservation_schema.p_reservations
            WHERE schedule_id={int(sid)} AND status='HOLD' AND deleted_at IS NULL) x""")
        emails = {u: e for e, u in getattr(self, 'accounts', [])}
        done = []
        for row in left:
            email = emails.get(row['user_id'])
            if not email: done.append({**row, 'released': False, 'reason': 'unknown account'}); continue
            status, _ = self.call('DELETE', f'/api/seats/schedules/{sid}/holds/{row["reservation_id"]}', self.login(email))
            done.append({**row, 'released': status == 204, 'status': status})
        self.report['cleanup'] = done
        if done: print(f'[cleanup] released leftover holds: {sum(d["released"] for d in done)}/{len(done)}', flush=True)

    def prometheus(self):
        start = self.report.get('load_start_epoch')
        if not start: return
        # k6는 지표를 5초마다 보내고 수집기는 15초 간격이라, 부하가 끝난 시각에서 끊으면 마지막 표본이 빠진다(짧은 회차는 k6_holds가 비었다).
        # 저장본만 15초 더 받고, 결과 요약(server_summary)은 측정 구간으로 자른다
        end = self.report.get('load_end_epoch', time.time())+15
        queries = {'targets': 'up', 'cpu_cores': 'rate(integration_container_cpu_usage_seconds_total[30s])',
                   'pg_waits': 'integration_pg_sessions',
                   'http_p99': 'histogram_quantile(0.99,sum by(le,application,uri)(rate(http_server_requests_seconds_bucket[30s])))',
                   **SERVER_QUERIES}
        # 보낸 부하만 조회한다. 보내지 않은 쪽(기준 회차의 인기 좌석 등)을 넣으면 빈 결과가 관측 누락으로 남는다
        if self.hot_enabled: queries['k6_holds'] = f'rate(k6_hold_attempts_total{{test_id="{self.run_id}"}}[30s])'
        if self.args.base_rate: queries['k6_base_holds'] = f'rate(k6_base_hold_attempts_total{{test_id="{self.run_id}"}}[30s])'
        gaps, summary = [], {}
        m_start, m_end = self.report.get('measure_start_epoch', start), self.report.get('measure_end_epoch', end)
        for name, query in queries.items():
            try:
                url = 'http://127.0.0.1:19090/api/v1/query_range?'+urllib.parse.urlencode({'query': query, 'start': start, 'end': end, 'step': 5})
                with self.http.open(url, timeout=5) as r: data = json.load(r)
                (self.out/('prometheus-'+name+'.json')).write_text(json.dumps(data), encoding='utf-8')
                if name in SERVER_QUERIES: summary[name] = series_summary(data, m_start, m_end)
                if not data.get('data', {}).get('result'): gaps.append(name)
            except Exception: gaps.append(name)
        samples_path = self.out/'pg-lock-samples.jsonl'
        samples = [json.loads(l) for l in samples_path.read_text(encoding='utf-8').splitlines()] if samples_path.exists() else []
        summary['pg_lock_100ms'] = sample_summary(samples, m_start, m_end)
        cpu_path = self.out/'cpu-samples.jsonl'
        cpu_rows = [json.loads(l) for l in cpu_path.read_text(encoding='utf-8').splitlines()] if cpu_path.exists() else []
        cpus = cpu_count(self.report.get('resources', {}).get(CPU_SAMPLER_SERVICE, {}).get('cpuset'))
        summary['reservation_cpu_200ms'] = cpu_summary(cpu_rows, m_start, m_end, cpus)
        self.report['server_summary'] = summary
        self.report['server_summary_basis'] = ('Prometheus 5s step raw values (gauges, no rate) within the measurement window; '
            'pg_lock_waits/pg_blocked come from the 15s collector; pg_lock_100ms from a 0.1s pg_stat_activity sampler; '
            'hikari_acquire_max/hold_http_max/gc_pause_max are Micrometer window maxima, hikari_acquire_mean is sum/count over 30s, '
            'gc_pause_ratio is paused seconds per second over 30s; reservation_cpu_200ms is cores used between 0.2s cgroup samples '
            '(saturated_ratio: intervals at >= 90% of the cpuset)')
        self.report['observation_gaps'] = gaps
        self.report['checks']['pg_lock_samples_recorded'] = summary['pg_lock_100ms'] is not None
        self.report['checks']['cpu_samples_recorded'] = summary['reservation_cpu_200ms'] is not None
        self.report['observation_notes'] = {'host_metrics': 'Start/restart observe.py before a measured stage. Missing series must not be interpreted as zero.'}

    def execute(self):
        try:
            self.save(); self.preflight(); self.prepare(); self.load(); self.review()
        except KeyboardInterrupt:
            self.report.update(verdict='INTERRUPTED', error='Operator interrupted; fixtures retained')
            if self.process and self.process.poll() is None:
                subprocess.run(['docker', 'stop', '--time', '10', self.container_name], capture_output=True, timeout=20)
        except Exception as e:
            self.report.update(verdict='FAIL', error=str(e) if isinstance(e, Failure) else type(e).__name__, failed_stage=self.stage)
        finally:
            if self.process and self.process.poll() is None:
                try:
                    subprocess.run(['docker', 'stop', '--time', '10', self.container_name], capture_output=True, timeout=20)
                    self.process.wait(timeout=15)
                except (OSError, subprocess.SubprocessError):
                    self.report['cleanup_warning'] = 'Check ticket-integration-s5-load status manually'
            if self.sampler: self.stop_sampler()
            try: self.cleanup()
            except Exception as e: self.report['cleanup_error'] = type(e).__name__
            self.prometheus()
            # 판정은 마지막에 한 번 한다. prometheus()가 표본기 check를 더하기 때문이다. 예외·중단이면 이미 FAIL·INTERRUPTED다
            if self.report['verdict'] == 'RUNNING':
                self.report['verdict'] = 'PASS' if all(self.report['checks'].values()) else 'FAIL'
            self.report['finished_at'] = datetime.now(KST).isoformat(); self.save()
        print('Verdict: '+self.report['verdict']+'; results: '+str(self.out), flush=True)
        if 'error' in self.report: print(str(self.report['error']), flush=True)
        if self.report.get('measurements'): print(json.dumps(self.report['measurements'], indent=2), flush=True)
        if self.report.get('server_summary'): print(json.dumps(self.report['server_summary'], indent=2), flush=True)
        failed = [k for k, v in self.report['checks'].items() if not v]
        if failed: print('Failed checks: '+', '.join(failed), flush=True)
        if self.report.get('observation_gaps'): print('Observation gaps: '+', '.join(self.report['observation_gaps']), flush=True)
        return 0 if self.report['verdict'] == 'PASS' else 1

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--rate', type=int, default=100, help='hold attempts started per second, spread evenly')
    p.add_argument('--burst', type=int, default=0,
                   help='N users fire together at every 1s wave (overrides --rate; rate=N). Use this to compare lock modes')
    p.add_argument('--duration', type=int, default=60, help='measured seconds')
    p.add_argument('--hot-seats', type=int, default=10, help='popular seats receiving all attempts (round robin)')
    p.add_argument('--seats', type=int, default=1000, help='seats in the schedule; the last one is used for the functional check')
    p.add_argument('--users', type=int, help='account pool, default max(100, rate*5): a user returns every users/rate seconds')
    p.add_argument('--hold-ms', type=int, default=500, help='winner keeps the seat this long before releasing')
    p.add_argument('--vus', type=int, help='default max(50, rate*2)')
    p.add_argument('--max-vus', type=int, help='default max(500, vus, rate*4)')
    p.add_argument('--base-rate', type=int, default=0,
                   help='also send holds on other (non-hot) seats at this rate per second, evenly (#390); 0 = off')
    p.add_argument('--base-users', type=int, help='accounts for --base-rate, default max(100, base-rate*5)')
    p.add_argument('--base-vus', type=int, help='default max(50, base-rate*2)')
    p.add_argument('--base-max-vus', type=int, help='default max(500, base-vus, base-rate*4)')
    p.add_argument('--allow-foreign', action='store_true', help=foreign.ALLOW_HELP)
    p.add_argument('--allow-missing-pg-diagnostics', action='store_true',
                   help='run even if the collector cannot read DB sessions (TS-12); checks.pg_diagnostics_up=false (FAIL)')
    a = p.parse_args()
    a.warmup = 0  # MixedScenario.load가 쓴다. 예열은 별도 회차로 한다
    if a.burst:
        if not 1 <= a.burst <= 1000: p.error('burst 1..1000')
        # 초당 시도 수가 burst다. 사용자 기본값·기대 시도 수·출력이 rate를 쓴다. VU는 burst개(VU마다 wave당 1번)
        a.rate = a.burst
        a.vus = a.max_vus = a.burst
    if a.rate == 0 and not a.base_rate: p.error('--rate 0 needs --base-rate (other seats only)')
    if a.base_users is None: a.base_users = max(100, a.base_rate*5) if a.base_rate else 0
    if a.base_vus is None: a.base_vus = max(50, a.base_rate*2) if a.base_rate else 0
    if a.base_max_vus is None: a.base_max_vus = max(500, a.base_vus, a.base_rate*4) if a.base_rate else 0
    if a.base_rate and not (1 <= a.base_rate <= 1000 and 2 <= a.base_users <= 5000 and 1 <= a.base_vus <= a.base_max_vus <= 5000):
        p.error('base-rate 1..1000, base-users 2..5000, 1<=base-vus<=base-max-vus<=5000')
    # 인기 좌석 부하가 없으면(--rate 0) 인기 좌석용 계정은 쓰지 않는다. 기능 확인은 따로 만든 계정을 쓴다
    if a.users is None: a.users = max(100, a.rate*5) if a.rate else 2
    if a.vus is None: a.vus = max(50, a.rate*2)
    if a.max_vus is None: a.max_vus = max(500, a.vus, a.rate*4)
    if not ((1 <= a.rate <= 1000 or a.rate == 0) and 10 <= a.duration <= 600 and 1 <= a.hot_seats and a.hot_seats < a.seats <= 20000
            and 2 <= a.users <= 5000 and 0 <= a.hold_ms <= 10000 and 1 <= a.vus <= a.max_vus <= 5000):
        p.error('rate 1..1000, duration 10..600, 1<=hot-seats<seats<=20000, users 2..5000, hold-ms 0..10000, 1<=vus<=max-vus<=5000')
    raise SystemExit(HoldContentionScenario(a).execute())
