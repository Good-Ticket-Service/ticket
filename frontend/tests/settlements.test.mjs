import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const code=ts.transpileModule(await readFile(new URL('../src/lib/settlements.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ESNext}}).outputText;
const s=await import(`data:text/javascript;base64,${Buffer.from(code).toString('base64')}`);
test('예상 정산은 양 끝 포함 31일까지만 허용한다',()=>{
 assert.equal(s.settlementPeriodError('2026-01-01','2026-01-31',true),null);
 assert.match(s.settlementPeriodError('2026-01-01','2026-02-01',true),/31일/);
 assert.match(s.settlementPeriodError('2026-01-02','2026-01-01'),/시작일/);
 assert.equal(s.settlementPeriodError('2026-02-30','2026-03-01',true),'올바른 날짜를 입력해 주세요.');
 assert.ok(s.settlementPeriodError('','',true));
 assert.equal(s.settlementPeriodError('',''),null);
});
test('기본 조회는 서울의 오늘을 포함한 최근 7일이다',()=>{
 assert.deepEqual(s.settlementRange(Date.parse('2026-11-12T16:00:00Z')),{from:'2026-11-07',to:'2026-11-13'});
});
test('회차 ID와 페이지는 서버 범위만 요청한다',()=>{
 for(const id of ['', '1','5000'])assert.equal(s.validScheduleFilter(id),true);
 for(const id of ['0','-1','1.2','text','9007199254740992'])assert.equal(s.validScheduleFilter(id),false);
 assert.equal(s.settlementPage('10000'),10000);
 for(const value of ['10001','-1','1.5','invalid'])assert.equal(s.settlementPage(value),0);
});
