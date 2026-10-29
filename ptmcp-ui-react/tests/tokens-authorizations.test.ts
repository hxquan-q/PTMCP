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

test('token status filter narrows to revoked after revocation, cursor advances', async () => {
  await withPreviewServer(async () => {
    // 造一条已撤销令牌
    const created = await access.createToken({
      name: '撤销联测',
      platformId: '1',
      scopes: ['mcp:catalog'],
      expiresAt: new Date(Date.now() + 86400_000).toISOString(),
    });
    await access.revokeToken(created.metadata.id);

    const active = await access.listMyTokens({ status: 'ACTIVE', pageSize: 100 });
    assert.ok(active.items.every((t) => (t.status ?? 'ACTIVE') === 'ACTIVE'));
    assert.ok(!active.items.some((t) => t.id === created.metadata.id));

    const revoked = await access.listMyTokens({ status: 'REVOKED', pageSize: 100 });
    assert.ok(revoked.items.some((t) => t.id === created.metadata.id));
    assert.ok(revoked.items.every((t) => t.status === 'REVOKED'));

    const paged = await access.listMyTokens({ pageSize: 1 });
    if (paged.nextCursor) {
      const next = await access.listMyTokens({ pageSize: 1, cursor: paged.nextCursor });
      assert.notEqual(next.items[0]?.id, paged.items[0]?.id);
    }
  });
});

test('authorization list pages by cursor without overlap', async () => {
  await withPreviewServer(async () => {
    const first = await access.listMyAuthorizations({ pageSize: 1 });
    if (!first.nextCursor) {
      assert.ok(first.items.length <= 1);
      return;
    }
    const second = await access.listMyAuthorizations({ pageSize: 1, cursor: first.nextCursor });
    assert.notEqual(second.items[0]?.id, first.items[0]?.id);
  });
});
