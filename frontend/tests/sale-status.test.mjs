import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';

const source = await readFile(new URL('../src/lib/saleStatus.ts', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
});
const { parseSaleStatuses, toggleSaleStatus, saleStatusParam, isEndedByPeriod } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
);

test('URL에 status가 없거나 알 수 없는 값뿐이면 판매중·판매예정이다', () => {
  assert.deepEqual(parseSaleStatuses(null), ['ON_SALE', 'UPCOMING']);
  assert.deepEqual(parseSaleStatuses(''), ['ON_SALE', 'UPCOMING']);
  assert.deepEqual(parseSaleStatuses('ALL,foo'), ['ON_SALE', 'UPCOMING']);
});

test('URL 값은 순서를 맞추고 중복·모르는 값을 버린다', () => {
  assert.deepEqual(parseSaleStatuses('ENDED,ON_SALE,ON_SALE,foo'), ['ON_SALE', 'ENDED']);
});

test('토글은 켜고 끄며 마지막 하나는 끄지 않는다', () => {
  assert.deepEqual(toggleSaleStatus(['ON_SALE', 'UPCOMING'], 'ENDED'), ['ON_SALE', 'UPCOMING', 'ENDED']);
  assert.deepEqual(toggleSaleStatus(['ON_SALE', 'UPCOMING'], 'ON_SALE'), ['UPCOMING']);
  assert.deepEqual(toggleSaleStatus(['ENDED'], 'ENDED'), ['ENDED']);
});

test('기본 선택이면 status를 보내지 않고, 아니면 쉼표로 보낸다', () => {
  assert.equal(saleStatusParam(['ON_SALE', 'UPCOMING']), null);
  assert.equal(saleStatusParam(['ON_SALE', 'UPCOMING', 'ENDED']), 'ON_SALE,UPCOMING,ENDED');
  assert.equal(saleStatusParam(['ENDED']), 'ENDED');
});

test('판매종료 표시는 마지막 공연 날짜가 오늘(KST)보다 이전일 때만이다', () => {
  const now = Date.parse('2026-11-14T00:30:00+09:00'); // UTC로는 11-13
  assert.equal(isEndedByPeriod({ to: '2026-11-13' }, now), true);
  assert.equal(isEndedByPeriod({ to: '2026-11-14' }, now), false);
  assert.equal(isEndedByPeriod({ to: '2026-12-01' }, now), false);
  assert.equal(isEndedByPeriod(null, now), false);
});
