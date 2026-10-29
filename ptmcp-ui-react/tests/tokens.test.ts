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
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input).replace('/backend/admin', '');
    return server(path, {
      method: init?.method,
      headers: init?.headers,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      signal: init?.signal,
    });
  }) as typeof fetch;
  try {
    await fn();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

test('PAT create hands out the plaintext exactly once and metadata stays redacted', async () => {
  await withPreviewServer(async () => {
    const expiresAt = new Date(Date.now() + 30 * 86400_000).toISOString();
    const created = await access.createToken({ name: '联测令牌', platformId: '1', scopes: ['mcp:catalog', 'mcp:query'], expiresAt });
    assert.ok(created.token, 'create must return the one-time plaintext token');
    assert.equal(created.metadata.name, '联测令牌');
    assert.equal(created.metadata.status ?? 'ACTIVE', 'ACTIVE');

    // 列表里只有前缀，不再有明文
    const page = await access.listMyTokens();
    const stored = page.items.find((t) => t.id === created.metadata.id);
    assert.ok(stored, 'created token must appear in the list');
    assert.equal((stored as unknown as Record<string, unknown>).token, undefined);
    assert.match(stored.prefix ?? '', /^preview_/);
    assert.deepEqual(stored.scopes, ['mcp:catalog', 'mcp:query']);

    // 撤销后状态翻转（软删）
    await access.revokeToken(created.metadata.id);
    const after = await access.listMyTokens();
    const revoked = after.items.find((t) => t.id === created.metadata.id);
    assert.ok(!revoked || revoked.status !== 'ACTIVE');
  });
});
