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

test('audit query applies verdict and datasource filters server-side', async () => {
  await withPreviewServer(async () => {
    const all = await access.listAudit({ pageSize: 100 });
    assert.ok(all.items.length > 0);
    const verdicts = new Set(all.items.map((r) => r.verdict ?? ''));
    const firstVerdict = [...verdicts][0]!;
    const byVerdict = await access.listAudit({ verdict: firstVerdict, pageSize: 100 });
    assert.ok(byVerdict.items.every((r) => (r.verdict ?? '') === firstVerdict));

    const dsIds = new Set(all.items.map((r) => r.datasourceId));
    const firstDs = [...dsIds][0]!;
    const byDs = await access.listAudit({ datasourceId: firstDs, pageSize: 100 });
    assert.ok(byDs.items.every((r) => r.datasourceId === firstDs));
    if (dsIds.size > 1) assert.ok(byDs.items.length < all.items.length, 'datasource filter must narrow when several sources exist');
  });
});

test('audit cursor pagination walks pages without overlap', async () => {
  await withPreviewServer(async () => {
    const first = await access.listAudit({ pageSize: 2 });
    if (!first.nextCursor) {
      assert.ok(first.items.length <= 2);
      return;
    }
    const second = await access.listAudit({ pageSize: 2, cursor: first.nextCursor });
    assert.ok(second.items.length > 0);
    const ids = [...first.items, ...second.items].map((r) => r.requestId);
    assert.equal(new Set(ids).size, ids.length);
  });
});
