"""Task 12(#141) 결제·환불 장애 주입 정합성 시험 실행기.

사용:
  python script/task12/run.py up                       단독 환경을 빌드해 띄운다
  python script/task12/run.py down                     단독 환경을 내린다(볼륨 유지). --volumes를 붙이면 DB도 지운다
  python script/task12/run.py list                     시나리오 목록
  python script/task12/run.py run A1 B1 [--stop-on-fail]
                                                       시나리오 실행. 결과는 script/task12/results/<실행 ID>/
  python script/task12/run.py run --group A            그룹 단위 실행(P·A·B·C·R·D·E)
  python script/task12/run.py status                   가장 최근 실행의 진행 로그와 결과
  python script/task12/run.py verify [TOSS|KAKAO]      현재 DB를 대조만 한다

TOSS와 KAKAO는 동시에 돌린다. fake-pg 장애가 PG별로 걸리고, 초기화·대조·완료 대기도 PG별로 하므로 서로 섞이지 않는다.
컨테이너 중단처럼 두 PG에 함께 영향을 주는 시나리오(exclusive)는 앞의 시나리오가 끝난 뒤 혼자 돌린다.
오래 걸리므로 백그라운드로 돌리고 status로 확인한다. 진행은 results/<실행 ID>/progress.log에 한 줄씩 남는다.
"""

import json
import os
import sys
import threading
import time
from datetime import datetime

import t12
from scenarios import SCENARIOS

# 원본 결과는 Git에 올리지 않는다(docs에는 공통 문서만 둔다). 요약은 #141과 PR에 남긴다.
RESULTS = os.path.join(t12.HERE, 'results')
_print_lock = threading.Lock()


def log(out_dir, line):
    stamp = datetime.now(t12.KST).strftime('%H:%M:%S')
    with _print_lock:
        print(f'{stamp} {line}', flush=True)
        with open(os.path.join(out_dir, 'progress.log'), 'a', encoding='utf-8') as f:
            f.write(f'{stamp} {line}\n')


def run_one(scenario, provider, out_dir):
    scope = 'ALL' if scenario.exclusive else provider
    fault_scope = None if scenario.exclusive else provider
    record = {
        'scenario': scenario.id,
        'provider': provider,
        'title': scenario.title,
        'exclusive': scenario.exclusive,
        'startedAt': datetime.now(t12.KST).isoformat(),
        'commit': t12.git_commit(),
    }
    try:
        if scenario.exclusive:
            t12.reset_all()
        else:
            t12.reset_provider(provider)
        stats_before = t12.FakePg.stats(fault_scope)
        outcome = scenario.run(provider)
        record['faultConfig'] = outcome.pop('faultConfig', None)
        record['faultStats'] = t12.FakePg.stats_delta(stats_before, t12.FakePg.stats(fault_scope))
        record['seed'] = t12.FakePg.seed()
        t12.FakePg.clear_faults(fault_scope)
        record['settle'] = t12.wait_settled(timeout=outcome.pop('settleTimeout', 180), provider=scope)
        violations = t12.verify(scope)
        expected = set(outcome.pop('expectedViolations', []))
        record['violations'] = violations
        record['unexpectedViolations'] = [v for v in violations if v['check'] not in expected]
        record['checks'] = outcome.pop('checks', [])
        record['notes'] = outcome
        failed_checks = [c for c in record['checks'] if not c['ok']]
        record['passed'] = not record['unexpectedViolations'] and not failed_checks and record['settle']['settled']
    except Exception as e:  # 시나리오 오류도 결과로 남긴다
        record['passed'] = False
        record['error'] = f'{type(e).__name__}: {e}'
        t12.FakePg.clear_faults(fault_scope)
    record['finishedAt'] = datetime.now(t12.KST).isoformat()
    with open(os.path.join(out_dir, f'{scenario.id}-{provider}.json'), 'w', encoding='utf-8') as f:
        json.dump(record, f, ensure_ascii=False, indent=2, default=str)
    return record


def reason_of(record):
    if record.get('error'):
        return record['error']
    bad = [v['check'] for v in record.get('unexpectedViolations', [])]
    bad += [c['name'] for c in record.get('checks', []) if not c['ok']]
    if not record.get('settle', {}).get('settled', True):
        bad.append('관찰 기간 안에 처리가 끝나지 않음')
    return ', '.join(bad)


class Stop:
    """--stop-on-fail일 때 한 줄이 불합격하면 다른 줄도 다음 시나리오부터 멈춘다."""

    def __init__(self, enabled):
        self.enabled = enabled
        self.event = threading.Event()

    def failed(self):
        if self.enabled:
            self.event.set()

    def is_set(self):
        return self.event.is_set()


def run_provider_lane(scenarios, provider, out_dir, summary, stop):
    """한 PG의 시나리오를 차례로 돌린다. 다른 PG 줄과 동시에 실행된다."""
    for scenario in scenarios:
        if stop.is_set() or provider not in scenario.providers:
            continue
        began = time.time()
        record = run_one(scenario, provider, out_dir)
        mark = 'PASS' if record['passed'] else 'FAIL'
        log(out_dir, f'{mark} {scenario.id:5} {provider:5} {time.time() - began:6.1f}s {reason_of(record)}')
        with _print_lock:
            summary.append({'scenario': scenario.id, 'provider': provider, 'passed': record['passed']})
        if not record['passed']:
            stop.failed()


def segments(ids):
    """동시에 돌릴 수 있는 묶음과 혼자 돌릴 시나리오로 나눈다. 순서는 그대로 지킨다."""
    batch = []
    for scenario_id in ids:
        scenario = SCENARIOS[scenario_id]
        if scenario.exclusive:
            if batch:
                yield 'parallel', batch
                batch = []
            yield 'exclusive', [scenario]
        else:
            batch.append(scenario)
    if batch:
        yield 'parallel', batch


def run(ids, stop_on_fail):
    run_id = datetime.now(t12.KST).strftime('%Y%m%d-%H%M%S')
    out_dir = os.path.join(RESULTS, run_id)
    os.makedirs(out_dir, exist_ok=True)
    log(out_dir, f'시작 {run_id} 커밋 {t12.git_commit()} 시나리오 {" ".join(ids)}')
    t12.reset_all()
    t12.FakePg.seed('clear')
    summary = []
    stop = Stop(stop_on_fail)
    for kind, scenarios in segments(ids):
        if stop.is_set():
            break
        if kind == 'exclusive':
            for provider in scenarios[0].providers:
                run_provider_lane(scenarios, provider, out_dir, summary, stop)
            continue
        lanes = [threading.Thread(target=run_provider_lane, args=(scenarios, p, out_dir, summary, stop))
                 for p in ('TOSS', 'KAKAO')]
        for lane in lanes:
            lane.start()
        for lane in lanes:
            lane.join()
    passed = sum(1 for r in summary if r['passed'])
    if stop.is_set():
        log(out_dir, '불합격으로 중단(--stop-on-fail)')
    log(out_dir, f'끝 통과 {passed}/{len(summary)}')
    with open(os.path.join(out_dir, 'summary.json'), 'w', encoding='utf-8') as f:
        json.dump({'runId': run_id, 'commit': t12.git_commit(), 'stopped': stop.is_set(), 'results': summary},
                  f, ensure_ascii=False, indent=2)
    return 0 if summary and passed == len(summary) and not stop.is_set() else 1


def status():
    runs = sorted(d for d in os.listdir(RESULTS) if os.path.isdir(os.path.join(RESULTS, d))) \
        if os.path.isdir(RESULTS) else []
    if not runs:
        print('실행 기록이 없다')
        return 0
    latest = os.path.join(RESULTS, runs[-1])
    print('실행:', runs[-1])
    progress = os.path.join(latest, 'progress.log')
    if os.path.exists(progress):
        print(open(progress, encoding='utf-8').read().rstrip())
    print('완료' if os.path.exists(os.path.join(latest, 'summary.json')) else '진행 중')
    return 0


def main(argv):
    if not argv or argv[0] in ('-h', '--help'):
        print(__doc__)
        return 0
    command, args = argv[0], argv[1:]
    if command == 'up':
        print(t12.compose('up', '-d', '--build', '--wait', *args))
        return 0
    if command == 'down':
        print(t12.compose('down', *args))
        return 0
    if command == 'list':
        for s in SCENARIOS.values():
            flag = '단독' if s.exclusive else '동시'
            print(f'{s.id:5} {"·".join(s.providers):11} {flag} {s.title}')
        return 0
    if command == 'status':
        return status()
    if command == 'verify':
        for v in t12.verify(args[0] if args else 'ALL'):
            print(v)
        return 0
    if command == 'run':
        stop_on_fail = '--stop-on-fail' in args
        args = [a for a in args if a != '--stop-on-fail']
        if args[:1] == ['--group']:
            ids = [i for i in SCENARIOS if i.startswith(args[1])]
        else:
            ids = args or list(SCENARIOS)
        unknown = [i for i in ids if i not in SCENARIOS]
        if unknown or not ids:
            print('없는 시나리오:', unknown or args)
            return 2
        return run(ids, stop_on_fail)
    print(__doc__)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
