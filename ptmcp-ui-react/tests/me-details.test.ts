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

test('my history detail returns the masked row by requestId and 404s for misses', async () => {
  await withPreviewServer(async () => {
    const page = await access.listMyHistory();
    const first = page.items[0]!;
    const detail = await access.getMyHistoryDetail(first.requestId);
    assert.equal(detail.requestId, first.requestId);
    assert.ok(detail.sqlMasked, 'personal view still carries the masked SQL');
    assert.ok(typeof detail.durationMs === 'number' || detail.durationMs === null);
    await assert.rejects(
      () => access.getMyHistoryDetail('no-such-request'),
      (error: unknown) => (error as { status?: number }).status === 404,
    );
  });
});

test('my dictionary detail exposes structure and annotations read-only', async () => {
  await withPreviewServer(async () => {
    const detail = await mePortalApi.dictionaryDetail<{ objectName: string; columns: unknown[]; dictionary?: { title?: string } }>('1', '1');
    assert.equal(detail.objectName, 'synthetic_orders');
    assert.ok(Array.isArray(detail.columns) && detail.columns.length > 0, 'column structure must be present');
    assert.ok(detail.dictionary?.title, 'dictionary annotation must be readable from the personal view');
    await assert.rejects(
      () => mePortalApi.dictionaryDetail('9999', '1'),
      (error: unknown) => (error as { status?: number }).status === 404,
    );
  });
});
