import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

// Vite resolves extensionless source imports; mirror that only while loading these TS modules.
const sourceImports = registerHooks({
  resolve(specifier: string, context: { parentURL?: string }, nextResolve: (s: string, c: unknown) => unknown) {
    const extensionlessSource = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionlessSource ? `${specifier}.ts` : specifier, context);
  },
});
const access = await import('../src/services/access.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const preview = await import('../src/preview/server.ts');
sourceImports.deregister();

const { createPreviewServer, setPreviewScenario } = preview;

async function withServer(fn: () => Promise<void>): Promise<void> {
  const server = createPreviewServer();
  const originalFetch = globalThis.fetch;
  setCsrfToken('test-csrf');
  setPreviewScenario('normal');
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) =>
    server(String(input).replace('/backend/admin', ''), {
      method: init?.method,
      headers: init?.headers,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      signal: init?.signal,
    })) as typeof fetch;
  try {
    await fn();
  } finally {
    setPreviewScenario('normal');
    globalThis.fetch = originalFetch;
  }
}

test('scenario lifecycle: error blocks all, conflict blocks writes only, normal restores', async () => {
  await withServer(async () => {
    // ─── normal：一切正常 ───
    const ok = await access.listAudit({ pageSize: 5 });
    assert.ok(ok.items.length > 0, 'normal scenario must return data');

    // ─── error：全部 503 ───
    setPreviewScenario('error');
    await assert.rejects(
      () => access.listAudit({ pageSize: 5 }),
      (e: unknown) => (e as { status?: number }).status === 503,
    );

    // ─── conflict：GET 正常，写操作 412 ───
    setPreviewScenario('conflict');
    const stillReads = await access.listAudit({ pageSize: 5 });
    assert.ok(stillReads.items.length > 0, 'conflict scenario must still allow reads');
    await assert.rejects(
      () =>
        access.createPlatform({ code: 'scenario-test', name: '场景测试', tier: 1, catalogMode: 'BLACKLIST', proofMode: 'NONE', status: 'ACTIVE' }),
      (e: unknown) => (e as { status?: number }).status === 412,
      'conflict scenario must reject writes with 412',
    );

    // ─── 恢复 normal ───
    setPreviewScenario('normal');
    const restored = await access.listAudit({ pageSize: 5 });
    assert.ok(restored.items.length > 0, 'resetting to normal must restore functionality');
  });
});

test('garbage cursor returns empty results without crashing (NaN slice)', async () => {
  await withServer(async () => {
    const page = await access.listAudit({ cursor: 'garbage-cursor', pageSize: 10 });
    assert.deepEqual(page.items, [], 'garbage cursor must return empty items, not crash');
    assert.equal(page.nextCursor, null, 'garbage cursor must yield no next cursor');

    // 同样适用于其他分页端点
    const tokens = await access.listMyTokens({ cursor: 'also-garbage', pageSize: 10 });
    assert.deepEqual(tokens.items, []);
    assert.equal(tokens.nextCursor, null);
  });
});
