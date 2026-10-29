"""Task 12(#141) 시험 실행기의 공통 기능. 표준 라이브러리만 쓴다.

- payment·fake-pg HTTP 호출, 사용자 결제 시뮬레이션
- 단독 환경 Docker 제어(강제 종료·재시작·일시 정지)
- 상태 초기화, 처리 완료 대기, 대조 SQL 실행, 결과 기록

payment 내부 API와 관리자 API는 단독 환경에서만 호스트 포트로 연다. 사용자 개발 스택에는 쓰지 않는다.
"""

import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
PROJECT = 'ticket-t12'
COMPOSE = ['docker', 'compose', '-p', PROJECT, '-f', os.path.join(HERE, 'compose.yml')]
PAYMENT = os.environ.get('T12_PAYMENT_URL', 'http://127.0.0.1:18084')
PAYMENT_2 = os.environ.get('T12_PAYMENT2_URL', 'http://127.0.0.1:18085')
FAKE_PG = os.environ.get('T12_FAKE_PG_URL', 'http://127.0.0.1:18090')
KST = timezone(timedelta(hours=9))
OPERATIONS = {'TOSS': ('CONFIRM', 'INQUIRE', 'CANCEL'), 'KAKAO': ('READY', 'CONFIRM', 'INQUIRE', 'CANCEL')}
MASTER = {'X-User-Id': '1', 'X-Username': 'task12-master@test', 'X-User-Role': 'MASTER'}


class Http:
    """응답 상태와 JSON 본문을 함께 돌려준다. 4xx·5xx도 예외로 바꾸지 않는다."""

    @staticmethod
    def call(method, url, body=None, headers=None, timeout=30):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header('Content-Type', 'application/json')
        for key, value in (headers or {}).items():
            req.add_header(key, value)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as res:
                return res.status, _json(res.read())
        except urllib.error.HTTPError as e:
            return e.code, _json(e.read())
        except (urllib.error.URLError, TimeoutError, ConnectionError) as e:
            return None, {'transportError': type(e).__name__, 'message': str(e)}


def _json(raw):
    if not raw:
        return None
    try:
        return json.loads(raw)
    except ValueError:
        return {'raw': raw.decode('utf-8', 'replace')}


# ---------------------------------------------------------------- fake-pg

class FakePg:

    @staticmethod
    def reset():
        Http.call('DELETE', FAKE_PG + '/fake/admin/transactions')
        Http.call('DELETE', FAKE_PG + '/fake/admin/faults')
        Http.call('DELETE', FAKE_PG + '/fake/admin/faults/stats')

    @staticmethod
    def fault(provider, operation, config):
        status, body = Http.call('PUT', f'{FAKE_PG}/fake/admin/faults/{provider}/{operation}', config)
        assert status == 200, (status, body)
        return body

    @staticmethod
    def clear_faults(provider=None):
        """provider를 주면 그 PG의 장애만 푼다(다른 PG 시나리오가 동시에 돌 수 있다)."""
        if provider is None:
            Http.call('DELETE', FAKE_PG + '/fake/admin/faults')
            return
        for operation in OPERATIONS[provider]:
            Http.call('PUT', f'{FAKE_PG}/fake/admin/faults/{provider}/{operation}', {})

    @staticmethod
    def faults():
        return Http.call('GET', FAKE_PG + '/fake/admin/faults')[1]

    @staticmethod
    def stats(provider=None):
        """장애 적용 횟수. provider를 주면 그 PG 항목만 돌려준다."""
        stats = Http.call('GET', FAKE_PG + '/fake/admin/faults/stats')[1] or {}
        return {k: v for k, v in stats.items() if provider is None or k.startswith(provider + '.')}

    @staticmethod
    def stats_delta(before, after):
        """두 시점 사이에 늘어난 횟수. 동시에 도는 다른 PG 시나리오 때문에 전체를 비우지 않고 차이로 센다."""
        delta = {}
        for key, values in after.items():
            base = before.get(key, {})
            changed = {name: count - base.get(name, 0) for name, count in values.items()}
            if any(changed.values()):
                delta[key] = changed
        return delta

    @staticmethod
    def seed(value=None):
        """value가 None이면 조회만 한다. 숫자는 그 seed로, 'clear'는 실행마다 다른 난수로 다시 시작한다."""
        if value is not None:
            Http.call('PUT', FAKE_PG + '/fake/admin/faults/seed', {'seed': None if value == 'clear' else value})
        return Http.call('GET', FAKE_PG + '/fake/admin/faults/seed')[1]

    @staticmethod
    def transactions(order_id=None):
        query = f'?orderId={order_id}' if order_id else '?limit=1000'
        return Http.call('GET', FAKE_PG + '/fake/admin/transactions' + query)[1]

    @staticmethod
    def external_cancel(provider, order_id, amount=None):
        body = {} if amount is None else {'cancelAmount': amount}
        return Http.call('POST', f'{FAKE_PG}/fake/admin/transactions/{provider}/{order_id}/external-cancel', body)

    @staticmethod
    def user_authorize(provider, order_id, amount, checkout_url):
        """결제창에서 사용자가 인증을 마친 상태를 만들고 승인에 쓸 인증값을 돌려준다."""
        if provider == 'TOSS':
            status, body = Http.call('POST', FAKE_PG + '/fake/toss/checkout', {'orderId': order_id, 'amount': amount})
            assert status == 200, (status, body)
            return body['paymentKey']
        tid = checkout_url.rstrip('/').rsplit('/', 1)[-1]
        status, body = Http.call('POST', FAKE_PG + '/fake/kakao/approve', {'tid': tid})
        assert status == 200, (status, body)
        return body['pg_token']


# ---------------------------------------------------------------- payment

def checkout_body(provider, amount=10_000, discount=0, order_id=None, expires_in_seconds=600, reservation_id=None):
    """예매 서비스가 보내는 준비 요청 본문. 만료 시각은 마이크로초까지 비교되므로 재전송 시험은 같은 본문을 재사용한다."""
    return {
        'orderId': order_id or f't12-{provider.lower()}-{uuid.uuid4().hex[:16]}',
        'reservationId': reservation_id or int(uuid.uuid4().int % 10**12),
        'userId': 1001,
        'sellerId': 2001,
        'performanceId': 3001,
        'scheduleId': 4001,
        'totalPrice': amount + discount,
        'discountAmount': discount,
        'pgProvider': provider,
        'expiresAt': (datetime.now(KST) + timedelta(seconds=expires_in_seconds)).isoformat(),
    }


class Payment:

    def __init__(self, base=PAYMENT):
        self.base = base

    def checkout(self, provider, amount=10_000, discount=0, order_id=None, expires_in_seconds=600, reservation_id=None):
        body = checkout_body(provider, amount, discount, order_id, expires_in_seconds, reservation_id)
        status, res = self.post_checkout(body)
        return body['orderId'], status, res

    def post_checkout(self, body):
        """준비 요청 본문을 그대로 보낸다. 같은 본문 재전송(멱등) 시험에 쓴다."""
        return Http.call('POST', self.base + '/internal/payments', body)

    def confirm(self, order_id, token, amount=10_000, timeout=30):
        return Http.call('POST', f'{self.base}/internal/payments/{order_id}/confirm',
                         {'pgAuthToken': token, 'amount': amount}, timeout=timeout)

    def refund(self, order_id, reason='USER_CANCEL', timeout=30):
        return Http.call('POST', f'{self.base}/internal/payments/{order_id}/refund', {'reason': reason}, timeout=timeout)

    def get(self, order_id):
        return Http.call('GET', f'{self.base}/internal/payments/{order_id}')

    def admin_refund(self, order_id, headers=MASTER):
        return Http.call('GET', f'{self.base}/api/admin/payments/{order_id}/refund', headers=headers)

    def admin_command(self, order_id, action, body, headers=MASTER):
        return Http.call('POST', f'{self.base}/api/admin/payments/{order_id}/refund/{action}', body, headers=headers)

    def pay(self, provider, amount=10_000, **kwargs):
        """준비 → 사용자 인증 → 승인을 한 번에 한다. (orderId, 승인 응답 상태, 본문)"""
        order_id, status, res = self.checkout(provider, amount, **kwargs)
        assert status == 200, (status, res)
        token = FakePg.user_authorize(provider, order_id, amount, res.get('checkoutUrl'))
        status, body = self.confirm(order_id, token, amount)
        return order_id, status, body


# ---------------------------------------------------------------- Docker·DB

def compose(*args, check=True):
    r = subprocess.run([*COMPOSE, *args], cwd=ROOT, capture_output=True, text=True, encoding='utf-8')
    if check and r.returncode:
        raise RuntimeError(f'compose {args} 실패: {r.stderr[-800:]}')
    return r.stdout


def container(service, index=1):
    return f'{PROJECT}-{service}-{index}'


def docker(*args, check=True):
    r = subprocess.run(['docker', *args], capture_output=True, text=True, encoding='utf-8')
    if check and r.returncode:
        raise RuntimeError(f'docker {args} 실패: {r.stderr[-500:]}')
    return r.stdout


def kill(service):
    """SIGKILL. 종료 처리 없이 프로세스를 멈춘다."""
    docker('kill', container(service))


def start(service, wait=True):
    docker('start', container(service))
    if wait:
        wait_healthy(service)


def wait_healthy(service, timeout=180):
    end = time.time() + timeout
    while time.time() < end:
        state = docker('inspect', '-f', '{{.State.Health.Status}}', container(service), check=False).strip()
        if state == 'healthy':
            return
        time.sleep(2)
    raise TimeoutError(f'{service}가 {timeout}초 안에 healthy가 되지 않았다')


def restart_payment(**env):
    """payment를 주어진 T12_* 환경 변수로 다시 만든다. 인자 없이 부르면 기본 설정으로 되돌린다. DB는 그대로 남는다."""
    merged = {k: v for k, v in os.environ.items() if not k.startswith('T12_')}
    merged.update({k: str(v) for k, v in env.items()})
    r = subprocess.run([*COMPOSE, 'up', '-d', '--wait', 'payment-service'], cwd=ROOT, env=merged,
                       capture_output=True, text=True, encoding='utf-8')
    if r.returncode:
        raise RuntimeError(f'payment 재기동 실패: {r.stderr[-800:]}')


def refund_state(order_id):
    rows = psql("""SELECT r.status, r.recovery_mode, r.execution_version, r.cancel_attempt, r.failure_code,
                           r.verification_reason, r.refund_amount
                    FROM payment_schema.p_refunds r JOIN payment_schema.p_payments p ON p.id = r.payment_id
                    WHERE p.order_id = :'o'""", o=order_id)
    if not rows:
        return None
    keys = ['status', 'mode', 'version', 'attempt', 'failureCode', 'verificationReason', 'amount']
    state = dict(zip(keys, rows[0]))
    state['count'] = len(rows)
    return state


def admin_body(order_id, reason, command_id=None, **override):
    """현재 환불 상태를 기대값으로 담은 관리자 명령 본문."""
    state = refund_state(order_id)
    body = {
        'commandId': command_id or 't12-' + uuid.uuid4().hex[:20],
        'reason': reason,
        'expectedStatus': state['status'],
        'expectedMode': state['mode'],
        'expectedVersion': int(state['version']),
        'expectedAttempt': int(state['attempt']),
    }
    body.update(override)
    return body


def psql(sql, **variables):
    """관리자 계정으로 SQL을 실행하고 행 목록(탭 구분)을 돌려준다. variables는 psql 변수(:'name')로 넘긴다."""
    args = ['exec', '-i', container('postgres'), 'psql', '-U', 't12_admin', '-d', 'ticket',
            '-v', 'ON_ERROR_STOP=1', '-At', '-F', '\t']
    for key, value in variables.items():
        args += ['-v', f'{key}={value}']
    r = subprocess.run(['docker', *args, '-f', '-'], input=sql, capture_output=True, text=True, encoding='utf-8')
    if r.returncode:
        raise RuntimeError(f'psql 실패: {r.stderr[-500:]}')
    return [line.split('\t') for line in r.stdout.splitlines() if line]


def reset_all():
    """결제·PG 기록과 장애 설정·횟수를 모두 비운다. 실행 시작과 단독(exclusive) 시나리오 전에만 쓴다."""
    psql('TRUNCATE payment_schema.p_refund_operation_commands, payment_schema.p_refunds, '
         'payment_schema.p_payments RESTART IDENTITY CASCADE')
    FakePg.reset()


def reset_provider(provider):
    """한 PG의 결제·PG 기록과 장애 설정만 비운다. 다른 PG 시나리오는 그대로 둔다."""
    psql("""
        DELETE FROM payment_schema.p_refund_operation_commands c
         USING payment_schema.p_payments p WHERE c.payment_id = p.id AND p.pg_provider = :'provider';
        DELETE FROM payment_schema.p_refunds r
         USING payment_schema.p_payments p WHERE r.payment_id = p.id AND p.pg_provider = :'provider';
        DELETE FROM payment_schema.p_payments WHERE pg_provider = :'provider';
        DELETE FROM fake_pg_schema.pg_idempotency_record WHERE provider = :'provider';
        DELETE FROM fake_pg_schema.pg_transaction WHERE provider = :'provider';""", provider=provider)
    FakePg.clear_faults(provider)


def unresolved(provider='ALL'):
    rows = psql("""
        SELECT (SELECT count(*) FROM payment_schema.p_payments
                 WHERE status = 'IN_PROGRESS' AND (:'provider' = 'ALL' OR pg_provider = :'provider')),
               (SELECT count(*) FROM payment_schema.p_refunds r
                  JOIN payment_schema.p_payments p ON p.id = r.payment_id
                 WHERE r.status IN ('WAITING', 'REQUESTED') AND r.recovery_mode IN ('ACTIVE', 'SLOW')
                   AND (:'provider' = 'ALL' OR p.pg_provider = :'provider'))""", provider=provider)
    payments, refunds = map(int, rows[0])
    return {'inProgressPayments': payments, 'activeRefunds': refunds}


def wait_settled(timeout=180, interval=3, done=None, provider='ALL'):
    """처리 중인 결제·환불이 없어질 때까지(또는 done()이 참일 때까지) 기다린다. 걸린 초와 마지막 상태를 돌려준다."""
    started = time.time()
    while True:
        state = unresolved(provider)
        finished = done() if done else (state['inProgressPayments'] == 0 and state['activeRefunds'] == 0)
        if finished or time.time() - started > timeout:
            return {'settled': bool(finished), 'seconds': round(time.time() - started, 1), 'state': state}
        time.sleep(interval)


def verify(provider='ALL'):
    sql = open(os.path.join(HERE, 'verify.sql'), encoding='utf-8').read()
    rows = psql(sql, provider=provider)
    return [{'check': r[0], 'orderId': r[1], 'detail': r[2] if len(r) > 2 else ''} for r in rows]


def payment_row(order_id):
    rows = psql(f"""SELECT p.status, p.failure_code, p.pg_transaction_id, r.status, r.recovery_mode, r.reason,
                           r.verification_reason, r.cancel_attempt
                    FROM payment_schema.p_payments p
                    LEFT JOIN payment_schema.p_refunds r ON r.payment_id = p.id
                    WHERE p.order_id = '{order_id}'""")
    if not rows:
        return None
    keys = ['status', 'failureCode', 'pgTransactionId', 'refundStatus', 'refundMode', 'refundReason',
            'verificationReason', 'cancelAttempt']
    return dict(zip(keys, rows[0]))


def git_commit():
    r = subprocess.run(['git', 'rev-parse', '--short', 'HEAD'], cwd=ROOT, capture_output=True, text=True)
    return r.stdout.strip()
