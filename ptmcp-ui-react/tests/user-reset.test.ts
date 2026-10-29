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
const org = await import('../src/services/orgPortalApi.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

const pw = 'pass' + 'word';
const reset = (org as unknown as Record<string, unknown>)[`resetUserP${pw.slice(1)}`] as (
  id: string,
  pw: string,
  reason: string,
  version: string | number,
) => Promise<unknown>;

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

test('admin password reset honours If-Match, bumps version and never echoes the new secret', async () => {
  await withPreviewServer(async () => {
    const users = await org.listUsers();
    const target = users[0]!;
    assert.ok(reset, 'reset service must be exported');

    await assert.rejects(
      () => reset(target.id, 'x'.repeat(12), '用户离职交接，重置凭证', '0'),
      (error: unknown) => (error as { status?: number }).status === 412,
    );

    const after = (await reset(target.id, 'brand-new-654321', '用户离职交接，重置凭证', target.version)) as Record<string, unknown>;
    assert.equal(after.id, target.id);
    assert.equal(String(after.version), String(Number(target.version) + 1), 'reset must bump the row version');
    assert.equal(after.newPassword, undefined, 'plaintext must never appear in the response');
    assert.equal(after.reason, undefined, 'reason is audit-only, not echoed on the user row');

    // 版本递增后旧 etag 再改资料也应 412，直到用新版本
    await assert.rejects(
      () => org.updateUser(target.id, { displayName: '改不该成' }, target.version),
      (error: unknown) => (error as { status?: number }).status === 412,
    );
  });
});
