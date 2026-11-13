import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const source = await readFile(new URL('../src/lib/account.ts', import.meta.url), 'utf8');
const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText;
const { loginDestination, profileChanges, PHONE_PATTERN, PASSWORD_PATTERN } = await import(
  `data:text/javascript;base64,${Buffer.from(code).toString('base64')}`
);
test('로그인 복귀는 앱 내부 경로만 허용한다', () => {
  assert.equal(loginDestination('/reservations/1/checkout?step=2'), '/reservations/1/checkout?step=2');
  for (const next of [null, 'https://other.test', '//other.test', '/\\other.test', '/\n/other.test'])
    assert.equal(loginDestination(next), '/');
});
test('회원 수정은 바뀐 이름·전화번호만 보내고 변경 없이는 요청하지 않는다', () => {
  const user = { name: '회원', phone: '010-1234-5678' };
  assert.equal(profileChanges(user, user), null);
  assert.deepEqual(profileChanges(user, { ...user, name: '새 이름' }), { name: '새 이름' });
  assert.deepEqual(profileChanges(user, { ...user, phone: '010-2345-6789' }), { phone: '010-2345-6789' });
});
test('가입 검증은 현재 서버 비밀번호·휴대폰 형식과 일치한다', () => {
  const password = new RegExp(`^${PASSWORD_PATTERN}$`),
    phone = new RegExp(`^${PHONE_PATTERN}$`);
  assert.equal(password.test('QaTicket2026!'), true);
  for (const text of [
    'short1!',
    'onlylower123!',
    'ONLYUPPER123!',
    'NoNumber!!!',
    'NoSpecial123',
    'Aa1!'.repeat(4),
  ])
    assert.equal(password.test(text), false);
  for (const text of ['010-1234-5678', '016-123-4567']) assert.equal(phone.test(text), true);
  for (const text of ['01012345678', '011-12-1234', '020-1234-5678']) assert.equal(phone.test(text), false);
});
