import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const code = ts.transpileModule(
  await readFile(new URL('../src/lib/adminManagement.ts', import.meta.url), 'utf8'),
  { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } },
).outputText;
const a = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
test('좌석 범위를 숫자·영문·직접 열 목록으로 생성하고 ID를 보존한다', () => {
  assert.deepEqual(a.seatDraft('A', '10-12', '2', 5).rows, ['10', '11', '12']);
  assert.equal(a.seatDraft('A', 'A-C', '2', 5).total, 6);
  const value = a.seatDraft('가구역', '가,나', '2', 5);
  assert.deepEqual(value.seats[3], { section: '가구역', rowLabel: '나', seatNumber: 2, venueSeatGradeId: 5 });
});
test('5,000석 경계와 과도한 배열 생성을 차단한다', () => {
  assert.equal(a.seatDraft('A', '1-100', '50', 1).seats.length, 5000);
  for (const [rows, count] of [
    ['1-101', '50'],
    ['1-1000000000000', '1'],
    ['1', '1000000000000'],
  ]) {
    const value = a.seatDraft('A', rows, count, 1);
    assert.ok(value.error);
    assert.equal(value.seats.length, 0);
  }
});
test('잘못된 좌석 위치·중복 열·등급을 요청하지 않는다', () => {
  for (const value of [
    a.seatDraft('', 'A', '1', 1),
    a.seatDraft('A', 'A,A', '1', 1),
    a.seatDraft('A', 'C-A', '1', 1),
    a.seatDraft('A', '1-A', '1', 1),
    a.seatDraft('A', 'A', '1', 0),
  ])
    assert.ok(value.error);
});
test('미확인 공연장 version은 0으로 추정하지 않는다', () => {
  assert.equal(a.venueVersion(undefined, ''), null);
  assert.equal(a.venueVersion({ version: 0, versionKnown: false }, ''), null);
  assert.equal(a.venueVersion(undefined, '0'), 0);
  assert.equal(a.venueVersion({ version: 2 }, ''), 2);
  for (const value of ['-1', '1.5', '9007199254740992']) assert.equal(a.venueVersion(undefined, value), null);
});
test('대기열은 유효한 회차 scope 1~100개만 생성하며 중복은 제거한다', () => {
  assert.deepEqual(a.roomScopes('12, 13\n12'), ['schedule:12', 'schedule:13']);
  for (const value of [
    '',
    '01',
    '0',
    '1.5',
    'schedule:12',
    Array.from({ length: 101 }, (_, i) => i + 1).join(','),
  ])
    assert.equal(a.roomScopes(value), null);
});
test('대기열 오픈 1분 경계와 종료 순서를 검증한다', () => {
  const now = Date.parse('2026-11-13T10:00:00+09:00');
  assert.equal(a.roomTimeError('2026-11-13T10:01', '2026-11-13T11:00', now), null);
  assert.ok(a.roomTimeError('2026-11-13T10:00', '2026-11-13T11:00', now));
  assert.ok(a.roomTimeError('2026-11-13T10:01', '2026-11-13T10:01', now));
});
