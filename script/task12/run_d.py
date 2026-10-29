"""D2 → D1 단독 백그라운드 실행. 서비스 결함은 수정하지 않고 결과를 보존한다."""
import json
import os
import subprocess
import sys
import time
from datetime import datetime

import t12

OUT = os.path.join(t12.HERE, 'results', datetime.now(t12.KST).strftime('%Y%m%d-%H%M%S') + '-D')
os.makedirs(OUT, exist_ok=True)


def save(name, value):
    with open(os.path.join(OUT, name), 'w', encoding='utf-8') as f:
        json.dump(value, f, ensure_ascii=False, indent=2, default=str)


def log(message):
    line = datetime.now(t12.KST).strftime('%H:%M:%S') + ' ' + message
    print(line, flush=True)
    with open(os.path.join(OUT, 'progress.log'), 'a', encoding='utf-8') as f:
        f.write(line + '\n')


def snapshot():
    return {
        'at': datetime.now(t12.KST).isoformat(),
        'payments': t12.psql('SELECT pg_provider,status,count(*) FROM payment_schema.p_payments GROUP BY 1,2'),
        'refunds': t12.psql('SELECT recovery_mode,status,count(*) FROM payment_schema.p_refunds GROUP BY 1,2'),
        'chargedFailed': t12.psql("SELECT p.order_id FROM payment_schema.p_payments p JOIN fake_pg_schema.pg_transaction t ON t.order_id=p.order_id AND t.provider=p.pg_provider WHERE p.status='FAILED' AND t.status='DONE'"),
    }


def run_case(case):
    log(case + ' 시작')
    t12.reset_all()
    t12.FakePg.seed(14120261029)
    faults = {
        'CONFIRM': {'responseLossRate': .1, 'delayedApprovalRate': .05, 'delayedApprovalMs': 70000},
        'INQUIRE': {'serverErrorRate': .05},
        'CANCEL': {'responseLossRate': .1},
    } if case == 'D1' else {}
    for provider in ('TOSS', 'KAKAO'):
        for operation, config in faults.items():
            t12.FakePg.fault(provider, operation, config)
    save(case + '-config.json', {'commit': t12.git_commit(), 'seed': t12.FakePg.seed(), 'faults': faults,
                                'iterations': 500, 'vus': 20, 'refundRatio': .5,
                                'checkoutRetries': 60, 'checkoutRetrySeconds': 2, 'paceSeconds': 1})
    mount = os.path.join(t12.HERE, 'k6') + ':/scripts:ro'
    result_mount = OUT + ':/results'
    args = ['docker', 'run', '--rm', '--name', 'ticket-t12-k6-' + case.lower(),
            '--network', 'ticket-t12_t12-net', '-v', mount, '-v', result_mount,
            '-e', 'ITERATIONS=500', '-e', 'VUS=20', '-e', 'REFUND_RATIO=0.5',
            '-e', 'CHECKOUT_RETRIES=60', '-e', 'PACE_SECONDS=1',
            '-e', 'RUN_ID=' + os.path.basename(OUT) + '-' + case,
            'grafana/k6:0.54.0', 'run', '--summary-export=/results/' + case + '-k6.json',
            '/scripts/payment-flow.js']
    samples, restarts = [], []
    started = time.monotonic()
    with open(os.path.join(OUT, case + '-k6.log'), 'w', encoding='utf-8') as f:
        proc = subprocess.Popen(args, stdout=f, stderr=subprocess.STDOUT)
        try:
            while proc.poll() is None:
                sample = snapshot()
                sample['elapsed'] = round(time.monotonic() - started, 1)
                samples.append(sample)
                count = sum(int(r[2]) for r in sample['payments'])
                # 500건 처리 중 서로 다른 두 지점에서 SIGKILL. 정상 부하는 중단하지 않는다.
                if case == 'D1' and len(restarts) < 2 and count >= (50, 200)[len(restarts)]:
                    log(f'{case} payment SIGKILL {len(restarts)+1}/2, 결제 {count}건')
                    t12.kill('payment-service')
                    t12.start('payment-service')
                    restarts.append({'elapsed': round(time.monotonic()-started, 1), 'paymentCount': count})
                save(case + '-samples.json', samples)
                time.sleep(3)
        finally:
            t12.start('payment-service')
            t12.FakePg.clear_faults()
    log(f'{case} 부하 종료(exit={proc.returncode}), 장애 해제 후 관찰')
    cleared = time.monotonic()
    # D1은 끝난 것처럼 보여도 최소 10분 관찰해 늦은 승인·자동환불을 확인한다.
    minimum = 600 if case == 'D1' else 0
    while True:
        sample = snapshot()
        samples.append(sample)
        save(case + '-samples.json', samples)
        elapsed = time.monotonic() - cleared
        pending = t12.unresolved()
        if elapsed >= minimum and not any(pending.values()):
            break
        if elapsed > 1200:
            break
        time.sleep(5)
    violations = {p: t12.verify(p) for p in ('TOSS', 'KAKAO')}
    counts = t12.psql('SELECT count(*),count(*) FILTER (WHERE status IN (\'DONE\',\'CANCELED\')) FROM payment_schema.p_payments')[0]
    result = {'scenario': case, 'commit': t12.git_commit(), 'k6Exit': proc.returncode,
              'faultStats': t12.FakePg.stats(), 'restarts': restarts, 'violations': violations,
              'final': snapshot(), 'observationSeconds': round(time.monotonic()-cleared, 1),
              'limitations': ['seed는 난수열을 고정하나 동시 실행 순서는 보장하지 않음',
                              '복수 과금 노출은 3~5초 표본이며 정확한 이벤트 시각이 아님']}
    result['passed'] = (proc.returncode == 0 and not any(violations.values())
                        and int(counts[0]) == 500
                        and (case != 'D2' or int(counts[1]) == 500)
                        and (case != 'D1' or len(restarts) == 2))
    save(case + '-result.json', result)
    log(f"{case} {'PASS' if result['passed'] else 'FAIL'}: 결제 {counts[0]}건, 대조 위반 {sum(map(len, violations.values()))}건")
    return {'scenario': case, 'passed': result['passed']}


if __name__ == '__main__':
    log('시작 D2 → D1 (실패도 기록 후 다음 시험 진행)')
    results = []
    try:
        t12.wait_healthy('payment-service')
        t12.wait_healthy('fake-pg')
        for case in (sys.argv[1:] or ['D2', 'D1']):
            if case not in ('D2', 'D1'):
                raise ValueError('지원하지 않는 시나리오: ' + case)
            results.append(run_case(case))
        save('summary.json', {'results': results, 'commit': t12.git_commit()})
        log('종료 ' + str(results))
    except Exception as exc:
        save('error.json', {'error': repr(exc), 'completed': results})
        log('실행 오류 ' + repr(exc))
        raise
    finally:
        t12.FakePg.clear_faults()
