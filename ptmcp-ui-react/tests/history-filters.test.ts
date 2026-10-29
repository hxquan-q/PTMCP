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

test('history filters narrow server-side by tool and result', async () => {
  await withPreviewServer(async () => {
    const all = await access.listMyHistory({ pageSize: 100 });
    assert.ok(all.items.length > 0);
    const tools = new Set(all.items.map((r) => r.tool));
    const results = new Set(all.items.map((r) => r.result));

    const firstTool = [...tools][0]!;
    const byTool = await access.listMyHistory({ tool: firstTool, pageSize: 100 });
    assert.ok(byTool.items.length > 0);
    assert.ok(
      byTool.items.every((r) => r.tool === firstTool),
      'tool filter must be applied server-side',
    );

    if (results.size > 1) {
      const firstResult = [...results][0]!;
      const byResult = await access.listMyHistory({ result: firstResult, pageSize: 100 });
      assert.ok(byResult.items.every((r) => r.result === firstResult));
      assert.ok(byResult.items.length < all.items.length, 'filter must actually narrow when multiple results exist');
    }
  });
});

test('history cursor pagination walks pages without overlap', async () => {
  await withPreviewServer(async () => {
    const first = await access.listMyHistory({ pageSize: 2 });
    if (!first.nextCursor) {
      assert.ok(first.items.length <= 2);
      return;
    }
    const second = await access.listMyHistory({ pageSize: 2, cursor: first.nextCursor });
    assert.ok(second.items.length > 0);
    const ids = [...first.items, ...second.items].map((r) => r.requestId);
    assert.equal(new Set(ids).size, ids.length, 'pages must not overlap');
  });
});
