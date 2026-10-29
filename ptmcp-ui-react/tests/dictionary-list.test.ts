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
const { mePortalApi } = await import('../src/services/mePortalApi.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

async function withPreviewServer(fn: () => Promise<void>): Promise<void> {
  const server = createPreviewServer();
  const originalFetch = globalThis.fetch;
  setCsrfToken('test-csrf');
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
    globalThis.fetch = originalFetch;
  }
}

test('personal dictionary list supports keyword and datasource filters with cursor paging', async () => {
  await withPreviewServer(async () => {
    const all = await mePortalApi.dictionaryList({ pageSize: 100 });
    assert.ok(all.items.length > 0);
    assert.ok(all.items.every((o) => typeof o.objectName === 'string'));

    const first = all.items[0]!;
    const byKeyword = await mePortalApi.dictionaryList({ keyword: first.objectName.slice(0, 5), pageSize: 100 });
    assert.ok(
      byKeyword.items.some((o) => o.id === first.id),
      'keyword filter must match by objectName',
    );

    if (new Set(all.items.map((o) => o.datasourceId)).size > 1) {
      const byDs = await mePortalApi.dictionaryList({ datasourceId: first.datasourceId, pageSize: 100 });
      assert.ok(byDs.items.every((o) => o.datasourceId === first.datasourceId));
      assert.ok(byDs.items.length < all.items.length);
    }

    const paged = await mePortalApi.dictionaryList({ pageSize: 1 });
    assert.ok(paged.nextCursor, 'single-item page must yield a cursor');
    const next = await mePortalApi.dictionaryList({ pageSize: 1, cursor: paged.nextCursor });
    assert.notEqual(next.items[0]?.id, paged.items[0]?.id, 'cursor must advance');
  });
});

test('access-context exposes datasource level for the personal cascade', async () => {
  await withPreviewServer(async () => {
    const ctx = await mePortalApi.accessContext.datasources('');
    assert.ok(ctx.items.length > 0);
    assert.equal(ctx.items[0]!.type, 'DATASOURCE');
  });
});
