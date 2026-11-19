import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import ts from 'typescript';
const moduleUrl = (source) =>
  `data:text/javascript;base64,${Buffer.from(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2020 } }).outputText).toString('base64')}`;
const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');
const format = moduleUrl(await read('../src/lib/format.ts'));
const tokensUrl = moduleUrl(
  (await read('../src/lib/entryTokens.ts')).replace(/from ['"]\.\/format['"]/, `from '${format}'`),
);
const tokens = await import(tokensUrl);
const { mergeSeatStatus, seatSections } = await import(moduleUrl(await read('../src/lib/seatMap.ts')));
function storage() {
  const values = new Map();
  return new Proxy(
    {
      getItem: (k) => values.get(k) ?? null,
      setItem: (k, v) => values.set(k, v),
      removeItem: (k) => values.delete(k),
    },
    {
      ownKeys: () => [...values.keys()],
      getOwnPropertyDescriptor: () => ({ enumerable: true, configurable: true }),
    },
  );
}

test('room 입장권은 포함된 회차 scope에서만 재사용하며 다른 회차·구형 저장값은 사용하지 않는다', () => {
  globalThis.sessionStorage = storage();
  sessionStorage.setItem('ticket.entryToken.1', JSON.stringify({ token: 'legacy', expiresAt: '2999-01-01' }));
  tokens.saveEntryToken(
    { roomId: 7, scopes: ['schedule:100', 'schedule:101'] },
    'room-seven',
    '2999-01-01T00:00:00+09:00',
  );
  assert.equal(tokens.entryToken(100), 'room-seven');
  assert.equal(tokens.entryToken(101), 'room-seven');
  assert.equal(tokens.entryToken(102), null);
  tokens.saveEntryToken({ roomId: 8, scopes: ['schedule:102'] }, 'room-eight', '2999-01-01T00:00:00+09:00');
  tokens.clearEntryToken(100);
  assert.equal(tokens.entryToken(101), null);
  assert.equal(tokens.entryToken(102), 'room-eight');
  tokens.clearEntryTokens();
  assert.deepEqual(Object.keys(sessionStorage), []);
});
test('만료되거나 깨진 입장권을 제거하고 저장소 차단에도 토큰을 만들어 통과시키지 않는다', () => {
  globalThis.sessionStorage = storage();
  tokens.saveEntryToken({ roomId: 1, scopes: ['schedule:10'] }, 'expired', '2000-01-01T00:00:00+09:00');
  sessionStorage.setItem('ticket.entryToken.v2.room.bad', 'invalid json');
  assert.equal(tokens.entryToken(10), null);
  assert.deepEqual(Object.keys(sessionStorage), []);
  globalThis.sessionStorage = new Proxy(
    {},
    {
      ownKeys() {
        throw new Error('blocked');
      },
      get() {
        throw new Error('blocked');
      },
    },
  );
  assert.equal(tokens.entryToken(10), null);
  assert.doesNotThrow(() => tokens.clearEntryTokens());
});
test('상태 비트맵의 낮은 비트부터 좌석과 짝짓고 버전·개수·길이가 다르면 합치지 않는다', () => {
  const layout = {
    scheduleId: 1,
    configVersion: 4,
    seats: Array.from({ length: 9 }, (_, i) => ({ scheduleSeatId: i + 1 })),
  };
  const status = {
    scheduleId: 1,
    configVersion: 4,
    seatCount: 9,
    asOf: '2026-11-13T10:00:00+09:00',
    selectableBitmap: Buffer.from([129, 1]).toString('base64'),
  };
  assert.deepEqual(
    mergeSeatStatus(layout, status).map((s) => s.selectable),
    [true, false, false, false, false, false, false, true, true],
  );
  assert.throws(() => mergeSeatStatus(layout, { ...status, configVersion: 5 }));
  assert.throws(() => mergeSeatStatus(layout, { ...status, seatCount: 8 }));
  assert.throws(() => mergeSeatStatus(layout, { ...status, selectableBitmap: 'AA==' }));
});
test('구역·열·번호를 자연 정렬하면서 상태 비트맵의 원본 좌석 순서는 보존한다', () => {
  const seat = (id, section, rowLabel, seatNumber) => ({ scheduleSeatId: id, section, rowLabel, seatNumber });
  const input = [seat(1, 'B구역', '10', 10), seat(2, 'A구역', '2', 2), seat(3, 'A구역', '2', 1)];
  const sections = seatSections(input);
  assert.equal(sections[0].name, 'A구역');
  assert.deepEqual(
    sections[0].rows[0].seats.map((s) => s.seatNumber),
    [1, 2],
  );
  assert.deepEqual(
    input.map((s) => s.scheduleSeatId),
    [1, 2, 3],
  );
});
test('layout 304는 이전 본문을 재사용하고 인증·입장권·ETag 헤더를 유지한다', async () => {
  const clientUrl = moduleUrl(
    (await read('../src/api/client.ts')).replace(
      /from ['"]\.\.\/lib\/entryTokens['"]/,
      `from '${tokensUrl}'`,
    ),
  );
  const { conditionalApi } = await import(clientUrl);
  globalThis.localStorage = { getItem: () => 'test-auth' };
  const originalFetch = globalThis.fetch;
  let received;
  try {
    globalThis.fetch = async (path, options) => {
      received = options;
      return new Response(null, { status: 304 });
    };
    const previous = { data: { configVersion: 3 }, etag: 'W/"3"' };
    assert.equal(
      await conditionalApi('/api/test/layout', previous, { headers: { 'X-Queue-Token': 'test-entry' } }),
      previous,
    );
    assert.equal(received.headers.Authorization, 'Bearer test-auth');
    assert.equal(received.headers['X-Queue-Token'], 'test-entry');
    assert.equal(received.headers['If-None-Match'], 'W/"3"');
    globalThis.fetch = async () =>
      new Response(JSON.stringify({ data: { configVersion: 4 } }), {
        status: 200,
        headers: { ETag: 'W/"4"', 'Content-Type': 'application/json' },
      });
    assert.deepEqual(await conditionalApi('/api/test/layout', previous), {
      data: { configVersion: 4 },
      etag: 'W/"4"',
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});
