import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const url = (source) =>
  `data:text/javascript;base64,${Buffer.from(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText).toString('base64')}`;
const format = url(await read('../src/lib/format.ts')),
  genres = url(await read('../src/lib/genres.ts'));
const coupons = await import(
  url(
    (await read('../src/lib/coupons.ts'))
      .replace(/from ['"]\.\/format['"]/, `from '${format}'`)
      .replace(/from ['"]\.\/genres['"]/, `from '${genres}'`),
  )
);
const { runPaymentAttempt, CheckoutChangedError } = await import(
  url(await read('../src/lib/checkoutFlow.ts'))
);
const now = Date.parse('2026-11-13T12:00:00+09:00');
const coupon = (overrides = {}) => ({
  userCouponId: 1,
  couponId: 1,
  name: '테스트',
  genre: null,
  discountType: 'FIXED',
  discountAmount: 5000,
  discountRate: null,
  maxDiscountAmount: null,
  minBaseAmount: null,
  status: 'AVAILABLE',
  issuedAt: '2026-11-01T10:00:00',
  expiresAt: '2026-12-01T10:00:00',
  usedAt: null,
  ...overrides,
});
const preview = (value, base, genre = 'MUSICAL', reserved = null) =>
  coupons.couponPreview(value, base, now, genre, reserved);

test('정액 할인은 최소 결제 100원을 남기고, 100원 이하 티켓에는 쿠폰을 적용하지 않는다', () => {
  assert.equal(preview(coupon(), 3000).discount, 2900);
  assert.equal(preview(coupon(), 10000).discount, 5000);
  assert.notEqual(preview(coupon(), 100).reason, null);
  assert.notEqual(preview(coupon(), 0).reason, null);
});
test('정률 할인은 원 미만 버림 → 최대 할인 → 최소 결제액 순서로 계산한다', () => {
  const rate = coupon({ discountType: 'RATE', discountAmount: null, discountRate: 15 });
  assert.equal(preview(rate, 1999).discount, 299);
  assert.equal(preview({ ...rate, maxDiscountAmount: 250 }, 1999).discount, 250);
  assert.equal(preview({ ...rate, discountRate: 99 }, 101).discount, 1);
  assert.equal(preview({ ...rate, discountRate: 99 }, 9000000000).discount, 2147483647);
  assert.equal(coupons.couponTitle(rate), '15% 할인');
});
test('최소 사용금액 경계와 장르 조건·장르 확인 실패를 구분한다', () => {
  assert.notEqual(preview(coupon({ minBaseAmount: 10000 }), 9999).reason, null);
  assert.equal(preview(coupon({ minBaseAmount: 10000 }), 10000).reason, null);
  assert.notEqual(preview(coupon({ genre: 'CONCERT' }), 10000).reason, null);
  assert.notEqual(preview(coupon({ genre: 'MUSICAL' }), 10000, null).reason, null);
  assert.equal(preview(coupon(), 10000, null).reason, null);
});
test('본 예매 RESERVED 쿠폰은 만료 뒤에도 유지하고, 다른 예매 점유·USED·만료 쿠폰은 막는다', () => {
  const expired = coupon({ expiresAt: '2026-11-13T12:00:00' });
  assert.notEqual(preview(expired, 10000).reason, null);
  assert.equal(preview({ ...expired, status: 'RESERVED' }, 10000, 'MUSICAL', 1).discount, 5000);
  assert.notEqual(preview({ ...expired, status: 'RESERVED' }, 10000, 'MUSICAL', 2).reason, null);
  assert.notEqual(preview(coupon({ status: 'USED' }), 10000).reason, null);
  assert.notEqual(preview(coupon({ discountAmount: null }), 10000).reason, null);
});
function setup(amount = 7000) {
  const calls = [];
  let prepared = null,
    authorization = null;
  const reservation = { reservationId: 1, status: 'HOLD', orderId: 'order-1' };
  const api = {
    reservation: async () => {
      calls.push(['reservation']);
      return reservation;
    },
    prepare: async (id) => {
      calls.push(['prepare', id]);
      return { orderId: 'order-1', amount, checkoutUrl: null };
    },
    authorize: async (orderId, value) => {
      calls.push(['authorize', orderId, value]);
      return 'test-auth';
    },
    confirm: async (auth) => {
      calls.push(['confirm', auth]);
      return { ...reservation, status: 'PAYMENT_IN_PROGRESS' };
    },
    onPrepared: (value) => {
      prepared = value;
    },
    onAuthorized: (value) => {
      authorization = value;
    },
  };
  return {
    api,
    calls,
    reservation,
    get prepared() {
      return prepared;
    },
    get authorization() {
      return authorization;
    },
  };
}
const attempt = (overrides = {}) => ({
  couponId: 1,
  expectedAmount: 7000,
  prepared: null,
  authorization: null,
  ...overrides,
});
test('정상 결제는 서버 금액으로 인증하고 메시지 승인 대기 상태를 그대로 반환한다', async () => {
  const s = setup();
  const result = await runPaymentAttempt(attempt(), s.api);
  assert.deepEqual(
    s.calls.map((c) => c[0]),
    ['prepare', 'authorize', 'confirm'],
  );
  assert.deepEqual(s.calls[1], ['authorize', 'order-1', 7000]);
  assert.equal(result.value.status, 'PAYMENT_IN_PROGRESS');
});
test('서버 금액이 예상과 다르면 인증 전에 멈추고 다음 클릭은 같은 준비를 사용한다', async () => {
  const s = setup(6500);
  assert.deepEqual(await runPaymentAttempt(attempt(), s.api), { kind: 'prepared' });
  assert.deepEqual(s.calls, [['prepare', 1]]);
  await runPaymentAttempt(attempt({ prepared: s.prepared, expectedAmount: 6500 }), s.api);
  assert.equal(s.calls.filter((c) => c[0] === 'prepare').length, 1);
  assert.deepEqual(
    s.calls.find((c) => c[0] === 'authorize'),
    ['authorize', 'order-1', 6500],
  );
});
test('승인 응답 유실 뒤 재시도해도 새 주문·인증을 만들지 않는다', async () => {
  const s = setup();
  let confirmations = 0;
  s.api.confirm = async (auth) => {
    s.calls.push(['confirm', auth]);
    if (++confirmations === 1) throw new Error('response lost');
    return { ...s.reservation, status: 'CONFIRMED' };
  };
  await assert.rejects(() => runPaymentAttempt(attempt(), s.api));
  const result = await runPaymentAttempt(
    attempt({ prepared: s.prepared, authorization: s.authorization }),
    s.api,
  );
  assert.equal(result.value.status, 'CONFIRMED');
  assert.equal(s.calls.filter((c) => c[0] === 'prepare').length, 1);
  assert.equal(s.calls.filter((c) => c[0] === 'authorize').length, 1);
  assert.deepEqual(
    s.calls.filter((c) => c[0] === 'confirm').map((c) => c[1]),
    [
      { orderId: 'order-1', pgAuthToken: 'test-auth' },
      { orderId: 'order-1', pgAuthToken: 'test-auth' },
    ],
  );
});
test('이미 결제 중이면 재승인하지 않고, HOLD 주문이 바뀌었으면 오래된 인증을 거절한다', async () => {
  const s = setup();
  const auth = { orderId: 'order-1', pgAuthToken: 'test-auth' };
  s.reservation.status = 'PAYMENT_IN_PROGRESS';
  const result = await runPaymentAttempt(attempt({ authorization: auth }), s.api);
  assert.equal(result.value.status, 'PAYMENT_IN_PROGRESS');
  assert.deepEqual(s.calls, [['reservation']]);
  s.reservation.status = 'HOLD';
  s.reservation.orderId = 'different-order';
  await assert.rejects(
    () => runPaymentAttempt(attempt({ authorization: auth }), s.api),
    CheckoutChangedError,
  );
  assert.equal(
    s.calls.some((c) => c[0] === 'confirm'),
    false,
  );
});
