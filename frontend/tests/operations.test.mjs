import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const source = await readFile(new URL('../src/lib/operationCommand.ts', import.meta.url), 'utf8');
const code = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
}).outputText;
const { commandSession, operationMinutes } = await import(
  'data:text/javascript;base64,' + Buffer.from(code).toString('base64')
);
test('응답 유실 뒤에는 ID뿐 아니라 경로·사유·기대 상태 전체를 재사용한다', () => {
  const session = commandSession();
  const input = { commandId: 'first', expectedVersion: 1, expectedAttempt: 2, note: '첫 메모' };
  const first = session.prepare('/first', input);
  input.note = '변경';
  session.settle(0);
  const replay = session.prepare('/other', { commandId: 'new', expectedVersion: 9, note: '다른 메모' });
  assert.equal(replay, first);
  assert.equal(replay.body.note, '첫 메모');
  assert.equal(replay.path, '/first');
  assert.equal(replay.body.expectedVersion, 1);
});
test('503을 포함한 서버 오류는 같은 명령을 유지하고 확정 결과 뒤에는 새 명령을 허용한다', () => {
  const session = commandSession();
  const first = session.prepare('/retry', { commandId: 'one' });
  for (const status of [503, 502, 500]) {
    session.settle(status);
    assert.equal(session.prepare('/retry', { commandId: 'two' }), first);
  }
  session.settle(409);
  const second = session.prepare('/resume', { commandId: 'two' });
  assert.notEqual(second, first);
  session.settle();
  assert.notEqual(session.prepare('/retry', { commandId: 'three' }), second);
});
test('환불 경과 시간 필터는 서버 정수 범위 안에서만 요청한다', () => {
  assert.equal(operationMinutes(''), '');
  assert.equal(operationMinutes('0'), '0');
  assert.equal(operationMinutes('5256000'), '5256000');
  for (const v of ['-1', '1.5', '5256001', 'Infinity', '1e3']) assert.throws(() => operationMinutes(v));
});
