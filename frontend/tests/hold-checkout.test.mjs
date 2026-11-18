import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const url = (source) =>
  `data:text/javascript;base64,${Buffer.from(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText).toString('base64')}`;
const { checkoutPath, reservationResolver, HOLD_ID } = await import(url(await read('../src/lib/holdCheckout.ts')));

const holdId = 'h_' + 'a'.repeat(32);
const hold = (overrides = {}) => ({
  reservationId: null,
  scheduleSeatId: 5,
  status: 'HOLD',
  holdExpiresAt: '2026-11-18T10:10:00',
  basePrice: 99000,
  holdId,
  ...overrides,
});

test('REDIS 모드 선점은 holdId 결제 화면, DB 모드 선점은 예약 결제 화면으로 간다', () => {
  assert.equal(checkoutPath(7, hold()), `/seats/schedules/7/holds/${holdId}/checkout`);
  assert.equal(checkoutPath(7, hold({ holdId: null, reservationId: 31 })), '/reservations/31/checkout');
});

test('holdId 형식만 받는다', () => {
  assert.ok(HOLD_ID.test(holdId));
  assert.ok(!HOLD_ID.test('31'));
  assert.ok(!HOLD_ID.test('h_' + 'A'.repeat(32)));
});

test('좌석 선택 완료는 한 번만 부르고 같은 예약 ID를 쓴다', async () => {
  let calls = 0;
  const resolve = reservationResolver(null, async () => {
    calls += 1;
    return hold({ holdId: null, reservationId: 41 });
  });
  assert.equal(await resolve(), 41);
  assert.equal(await resolve(), 41);
  assert.equal(calls, 1);
});

test('좌석 선택 완료가 실패하면 다음 호출에서 다시 부른다', async () => {
  let calls = 0;
  const resolve = reservationResolver(null, async () => {
    calls += 1;
    if (calls === 1) throw new Error('일시 오류');
    return hold({ holdId: null, reservationId: 42 });
  });
  await assert.rejects(resolve);
  assert.equal(await resolve(), 42);
  assert.equal(calls, 2);
});

test('예약 ID가 없는 응답은 실패로 보고 다시 부를 수 있다', async () => {
  let calls = 0;
  const resolve = reservationResolver(null, async () => {
    calls += 1;
    return calls === 1 ? hold() : hold({ holdId: null, reservationId: 43 });
  });
  await assert.rejects(resolve);
  assert.equal(await resolve(), 43);
});

test('이미 예약이 있으면 좌석 선택 완료를 부르지 않는다', async () => {
  const resolve = reservationResolver(31, async () => assert.fail('부르면 안 됨'));
  assert.equal(await resolve(), 31);
});

const values = new Map();
globalThis.sessionStorage = new Proxy(
  {
    getItem: (k) => values.get(k) ?? null,
    setItem: (k, v) => values.set(k, String(v)),
    removeItem: (k) => values.delete(k),
  },
  { ownKeys: () => [...values.keys()], getOwnPropertyDescriptor: () => ({ enumerable: true, configurable: true }) },
);
const holdCheckoutUrl = url(await read('../src/lib/holdCheckout.ts'));
const memory = await import(
  url((await read('../src/lib/holdMemory.ts')).replace(/from ['"]\.\/holdCheckout['"]/, `from '${holdCheckoutUrl}'`))
);

test('회차별로 이 탭의 선점을 기억하고 형식이 틀린 값은 쓰지 않는다', () => {
  values.clear();
  memory.rememberHold(7, holdId);
  assert.equal(memory.rememberedHold(7), holdId);
  assert.equal(memory.rememberedHold(8), null);
  values.set('ticket.hold.9', 'not-a-hold');
  assert.equal(memory.rememberedHold(9), null);
});

test('holdId를 주면 같은 선점일 때만 잊고, 회원이 바뀌면 모두 잊는다', () => {
  values.clear();
  memory.rememberHold(7, holdId);
  memory.forgetHold(7, 'h_' + 'b'.repeat(32));
  assert.equal(memory.rememberedHold(7), holdId);
  memory.forgetHold(7, holdId);
  assert.equal(memory.rememberedHold(7), null);
  memory.rememberHold(7, holdId);
  memory.rememberHold(8, holdId);
  values.set('ticket.entryToken.x', 'keep');
  memory.forgetAllHolds();
  assert.equal(memory.rememberedHold(7), null);
  assert.equal(memory.rememberedHold(8), null);
  assert.equal(values.get('ticket.entryToken.x'), 'keep');
});
