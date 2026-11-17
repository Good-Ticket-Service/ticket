import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';

const source = await readFile(new URL('../src/lib/searchSort.ts', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
});
const { hasSearchKeyword, newSearchParams, resolveSearchSort, searchSortOptions } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
);

test('스페이스만 있으면 검색어가 없다', () => {
  assert.equal(hasSearchKeyword('   '), false);
  assert.equal(hasSearchKeyword(''), false);
  assert.equal(hasSearchKeyword(' 시 카고 '), true);
});

test('서버와 같은 공백 범위로 검색어 유무를 판단한다', () => {
  // 서버(Java)는 앞뒤의 제어 공백·유니코드 공백을 지운다. JS trim()은 U+001C를 지우지 않는다.
  assert.equal(hasSearchKeyword('\u001C'), false);
  assert.equal(hasSearchKeyword('\u00A0\u3000\t'), false);
  assert.equal(hasSearchKeyword('\u001C시카고\u3000'), true);
  // 서버는 U+FEFF를 공백으로 보지 않는다. JS trim()은 지운다.
  assert.equal(hasSearchKeyword('\uFEFF'), true);
});

test('검색어가 있으면 sort가 없거나 모르는 값일 때 관련도순이다', () => {
  assert.equal(resolveSearchSort(null, true), 'RELEVANCE');
  assert.equal(resolveSearchSort('RELEVANCE', true), 'RELEVANCE');
  assert.equal(resolveSearchSort('foo', true), 'RELEVANCE');
  assert.equal(resolveSearchSort('LATEST', true), 'LATEST');
  assert.equal(resolveSearchSort('BOOKING_OPEN_ASC', true), 'BOOKING_OPEN_ASC');
});

test('검색어가 없으면 관련도순을 보내지 않는다', () => {
  assert.equal(resolveSearchSort(null, false), 'LATEST');
  assert.equal(resolveSearchSort('RELEVANCE', false), 'LATEST');
  assert.equal(resolveSearchSort('BOOKING_OPEN_ASC', false), 'BOOKING_OPEN_ASC');
});

test('검색어가 없으면 관련도순 버튼을 숨긴다', () => {
  assert.deepEqual(searchSortOptions(true).map((o) => o.value), ['RELEVANCE', 'LATEST', 'BOOKING_OPEN_ASC']);
  assert.deepEqual(searchSortOptions(false).map((o) => o.value), ['LATEST', 'BOOKING_OPEN_ASC']);
});

test('새로 검색하면 정렬과 페이지를 지우고 필터는 유지한다', () => {
  const current = new URLSearchParams('keyword=오페라&sort=LATEST&page=2&genre=MUSICAL&region=SEOUL&status=ENDED');
  const next = newSearchParams(current, '햄릿');
  assert.equal(next.get('keyword'), '햄릿');
  assert.equal(next.get('sort'), null);
  assert.equal(next.get('page'), null);
  assert.equal(next.get('genre'), 'MUSICAL');
  assert.equal(next.get('region'), 'SEOUL');
  assert.equal(next.get('status'), 'ENDED');
  assert.equal(resolveSearchSort(next.get('sort'), true), 'RELEVANCE');
  // 검색어를 비우고 검색하면 최신순으로 돌아간다.
  assert.equal(newSearchParams(current, '').get('keyword'), null);
  assert.equal(resolveSearchSort(newSearchParams(current, '').get('sort'), false), 'LATEST');
});
