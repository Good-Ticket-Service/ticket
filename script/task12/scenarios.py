"""Task 12 시나리오. ID와 기대 결과는 #141과 결제 담당 Task 12 계획을 따른다.

시나리오 함수는 provider를 받아 dict를 돌려준다.
- checks: [{'name', 'ok', 'detail'}] 시나리오 고유 확인
- faultConfig: 적용한 장애 설정(기록용)
- expectedViolations: 이 시나리오에서 의도한 대조 위반 ID(예: 운영 인수로 남는 환불)
- settleTimeout: 처리 완료를 기다릴 최대 초
그 밖의 키는 notes로 기록한다. 장애 해제·처리 완료 대기·대조는 실행기가 한다.
"""

import json
import os
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime
from typing import Callable

import t12

BOTH = ('TOSS', 'KAKAO')


@dataclass
class Scenario:
    id: str
    title: str
    run: Callable[[str], dict]
    providers: tuple = BOTH
    # 컨테이너 중단·재기동·전체 부하처럼 두 PG에 함께 영향을 주는 시나리오. 다른 시나리오와 동시에 돌리지 않는다.
    exclusive: bool = False


def check(name, ok, detail=None):
    return {'name': name, 'ok': bool(ok), 'detail': detail}


def pg_status(order_id):
    rows = t12.FakePg.transactions(order_id) or []
    return rows[0]['status'] if rows else None


def requests_of(provider, operation, before):
    """before 이후 fake-pg에 들어온 provider·operation 요청 수."""
    after = t12.FakePg.stats(provider)
    key = f'{provider}.{operation}'
    return after.get(key, {}).get('requests', 0) - before.get(key, {}).get('requests', 0)


def has_code(body, code):
    """공통 오류 응답 형식과 무관하게 본문에 업무 오류 코드가 있는지 본다."""
    return code in str(body)


def wait_status(order_id, statuses, timeout=60):
    end = time.time() + timeout
    row = None
    while time.time() < end:
        row = t12.payment_row(order_id)
        if row and row['status'] in statuses:
            return row
        time.sleep(1)
    return row


# ---------------------------------------------------------------- P. 준비·만료

def p1_checkout_replay(provider):
    pay = t12.Payment()
    before = t12.FakePg.stats(provider)
    body = t12.checkout_body(provider)
    first = pay.post_checkout(body)
    second = pay.post_checkout(body)
    concurrent_body = t12.checkout_body(provider)
    with ThreadPoolExecutor(max_workers=10) as pool:
        results = list(pool.map(lambda _: pay.post_checkout(concurrent_body), range(10)))
    seq_ok = first[0] == 200 and second[0] == 200 and first[1]['paymentId'] == second[1]['paymentId']
    ids = {r[1].get('paymentId') for r in results if r[0] == 200}
    order_ids = (body['orderId'], concurrent_body['orderId'])
    rows = t12.psql("SELECT count(*) FROM payment_schema.p_payments WHERE order_id IN (:'a', :'b')",
                    a=order_ids[0], b=order_ids[1])
    checks = [
        check('순차 재전송: 200·같은 paymentId', seq_ok, [first[0], second[0]]),
        check('동시 10건: 모두 200', all(r[0] == 200 for r in results), [r[0] for r in results]),
        check('동시 10건: 같은 paymentId', len(ids) == 1, sorted(ids)),
        check('결제 행 2개(주문당 1개)', rows[0][0] == '2', rows),
    ]
    notes = {'readyRequests': requests_of(provider, 'READY', before)}
    # 저장한 결제 정보로 실제 결제가 끝까지 되는지 본다. 카카오는 재현 요청이 PG 준비를 다시 불러 tid가 바뀔 수 있다(F1)
    order_id = concurrent_body['orderId']
    stored = t12.payment_row(order_id)
    if provider == 'KAKAO':
        pg = (t12.FakePg.transactions(order_id) or [{}])[0]
        notes['storedTid'] = stored['pgTransactionId']
        notes['pgCurrentTid'] = pg.get('paymentKey')
        checks.append(check('F1: 저장한 tid = PG 현재 tid', stored['pgTransactionId'] == pg.get('paymentKey'),
                            {'stored': stored['pgTransactionId'], 'pg': pg.get('paymentKey'),
                             'readyRequests': notes['readyRequests']}))
    checkout_url = next((r[1].get('checkoutUrl') for r in results if r[0] == 200), None)
    try:
        token = t12.FakePg.user_authorize(provider, order_id, 10_000, checkout_url)
        status, res = pay.confirm(order_id, token)
        checks.append(check('재현된 결제로 승인 완료(DONE)', status == 200 and res.get('status') == 'DONE',
                            {'status': status, 'body': res}))
    except AssertionError as e:
        checks.append(check('재현된 결제로 승인 완료(DONE)', False, f'사용자 인증 실패: {e}'))
    repeated = p1_concurrent_rounds(provider)
    checks += repeated.pop('checks')
    return {'orderIds': list(order_ids), 'checks': checks, **notes, 'repeated': repeated}


def p2_checkout_conflict(provider):
    pay = t12.Payment()
    body = t12.checkout_body(provider)
    first = pay.post_checkout(body)
    changed = dict(body, totalPrice=body['totalPrice'] - 1000)
    second = pay.post_checkout(changed)
    row = t12.payment_row(body['orderId'])
    amount = t12.psql("SELECT amount FROM payment_schema.p_payments WHERE order_id = :'o'", o=body['orderId'])
    return {
        'orderId': body['orderId'],
        'checks': [
            check('첫 준비 200', first[0] == 200, first[0]),
            check('다른 금액: 409 PAYMENT_ORDER_CONFLICT',
                  second[0] == 409 and has_code(second[1], 'PAYMENT_ORDER_CONFLICT'), second),
            check('기존 결제 READY·금액 그대로', row['status'] == 'READY' and amount[0][0] == '10000',
                  {'row': row, 'amount': amount}),
        ],
    }


def p3_kakao_ready_faults(provider):
    pay = t12.Payment()
    checks, cases = [], []
    for name, config in [('5xx', {'serverErrorRate': 1}),
                         ('응답 유실', {'responseLossRate': 1, 'holdMs': 12000}),
                         ('지연', {'latencyMs': 12000})]:
        t12.FakePg.fault(provider, 'READY', config)
        order_id, status, res = pay.checkout(provider)
        t12.FakePg.clear_faults(provider)
        row = t12.payment_row(order_id)
        cases.append({'case': name, 'orderId': order_id, 'status': status, 'response': res, 'row': row})
        checks.append(check(f'{name}: 200 FAILED READY_FAILED',
                            status == 200 and res.get('status') == 'FAILED'
                            and res.get('failureCode') == 'READY_FAILED',
                            {'status': status, 'body': res}))
        checks.append(check(f'{name}: PG 과금 없음', pg_status(order_id) in (None, 'READY', 'EXPIRED'),
                            pg_status(order_id)))
    return {'cases': cases, 'checks': checks,
            'faultConfig': 'READY: serverErrorRate=1 / responseLossRate=1,holdMs=12000 / latencyMs=12000'}


def p4_ready_expiry(provider):
    pay = t12.Payment()
    before = t12.FakePg.stats(provider)
    order_id, status, res = pay.checkout(provider, expires_in_seconds=20)
    token = t12.FakePg.user_authorize(provider, order_id, 10_000, res.get('checkoutUrl'))
    row = wait_status(order_id, {'FAILED'}, timeout=45)
    confirm_status, confirm_body = pay.confirm(order_id, token)
    confirm_requests = requests_of(provider, 'CONFIRM', before)
    return {
        'orderId': order_id,
        'checks': [
            check('만료 잡이 FAILED EXPIRED로 닫음',
                  row and row['status'] == 'FAILED' and row['failureCode'] == 'EXPIRED', row),
            check('만료 후 승인: 200 FAILED', confirm_status == 200 and confirm_body.get('status') == 'FAILED',
                  {'status': confirm_status, 'body': confirm_body}),
            check('PG 승인 요청 0', confirm_requests == 0, confirm_requests),
            check('PG 과금 없음', pg_status(order_id) != 'DONE', pg_status(order_id)),
        ],
    }


def p5_superseded_order(provider):
    pay = t12.Payment()
    reservation_id = int(time.time() * 1000) % 10**12 + (0 if provider == 'TOSS' else 1)
    order_a, status_a, res_a = pay.checkout(provider, reservation_id=reservation_id)
    token_a = t12.FakePg.user_authorize(provider, order_a, 10_000, res_a.get('checkoutUrl'))
    order_b, status_b, res_b = pay.checkout(provider, reservation_id=reservation_id)
    before = t12.FakePg.stats(provider)
    confirm_a = pay.confirm(order_a, token_a)
    a_confirm_requests = requests_of(provider, 'CONFIRM', before)
    token_b = t12.FakePg.user_authorize(provider, order_b, 10_000, res_b.get('checkoutUrl'))
    confirm_b = pay.confirm(order_b, token_b)
    row_a = t12.payment_row(order_a)
    return {
        'orderIds': [order_a, order_b],
        'checks': [
            check('A·B 준비 200', status_a == 200 and status_b == 200, [status_a, status_b]),
            check('A는 FAILED SUPERSEDED', row_a['status'] == 'FAILED' and row_a['failureCode'] == 'SUPERSEDED', row_a),
            check('A 승인: 200 FAILED, PG 승인 요청 0',
                  confirm_a[0] == 200 and confirm_a[1].get('status') == 'FAILED' and a_confirm_requests == 0,
                  {'response': confirm_a, 'pgConfirmRequests': a_confirm_requests}),
            check('A PG 과금 없음', pg_status(order_a) != 'DONE', pg_status(order_a)),
            check('B 승인 DONE', confirm_b[0] == 200 and confirm_b[1].get('status') == 'DONE', confirm_b),
        ],
    }


def p6_confirm_without_user_auth(provider):
    pay = t12.Payment()
    order_id, status, res = pay.checkout(provider)
    fake_token = 'tgen_' + 'f' * 32 if provider == 'TOSS' else 'not-issued-pg-token'
    confirm_status, body = pay.confirm(order_id, fake_token)
    row = wait_status(order_id, {'FAILED', 'DONE'}, timeout=90)
    return {
        'orderId': order_id,
        'confirmResponse': body,
        'settleTimeout': 120,
        'checks': [
            check('승인 응답 200', confirm_status == 200, confirm_status),
            check('결제 FAILED', row and row['status'] == 'FAILED', row),
            check('PG 과금 없음', pg_status(order_id) != 'DONE', pg_status(order_id)),
        ],
    }


def prepared(provider, **kwargs):
    """준비와 사용자 인증까지 마친 주문. (orderId, 승인 토큰)"""
    pay = t12.Payment()
    order_id, status, res = pay.checkout(provider, **kwargs)
    assert status == 200 and res.get('status') == 'READY', (status, res)
    return order_id, t12.FakePg.user_authorize(provider, order_id, kwargs.get('amount', 10_000), res.get('checkoutUrl'))


def pg_row(order_id):
    rows = t12.FakePg.transactions(order_id) or []
    return rows[0] if rows else {}


def seconds_between(start, end):
    if not start or not end:
        return None
    fmt = lambda v: datetime.fromisoformat(v)
    return round((fmt(end) - fmt(start)).total_seconds(), 1)


def wait_refund(order_id, statuses=('DONE',), timeout=240):
    end = time.time() + timeout
    row = None
    while time.time() < end:
        row = t12.payment_row(order_id)
        # 실패 결제 자동 취소의 금액 재확인(FAILED·VOID_CANCEL_REJECTED)은 끝난 상태가 아니다(#162).
        if row and row['refundStatus'] in statuses and row.get('verificationReason') != 'VOID_CANCEL_REJECTED':
            return row
        time.sleep(2)
    return row


def auto_void_checks(order_id, failure_codes):
    """늦게 발견한 승인을 AUTO_VOID로 되돌렸는지 확인한다(결제 FAILED 유지, 환불 DONE, PG CANCELED)."""
    row = t12.payment_row(order_id)
    pg = pg_row(order_id)
    return [
        check(f'결제 FAILED {"/".join(failure_codes)}',
              row and row['status'] == 'FAILED' and row['failureCode'] in failure_codes, row),
        check('AUTO_VOID 환불 DONE', row and row['refundReason'] == 'AUTO_VOID' and row['refundStatus'] == 'DONE', row),
        check('PG CANCELED(순수취 0)', pg.get('status') == 'CANCELED', pg.get('status')),
    ], {'pgApprovedAt': pg.get('approvedAt'), 'pgCanceledAt': pg.get('canceledAt'),
        'chargeExposureSeconds': seconds_between(pg.get('approvedAt'), pg.get('canceledAt'))}


# ---------------------------------------------------------------- A. 승인

def a2_server_error_before_processing(provider):
    order_id, token = prepared(provider)
    before = t12.FakePg.stats(provider)
    t12.FakePg.fault(provider, 'CONFIRM', {'serverErrorRate': 1})
    started = time.time()
    status, body = t12.Payment().confirm(order_id, token)
    row = wait_status(order_id, {'FAILED', 'DONE'}, timeout=150)
    elapsed = round(time.time() - started, 1)
    return {
        'orderId': order_id, 'secondsToFailed': elapsed, 'faultConfig': {'CONFIRM': {'serverErrorRate': 1}},
        'checks': [
            check('승인 응답 200 IN_PROGRESS(결과 불명)', status == 200 and body.get('status') == 'IN_PROGRESS', body),
            check('미발견 유예 뒤 FAILED NOT_APPROVED',
                  row and row['status'] == 'FAILED' and row['failureCode'] == 'NOT_APPROVED', row),
            check('60초 유예 전에 실패로 닫지 않음', elapsed >= 55, elapsed),
            check('승인 자동 재시도 없음(PG 승인 요청 1)', requests_of(provider, 'CONFIRM', before) == 1,
                  requests_of(provider, 'CONFIRM', before)),
        ],
    }


def a3_card_rejected(provider):
    pay = t12.Payment()
    reservation_id = int(time.time() * 1000) % 10**12 + (0 if provider == 'TOSS' else 1)
    order_id, token = prepared(provider, reservation_id=reservation_id)
    before = t12.FakePg.stats(provider)
    t12.FakePg.fault(provider, 'CONFIRM', {'failRate': 1})
    status, body = pay.confirm(order_id, token)
    t12.FakePg.clear_faults(provider)
    time.sleep(3)
    rejected_requests = requests_of(provider, 'CONFIRM', before)
    retry_id, retry_token = prepared(provider, reservation_id=reservation_id)
    retry = pay.confirm(retry_id, retry_token)
    return {
        'orderIds': [order_id, retry_id], 'faultConfig': {'CONFIRM': {'failRate': 1}},
        'checks': [
            check('승인 응답 200 FAILED REJECTED', status == 200 and body.get('status') == 'FAILED'
                  and body.get('failureType') == 'REJECTED', body),
            check('PG 원본 거절 코드 보존', body.get('failureCode') in ('REJECT_CARD_PAYMENT', '-780'), body.get('failureCode')),
            check('자동 재시도 없음(PG 승인 요청 1)', rejected_requests == 1, rejected_requests),
            check('같은 예매 새 주문은 DONE', retry[0] == 200 and retry[1].get('status') == 'DONE', retry),
        ],
    }


def a4_response_lost_after_approval(provider):
    order_id, token = prepared(provider)
    t12.FakePg.fault(provider, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 15000})
    started = time.time()
    status, body = t12.Payment().confirm(order_id, token)
    row = wait_status(order_id, {'DONE', 'FAILED'}, timeout=90)
    return {
        'orderId': order_id, 'secondsToResolved': round(time.time() - started, 1),
        'faultConfig': {'CONFIRM': {'responseLossRate': 1, 'holdMs': 15000}},
        'checks': [
            check('승인 응답 200 IN_PROGRESS', status == 200 and body.get('status') == 'IN_PROGRESS', body),
            check('복구 조회로 DONE', row and row['status'] == 'DONE', row),
            check('PG DONE', pg_status(order_id) == 'DONE', pg_status(order_id)),
        ],
    }


def _unresolved_then_found(provider, order_id, token, extra_inquire=None):
    """승인 응답 유실 + 조회 장애로 60초 시한에 PG_UNRESOLVED를 만든 뒤 조회를 풀어 대사가 발견하게 한다."""
    t12.FakePg.fault(provider, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 15000})
    t12.FakePg.fault(provider, 'INQUIRE', extra_inquire or {'serverErrorRate': 1})
    status, body = t12.Payment().confirm(order_id, token)
    failed = wait_status(order_id, {'FAILED', 'DONE'}, timeout=120)
    t12.FakePg.clear_faults(provider)
    return status, body, failed


def a5_lost_and_inquiry_down(provider):
    order_id, token = prepared(provider)
    status, body, failed = _unresolved_then_found(provider, order_id, token)
    wait_refund(order_id, timeout=240)
    checks, notes = auto_void_checks(order_id, ('PG_UNRESOLVED',))
    return {
        'orderId': order_id, **notes, 'settleTimeout': 60,
        'faultConfig': {'CONFIRM': {'responseLossRate': 1}, 'INQUIRE': {'serverErrorRate': 1}},
        'checks': [check('승인 응답 200 IN_PROGRESS', body.get('status') == 'IN_PROGRESS', body),
                   check('60초 시한에 PG_UNRESOLVED', failed and failed['failureCode'] == 'PG_UNRESOLVED', failed)] + checks,
    }


def a6_delayed_approval(provider):
    order_id, token = prepared(provider)
    config = {'delayedApprovalRate': 1, 'delayedApprovalMs': 70000, 'holdMs': 15000}
    t12.FakePg.fault(provider, 'CONFIRM', config)
    status, body = t12.Payment().confirm(order_id, token)
    failed = wait_status(order_id, {'FAILED', 'DONE'}, timeout=120)
    t12.FakePg.clear_faults(provider)
    wait_refund(order_id, timeout=300)
    checks, notes = auto_void_checks(order_id, ('NOT_APPROVED', 'PG_UNRESOLVED'))
    return {
        'orderId': order_id, **notes, 'settleTimeout': 60, 'faultConfig': {'CONFIRM': config},
        'checks': [check('승인 응답 200 IN_PROGRESS', body.get('status') == 'IN_PROGRESS', body),
                   check('늦은 승인을 DONE으로 되살리지 않음', failed and failed['status'] == 'FAILED', failed)] + checks,
    }


def a7_slower_than_timeout(provider):
    order_id, token = prepared(provider)
    t12.FakePg.fault(provider, 'CONFIRM', {'latencyMs': 12000})
    status, body = t12.Payment().confirm(order_id, token)
    row = wait_status(order_id, {'DONE', 'FAILED'}, timeout=90)
    return {
        'orderId': order_id, 'faultConfig': {'CONFIRM': {'latencyMs': 12000}},
        'checks': [
            check('승인 응답 200 IN_PROGRESS', body.get('status') == 'IN_PROGRESS', body),
            check('복구 조회로 DONE', row and row['status'] == 'DONE', row),
            check('PG DONE', pg_status(order_id) == 'DONE', pg_status(order_id)),
        ],
    }


def a8_rate_limited(provider):
    orders = [prepared(provider) for _ in range(20)]
    before = t12.FakePg.stats(provider)
    t12.FakePg.fault(provider, 'CONFIRM', {'rateLimitPerSecond': 1})
    pay = t12.Payment()
    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(lambda o: pay.confirm(*o), orders))
    t12.FakePg.clear_faults(provider)
    statuses = [r[1].get('status') for r in results]
    busy = [r for r in results if r[1].get('failureCode') == 'PG_BUSY']
    after = t12.FakePg.stats(provider).get(f'{provider}.CONFIRM', {})
    limited = after.get('rateLimited', 0) - before.get(f'{provider}.CONFIRM', {}).get('rateLimited', 0)
    return {
        'statuses': statuses, 'faultConfig': {'CONFIRM': {'rateLimitPerSecond': 1}},
        'checks': [
            check('모두 DONE 또는 FAILED PG_BUSY', all(s in ('DONE', 'FAILED') for s in statuses)
                  and len(busy) == statuses.count('FAILED'), statuses),
            check('PG_BUSY 건수 = fake-pg 429 횟수', len(busy) == limited, {'busy': len(busy), 'rateLimited': limited}),
            check('한도 안의 건은 DONE', statuses.count('DONE') >= 1, statuses.count('DONE')),
        ],
    }


def a9_pg_down(provider):
    pay = t12.Payment()
    reservation_id = int(time.time() * 1000) % 10**12
    order_id, token = prepared(provider, reservation_id=reservation_id)
    t12.docker('stop', t12.container('fake-pg'))
    try:
        status, body = pay.confirm(order_id, token)
        row = wait_status(order_id, {'FAILED', 'DONE'}, timeout=100)
    finally:
        t12.start('fake-pg')
    # fake-pg가 healthy가 돼도 payment가 바로 연결하지 못하는 구간이 있다(재기동으로 바뀐 주소의 DNS 캐시로 추정).
    # 연결이 회복될 때까지 새 주문으로 시도하고, 회복까지 걸린 시간을 기록한다. 다음 시나리오에 번지지 않게 한다.
    restarted = time.time()
    retry = None
    while time.time() - restarted < 120:
        retry_id, retry_token = prepared(provider, reservation_id=reservation_id) if provider == 'TOSS' else (None, None)
        if provider == 'KAKAO':
            retry_id, status_k, res_k = pay.checkout(provider, reservation_id=reservation_id)
            if res_k.get('status') != 'READY':
                time.sleep(3)
                continue
            retry_token = t12.FakePg.user_authorize(provider, retry_id, 10_000, res_k.get('checkoutUrl'))
        retry = pay.confirm(retry_id, retry_token)
        if retry[1].get('status') == 'DONE':
            break
        time.sleep(3)
    recovery_seconds = round(time.time() - restarted, 1)
    return {
        'orderIds': [order_id, retry_id], 'confirmResponse': body, 'settleTimeout': 120,
        'pgReachableAfterRestartSeconds': recovery_seconds,
        'checks': [
            check('PG 다운 중 승인: FAILED(미과금)', row and row['status'] == 'FAILED', row),
            check('연결 실패를 PG_UNAVAILABLE로 즉시 분류', body.get('failureCode') == 'PG_UNAVAILABLE', body),
            check('PG 과금 없음', pg_status(order_id) != 'DONE', pg_status(order_id)),
            check('PG 복구 후 120초 안에 새 주문 DONE', retry and retry[1].get('status') == 'DONE',
                  {'retry': retry, 'seconds': recovery_seconds}),
        ],
    }


def a10_concurrent_confirm(provider):
    order_id, token = prepared(provider)
    before = t12.FakePg.stats(provider)
    pay = t12.Payment()
    with ThreadPoolExecutor(max_workers=50) as pool:
        results = list(pool.map(lambda _: pay.confirm(order_id, token), range(50)))
    row = wait_status(order_id, {'DONE', 'FAILED'}, timeout=60)
    statuses = sorted({r[1].get('status') for r in results})
    return {
        'orderId': order_id, 'responseStatuses': statuses,
        'checks': [
            check('응답 모두 200', all(r[0] == 200 for r in results), sorted({r[0] for r in results})),
            check('응답 상태는 IN_PROGRESS·DONE만', set(statuses) <= {'IN_PROGRESS', 'DONE'}, statuses),
            check('PG 승인 요청 1', requests_of(provider, 'CONFIRM', before) == 1, requests_of(provider, 'CONFIRM', before)),
            check('최종 DONE', row and row['status'] == 'DONE', row),
        ],
    }


def a11_expiry_boundary(provider):
    pay = t12.Payment()
    before = t12.FakePg.stats(provider)
    on_time, on_time_token = prepared(provider, expires_in_seconds=15)
    late, late_token = prepared(provider, expires_in_seconds=15)
    created = time.time()
    time.sleep(max(0, 12 - (time.time() - created)))
    first = pay.confirm(on_time, on_time_token)
    time.sleep(max(0, 18 - (time.time() - created)))
    second = pay.confirm(late, late_token)
    late_row = t12.payment_row(late)
    return {
        'orderIds': [on_time, late],
        'checks': [
            check('만료 3초 전 승인 DONE', first[1].get('status') == 'DONE', first),
            check('만료 3초 후 승인 FAILED EXPIRED', second[1].get('status') == 'FAILED'
                  and late_row['failureCode'] == 'EXPIRED', {'response': second, 'row': late_row}),
            check('PG 승인 요청은 만료 전 1건뿐', requests_of(provider, 'CONFIRM', before) == 1,
                  requests_of(provider, 'CONFIRM', before)),
        ],
    }


def a12_ab_double_charge(provider):
    pay = t12.Payment()
    reservation_id = int(time.time() * 1000) % 10**12 + (0 if provider == 'TOSS' else 1)
    order_a, token_a = prepared(provider, reservation_id=reservation_id)
    t12.FakePg.fault(provider, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 15000})
    t12.FakePg.fault(provider, 'INQUIRE', {'serverErrorRate': 1})
    pay.confirm(order_a, token_a)
    failed_a = wait_status(order_a, {'FAILED', 'DONE'}, timeout=120)
    t12.FakePg.fault(provider, 'CONFIRM', {})
    order_b, token_b = prepared(provider, reservation_id=reservation_id)
    b = pay.confirm(order_b, token_b)
    t12.FakePg.clear_faults(provider)
    wait_refund(order_a, timeout=240)
    checks, notes = auto_void_checks(order_a, ('PG_UNRESOLVED',))
    pg_b = pg_row(order_b)
    overlap = seconds_between(pg_b.get('approvedAt'), notes['pgCanceledAt'])
    return {
        'orderIds': [order_a, order_b], **notes, 'settleTimeout': 60,
        'doubleCharge': {'orders': 2, 'amount': 20_000, 'overlapSeconds': overlap},
        'checks': [check('A는 PG_UNRESOLVED로 닫힘', failed_a and failed_a['failureCode'] == 'PG_UNRESOLVED', failed_a),
                   check('B DONE 유지', b[1].get('status') == 'DONE' and pg_b.get('status') == 'DONE',
                         {'b': b, 'pg': pg_b.get('status')})] + checks,
    }


def a13_amount_mismatch(provider):
    order_id, token = prepared(provider)
    before = t12.FakePg.stats(provider)
    status, body = t12.Payment().confirm(order_id, token, amount=9_000)
    row = t12.payment_row(order_id)
    return {
        'orderId': order_id,
        'checks': [
            check('400 PAYMENT_AMOUNT_MISMATCH', status == 400 and has_code(body, 'PAYMENT_AMOUNT_MISMATCH'), body),
            check('결제 READY 유지', row['status'] == 'READY', row),
            check('PG 승인 요청 0', requests_of(provider, 'CONFIRM', before) == 0, requests_of(provider, 'CONFIRM', before)),
        ],
    }


def a14_refund_during_approval(provider):
    order_id, token = prepared(provider)
    t12.FakePg.fault(provider, 'CONFIRM', {'latencyMs': 8000})
    pay = t12.Payment()
    with ThreadPoolExecutor(max_workers=1) as pool:
        confirm = pool.submit(pay.confirm, order_id, token)
        time.sleep(3)
        refund = pay.refund(order_id)
        confirm.result()
    row = wait_refund(order_id, timeout=90)
    return {
        'orderId': order_id, 'refundResponse': refund, 'faultConfig': {'CONFIRM': {'latencyMs': 8000}},
        'checks': [
            check('승인 중 환불 요청은 WAITING으로 접수', refund[0] == 200 and refund[1].get('refundStatus') == 'WAITING', refund),
            check('승인 뒤 환불 DONE·결제 CANCELED', row and row['refundStatus'] == 'DONE' and row['status'] == 'CANCELED', row),
            check('PG CANCELED', pg_status(order_id) == 'CANCELED', pg_status(order_id)),
        ],
    }


def a15_tampered_approval(provider):
    pay = t12.Payment()
    cases, checks = [], []
    for mode in ('MISSING_FIELDS', 'OTHER_TRANSACTION', 'WRONG_AMOUNT'):
        order_id, token = prepared(provider)
        t12.FakePg.fault(provider, 'CONFIRM', {'tamperRate': 1, 'tamperMode': mode})
        status, body = pay.confirm(order_id, token)
        t12.FakePg.clear_faults(provider)
        if mode == 'WRONG_AMOUNT':
            row = wait_refund(order_id, statuses=('DONE', 'FAILED'), timeout=120)
            refund_amount = t12.psql("SELECT r.refund_amount FROM payment_schema.p_refunds r JOIN payment_schema.p_payments p "
                                     "ON p.id = r.payment_id WHERE p.order_id = :'o'", o=order_id)
            cases.append({'mode': mode, 'response': body, 'row': row, 'refundAmount': refund_amount})
            checks.append(check('WRONG_AMOUNT: 승인액 불일치로 FAILED AMOUNT_MISMATCH',
                                row and row['status'] == 'FAILED' and row['failureCode'] == 'AMOUNT_MISMATCH', row))
            checks.append(check('WRONG_AMOUNT(F2): AUTO_VOID 환불 금액 = 실제 승인액 10000',
                                refund_amount and refund_amount[0][0] == '10000', refund_amount))
            checks.append(check('WRONG_AMOUNT: 실제 과금이 취소됨(PG CANCELED)', pg_status(order_id) == 'CANCELED',
                                pg_status(order_id)))
        else:
            row = wait_status(order_id, {'DONE', 'FAILED'}, timeout=90)
            cases.append({'mode': mode, 'response': body, 'row': row})
            checks.append(check(f'{mode}: 잘못된 응답을 승인으로 저장하지 않고 조회로 DONE',
                                row and row['status'] == 'DONE' and row['pgTransactionId'] == pg_row(order_id).get('paymentKey'),
                                {'row': row, 'pgKey': pg_row(order_id).get('paymentKey')}))
    return {'cases': cases, 'checks': checks, 'settleTimeout': 60,
            'faultConfig': 'CONFIRM tamperRate=1, tamperMode=MISSING_FIELDS/OTHER_TRANSACTION/WRONG_AMOUNT'}


def a16_tampered_inquiry(provider):
    cases, checks = [], []
    for mode in ('OTHER_TRANSACTION', 'WRONG_AMOUNT'):
        order_id, token = prepared(provider)
        status, body, failed = _unresolved_then_found(provider, order_id, token,
                                                      extra_inquire={'tamperRate': 1, 'tamperMode': mode})
        row = wait_refund(order_id, statuses=('DONE', 'FAILED'), timeout=240)
        pg = pg_row(order_id)
        cases.append({'mode': mode, 'failedAt60s': failed, 'final': row, 'pg': pg.get('status')})
        checks.append(check(f'{mode}: 변조된 조회를 승인 사실로 쓰지 않음(DONE 아님)',
                            failed and failed['status'] == 'FAILED', failed))
        checks.append(check(f'{mode}: 변조 해제 뒤 실제 과금 취소(PG CANCELED)', pg.get('status') == 'CANCELED',
                            {'pg': pg.get('status'), 'row': row}))
    return {'cases': cases, 'checks': checks, 'settleTimeout': 60,
            'faultConfig': 'CONFIRM responseLossRate=1 + INQUIRE tamperRate=1 (OTHER_TRANSACTION/WRONG_AMOUNT) 시한까지'}


def a17_retry_while_pending(provider):
    order_id, token = prepared(provider)
    before = t12.FakePg.stats(provider)
    t12.FakePg.fault(provider, 'CONFIRM', {'latencyMs': 8000})
    pay = t12.Payment()
    with ThreadPoolExecutor(max_workers=1) as pool:
        first = pool.submit(pay.confirm, order_id, token)
        time.sleep(2)
        started = time.time()
        second = pay.confirm(order_id, token)
        second_seconds = round(time.time() - started, 2)
        first.result()
    row = wait_status(order_id, {'DONE', 'FAILED'}, timeout=60)
    return {
        'orderId': order_id, 'secondResponseSeconds': second_seconds, 'faultConfig': {'CONFIRM': {'latencyMs': 8000}},
        'checks': [
            check('재요청은 기다리지 않고 200 IN_PROGRESS', second[0] == 200 and second[1].get('status') == 'IN_PROGRESS'
                  and second_seconds < 3, {'response': second, 'seconds': second_seconds}),
            check('PG 승인 요청 1', requests_of(provider, 'CONFIRM', before) == 1, requests_of(provider, 'CONFIRM', before)),
            check('최종 DONE', row and row['status'] == 'DONE', row),
        ],
    }


def a18_expiry_while_unknown(provider):
    order_id, token = prepared(provider, expires_in_seconds=30)
    t12.FakePg.fault(provider, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 15000})
    t12.FakePg.fault(provider, 'INQUIRE', {'serverErrorRate': 1})
    created = time.time()
    t12.Payment().confirm(order_id, token)
    time.sleep(max(0, 40 - (time.time() - created)))
    after_expiry = t12.payment_row(order_id)
    failed = wait_status(order_id, {'FAILED', 'DONE'}, timeout=90)
    t12.FakePg.clear_faults(provider)
    wait_refund(order_id, timeout=240)
    checks, notes = auto_void_checks(order_id, ('PG_UNRESOLVED',))
    return {
        'orderId': order_id, **notes, 'settleTimeout': 60,
        'checks': [check('만료 시각이 지나도 IN_PROGRESS 유지(만료로 닫지 않음)',
                         after_expiry['status'] == 'IN_PROGRESS', after_expiry),
                   check('60초 시한에 PG_UNRESOLVED', failed and failed['failureCode'] == 'PG_UNRESOLVED', failed)] + checks,
    }


def p1_concurrent_rounds(provider, rounds=10):
    """같은 주문 동시 준비 10건을 여러 회 반복한다. 경쟁 조건이라 한 번으로는 잡히지 않는다(F1)."""
    pay = t12.Payment()
    anomalies, ready_calls = [], []
    for i in range(rounds):
        before = t12.FakePg.stats(provider)
        body = t12.checkout_body(provider)
        with ThreadPoolExecutor(max_workers=10) as pool:
            results = list(pool.map(lambda _: pay.post_checkout(body), range(10)))
        ready_calls.append(requests_of(provider, 'READY', before))
        row = t12.payment_row(body['orderId'])
        ids = {r[1].get('paymentId') for r in results if r[0] == 200}
        problem = None
        if not all(r[0] == 200 for r in results) or len(ids) != 1:
            problem = 'responses'
        elif row['status'] != 'READY':
            problem = f"{row['status']} {row['failureCode']}"
        elif provider == 'KAKAO' and row['pgTransactionId'] != pg_row(body['orderId']).get('paymentKey'):
            problem = 'stored tid != PG current tid'
        if problem:
            anomalies.append({'round': i, 'orderId': body['orderId'], 'problem': problem})
    return {'rounds': rounds, 'readyCallsPerRound': ready_calls, 'anomalies': anomalies,
            'checks': [check(f'동시 준비 {rounds}회 모두 정상(F1)', not anomalies, anomalies)]}



def a1_normal_approve(provider):
    pay = t12.Payment()
    order_id, status, body = pay.pay(provider)
    row = t12.payment_row(order_id)
    return {
        'orderId': order_id,
        'checks': [
            check('승인 응답 200', status == 200, status),
            check('payment DONE', row and row['status'] == 'DONE', row),
            check('PG DONE', pg_status(order_id) == 'DONE', pg_status(order_id)),
        ],
    }


def paid(provider):
    """승인까지 끝난 주문."""
    order_id, status, body = t12.Payment().pay(provider)
    assert status == 200 and body.get('status') == 'DONE', (status, body)
    return order_id


def wait_refund_state(order_id, predicate, timeout=240, interval=2):
    end = time.time() + timeout
    state = None
    while time.time() < end:
        state = t12.refund_state(order_id)
        if state and predicate(state):
            return state
        time.sleep(interval)
    return state


def refund_done_checks(order_id, attempt='0'):
    state = t12.refund_state(order_id)
    row = t12.payment_row(order_id)
    return [
        check('환불 DONE·결제 CANCELED', state and state['status'] == 'DONE' and row['status'] == 'CANCELED',
              {'refund': state, 'payment': row and row['status']}),
        check('PG CANCELED', pg_status(order_id) == 'CANCELED', pg_status(order_id)),
        check(f'취소 키 세대 유지(cancelAttempt {attempt})', state and state['attempt'] == attempt, state),
    ]


# ---------------------------------------------------------------- B. 환불

def b2_cancel_5xx_then_recover(provider):
    order_id = paid(provider)
    t12.FakePg.fault(provider, 'CANCEL', {'serverErrorRate': 1})
    refund = t12.Payment().refund(order_id)
    during = wait_refund_state(order_id, lambda s: False, timeout=180)
    t12.FakePg.clear_faults(provider)
    wait_refund_state(order_id, lambda s: s['status'] == 'DONE', timeout=360)
    return {
        'orderId': order_id, 'refundResponse': refund, 'faultConfig': {'CANCEL': {'serverErrorRate': 1}, 'seconds': 180},
        'checks': [check('장애 중 REQUESTED 유지', during and during['status'] == 'REQUESTED', during)]
        + refund_done_checks(order_id),
    }


def b3_cancel_response_lost(provider):
    order_id = paid(provider)
    before = t12.FakePg.stats(provider)
    t12.FakePg.fault(provider, 'CANCEL', {'responseLossRate': 1, 'holdMs': 15000})
    refund = t12.Payment().refund(order_id)
    t12.FakePg.clear_faults(provider)
    wait_refund_state(order_id, lambda s: s['status'] == 'DONE', timeout=120)
    after = t12.FakePg.stats(provider).get(f'{provider}.CANCEL', {})
    return {
        'orderId': order_id, 'refundResponse': refund, 'faultConfig': {'CANCEL': {'responseLossRate': 1}},
        'cancelResponseLosses': after.get('responseLosses', 0) - before.get(f'{provider}.CANCEL', {}).get('responseLosses', 0),
        'checks': [check('첫 응답은 결과 불명(REQUESTED)', refund[1].get('refundStatus') == 'REQUESTED', refund)]
        + refund_done_checks(order_id),
    }


def b4_cancel_lost_and_inquiry_down(provider):
    order_id = paid(provider)
    t12.FakePg.fault(provider, 'CANCEL', {'responseLossRate': 1, 'holdMs': 15000})
    t12.FakePg.fault(provider, 'INQUIRE', {'serverErrorRate': 1})
    t12.Payment().refund(order_id)
    t12.FakePg.fault(provider, 'CANCEL', {})
    during = wait_refund_state(order_id, lambda s: False, timeout=120)
    t12.FakePg.clear_faults(provider)
    wait_refund_state(order_id, lambda s: s['status'] == 'DONE', timeout=360)
    return {
        'orderId': order_id, 'faultConfig': {'CANCEL': {'responseLossRate': 1}, 'INQUIRE': {'serverErrorRate': 1, 'seconds': 120}},
        'checks': [check('조회 장애 중 결과 불명만으로 DONE·FAILED로 바꾸지 않음(REQUESTED)',
                         during and during['status'] == 'REQUESTED', during)] + refund_done_checks(order_id),
    }


def _paused_refund(provider):
    """PG 업무 거절로 FAILED·PAUSED가 된 환불."""
    order_id = paid(provider)
    t12.FakePg.fault(provider, 'CANCEL', {'failRate': 1})
    refund = t12.Payment().refund(order_id)
    t12.FakePg.clear_faults(provider)
    state = wait_refund_state(order_id, lambda s: s['status'] == 'FAILED', timeout=60)
    return order_id, refund, state


def b5a_business_rejection(provider):
    """업무 거절로 멈춘 환불을, 거절 원인을 해결한 뒤(장애 해제) 관리자 retry(ROOT_CAUSE_FIXED)로 새 키 세대에서 끝낸다(#164)."""
    order_id, refund, state = _paused_refund(provider)
    pay = t12.Payment()
    retry = pay.admin_command(order_id, 'retry', t12.admin_body(order_id, 'ROOT_CAUSE_FIXED'))
    done = wait_refund_state(order_id, lambda s: s['status'] == 'DONE', timeout=90)
    commands = t12.psql("SELECT action, result FROM payment_schema.p_refund_operation_commands c "
                        "JOIN payment_schema.p_payments p ON p.id = c.payment_id WHERE p.order_id = :'o' "
                        "ORDER BY c.processed_at", o=order_id)
    return {
        'orderId': order_id, 'refundResponse': refund, 'commands': commands, 'faultConfig': {'CANCEL': {'failRate': 1}},
        'checks': [
            check('업무 거절 → FAILED·PAUSED', state and state['status'] == 'FAILED' and state['mode'] == 'PAUSED', state),
            check('원인 해결 후 관리자 retry 적용(200)', retry[0] == 200, retry),
            check('새 키 세대(cancelAttempt 1)로 DONE', done and done['status'] == 'DONE' and done['attempt'] == '1', done),
            check('운영 명령 1건 기록(APPLIED)', [c[1] for c in commands] == ['APPLIED'], commands),
            check('PG CANCELED', pg_status(order_id) == 'CANCELED', pg_status(order_id)),
        ],
    }


def b5b_auth_configuration_fixed(_provider):
    """두 PG를 함께 돈다. 잘못된 PG 키로 payment를 재기동해 환불을 인증 오류로 실패시킨 뒤, 키를 고치고 관리자 retry."""
    orders = {p: paid(p) for p in ('TOSS', 'KAKAO')}
    t12.restart_payment(T12_PG_TOSS_SECRET_KEY='test_sk_wrong_key', T12_PG_KAKAO_SECRET_KEY='DEV_wrong_key')
    try:
        refunds = {p: t12.Payment().refund(o) for p, o in orders.items()}
        failed = {p: wait_refund_state(o, lambda s: s['status'] == 'FAILED', timeout=90) for p, o in orders.items()}
    finally:
        t12.restart_payment()
    pay = t12.Payment()
    retries = {p: pay.admin_command(o, 'retry', t12.admin_body(o, 'AUTH_CONFIGURATION_FIXED')) for p, o in orders.items()}
    done = {p: wait_refund_state(o, lambda s: s['status'] == 'DONE', timeout=120) for p, o in orders.items()}
    checks = []
    for p, o in orders.items():
        checks += [
            check(f'{p}: 인증 오류 → FAILED·PAUSED', failed[p] and failed[p]['status'] == 'FAILED'
                  and failed[p]['mode'] == 'PAUSED' and failed[p]['failureCode'] in ('UNAUTHORIZED_KEY', '-401'), failed[p]),
            check(f'{p}: 키 수정 후 retry 적용', retries[p][0] == 200, retries[p]),
            check(f'{p}: 새 키 세대(cancelAttempt 1)로 DONE', done[p] and done[p]['status'] == 'DONE' and done[p]['attempt'] == '1', done[p]),
            check(f'{p}: PG CANCELED', pg_status(o) == 'CANCELED', pg_status(o)),
        ]
    return {'orderIds': list(orders.values()), 'refunds': refunds, 'retries': retries, 'checks': checks,
            'settleTimeout': 120}


def b6_slow_mode(_provider):
    """시간 단축: 환불 SLOW 전환 3분, SLOW 첫 간격 30초. 두 PG를 함께 돈다."""
    t12.restart_payment(T12_REFUND_SLOW_AFTER_MINUTES=3, T12_REFUND_SLOW_INITIAL_SECONDS=30)
    try:
        orders = {p: paid(p) for p in ('TOSS', 'KAKAO')}
        for p in orders:
            t12.FakePg.fault(p, 'CANCEL', {'serverErrorRate': 1})
        for o in orders.values():
            t12.Payment().refund(o)
        slow = {p: wait_refund_state(o, lambda s: s['mode'] == 'SLOW', timeout=330, interval=5) for p, o in orders.items()}
        time.sleep(30)
        for p in orders:
            t12.FakePg.clear_faults(p)
        done = {p: wait_refund_state(o, lambda s: s['status'] == 'DONE', timeout=240, interval=5) for p, o in orders.items()}
    finally:
        t12.restart_payment()
    checks = []
    for p, o in orders.items():
        checks += [
            check(f'{p}: 장애 지속 → SLOW 전환', slow[p] and slow[p]['mode'] == 'SLOW', slow[p]),
            check(f'{p}: 장애 해제 후 자동 DONE(같은 키)', done[p] and done[p]['status'] == 'DONE' and done[p]['attempt'] == '0', done[p]),
        ]
    return {'orderIds': list(orders.values()), 'timeCompressed': 'slowAfterMinutes=3, slowInitialSeconds=30',
            'checks': checks}


def b7_concurrent_refunds(provider):
    order_id = paid(provider)
    before = t12.FakePg.stats(provider)
    pay = t12.Payment()
    with ThreadPoolExecutor(max_workers=20) as pool:
        results = list(pool.map(lambda _: pay.refund(order_id), range(20)))
    wait_refund_state(order_id, lambda s: s['status'] == 'DONE', timeout=90)
    cancels = requests_of(provider, 'CANCEL', before)
    state = t12.refund_state(order_id)
    return {
        'orderId': order_id, 'responseStatuses': sorted({str(r[1].get('refundStatus')) for r in results}),
        'checks': [
            check('응답 모두 200', all(r[0] == 200 for r in results), sorted({r[0] for r in results})),
            check('환불 행 1', state and state['count'] == 1, state),
            check('PG 취소 요청 1', cancels == 1, cancels),
        ] + refund_done_checks(order_id),
    }


def b8_refund_not_applicable(provider):
    pay = t12.Payment()
    before = t12.FakePg.stats(provider)
    ready_id, _, _ = pay.checkout(provider)
    ready = pay.refund(ready_id)
    failed_id, token = prepared(provider)
    t12.FakePg.fault(provider, 'CONFIRM', {'failRate': 1})
    pay.confirm(failed_id, token)
    t12.FakePg.clear_faults(provider)
    failed = pay.refund(failed_id)
    done_id = paid(provider)
    first = pay.refund(done_id)
    again = pay.refund(done_id)
    auto_void = pay.refund(paid(provider), reason='AUTO_VOID')
    return {
        'orderIds': [ready_id, failed_id, done_id],
        'checks': [
            check('READY 환불: NOT_REQUIRED, 결제 FAILED ABANDONED',
                  ready[1].get('refundStatus') == 'NOT_REQUIRED' and t12.payment_row(ready_id)['failureCode'] == 'ABANDONED',
                  {'response': ready, 'row': t12.payment_row(ready_id)}),
            check('FAILED 환불: NOT_REQUIRED', failed[1].get('refundStatus') == 'NOT_REQUIRED', failed),
            check('CANCELED 재요청: 기존 DONE 반환', first[1].get('refundStatus') == 'DONE' and again[1].get('refundStatus') == 'DONE',
                  [first, again]),
            check('AUTO_VOID 사유 요청 400', auto_void[0] == 400, auto_void),
            check('PG 취소 요청은 정상 환불 1건뿐', requests_of(provider, 'CANCEL', before) == 1, requests_of(provider, 'CANCEL', before)),
        ],
    }


def b9_auto_void_and_user_refund(provider):
    order_id, token = prepared(provider)
    pay = t12.Payment()
    t12.FakePg.fault(provider, 'CONFIRM', {'delayedApprovalRate': 1, 'delayedApprovalMs': 70000, 'holdMs': 15000})
    pay.confirm(order_id, token)
    wait_status(order_id, {'FAILED', 'DONE'}, timeout=120)
    t12.FakePg.clear_faults(provider)
    early = pay.refund(order_id)
    wait_refund(order_id, timeout=300)
    late = pay.refund(order_id)
    state = t12.refund_state(order_id)
    return {
        'orderId': order_id, 'earlyRefund': early, 'lateRefund': late, 'settleTimeout': 60,
        'checks': [
            check('실패 확정 직후 사용자 환불: NOT_REQUIRED 또는 기존 환불', early[0] == 200, early),
            check('환불 행 1(AUTO_VOID)', state and state['count'] == 1 and t12.payment_row(order_id)['refundReason'] == 'AUTO_VOID', state),
            check('AUTO_VOID 뒤 사용자 환불: 기존 DONE 반환', late[1].get('refundStatus') == 'DONE', late),
            check('PG CANCELED', pg_status(order_id) == 'CANCELED', pg_status(order_id)),
        ],
    }


def b10_operation_commands(provider):
    order_id, _, state = _paused_refund(provider)
    pay = t12.Payment()
    stale = t12.admin_body(order_id, 'RESUME_OBSERVATION')
    bodies = [t12.admin_body(order_id, 'RESUME_OBSERVATION') for _ in range(2)]
    with ThreadPoolExecutor(max_workers=2) as pool:
        concurrent = list(pool.map(lambda b: pay.admin_command(order_id, 'resume', b), bodies))
    winner = bodies[0] if concurrent[0][0] == 200 else bodies[1]
    replay = pay.admin_command(order_id, 'resume', winner)
    conflict = pay.admin_command(order_id, 'resume', dict(winner, reason='ROOT_CAUSE_FIXED'))
    stale_resp = pay.admin_command(order_id, 'resume', dict(stale, commandId='t12-stale-' + order_id[-8:]))
    after = t12.refund_state(order_id)
    return {
        'orderId': order_id, 'concurrent': concurrent, 'replay': replay, 'conflict': conflict, 'stale': stale_resp,
        'checks': [
            check('동시 resume 2건 중 1건만 적용', sorted(r[0] for r in concurrent) in ([200, 409],), [r[0] for r in concurrent]),
            check('같은 commandId 재전송은 같은 결과(200)', replay[0] == 200, replay),
            check('같은 commandId 다른 내용은 409', conflict[0] == 409, conflict),
            check('낡은 기대 버전 명령은 409', stale_resp[0] == 409, stale_resp),
            check('resume 후 VERIFY_ONLY, 키·시도 번호 유지', after['mode'] == 'VERIFY_ONLY' and after['attempt'] == state['attempt'],
                  {'before': state, 'after': after}),
        ],
    }


def b11_unauthorized_admin(provider):
    order_id, _, state = _paused_refund(provider)
    before = t12.FakePg.stats(provider)
    pay = t12.Payment()
    body = t12.admin_body(order_id, 'RESUME_OBSERVATION')
    no_header = pay.admin_command(order_id, 'resume', body, headers={})
    as_user = pay.admin_command(order_id, 'resume', body,
                                headers={'X-User-Id': '2', 'X-Username': 'user@test', 'X-User-Role': 'USER'})
    after = t12.refund_state(order_id)
    return {
        'orderId': order_id, 'noHeader': no_header, 'asUser': as_user,
        'note': 'MASTER 헤더 위조는 서비스 직접 접근에서 통과한다. 서비스 포트 비공개·Gateway 경유가 배포 조건이다(B11 결과에 기록)',
        'checks': [
            check('인증 헤더 없음: 거절(4xx)', no_header[0] and 400 <= no_header[0] < 500, no_header),
            check('USER 역할: 403', as_user[0] == 403, as_user),
            check('상태 불변(PAUSED)', after['mode'] == 'PAUSED' and after['status'] == state['status'], after),
            check('PG 호출 0', sum(v.get('requests', 0) for v in t12.FakePg.stats(provider).values())
                  == sum(v.get('requests', 0) for v in before.values()), None),
        ],
    }


def b13_tampered_cancel(provider):
    """변조된 취소 응답 → 조회 전용(VERIFY_ONLY) → 저빈도 조회(첫 간격 600초±20%)로 실제 취소를 확인할 때까지 본다."""
    pay = t12.Payment()
    cases = []
    for mode in ('WRONG_AMOUNT', 'MISSING_FIELDS'):
        order_id = paid(provider)
        before = t12.FakePg.stats(provider)
        t12.FakePg.fault(provider, 'CANCEL', {'tamperRate': 1, 'tamperMode': mode})
        refund = pay.refund(order_id)
        t12.FakePg.clear_faults(provider)
        # 첫 PG 취소는 환불 요청 안에서 끝난다. 다음 주문 전에 이 주문의 취소 요청 수와 조회 전용 전환을 잰다
        cancels = requests_of(provider, 'CANCEL', before)
        verify_only = wait_refund_state(order_id, lambda s: s['mode'] == 'VERIFY_ONLY' or s['status'] == 'DONE', 30)
        cases.append({'mode': mode, 'orderId': order_id, 'firstResponse': refund, 'cancelRequests': cancels,
                      'verifyOnly': verify_only, 'requestedAt': time.time()})
    checks = []
    for case in cases:
        verify_only = case['verifyOnly']
        final = wait_refund_state(case['orderId'], lambda s: s['status'] == 'DONE', timeout=900, interval=5)
        case['final'] = final
        case['secondsToDone'] = round(time.time() - case['requestedAt'], 1) if final and final['status'] == 'DONE' else None
        mode = case['mode']
        checks += [
            check(f'{mode}: 변조 응답으로 바로 DONE 처리하지 않음(조회 전용)',
                  case['firstResponse'][1].get('refundStatus') != 'DONE' and verify_only and verify_only['mode'] == 'VERIFY_ONLY',
                  {'first': case['firstResponse'], 'state': verify_only}),
            check(f'{mode}: 조회로 실제 취소 확인 후 DONE(15분 안)', final and final['status'] == 'DONE',
                  {'final': final, 'seconds': case['secondsToDone']}),
            check(f'{mode}: PG 취소 1회', case['cancelRequests'] == 1 and pg_status(case['orderId']) == 'CANCELED',
                  case['cancelRequests']),
        ]
    return {'cases': cases, 'checks': checks, 'faultConfig': 'CANCEL tamperRate=1 WRONG_AMOUNT / MISSING_FIELDS',
            'note': 'VERIFY_ONLY 재조회는 저빈도 일정(slowInitialSeconds=600, ±20%)이라 DONE까지 약 8~12분'}


def b14_external_partial_cancel(provider):
    order_id = paid(provider)
    status, view = t12.FakePg.external_cancel(provider, order_id, 3000)
    refund = t12.Payment().refund(order_id)
    state = wait_refund_state(order_id, lambda s: s['mode'] in ('PAUSED', 'VERIFY_ONLY'), timeout=90)
    return {
        'orderId': order_id, 'externalCancel': view, 'refundResponse': refund,
        'expectedViolations': ['V3_STATUS_MISMATCH', 'V7_NET_MISMATCH'],
        'checks': [
            check('외부 부분 취소 적용', status == 200 and view.get('status') == 'PARTIAL_CANCELED', view),
            check('전액 환불을 DONE으로 숨기지 않음', state and state['status'] != 'DONE', state),
            check('자동 처리 멈춤(PAUSED 또는 VERIFY_ONLY)', state and state['mode'] in ('PAUSED', 'VERIFY_ONLY'), state),
        ],
    }


def b15_external_full_cancel(provider):
    order_id = paid(provider)
    status, view = t12.FakePg.external_cancel(provider, order_id)
    refund = t12.Payment().refund(order_id)
    wait_refund_state(order_id, lambda s: s['status'] == 'DONE', timeout=90)
    return {
        'orderId': order_id, 'externalCancel': view, 'refundResponse': refund,
        'checks': [check('외부 전액 취소 적용', status == 200 and view.get('status') == 'CANCELED', view)]
        + refund_done_checks(order_id),
    }



PROVIDERS = ('TOSS', 'KAKAO')


def in_background(fn, *args):
    """요청을 다른 스레드에서 보내고 Future를 돌려준다. 강제 종료로 연결이 끊겨도 예외를 결과로 담는다."""
    pool = ThreadPoolExecutor(max_workers=1)
    future = pool.submit(fn, *args)
    pool.shutdown(wait=False)
    return future


def restart_payment_after(kill_after, action):
    """action을 보낸 뒤 kill_after초에 payment를 강제 종료하고 다시 띄운다."""
    futures = action()
    time.sleep(kill_after)
    t12.kill('payment-service')
    killed_at = time.time()
    t12.start('payment-service')
    return futures, round(time.time() - killed_at, 1)


def final_payment_checks(order_ids, statuses=('DONE',)):
    checks = []
    for p, o in order_ids.items():
        row = t12.payment_row(o)
        checks.append(check(f'{p}: 재시작 뒤에도 결제 기록 유지', row is not None, row))
        checks.append(check(f'{p}: 최종 {"/".join(statuses)}', row and row['status'] in statuses, row))
    return checks


def c1_kill_during_approval(_provider):
    t12.restart_payment()
    orders = {p: prepared(p) for p in PROVIDERS}
    before = {p: t12.FakePg.stats(p) for p in PROVIDERS}
    for p in PROVIDERS:
        t12.FakePg.fault(p, 'CONFIRM', {'latencyMs': 8000})
    pay = t12.Payment()
    _, downtime = restart_payment_after(3, lambda: [in_background(pay.confirm, *orders[p]) for p in PROVIDERS])
    for p in PROVIDERS:
        t12.FakePg.clear_faults(p)
    ids = {p: o[0] for p, o in orders.items()}
    for o in ids.values():
        wait_status(o, {'DONE', 'FAILED'}, timeout=150)
    checks = final_payment_checks(ids, ('DONE', 'FAILED'))
    for p, o in ids.items():
        row = t12.payment_row(o)
        consistent = (row['status'] == 'DONE') == (pg_status(o) == 'DONE')
        checks.append(check(f'{p}: 결제 상태와 PG 상태 일치', consistent, {'payment': row['status'], 'pg': pg_status(o)}))
        checks.append(check(f'{p}: PG 승인 요청 1', requests_of(p, 'CONFIRM', before[p]) == 1, requests_of(p, 'CONFIRM', before[p])))
    return {'orderIds': list(ids.values()), 'restartSeconds': downtime, 'settleTimeout': 180, 'checks': checks,
            'faultConfig': 'CONFIRM latencyMs=8000, 3초 뒤 payment SIGKILL'}


def c2_kill_after_pg_approved(_provider):
    orders = {p: prepared(p) for p in PROVIDERS}
    for p in PROVIDERS:
        t12.FakePg.fault(p, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 15000})
    pay = t12.Payment()
    _, downtime = restart_payment_after(3, lambda: [in_background(pay.confirm, *orders[p]) for p in PROVIDERS])
    for p in PROVIDERS:
        t12.FakePg.clear_faults(p)
    ids = {p: o[0] for p, o in orders.items()}
    for o in ids.values():
        wait_status(o, {'DONE', 'FAILED'}, timeout=150)
    checks = final_payment_checks(ids)
    checks += [check(f'{p}: PG DONE(승인 1)', pg_status(o) == 'DONE', pg_status(o)) for p, o in ids.items()]
    return {'orderIds': list(ids.values()), 'restartSeconds': downtime, 'settleTimeout': 180, 'checks': checks,
            'faultConfig': 'CONFIRM responseLossRate=1 holdMs=15000, PG 승인 기록 뒤 응답을 붙잡는 동안 SIGKILL'}


def c3_kill_during_cancel(_provider):
    ids = {p: paid(p) for p in PROVIDERS}
    before = {p: t12.FakePg.stats(p) for p in PROVIDERS}
    for p in PROVIDERS:
        t12.FakePg.fault(p, 'CANCEL', {'latencyMs': 8000})
    pay = t12.Payment()
    _, downtime = restart_payment_after(3, lambda: [in_background(pay.refund, ids[p]) for p in PROVIDERS])
    for p in PROVIDERS:
        t12.FakePg.clear_faults(p)
    checks = []
    for p, o in ids.items():
        state = wait_refund_state(o, lambda s: s['status'] == 'DONE', timeout=240, interval=3)
        checks += [
            check(f'{p}: 재시작 뒤 환불 DONE', state and state['status'] == 'DONE', state),
            check(f'{p}: PG CANCELED', pg_status(o) == 'CANCELED', pg_status(o)),
            check(f'{p}: 같은 키 세대(cancelAttempt 0)', state and state['attempt'] == '0', state),
        ]
    cancels = {p: requests_of(p, 'CANCEL', before[p]) for p in PROVIDERS}
    return {'orderIds': list(ids.values()), 'restartSeconds': downtime, 'cancelRequests': cancels, 'checks': checks,
            'faultConfig': 'CANCEL latencyMs=8000, 3초 뒤 payment SIGKILL'}


def _start_second_instance():
    t12.compose('--profile', 'multi', 'up', '-d', '--wait', 'payment-service-2')


def _stop_second_instance():
    t12.compose('--profile', 'multi', 'stop', 'payment-service-2', check=False)


def c4_kill_one_of_two(_provider):
    _start_second_instance()
    try:
        orders = [(p, paid(p)) for p in PROVIDERS for _ in range(5)]
        for p in PROVIDERS:
            t12.FakePg.fault(p, 'CANCEL', {'serverErrorRate': 1})
        pay = t12.Payment()
        for _, o in orders:
            pay.refund(o)
        # 첫 시도는 5xx로 결과 불명 → 잡이 다시 점유한다. 이제 취소가 6초씩 걸리게 바꾸고, 점유 중일 때 1번 인스턴스를 죽인다
        for p in PROVIDERS:
            t12.FakePg.fault(p, 'CANCEL', {'latencyMs': 6000})
        time.sleep(8)
        t12.kill('payment-service')
        killed_at = time.time()
        states = {o: wait_refund_state(o, lambda s: s['status'] == 'DONE', timeout=240, interval=3) for _, o in orders}
        drained = round(time.time() - killed_at, 1)
        for p in PROVIDERS:
            t12.FakePg.clear_faults(p)
        t12.start('payment-service')
    finally:
        _stop_second_instance()
    checks = [check('1번 종료 뒤 2번이 인수해 모든 환불 DONE', all(s and s['status'] == 'DONE' for s in states.values()),
                    {o: s and s['status'] for o, s in states.items()}),
              check('환불 행은 결제당 1개', all(s and s['count'] == 1 for s in states.values()), None),
              check('모든 PG 거래 CANCELED', all(pg_status(o) == 'CANCELED' for _, o in orders), None)]
    return {'orderIds': [o for _, o in orders], 'secondsToDrainAfterKill': drained, 'checks': checks,
            'faultConfig': 'CANCEL 5xx로 결과 불명 → latencyMs=6000, 점유 중 1번 인스턴스 SIGKILL'}


def c5_db_pause(_provider):
    pay = t12.Payment()
    paid_orders = {p: paid(p) for p in PROVIDERS}
    pending = {p: prepared(p) for p in PROVIDERS}
    for p in PROVIDERS:
        t12.FakePg.fault(p, 'CONFIRM', {'latencyMs': 3000})
    futures = [in_background(pay.confirm, *pending[p]) for p in PROVIDERS]
    futures += [in_background(pay.refund, paid_orders[p]) for p in PROVIDERS]
    time.sleep(1)
    t12.docker('pause', t12.container('postgres'))
    paused_at = time.time()
    time.sleep(30)
    t12.docker('unpause', t12.container('postgres'))
    responses = []
    for f in futures:
        try:
            responses.append(f.result(timeout=60))
        except Exception as e:
            responses.append(('exception', str(e)))
    for p in PROVIDERS:
        t12.FakePg.clear_faults(p)
    return {'orderIds': list(paid_orders.values()) + [o for o, _ in pending.values()],
            'pausedSeconds': round(time.time() - paused_at, 1), 'responsesDuringPause': responses,
            'settleTimeout': 300,
            'checks': [check('DB 재개 뒤 payment 정상 응답', t12.Http.call('GET', t12.PAYMENT + '/actuator/health')[0] == 200, None)]}


def c6_fake_pg_restart(_provider):
    """승인을 처리하기 전 대기(5초) 중인 fake-pg를 즉시 강제 종료(SIGKILL)하고 다시 띄운다.

    docker restart는 SIGTERM이라 Spring Boot 정상 종료가 진행 중 요청을 끝까지 처리한다(첫 실행에서 확인).
    PG가 처리 도중 갑자기 죽는 상황을 만들려면 kill을 쓴다. 끊긴 시점에 따라 미과금 FAILED 또는 과금 DONE이 모두
    가능하므로 결제 상태와 PG 상태의 일치로 판정한다.
    """
    pay = t12.Payment()
    orders = {p: prepared(p) for p in PROVIDERS}
    for p in PROVIDERS:
        t12.FakePg.fault(p, 'CONFIRM', {'latencyMs': 5000})
    futures = [in_background(pay.confirm, *orders[p]) for p in PROVIDERS]
    time.sleep(2)
    t12.kill('fake-pg')
    killed_at = time.time()
    t12.start('fake-pg')
    restart_seconds = round(time.time() - killed_at, 1)
    faults_after_restart = t12.FakePg.faults()
    responses = []
    for f in futures:
        try:
            responses.append(f.result(timeout=60))
        except Exception as e:
            responses.append(('exception', str(e)))
    ids = {p: o[0] for p, o in orders.items()}
    rows = {p: wait_status(o, {'DONE', 'FAILED'}, timeout=150) for p, o in ids.items()}
    checks = [check('재시작으로 fake-pg 장애 설정이 비워짐(메모리 보관)', faults_after_restart == {}, faults_after_restart)]
    for p, o in ids.items():
        row = rows[p]
        consistent = row and ((row['status'] == 'DONE') == (pg_status(o) == 'DONE'))
        checks.append(check(f'{p}: 결제 상태와 PG 상태 일치', consistent, {'payment': row, 'pg': pg_status(o)}))
    return {'orderIds': list(ids.values()), 'confirmResponses': responses, 'fakePgRestartSeconds': restart_seconds,
            'outcomes': {p: (rows[p] and rows[p]['status'], rows[p] and rows[p]['failureCode'], pg_status(o))
                         for p, o in ids.items()},
            'settleTimeout': 180, 'checks': checks,
            'faultConfig': 'CONFIRM latencyMs=5000, 처리 전 대기 중 fake-pg SIGKILL'}


def c7_payment_down_three_minutes(_provider):
    pay = t12.Payment()
    orders = [(p, prepared(p)) for p in PROVIDERS for _ in range(3)]
    for p in PROVIDERS:
        t12.FakePg.fault(p, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 12000})
    with ThreadPoolExecutor(max_workers=len(orders)) as pool:
        list(pool.map(lambda o: pay.confirm(*o[1]), orders))
    for p in PROVIDERS:
        t12.FakePg.clear_faults(p)
    t12.docker('stop', t12.container('payment-service'))
    time.sleep(180)
    t12.start('payment-service')
    restarted = time.time()
    resolved = {}
    for _, (o, _) in orders:
        row = wait_status(o, {'DONE', 'FAILED'}, timeout=180)
        resolved[o] = {'status': row and row['status'], 'secondsAfterRestart': round(time.time() - restarted, 1)}
    return {'orderIds': [o for _, (o, _) in orders], 'resolved': resolved,
            'checks': [check('중지 동안 밀린 결과 불명 건을 재시작 뒤 모두 DONE으로 확정',
                             all(v['status'] == 'DONE' for v in resolved.values()), resolved)]}


def c8_network_cut(_provider):
    net = t12.PROJECT + '_t12-net'
    pay = t12.Payment()
    orders = {p: prepared(p) for p in PROVIDERS}
    for p in PROVIDERS:
        t12.FakePg.fault(p, 'CONFIRM', {'latencyMs': 4000})
    futures = [in_background(pay.confirm, *orders[p]) for p in PROVIDERS]
    time.sleep(2)
    t12.docker('network', 'disconnect', net, t12.container('payment-service'))
    time.sleep(15)
    t12.docker('network', 'connect', '--alias', 'payment-service', net, t12.container('payment-service'))
    for p in PROVIDERS:
        t12.FakePg.clear_faults(p)
    responses = []
    for f in futures:
        try:
            responses.append(f.result(timeout=60))
        except Exception as e:
            responses.append(('exception', str(e)))
    ids = {p: o[0] for p, o in orders.items()}
    for o in ids.values():
        wait_status(o, {'DONE', 'FAILED'}, timeout=150)
    checks = []
    for p, o in ids.items():
        row = t12.payment_row(o)
        consistent = (row['status'] == 'DONE') == (pg_status(o) == 'DONE')
        checks.append(check(f'{p}: 네트워크 절단 뒤 결제·PG 상태 일치', consistent, {'payment': row['status'], 'pg': pg_status(o)}))
    return {'orderIds': list(ids.values()), 'confirmResponses': responses, 'settleTimeout': 240, 'checks': checks,
            'note': 'payment와 DB가 같은 네트워크라 절단 동안 DB 연결도 끊긴다'}


def c9_two_instances_same_recovery(_provider):
    _start_second_instance()
    try:
        pay = t12.Payment()
        orders = [(p, prepared(p)) for p in PROVIDERS for _ in range(4)]
        for p in PROVIDERS:
            t12.FakePg.fault(p, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 12000})
            t12.FakePg.fault(p, 'INQUIRE', {'latencyMs': 3000})
        with ThreadPoolExecutor(max_workers=len(orders)) as pool:
            list(pool.map(lambda o: pay.confirm(*o[1]), orders))
        t12.FakePg.fault('TOSS', 'CONFIRM', {})
        t12.FakePg.fault('KAKAO', 'CONFIRM', {})
        rows = {o: wait_status(o, {'DONE', 'FAILED'}, timeout=150) for _, (o, _) in orders}
        for p in PROVIDERS:
            t12.FakePg.clear_faults(p)
    finally:
        _stop_second_instance()
    return {'orderIds': list(rows), 'checks': [
        check('두 인스턴스가 함께 복구해도 모두 DONE', all(r and r['status'] == 'DONE' for r in rows.values()),
              {o: r and r['status'] for o, r in rows.items()}),
        check('AUTO_VOID 없음', all(not r['refundReason'] for r in rows.values()), None)]}


def c10_slow_inquiry_backlog(_provider):
    pay = t12.Payment()
    orders = [(p, prepared(p)) for p in PROVIDERS for _ in range(10)]
    for p in PROVIDERS:
        t12.FakePg.fault(p, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 11000})
        t12.FakePg.fault(p, 'INQUIRE', {'latencyMs': 9000})
    with ThreadPoolExecutor(max_workers=len(orders)) as pool:
        futures = []
        for _, o in orders:
            futures.append(pool.submit(pay.confirm, *o))
            time.sleep(1)
        [f.result() for f in futures]
    started = time.time()
    rows = {o: wait_status(o, {'DONE', 'FAILED'}, timeout=300) for _, (o, _) in orders}
    for p in PROVIDERS:
        t12.FakePg.clear_faults(p)
    done = sum(1 for r in rows.values() if r and r['status'] == 'DONE')
    failed = sum(1 for r in rows.values() if r and r['status'] == 'FAILED')
    return {'orderIds': list(rows), 'done': done, 'failedUnresolved': failed,
            'secondsToJudgeAll': round(time.time() - started, 1), 'settleTimeout': 600,
            'note': '조회가 9초 걸리면 복구 잡이 60초 판정 시한 안에 다 확인하지 못해 PG_UNRESOLVED → 대사 AUTO_VOID로 넘어갈 수 있다(R08)',
            'checks': [check('모든 건이 판정됨(굶주림 없음)', done + failed == len(orders), {'done': done, 'failed': failed})]}


COMPRESSED = {'T12_RECONCILE_CHECKPOINTS': '1,2,3'}


def reconcile_state(order_id):
    rows = t12.psql("""SELECT p.status, p.failure_code, p.reconcile_count, p.reconcile_retry_count,
                              p.reconcile_unknown_since IS NOT NULL, p.failed_at,
                              (SELECT min(r.created_at) FROM payment_schema.p_refunds r WHERE r.payment_id = p.id)
                       FROM payment_schema.p_payments p WHERE p.order_id = :'o'""", o=order_id)
    keys = ['status', 'failureCode', 'reconcileCount', 'retryCount', 'unknownSince', 'failedAt', 'refundCreatedAt']
    return dict(zip(keys, rows[0])) if rows else None


def detection_seconds(order_id):
    """PG가 실제로 승인한 시각부터 우리가 AUTO_VOID 환불을 만든(=과금을 발견한) 시각까지."""
    pg = pg_row(order_id)
    state = reconcile_state(order_id)
    if not pg.get('approvedAt') or not state or not state['refundCreatedAt']:
        return None
    approved = datetime.fromisoformat(pg['approvedAt'])
    found = datetime.fromisoformat(state['refundCreatedAt'][:26])
    return round((found - approved).total_seconds(), 1)


def r1_late_charge_found_at_checkpoints(_provider):
    """실패 확정 뒤 약 30초·90초·150초에 PG 승인이 반영되는 주문을 만들고, 1·2·3분 체크포인트가 찾는지 본다."""
    t12.restart_payment(**COMPRESSED)
    try:
        pay = t12.Payment()
        orders = []
        # 실패 확정은 승인 요청 후 약 60초(미발견 유예). 승인 반영 시각 = 요청 + delayedApprovalMs
        for p in PROVIDERS:
            for delay in (90_000, 150_000, 210_000):
                order_id, token = prepared(p)
                t12.FakePg.fault(p, 'CONFIRM', {'delayedApprovalRate': 1, 'delayedApprovalMs': delay, 'holdMs': 12000})
                in_background(pay.confirm, order_id, token)
                orders.append((p, order_id, delay))
                time.sleep(1)
        time.sleep(15)
        for p in PROVIDERS:
            t12.FakePg.clear_faults(p)
        results = {}
        for p, o, delay in orders:
            row = wait_refund(o, timeout=480)
            results[o] = {'provider': p, 'approvalDelayMs': delay, 'failure': reconcile_state(o)['failureCode'],
                          'refund': row and row['refundStatus'], 'pg': pg_status(o),
                          'chargeToDetectSeconds': detection_seconds(o)}
    finally:
        t12.restart_payment()
    return {'orderIds': [o for _, o, _ in orders], 'results': results, 'timeCompressed': 'checkpoints 1,2,3분',
            'checks': [check('늦은 과금을 모두 발견해 AUTO_VOID로 취소', all(r['refund'] == 'DONE' and r['pg'] == 'CANCELED'
                                                             for r in results.values()), results),
                       check('승인 반영 전에 실패로 닫힘(NOT_APPROVED)', all(r['failure'] == 'NOT_APPROVED' for r in results.values()),
                             {o: r['failure'] for o, r in results.items()})]}


def r2_inquiry_failure_persists(_provider):
    """대사 조회가 4분 동안 실패해도 체크포인트를 소비하지 않고 백오프로 재시도하다가, 풀리면 늦은 과금을 찾는다.
    TOSS는 조회 5xx, KAKAO는 읽기 타임아웃(조회 12초 지연)."""
    t12.restart_payment(**COMPRESSED)
    try:
        pay = t12.Payment()
        orders = {p: prepared(p) for p in PROVIDERS}
        for p in PROVIDERS:
            t12.FakePg.fault(p, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 12000})
        t12.FakePg.fault('TOSS', 'INQUIRE', {'serverErrorRate': 1})
        t12.FakePg.fault('KAKAO', 'INQUIRE', {'latencyMs': 12000})
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda p: pay.confirm(*orders[p]), PROVIDERS))
        for p in PROVIDERS:
            t12.FakePg.fault(p, 'CONFIRM', {})
        ids = {p: o[0] for p, o in orders.items()}
        for o in ids.values():
            wait_status(o, {'FAILED', 'DONE'}, timeout=120)
        failed_at = time.time()
        time.sleep(max(0, 240 - (time.time() - failed_at)))
        during = {p: reconcile_state(o) for p, o in ids.items()}
        for p in PROVIDERS:
            t12.FakePg.clear_faults(p)
        finals = {p: wait_refund(o, timeout=420) for p, o in ids.items()}
    finally:
        t12.restart_payment()
    checks = []
    for p, o in ids.items():
        d = during[p]
        checks += [
            check(f'{p}: 실패 확정 PG_UNRESOLVED', d['failureCode'] == 'PG_UNRESOLVED', d),
            check(f'{p}: 조회 장애 중 체크포인트 미소비(reconcile_count 0)', d['reconcileCount'] == '0', d),
            check(f'{p}: 불명 재시도·연속 불명 시각 기록', int(d['retryCount']) >= 2 and d['unknownSince'] == 't', d),
            check(f'{p}: 장애 해제 뒤 발견 → AUTO_VOID DONE', finals[p] and finals[p]['refundStatus'] == 'DONE'
                  and pg_status(o) == 'CANCELED', finals[p]),
        ]
    return {'orderIds': list(ids.values()), 'during': during, 'timeCompressed': 'checkpoints 1,2,3분',
            'faultConfig': 'CONFIRM 응답 유실 + INQUIRE(TOSS 5xx, KAKAO 12초 지연) 실패 확정 후 약 4분 유지',
            'checks': checks}


def r3_kill_during_reconcile(_provider):
    """대사 점유 중 payment를 죽이고 3분 뒤 재시작한다. 늦은 승인 사실을 잃지 않고 환불은 한 번만 만든다."""
    t12.restart_payment(**COMPRESSED)
    try:
        pay = t12.Payment()
        orders = {p: prepared(p) for p in PROVIDERS}
        for p in PROVIDERS:
            t12.FakePg.fault(p, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 12000})
            t12.FakePg.fault(p, 'INQUIRE', {'serverErrorRate': 1})
        with ThreadPoolExecutor(max_workers=2) as pool:
            list(pool.map(lambda p: pay.confirm(*orders[p]), PROVIDERS))
        ids = {p: o[0] for p, o in orders.items()}
        for o in ids.values():
            wait_status(o, {'FAILED', 'DONE'}, timeout=120)
        # 1분 체크포인트 직전에 조회 장애를 느린 조회로 바꿔, 대사가 점유한 채 조회하는 동안 죽인다
        for p in PROVIDERS:
            t12.FakePg.fault(p, 'CONFIRM', {})
            t12.FakePg.fault(p, 'INQUIRE', {'latencyMs': 8000})
        time.sleep(62)
        t12.kill('payment-service')
        time.sleep(180)
        for p in PROVIDERS:
            t12.FakePg.clear_faults(p)
        t12.restart_payment(**COMPRESSED)
        t12.start('payment-service')
        finals = {p: wait_refund(o, timeout=300) for p, o in ids.items()}
        states = {p: reconcile_state(o) for p, o in ids.items()}
        counts = {p: t12.refund_state(o) for p, o in ids.items()}
    finally:
        t12.restart_payment()
    checks = []
    for p, o in ids.items():
        checks += [
            check(f'{p}: 재시작 뒤 늦은 승인 발견 → AUTO_VOID DONE', finals[p] and finals[p]['refundStatus'] == 'DONE'
                  and pg_status(o) == 'CANCELED', finals[p]),
            check(f'{p}: 환불 행 1', counts[p] and counts[p]['count'] == 1, counts[p]),
        ]
    return {'orderIds': list(ids.values()), 'reconcileStates': states, 'timeCompressed': 'checkpoints 1,2,3분',
            'note': '여러 체크포인트가 지난 뒤 재개했을 때 reconcile_count가 한 번에 끝까지 가지 않는지는 reconcileStates로 기록',
            'checks': checks}


# ---------------------------------------------------------------- E. 운영·관측

ALERT_MOCK = 'http://127.0.0.1:19094'
PROMETHEUS = 'http://127.0.0.1:19090'
OBS_SERVICES = ('prometheus', 'alertmanager', 'alert-mock')
# compose 기본 시험 키(T12_PG_*_SECRET_KEY 미설정). 로그·응답·경보 본문에 나오면 안 된다(E3)
TEST_SECRET_KEYS = ('test_sk_fake_toss', 'DEV_fake_kakao')


def obs_up():
    """obs 프로필을 띄우고 Prometheus가 payment를 수집할 때까지 기다린다."""
    t12.compose('--profile', 'obs', 'up', '-d', *OBS_SERVICES)
    end = time.time() + 120
    while time.time() < end:
        status, body = t12.Http.call('GET', PROMETHEUS + '/api/v1/query?query=up%7Bjob%3D%22payment-service%22%7D')
        if status == 200 and any(r['value'][1] == '1' for r in body['data']['result']):
            return
        time.sleep(3)
    raise TimeoutError('Prometheus가 payment를 수집하지 못했다')


def alert_mode(mode):
    status, _ = t12.Http.call('POST', ALERT_MOCK + '/mode/' + mode)
    assert status == 200, status


def alert_messages():
    """alert-mock이 받은 Slack 본문. (받은 시각, 상태 firing·resolved, 제목, 본문 문자열)"""
    status, body = t12.Http.call('GET', ALERT_MOCK + '/messages')
    assert status == 200, status
    result = []
    for m in body['messages']:
        payload = m['payload']
        text = json.dumps(payload, ensure_ascii=False)
        title = ' '.join(a.get('title', '') for a in payload.get('attachments', [])) or payload.get('text', '')[:120]
        state = 'resolved' if '[해제]' in title or '[RESOLVED]' in title else 'firing'
        result.append({'receivedAt': m['receivedAt'], 'mode': m['mode'], 'state': state, 'title': title, 'text': text})
    return result


def find_alert(messages, alertname, state, provider=None, after=0):
    for m in messages:
        if (m['receivedAt'] >= after and m['state'] == state and alertname in m['title']
                and (provider is None or provider in m['title'])):
            return m
    return None


def wait_alerts(expected, state, timeout, after, tick=None, seen=None):
    """expected의 (alertname 후보 튜플, provider)가 모두 state로 들어올 때까지 기다린다. 이름별 첫 수신 시각을 돌려준다."""
    end = time.time() + timeout
    seen = {} if seen is None else seen
    while time.time() < end:
        messages = alert_messages()
        for key, (names, provider) in expected.items():
            if key not in seen:
                hit = next((m for n in names if (m := find_alert(messages, n, state, provider, after))), None)
                if hit:
                    seen[key] = {'seconds': round(hit['receivedAt'] - after, 1), 'title': hit['title']}
        if len(seen) == len(expected):
            break
        if tick:
            tick()
        time.sleep(5)
    return seen


def e1_alert_routes(_provider):
    """실제 경보 규칙·Alertmanager 설정으로 위험 상황마다 경보가 오고 풀리는지 alert-mock 수신으로 확인한다."""
    obs_up()
    alert_mode('ok')
    t12.Http.call('DELETE', ALERT_MOCK + '/messages')
    pay = t12.Payment()
    started = time.time()

    # 1) 운영 인수: 업무 거절 → FAILED·PAUSED (TOSS)
    paused_order, _, paused_state = _paused_refund('TOSS')
    # 2) 거래 불일치: PG 외부 부분 취소 뒤 전액 환불 (KAKAO)
    external_order = paid('KAKAO')
    t12.FakePg.external_cancel('KAKAO', external_order, 3000)
    pay.refund(external_order)
    external_state = wait_refund_state(external_order, lambda s: s['mode'] in ('PAUSED', 'VERIFY_ONLY'), timeout=90)
    # 3) 미완료 환불 노화: 취소 5xx 지속 (TOSS)
    aging_order = paid('TOSS')
    t12.FakePg.fault('TOSS', 'CANCEL', {'serverErrorRate': 1})
    pay.refund(aging_order)
    # 4) PG 실패율: 처리 전 5xx 승인 부하 (KAKAO CONFIRM)
    t12.FakePg.fault('KAKAO', 'CONFIRM', {'serverErrorRate': 1})
    load = {'confirms': 0}

    def keep_load():
        if 'degraded' not in fired:
            for _ in range(3):
                order_id, token = prepared('KAKAO')
                pay.confirm(order_id, token)
                load['confirms'] += 1

    expected = {
        'needsOperator': (('PaymentRefundNeedsOperator',), 'TOSS'),
        'inconsistent': (('PaymentRefundInconsistent', 'PaymentRefundNeedsOperator'), 'KAKAO'),
        'aging': (('PaymentRefundAging',), 'TOSS'),
        'degraded': (('PaymentPgDegraded',), 'KAKAO'),
    }
    fired = {}
    wait_alerts(expected, 'firing', 720, started, tick=keep_load, seen=fired)
    t12.FakePg.clear_faults()
    cleared = time.time()
    wait_refund_state(aging_order, lambda s: s['status'] == 'DONE', timeout=360)
    settled = t12.wait_settled(timeout=180)
    violations = t12.verify('ALL')
    allowed = {'V3_STATUS_MISMATCH', 'V7_NET_MISMATCH'}
    unexpected = [v for v in violations if not (v['check'] in allowed and v['orderId'] in (external_order, '-'))]
    # 운영 인수 두 건은 시스템 안에서 풀 수단이 없다(F3). 기록을 지워 위험 조건을 없애고 해제 알림 경로만 확인한다
    t12.reset_all()
    reset_at = time.time()
    resolved = wait_alerts(expected, 'resolved', 600, cleared)

    # 5) 수집 대상 없음: payment 3분 중지 → 재시작
    t12.docker('stop', t12.container('payment-service'))
    stopped = time.time()
    missing = {'missing': (('PaymentTargetMissing',), None)}
    missing_fired = wait_alerts(missing, 'firing', 240, stopped)
    remaining = 180 - (time.time() - stopped)
    if remaining > 0:
        time.sleep(remaining)
    t12.start('payment-service')
    restarted = time.time()
    missing_resolved = wait_alerts(missing, 'resolved', 480, restarted)
    messages = alert_messages()

    checks = [check(f'경보 수신: {k}', k in fired, fired.get(k)) for k in expected]
    checks += [check(f'해제 수신: {k}', k in resolved, resolved.get(k)) for k in expected]
    checks += [
        check('payment 중지 → PaymentTargetMissing 수신', 'missing' in missing_fired, missing_fired),
        check('재시작 → PaymentTargetMissing 해제 수신', 'missing' in missing_resolved, missing_resolved),
        check('운영 인수 환불 PAUSED(TOSS)', paused_state and paused_state['mode'] == 'PAUSED', paused_state),
        check('외부 부분 취소 환불 자동 처리 멈춤(KAKAO)', external_state and external_state['mode'] in ('PAUSED', 'VERIFY_ONLY'),
              external_state),
        check('부하·장애 해제 후 처리 중 0', settled['settled'], settled),
        check('외부 부분 취소 건 외 대조 위반 0', not unexpected, unexpected),
        check('경보 본문에 주문 ID 없음', not any(o in m['text'] for m in messages
                                          for o in (paused_order, external_order, aging_order)), None),
    ]
    return {
        'orderIds': [paused_order, external_order, aging_order], 'loadConfirms': load['confirms'],
        'firedSecondsFromStart': fired, 'resolvedSecondsFromFaultCleared': resolved,
        'resetSecondsAfterCleared': round(reset_at - cleared, 1),
        'targetMissing': {'firedSecondsFromStop': missing_fired, 'resolvedSecondsFromRestart': missing_resolved},
        'violationsBeforeReset': violations, 'messageCount': len(messages),
        'messages': [{k: m[k] for k in ('receivedAt', 'mode', 'state', 'title')} for m in messages],
        'faultConfig': {'TOSS.CANCEL': {'serverErrorRate': 1}, 'KAKAO.CONFIRM': {'serverErrorRate': 1},
                        'TOSS.CANCEL(운영 인수)': {'failRate': 1}, 'KAKAO 외부 부분 취소': 3000,
                        'payment 중지': '180초'},
        'limitation': '운영 인수 경보 해제는 시험 기록 삭제로 확인했다. 실제 운영 해제 경로가 아니다(F3)',
        'settleTimeout': 240, 'checks': checks,
    }


def e2_command_audit(provider):
    order_id, _, state = _paused_refund(provider)
    pay = t12.Payment()
    denied = pay.admin_command(order_id, 'retry', t12.admin_body(order_id, 'ROOT_CAUSE_FIXED'))
    applied = pay.admin_command(order_id, 'resume', t12.admin_body(order_id, 'RESUME_OBSERVATION'))
    rows = t12.psql("""SELECT c.command_id, c.action, c.actor, c.reason, c.result, c.expected_state, c.before_state,
                              c.after_state, c.inquiry, c.processed_at, c.input_hash
                         FROM payment_schema.p_refund_operation_commands c
                         JOIN payment_schema.p_payments p ON p.id = c.payment_id
                        WHERE p.order_id = :'o' ORDER BY c.processed_at""", o=order_id)
    keys = ['commandId', 'action', 'actor', 'reason', 'result', 'expectedState', 'beforeState', 'afterState',
            'inquiry', 'processedAt', 'inputHash']
    records = [dict(zip(keys, r)) for r in rows]
    complete = all(all(r[k] for k in keys) for r in records)
    applied_record = next((r for r in records if r['result'] == 'APPLIED'), {})
    return {
        'orderId': order_id, 'denied': denied, 'applied': applied, 'records': records,
        'checks': [
            check('명령 2건 기록(retry DENIED, resume APPLIED)', [r['result'] for r in records] == ['DENIED', 'APPLIED'], records),
            check('모든 기록에 명령 ID·작업자·사유·기대/이전/이후 상태·PG 확인·처리 시각·입력 해시', complete, records),
            # 작업자는 호출 헤더의 사용자 ID로 `USER:{id}` 형식으로 남는다(첫 실행에서 확인)
            check('작업자 = 호출 관리자(USER:{X-User-Id})', all(r['actor'] == 'USER:' + t12.MASTER['X-User-Id'] for r in records),
                  [r['actor'] for r in records]),
            check('적용 기록의 이전 PAUSED → 이후 VERIFY_ONLY', 'PAUSED' in applied_record.get('beforeState', '')
                  and 'VERIFY_ONLY' in applied_record.get('afterState', ''), applied_record),
        ],
    }


def _scan(sources, needles):
    """sources(이름 → 문자열)에서 needles(종류 → 값 목록)가 몇 번 나오는지 센다. 값 자체는 결과에 남기지 않는다."""
    hits = {}
    for source, text in sources.items():
        for kind, values in needles.items():
            count = sum(text.count(v) for v in values if v)
            if count:
                hits[f'{source}:{kind}'] = count
    return hits


def e3_secret_exposure(_provider):
    since = datetime.now(t12.KST).isoformat()
    pay = t12.Payment()
    tokens = {'TOSS': [], 'KAKAO': []}
    responses = []
    for p in PROVIDERS:
        order_id, token = prepared(p)
        tokens[p].append(token)
        responses.append(pay.confirm(order_id, token))
        responses.append(pay.refund(order_id))
        responses.append(pay.get(order_id))
        # 결과 불명 → 복구 경로의 로그·last_error도 본다
        lost_order, lost_token = prepared(p)
        tokens[p].append(lost_token)
        t12.FakePg.fault(p, 'CONFIRM', {'responseLossRate': 1, 'holdMs': 12000})
        responses.append(pay.confirm(lost_order, lost_token))
        t12.FakePg.clear_faults(p)
        paused_order, refund, _ = _paused_refund(p)
        responses += [refund, pay.admin_refund(paused_order),
                      pay.admin_command(paused_order, 'retry', t12.admin_body(paused_order, 'ROOT_CAUSE_FIXED')),
                      pay.admin_command(paused_order, 'resume', t12.admin_body(paused_order, 'RESUME_OBSERVATION'))]
    t12.wait_settled(timeout=120)
    columns = t12.psql("""SELECT coalesce(failure_message,'') || ' ' || coalesce(last_error,'') FROM payment_schema.p_payments
                          UNION ALL
                          SELECT coalesce(failure_message,'') || ' ' || coalesce(last_error,'') FROM payment_schema.p_refunds""")
    sources = {
        'payment 로그': t12.docker('logs', '--since', since, t12.container('payment-service')),
        'fake-pg 로그': t12.docker('logs', '--since', since, t12.container('fake-pg')),
        'failure_message·last_error': '\n'.join(r[0] for r in columns),
        '응답 본문': json.dumps(responses, ensure_ascii=False),
    }
    status, _ = t12.Http.call('GET', ALERT_MOCK + '/health')
    if status == 200:
        sources['alert-mock 본문'] = '\n'.join(m['text'] for m in alert_messages())
    secrets = {'시크릿 키': list(TEST_SECRET_KEYS), '카카오 pg_token': tokens['KAKAO']}
    hits = _scan(sources, secrets)
    # 토스 paymentKey는 승인 인증값이면서 PG 거래 ID로 저장·조회된다. 노출 위치만 기록한다
    toss_key_hits = _scan(sources, {'토스 paymentKey': tokens['TOSS']})
    return {
        'scannedSources': {k: len(v) for k, v in sources.items()}, 'hits': hits, 'tossPaymentKeyOccurrences': toss_key_hits,
        'checks': [
            check('시크릿 키·카카오 pg_token 노출 0', not hits, hits),
            check('검사 대상 로그가 비어 있지 않음', all(len(sources[k]) > 0 for k in ('payment 로그', '응답 본문')),
                  {k: len(v) for k, v in sources.items()}),
        ],
        'settleTimeout': 120,
    }


def e4_alert_path_down(_provider):
    obs_up()
    pay = t12.Payment()

    def flow():
        result = {}
        for p in PROVIDERS:
            begin = time.time()
            order_id, status, body = pay.pay(p)
            approve_seconds = round(time.time() - begin, 2)
            begin = time.time()
            refund = pay.refund(order_id)
            result[p] = {'orderId': order_id, 'confirm': (status, body.get('status')), 'approveSeconds': approve_seconds,
                         'refund': (refund[0], refund[1].get('refundStatus')), 'refundSeconds': round(time.time() - begin, 2),
                         'row': t12.payment_row(order_id)}
        return result

    t12.docker('stop', t12.container('alertmanager'))
    try:
        stopped = flow()
    finally:
        t12.docker('start', t12.container('alertmanager'))
    alert_mode('500')
    try:
        failing = flow()
    finally:
        alert_mode('ok')
    checks = []
    for name, result in (('Alertmanager 중지', stopped), ('alert-mock 500', failing)):
        for p, r in result.items():
            checks.append(check(f'{name} {p}: 승인 DONE·환불 DONE', r['confirm'] == (200, 'DONE') and r['refund'][0] == 200
                                and r['row']['status'] == 'CANCELED' and r['row']['refundStatus'] == 'DONE', r))
    return {'alertmanagerStopped': stopped, 'alertMock500': failing, 'checks': checks}


def e6_alert_receiver_is_mock(_provider):
    obs_up()
    mounted = t12.docker('exec', t12.container('alertmanager'), 'cat', '/etc/alertmanager/secrets/slack_webhook_url').strip()
    expected = open(os.path.join(t12.HERE, 'alert-mock-url'), encoding='utf-8').read().strip()
    mounts = json.loads(t12.docker('inspect', '-f', '{{json .Mounts}}', t12.container('alertmanager')))
    real_secret_mounted = any('alertmanager/secrets' in m.get('Source', '').replace('\\', '/') for m in mounts)
    status, body = t12.Http.call('GET', 'http://127.0.0.1:19093/api/v2/receivers')
    return {
        'receivers': body if status == 200 else status,
        'mountSources': [m.get('Source', '').rsplit('/', 1)[-1] for m in mounts],
        'checks': [
            check('Alertmanager 수신 URL 파일 = 모의 수신기 URL', mounted == expected, '같음' if mounted == expected else '다름'),
            check('실제 Slack 비밀 파일 디렉터리를 마운트하지 않음', not real_secret_mounted, None),
            check('모의 수신기가 E1 경보를 받음', len(alert_messages()) > 0, len(alert_messages())),
        ],
    }


def b1_normal_refund(provider):
    pay = t12.Payment()
    order_id, status, _ = pay.pay(provider)
    assert status == 200, status
    refund_status, refund_body = pay.refund(order_id)
    settled = t12.wait_settled(timeout=60, provider=provider)
    row = t12.payment_row(order_id)
    return {
        'orderId': order_id,
        'refundResponse': refund_body,
        'checks': [
            check('환불 접수 응답 2xx', refund_status and 200 <= refund_status < 300, refund_status),
            check('환불 DONE·payment CANCELED', row and row['refundStatus'] == 'DONE' and row['status'] == 'CANCELED', row),
            check('PG CANCELED', pg_status(order_id) == 'CANCELED', pg_status(order_id)),
            check('대기 중 처리 완료', settled['settled'], settled),
        ],
    }


SCENARIOS = {s.id: s for s in [
    Scenario('E1', '경보 규칙별 수신·해제 (obs, TOSS·KAKAO 함께)', e1_alert_routes, providers=('TOSS',), exclusive=True),
    Scenario('E2', '관리자 명령 기록', e2_command_audit),
    Scenario('E3', '비밀값 노출 검사 (TOSS·KAKAO 함께)', e3_secret_exposure, providers=('TOSS',), exclusive=True),
    Scenario('E4', '경보 경로 장애 중 결제·환불 (obs, TOSS·KAKAO 함께)', e4_alert_path_down, providers=('TOSS',), exclusive=True),
    Scenario('E6', '시험 경보 수신처는 모의 수신기 (obs)', e6_alert_receiver_is_mock, providers=('TOSS',), exclusive=True),
    Scenario('R1', '실패 후 늦은 과금 발견 (시간 단축, TOSS·KAKAO 함께)', r1_late_charge_found_at_checkpoints,
             providers=('TOSS',), exclusive=True),
    Scenario('R2', '대사 조회 장애 지속 (시간 단축, TOSS·KAKAO 함께)', r2_inquiry_failure_persists,
             providers=('TOSS',), exclusive=True),
    Scenario('R3', '대사 중 종료 후 재개 (시간 단축, TOSS·KAKAO 함께)', r3_kill_during_reconcile,
             providers=('TOSS',), exclusive=True),
    Scenario('C1', '승인 PG 호출 중 payment 강제 종료 (TOSS·KAKAO 함께)', c1_kill_during_approval, providers=('TOSS',), exclusive=True),
    Scenario('C2', 'PG 승인 기록 후 DB 저장 전 종료 (TOSS·KAKAO 함께)', c2_kill_after_pg_approved, providers=('TOSS',), exclusive=True),
    Scenario('C3', '환불 PG 취소 중 종료 (TOSS·KAKAO 함께)', c3_kill_during_cancel, providers=('TOSS',), exclusive=True),
    Scenario('C4', '2대 중 1대가 점유 중 종료 (TOSS·KAKAO 함께)', c4_kill_one_of_two, providers=('TOSS',), exclusive=True),
    Scenario('C5', 'DB 30초 정지 (TOSS·KAKAO 함께)', c5_db_pause, providers=('TOSS',), exclusive=True),
    Scenario('C6', 'fake-pg 재시작 (TOSS·KAKAO 함께)', c6_fake_pg_restart, providers=('TOSS',), exclusive=True),
    Scenario('C7', 'payment 3분 중지 후 재시작 (TOSS·KAKAO 함께)', c7_payment_down_three_minutes, providers=('TOSS',), exclusive=True),
    Scenario('C8', '네트워크 실제 절단 (TOSS·KAKAO 함께)', c8_network_cut, providers=('TOSS',), exclusive=True),
    Scenario('C9', '2대가 같은 결제를 동시에 복구 (TOSS·KAKAO 함께)', c9_two_instances_same_recovery, providers=('TOSS',), exclusive=True),
    Scenario('C10', '느린 조회로 복구 적체 (TOSS·KAKAO 함께)', c10_slow_inquiry_backlog, providers=('TOSS',), exclusive=True),
    Scenario('P1', '같은 주문 준비 재전송·동시 요청', p1_checkout_replay),
    Scenario('P2', '같은 주문에 다른 금액', p2_checkout_conflict),
    Scenario('P3', '카카오 준비 단계 장애', p3_kakao_ready_faults, providers=('KAKAO',)),
    Scenario('P4', '준비만 하고 만료', p4_ready_expiry),
    Scenario('P5', '대체된 예전 주문으로 승인', p5_superseded_order),
    Scenario('P6', '사용자 인증 없이 승인', p6_confirm_without_user_auth),
    Scenario('A1', '정상 승인', a1_normal_approve),
    Scenario('A2', '처리 전 5xx', a2_server_error_before_processing),
    Scenario('A3', '카드 거절', a3_card_rejected),
    Scenario('A4', '승인 후 응답 유실', a4_response_lost_after_approval),
    Scenario('A5', '응답 유실 + 조회 장애 → 늦은 발견', a5_lost_and_inquiry_down),
    Scenario('A6', '늦은 승인(70초)', a6_delayed_approval),
    Scenario('A7', '응답이 타임아웃보다 느림', a7_slower_than_timeout),
    Scenario('A8', 'PG 처리 한도 초과(429)', a8_rate_limited),
    Scenario('A9', 'PG 서버 다운', a9_pg_down, exclusive=True),
    Scenario('A10', '같은 주문 승인 50개 동시', a10_concurrent_confirm),
    Scenario('A11', '만료 경계', a11_expiry_boundary),
    Scenario('A12', 'A/B 시도 복수 과금', a12_ab_double_charge),
    Scenario('A13', '준비 금액과 다른 승인 금액', a13_amount_mismatch),
    Scenario('A14', '승인 처리 중 환불 요청', a14_refund_during_approval),
    Scenario('A15', '승인 응답 변조', a15_tampered_approval),
    Scenario('A16', '조회 응답 변조', a16_tampered_inquiry),
    Scenario('A17', '승인 지연 중 같은 승인 재요청', a17_retry_while_pending),
    Scenario('A18', '결과 불명 중 결제 만료 시각 경과', a18_expiry_while_unknown),
    Scenario('B1', '정상 전액 환불', b1_normal_refund),
    Scenario('B2', '취소 5xx 지속 후 회복', b2_cancel_5xx_then_recover),
    Scenario('B3', '취소 후 응답 유실', b3_cancel_response_lost),
    Scenario('B4', '취소 응답 유실 + 조회 장애', b4_cancel_lost_and_inquiry_down),
    Scenario('B5a', '취소 업무 거절 → 운영 인수', b5a_business_rejection),
    Scenario('B5b', '인증 설정 오류 → 관리자 retry (TOSS·KAKAO 함께)', b5b_auth_configuration_fixed,
             providers=('TOSS',), exclusive=True),
    Scenario('B6', '취소 장애 장기 지속 → SLOW (시간 단축, TOSS·KAKAO 함께)', b6_slow_mode,
             providers=('TOSS',), exclusive=True),
    Scenario('B7', '같은 주문 환불 20개 동시', b7_concurrent_refunds),
    Scenario('B8', '환불할 수 없는 상태', b8_refund_not_applicable),
    Scenario('B9', '자동 환불과 사용자 환불 겹침', b9_auto_void_and_user_refund),
    Scenario('B10', '운영 중단 환불 resume·동시 명령', b10_operation_commands),
    Scenario('B11', '권한 없는 관리자 호출', b11_unauthorized_admin),
    Scenario('B13', '취소 응답 변조', b13_tampered_cancel),
    Scenario('B14', 'PG 외부 부분 취소 후 환불', b14_external_partial_cancel),
    Scenario('B15', 'PG 외부 전액 취소 후 환불', b15_external_full_cancel),
]}
