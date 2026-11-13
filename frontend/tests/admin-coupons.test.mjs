import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const url = (source) =>
  `data:text/javascript;base64,${Buffer.from(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText).toString('base64')}`;
const format = url(await read('../src/lib/format.ts'));
const a = await import(
  url((await read('../src/lib/adminCoupons.ts')).replace(/from ['"]\.\/format['"]/, `from '${format}'`))
);
const now = Date.parse('2026-11-13T12:00:00+09:00');
const coupon = (overrides = {}) => ({
  couponId: 1,
  name: '검증 쿠폰',
  category: 'GENERAL',
  genre: null,
  discountType: 'RATE',
  discountAmount: null,
  discountRate: 15,
  maxDiscountAmount: 20000,
  minBaseAmount: 100000,
  totalQuantity: 100,
  remainingQuantity: 100,
  issueStatus: 'UPCOMING',
  issueStartAt: '2026-12-01T10:00:00',
  issueEndAt: '2026-12-31T18:00:00',
  validDays: 30,
  ...overrides,
});
test('생성은 할인 종류에 맞는 필드만 보내고 웰컴 7일을 검증한다', () => {
  const form = { ...a.toCouponForm(coupon()), category: 'WELCOME', validDays: '7', discountAmount: '5000' };
  const body = a.createCouponBody(form, now);
  assert.equal(body.discountType, 'RATE');
  assert.equal(body.discountRate, 15);
  assert.equal(body.discountAmount, undefined);
  assert.throws(() => a.createCouponBody({ ...form, validDays: '30' }, now), /7일/);
  assert.throws(() => a.createCouponBody({ ...form, discountRate: '100' }, now), /99/);
});
test('정책 일부 수정도 전체 묶음을 보내고 선택값 제거는 생략으로 표현한다', () => {
  const c = coupon(),
    form = a.toCouponForm(c);
  const body = a.updateCouponBody({ ...form, discountRate: '20' }, c, c, now);
  assert.deepEqual(body, {
    discountType: 'RATE',
    discountRate: 20,
    maxDiscountAmount: 20000,
    minBaseAmount: 100000,
  });
  assert.deepEqual(a.updateCouponBody({ ...form, minBaseAmount: '' }, c, c, now), {
    discountType: 'RATE',
    discountRate: 15,
    maxDiscountAmount: 20000,
  });
  assert.deepEqual(a.updateCouponBody({ ...form, name: '새 이름' }, c, c, now), { name: '새 이름' });
  assert.deepEqual(a.updateCouponBody(form, c, c, now), {});
});
test('일반→웰컴은 7일을 함께 보내고 장르 해제를 성공처럼 처리하지 않는다', () => {
  const c = coupon(),
    form = a.toCouponForm(c);
  assert.deepEqual(a.updateCouponBody({ ...form, category: 'WELCOME' }, c, c, now), {
    category: 'WELCOME',
    validDays: 7,
  });
  const genre = coupon({ genre: 'MUSICAL' });
  assert.throws(
    () => a.updateCouponBody({ ...a.toCouponForm(genre), genre: '' }, genre, genre, now),
    /삭제 후/,
  );
});
test('발급 시작·종료 경계를 지나면 수정 필드를 제한한다', () => {
  const c = coupon();
  const start = Date.parse(c.issueStartAt + '+09:00'),
    end = Date.parse(c.issueEndAt + '+09:00');
  assert.equal(a.couponStatus(c, start - 1), 'UPCOMING');
  assert.equal(a.couponStatus(c, start), 'ISSUING');
  assert.equal(a.couponStatus(c, end), 'ENDED');
  assert.throws(
    () => a.updateCouponBody({ ...a.toCouponForm(c), discountRate: '20' }, c, c, start),
    /발급 시작/,
  );
  assert.deepEqual(a.updateCouponBody({ ...a.toCouponForm(c), name: '종료 이름' }, c, c, end), {
    name: '종료 이름',
  });
  assert.throws(
    () => a.updateCouponBody({ ...a.toCouponForm(c), totalQuantity: '200' }, c, c, end),
    /이름만/,
  );
});
test('총 수량은 최신 발급 수보다 낮출 수 없고 종료 시각도 검증한다', () => {
  const c = coupon(),
    latest = coupon({ remainingQuantity: 10 }),
    form = a.toCouponForm(c);
  assert.throws(() => a.updateCouponBody({ ...form, totalQuantity: '89' }, c, latest, now), /발급된 수량/);
  assert.equal(a.updateCouponBody({ ...form, totalQuantity: '90' }, c, latest, now).totalQuantity, 90);
  assert.throws(() => a.createCouponBody({ ...form, issueEndAt: '2026-11-01T10:00' }, now), /발급 종료/);
});
