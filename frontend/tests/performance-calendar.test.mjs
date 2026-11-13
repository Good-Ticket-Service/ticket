import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';

function moduleUrl(source) {
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
  });
  return `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`;
}
const format = moduleUrl(await readFile(new URL('../src/lib/format.ts', import.meta.url), 'utf8'));
const source = (
  await readFile(new URL('../src/lib/performanceCalendar.ts', import.meta.url), 'utf8')
).replace(/from ['"]\.\/format['"]/, `from '${format}'`);
const { calendarDate, canBook, initialCalendarSelection, shiftCalendarMonth, monthCells, schedulesByDate } =
  await import(moduleUrl(source));
const now = Date.parse('2026-11-13T12:00:00+09:00');
const schedule = (id, startAt, overrides = {}) => ({
  id,
  performanceId: 1,
  startAt,
  bookingOpenAt: '2026-11-01T10:00:00+09:00',
  bookingCloseAt: '2026-12-01T18:00:00+09:00',
  salesStatus: 'ON_SALE',
  salesSuspended: false,
  canceledAt: null,
  ...overrides,
});

test('회차 전체 응답에서 서울 기준 월·날짜를 나누고 시간순으로 정렬한다', () => {
  const input = [
    schedule(2, '2026-12-01T10:00:00+09:00'),
    schedule(1, '2026-11-30T15:30:00Z'),
    schedule(3, '2026-11-30T23:00:00'),
  ];
  const days = schedulesByDate(input);
  assert.deepEqual([...days.keys()], ['2026-11-30', '2026-12-01']);
  assert.deepEqual(
    days.get('2026-12-01').map((s) => s.id),
    [1, 2],
  );
  assert.deepEqual(
    input.map((s) => s.id),
    [2, 1, 3],
  );
  assert.equal(calendarDate('2026-11-30T15:00:00Z'), '2026-12-01');
});
test('가장 빠른 판매 중 회차를 먼저 선택하고 마감·중지·취소 회차는 제외한다', () => {
  const schedules = [
    schedule(4, '2026-11-18T19:00:00+09:00'),
    schedule(3, '2026-11-17T19:00:00+09:00'),
    schedule(1, '2026-11-14T19:00:00+09:00', { salesSuspended: true }),
    schedule(2, '2026-11-15T19:00:00+09:00', { bookingCloseAt: '2026-11-13T12:00:00+09:00' }),
  ];
  assert.deepEqual(initialCalendarSelection(schedules, now), {
    month: '2026-11',
    date: '2026-11-17',
    scheduleId: 3,
  });
  assert.equal(
    canBook(schedule(8, '2026-11-20T19:00:00+09:00', { canceledAt: '2026-11-12T12:00:00+09:00' }), now),
    false,
  );
});
test('판매 중 회차가 없으면 미래 회차 날짜만 표시하고 시간을 자동 선택하지 않는다', () => {
  assert.deepEqual(
    initialCalendarSelection([schedule(1, '2026-12-12T19:00:00+09:00', { salesStatus: 'BEFORE_OPEN' })], now),
    { month: '2026-12', date: '2026-12-12', scheduleId: null },
  );
  assert.deepEqual(initialCalendarSelection([], now), { month: '2026-11', date: '', scheduleId: null });
  assert.deepEqual(
    initialCalendarSelection([schedule(1, '2026-10-12T19:00:00+09:00', { salesStatus: 'CLOSED' })], now),
    { month: '2026-11', date: '', scheduleId: null },
  );
});
test('예매 오픈·마감 경계를 확인하고 취소 회차만 남으면 이번 달을 표시한다', () => {
  const item = schedule(1, '2026-11-20T19:00:00+09:00', { bookingOpenAt: '2026-11-13T12:00:00+09:00' });
  assert.equal(canBook(item, now), true);
  assert.equal(canBook(item, now - 1), false);
  assert.equal(canBook({ ...item, bookingCloseAt: item.bookingOpenAt }, now), false);
  assert.deepEqual(initialCalendarSelection([{ ...item, salesStatus: 'CANCELED' }], now), {
    month: '2026-11',
    date: '',
    scheduleId: null,
  });
});
test('연도 경계와 윤년·일요일 시작을 포함한 월별 셀을 만든다', () => {
  assert.equal(shiftCalendarMonth('2026-12', 1), '2027-01');
  assert.equal(shiftCalendarMonth('2027-01', -1), '2026-12');
  assert.equal(monthCells('2028-02').filter(Boolean).length, 29);
  assert.equal(monthCells('2026-11')[0], '2026-11-01');
  assert.equal(monthCells('2026-11').length % 7, 0);
});
