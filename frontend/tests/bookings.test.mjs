import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const url = (source) =>
  `data:text/javascript;base64,${Buffer.from(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText).toString('base64')}`;
const format = url(await read('../src/lib/format.ts'));
const b = await import(
  url((await read('../src/lib/bookings.ts')).replace(/from ['"]\.\/format['"]/, `from '${format}'`))
);
const start = '2026-11-13T14:00:00';
const end = Date.parse('2026-11-13T14:00:00+09:00');
test('7개 상태를 서로 다른 문구로 표시하고 결제 대기를 승인 실패로 단정하지 않는다', () => {
  const states = ['HOLD', 'PAYMENT_IN_PROGRESS', 'CONFIRMED', 'CANCELING', 'CANCELED', 'EXPIRED', 'RELEASED'];
  assert.equal(new Set(states.map((s) => b.BOOKING_COPY[s].label)).size, 7);
  assert.equal(b.BOOKING_COPY.HOLD.title, '결제를 기다리고 있어요');
  assert.match(b.BOOKING_COPY.CANCELED.body, /시간이 걸릴/);
});
test('확정된 예매만 공연 시작 직전까지 취소할 수 있다', () => {
  assert.equal(b.canCancelBooking('CONFIRMED', start, end - 1), true);
  assert.equal(b.canCancelBooking('CONFIRMED', start, end), false);
  for (const s of ['HOLD', 'PAYMENT_IN_PROGRESS', 'CANCELING', 'CANCELED', 'EXPIRED', 'RELEASED'])
    assert.equal(b.canCancelBooking(s, start, end - 1), false);
});
test('결제 이어하기는 HOLD의 선점 만료 직전까지만 노출한다', () => {
  assert.equal(b.canResumeBooking('HOLD', start, end - 1), true);
  assert.equal(b.canResumeBooking('HOLD', start, end), false);
  assert.equal(b.canResumeBooking('HOLD', undefined, end - 1), false);
  assert.equal(b.canResumeBooking('PAYMENT_IN_PROGRESS', start, end - 1), false);
});
test('오래된 티켓 코드가 남아 있어도 확정 상태에서만 표시한다', () => {
  const tickets = [
    { scheduleSeatId: 1, ticketCode: 'actual-code' },
    { scheduleSeatId: 2, ticketCode: null },
  ];
  assert.equal(b.bookingTicketCodes({ status: 'CONFIRMED', tickets }).length, 1);
  for (const status of ['HOLD', 'PAYMENT_IN_PROGRESS', 'CANCELED', 'CANCELING', 'EXPIRED', 'RELEASED'])
    assert.deepEqual(b.bookingTicketCodes({ status, tickets }), []);
});
test('서버 Retry-After를 따르고 완료 상태의 폴링은 종료한다', () => {
  assert.equal(b.bookingPollInterval('PAYMENT_IN_PROGRESS', '7', end), 7000);
  assert.equal(b.bookingPollInterval('PAYMENT_IN_PROGRESS', new Date(end + 9000).toUTCString(), end), 9000);
  assert.equal(b.bookingPollInterval('CANCELING', null, end), 3000);
  assert.equal(b.bookingPollInterval('CANCELING', 'invalid', end), 3000);
  assert.equal(b.bookingPollInterval('HOLD', '7', end), 5000);
  for (const s of ['CONFIRMED', 'CANCELED', 'EXPIRED', 'RELEASED'])
    assert.equal(b.bookingPollInterval(s, '7', end), false);
});
