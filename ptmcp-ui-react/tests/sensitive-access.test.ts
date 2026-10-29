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

test('sensitive-access trail starts empty, grows per decrypt and keeps reason/fields', async () => {
  await withPreviewServer(async () => {
    const page = await access.listAudit({ pageSize: 10 });
    const requestId = page.items[0]!.requestId;

    const before = await access.listSensitiveAccess(requestId);
    assert.deepEqual(before, [], 'fresh preview must have no decrypt trail');

    await access.decryptAudit(requestId, '排查线上工单 123 需要核对执行语句', ['sqlRaw']);
    await access.decryptAudit(requestId, '复核脱敏是否泄露字面量，二次抽查', ['sqlRaw', 'sqlFinal']);

    const after = await access.listSensitiveAccess(requestId);
    assert.equal(after.length, 2, 'each decrypt must append exactly one trail row');
    assert.equal(after[0]!.operatorId, '1');
    assert.match(after[0]!.reason, /工单 123/);
    assert.deepEqual(after[0]!.fields, ['sqlRaw']);
    assert.deepEqual(after[1]!.fields, ['sqlRaw', 'sqlFinal']);
    assert.ok(after[0]!.createdAt <= after[1]!.createdAt, 'trail stays chronological');
  });
});

test('sensitive-access trail 404s for unknown requests', async () => {
  await withPreviewServer(async () => {
    await assert.rejects(
      () => access.listSensitiveAccess('no-such-request'),
      (error: unknown) => (error as { status?: number }).status === 404,
    );
  });
});
