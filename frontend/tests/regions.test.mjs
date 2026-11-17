import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';

const source = await readFile(new URL('../src/lib/regions.ts', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
});
const { REGION_OPTIONS, regionLabel } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
);

test('서버 Region과 같은 16개 코드를 순서대로 둔다', () => {
  assert.deepEqual(
    REGION_OPTIONS.map((option) => option.value),
    ['SEOUL', 'GYEONGGI', 'INCHEON', 'GANGWON', 'CHUNGBUK', 'CHUNGNAM', 'DAEJEON', 'SEJONG',
      'JEONBUK', 'JEONNAM_GWANGJU', 'GYEONGBUK', 'GYEONGNAM', 'DAEGU', 'ULSAN', 'BUSAN', 'JEJU'],
  );
});

test('코드는 화면 이름으로, 모르는 값은 그대로 보여 준다', () => {
  assert.equal(regionLabel('SEOUL'), '서울');
  assert.equal(regionLabel('JEONNAM_GWANGJU'), '전남광주');
  assert.equal(regionLabel('서울'), '서울');
});
