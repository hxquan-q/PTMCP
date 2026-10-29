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

async function firstRequestId(): Promise<string> {
  const page = await access.listAudit();
  return page.items[0]!.requestId;
}

test('audit decrypt rejects weak reasons, unknown fields and unknown requests', async () => {
  await withPreviewServer(async () => {
    const requestId = await firstRequestId();
    await assert.rejects(
      () => access.decryptAudit(requestId, '太短', ['sqlRaw']),
      (error: unknown) => (error as { status?: number }).status === 400,
    );
    await assert.rejects(
      () => access.decryptAudit(requestId, 'x'.repeat(501), ['sqlRaw']),
      (error: unknown) => (error as { status?: number }).status === 400,
    );
    await assert.rejects(
      () => access.decryptAudit(requestId, '排查线上工单 123 需要核对实际执行语句', ['not-a-field']),
      (error: unknown) => (error as { status?: number }).status === 400,
    );
    await assert.rejects(
      () => access.decryptAudit('no-such-request', '排查线上工单 123 需要核对实际执行语句', ['sqlRaw']),
      (error: unknown) => (error as { status?: number }).status === 404,
    );
  });
});

test('audit decrypt returns short-lived plaintext with an expiry', async () => {
  await withPreviewServer(async () => {
    const requestId = await firstRequestId();
    const result = await access.decryptAudit(requestId, '排查线上工单 123 需要核对实际执行语句', ['sqlRaw', 'sqlFinal']);
    assert.ok(result.sqlRaw);
    assert.ok(result.sqlFinal);
    assert.match(result.sqlRaw, /synthetic preview only/);
    const expiry = new Date(result.expiresAt).getTime();
    assert.ok(expiry > Date.now() && expiry <= Date.now() + 60_000, 'expiry must be within a minute');
  });
});
