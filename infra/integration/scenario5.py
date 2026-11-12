"""S5 좌석 선점 경쟁(#313): 인기 좌석 몇 개에 선점을 몰고, 성공하면 잠시 쥐었다가 해제해 다른 사용자가 다시 선점하게 한다.

python infra/integration/scenario5.py --rate 250 --duration 60 --hot-seats 10
rate는 초당 선점 시도 수다. 조회·결제는 하지 않는다. 회차마다 새 공연·회차·계정을 준비한다.
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
# 1초 표본기. 수집기(15초)로는 60초 측정에 표본이 4개뿐이라 잠금 대기 세션을 따로 센다. 두 모드에 같은 부담이다
LOCK_SAMPLE_SQL = ("SELECT json_build_object('t',extract(epoch FROM clock_timestamp()),"
                   "'lock',count(*) FILTER(WHERE wait_event_type='Lock'),"
                   "'blocked',count(*) FILTER(WHERE cardinality(pg_blocking_pids(pid))>0),"
                   "'active',count(*) FILTER(WHERE state='active')) "
                   "FROM pg_stat_activity WHERE backend_type='client backend' AND pid<>pg_backend_pid() "
                   "AND application_name<>'" + SAMPLER_APP + "'")
SAMPLER_MAX_SECONDS = 3600
# reservation만 본다. application 라벨은 Eureka 이름(대문자)일 수 있어 대소문자를 가리지 않는다
RESERVATION = 'application=~"(?i)reservation-service"'
SERVER_QUERIES = {
    'hikari_pending': f'hikaricp_connections_pending{{{RESERVATION}}}',
    'hikari_active': f'hikaricp_connections_active{{{RESERVATION}}}',
    'tomcat_busy': f'tomcat_threads_busy_threads{{{RESERVATION}}}',
    # Lock 대기 세션이 하나도 없으면 시계열 자체가 없다. 수집이 된 시각에는 0으로 채운다(빈칸과 0을 구분)
    'pg_lock_waits': 'sum(integration_pg_sessions{wait_type="Lock"}) or 0*sum(integration_pg_sessions)',
    'pg_blocked': 'integration_pg_blocked_sessions',
}

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
    """1초 표본기 결과의 측정 구간 평균·최대."""
    rows = [s for s in samples if start <= s['t'] <= end]
    if not rows: return None
    return {key: {'avg': sum(r[key] for r in rows) / len(rows), 'max': max(r[key] for r in rows)}
            for key in ('lock', 'blocked', 'active')} | {'samples': len(rows)}

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
        Scenario.__init__(self, args.users, 180, scenario=5, allow_foreign=args.allow_foreign)
        self.lock_mode = self.env.get('INTEGRATION_SEAT_LOCK_MODE', 'wait')
        self.report.update(workload={'attempts_per_second': args.rate, 'duration_seconds': args.duration,
            'hot_seats': args.hot_seats, 'seat_count': args.seats, 'users': args.users, 'hold_ms': args.hold_ms,
            'preallocated_vus': args.vus, 'max_vus': args.max_vus, 'seat_lock_mode': self.lock_mode,
            'seat_selection': 'iteration i -> users[i % users], hot_seats[i % hot_seats]; winners release after hold_ms',
            'authentication': 'accounts login in k6 setup; tokens reused during measurement'},
            limitations=['Hold and release only; no seat reads, no payment',
                'Gateway path (Caddy -> Gateway -> reservation); sale validation calls performance-service',
                'Lock mode is a container setting; compare runs with the same build and conditions'])
        self.process = None
        self.sampler = None
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
        self.functional_check(sid, available[-1])
        self.stage = 'prepare'
        meta = {'run_id': self.run_id, 'rate': self.args.rate, 'duration': self.args.duration,
                'vus': self.args.vus, 'max_vus': self.args.max_vus, 'password': PASSWORD, 'schedule_id': sid,
                'hot_seats': hot, 'hold_ms': self.args.hold_ms, 'lock_mode': self.lock_mode,
                'lock_busy_message': LOCK_BUSY_MESSAGE}
        users = [{'email': e, 'user_id': u} for e, u in self.accounts]
        # 공개 로컬 시험 비밀번호와 시험 ID만 저장. JWT는 k6 메모리에만 둔다
        (self.out/'input.json').write_text(json.dumps({'meta': meta, 'users': users}), encoding='utf-8')
        self.report['prepared_at'] = datetime.now(KST).isoformat(); self.save()

    # ---------- 1초 잠금 대기 표본기 ----------
    def start_sampler(self):
        path = self.out/'pg-lock-samples.jsonl'
        conn = f'dbname=ticket application_name={SAMPLER_APP}'
        cmd = self.cmd+['exec', '-T', 'postgres', 'psql', '-U', self.env['POSTGRES_USER'], '-X', '-qAt', conn]
        try:
            self.sampler = subprocess.Popen(cmd, env=self.env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                            stderr=subprocess.DEVNULL, text=True, encoding='utf-8', errors='replace')
            # 반복 횟수에 상한을 둔다. 정리가 실패해도 psql이 컨테이너에 끝없이 남지 않게 한다
            self.sampler.stdin.write(LOCK_SAMPLE_SQL+f' \\watch i=1 c={SAMPLER_MAX_SECONDS}\n'); self.sampler.stdin.close()
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

    def load(self):
        self.start_sampler()
        try: super().load()
        finally: self.stop_sampler()

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
        expected = self.args.rate*self.args.duration
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
        checks['arrival_rate_achieved'] = value('dropped_iterations') == 0 and abs(attempts-expected) <= 3
        checks['measurement_windows_recorded'] = 'hold' in self.report.get('measurement_windows', {})
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
            WHERE r.schedule_id={sid} AND r.deleted_at IS NULL) x),
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
        checks['released_seats_reheld'] = all(n >= 2 for n in timeline['holds_per_seat'].values())
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
                   'k6_holds': f'rate(k6_hold_attempts_total{{test_id="{self.run_id}"}}[30s])', **SERVER_QUERIES}
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
        summary['pg_lock_1s'] = sample_summary(samples, m_start, m_end)
        self.report['server_summary'] = summary
        self.report['server_summary_basis'] = ('Prometheus 5s step raw values (gauges, no rate) within the measurement window; '
            'pg_lock_waits/pg_blocked come from the 15s collector; pg_lock_1s from a 1s pg_stat_activity sampler')
        self.report['observation_gaps'] = gaps
        self.report['checks']['pg_lock_samples_recorded'] = summary['pg_lock_1s'] is not None
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
    p.add_argument('--rate', type=int, default=100, help='hold attempts started per second')
    p.add_argument('--duration', type=int, default=60, help='measured seconds')
    p.add_argument('--hot-seats', type=int, default=10, help='popular seats receiving all attempts (round robin)')
    p.add_argument('--seats', type=int, default=1000, help='seats in the schedule; the last one is used for the functional check')
    p.add_argument('--users', type=int, help='account pool, default max(100, rate*5): a user returns every users/rate seconds')
    p.add_argument('--hold-ms', type=int, default=500, help='winner keeps the seat this long before releasing')
    p.add_argument('--vus', type=int, help='default max(50, rate*2)')
    p.add_argument('--max-vus', type=int, help='default max(500, vus, rate*4)')
    p.add_argument('--allow-foreign', action='store_true', help=foreign.ALLOW_HELP)
    p.add_argument('--allow-missing-pg-diagnostics', action='store_true',
                   help='run even if the collector cannot read DB sessions (TS-12); checks.pg_diagnostics_up=false (FAIL)')
    a = p.parse_args()
    a.warmup = 0  # MixedScenario.load가 쓴다. 예열은 별도 회차로 한다
    if a.users is None: a.users = max(100, a.rate*5)
    if a.vus is None: a.vus = max(50, a.rate*2)
    if a.max_vus is None: a.max_vus = max(500, a.vus, a.rate*4)
    if not (1 <= a.rate <= 1000 and 10 <= a.duration <= 600 and 1 <= a.hot_seats and a.hot_seats < a.seats <= 20000
            and 2 <= a.users <= 5000 and 0 <= a.hold_ms <= 10000 and 1 <= a.vus <= a.max_vus <= 5000):
        p.error('rate 1..1000, duration 10..600, 1<=hot-seats<seats<=20000, users 2..5000, hold-ms 0..10000, 1<=vus<=max-vus<=5000')
    raise SystemExit(HoldContentionScenario(a).execute())
