import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';

// 기존 TypeScript 컴파일러로 순수 표시 함수를 읽어 브라우저 없이 호환 규칙을 검증한다.
async function load(source) {
  const text = await readFile(new URL(source, import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(text, {
    compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 },
  });
  return import(`data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`);
}

const { ageGuidanceLabel } = await load('../src/lib/labels.ts');
const { GENRE_OPTIONS, genreLabel } = await load('../src/lib/genres.ts');

test('원문 안내를 기존 등급보다 먼저 표시한다', () => {
  for (const guidance of ['만 8세 이상', '36개월 이상', '초등학생 이상', '전체 관람가']) {
    assert.equal(ageGuidanceLabel(guidance, '12'), guidance);
    assert.equal(ageGuidanceLabel(guidance, null), guidance);
  }
});

test('기존 한글 등급을 유지하고 미제공을 전체 관람가로 추정하지 않는다', () => {
  for (const [rating, label] of Object.entries({ ALL: '전체 관람가', 12: '12세 이상', 15: '15세 이상', 19: '19세 이상' })) {
    assert.equal(ageGuidanceLabel(null, rating), label);
  }
  assert.equal(ageGuidanceLabel(null, null), '정보 미제공');
  assert.equal(ageGuidanceLabel(undefined, undefined), '정보 미제공');
  assert.equal(ageGuidanceLabel('   ', null), '정보 미제공');
});

test('등록·검색용 선택지가 모든 장르의 표시 이름과 일치한다', () => {
  assert.equal(GENRE_OPTIONS.length, 9);
  assert.equal(new Set(GENRE_OPTIONS.map(({ value }) => value)).size, 9);
  for (const { value, label } of GENRE_OPTIONS) assert.equal(genreLabel(value), label);
  assert.equal(genreLabel('CLASSICAL'), '클래식');
  assert.equal(genreLabel('CONCERT'), '콘서트');
  assert.equal(genreLabel('KOREAN_MUSIC'), '국악');
  assert.equal(genreLabel('DANCE'), '무용');
  assert.equal(genreLabel('CIRCUS_MAGIC'), '서커스·마술');
  assert.equal(genreLabel('MULTIDISCIPLINARY'), '복합 공연');
});
