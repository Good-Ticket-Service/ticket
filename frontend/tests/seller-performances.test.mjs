import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const code = ts.transpileModule(
  await readFile(new URL('../src/lib/sellerPerformances.ts', import.meta.url), 'utf8'),
  { compilerOptions: { module: ts.ModuleKind.ESNext } },
).outputText;
const s = await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
test('공연 검색은 브라우저 기록만 필터링하고 공개 상태를 구분한다', () => {
  const records = [
    { id: 12, title: 'Live Seoul', publicationStatus: 'PUBLISHED' },
    { id: 23, title: '겨울 공연', publicationStatus: 'DRAFT' },
  ];
  assert.deepEqual(s.filterPerformanceRecords(records, ' live ', 'PUBLISHED'), [records[0]]);
  assert.deepEqual(s.filterPerformanceRecords(records, '23', ''), [records[1]]);
  assert.deepEqual(s.filterPerformanceRecords(records, '', 'UNKNOWN'), []);
});
test('모르는 version은 0으로 추정하지 않고 명시적인 0과 구분한다', () => {
  assert.equal(s.performanceVersion(undefined, ''), null);
  assert.equal(s.performanceVersion({ version: 0, publicationStatus: 'UNKNOWN' }, ''), null);
  assert.equal(s.performanceVersion({ version: 0, versionKnown: false }, ''), null);
  assert.equal(s.performanceVersion(undefined, '0'), 0);
  assert.equal(s.performanceVersion({ version: 3 }, ''), 3);
  for (const text of ['-1', '1.5', 'abc', '9007199254740992'])
    assert.equal(s.performanceVersion(undefined, text), null);
});
test('서울 회차 시각은 오픈 < 마감 ≤ 시작 경계를 따른다', () => {
  const value = {
    bookingOpenAt: '2026-12-01T10:00',
    bookingCloseAt: '2026-12-01T18:00',
    startAt: '2026-12-01T18:00',
  };
  assert.equal(s.validScheduleTimes(value), true);
  assert.equal(s.validScheduleTimes({ ...value, bookingCloseAt: value.bookingOpenAt }), false);
  assert.equal(s.validScheduleTimes({ ...value, startAt: '2026-12-01T17:59' }), false);
  assert.equal(s.validScheduleTimes({ ...value, startAt: '' }), false);
});
