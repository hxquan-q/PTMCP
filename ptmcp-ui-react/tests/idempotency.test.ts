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
const { idempotencyKey, setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

async function withHeaderCapture(fn: (headers: Headers[]) => Promise<void>): Promise<void> {
  const server = createPreviewServer();
  const originalFetch = globalThis.fetch;
  const capturedHeaders: Headers[] = [];
  setCsrfToken('test-csrf');
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.method === 'POST') capturedHeaders.push(new Headers(init.headers));
    return server(String(input).replace('/backend/admin', ''), {
      method: init?.method,
      headers: init?.headers,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      signal: init?.signal,
    });
  }) as typeof fetch;
  try {
    await fn(capturedHeaders);
  } finally {
    globalThis.fetch = originalFetch;
  }
}

const body = { key: 'IDEM_TEST', name: '幂等联测', enabled: true, permissionCodes: ['ptmcp:user:list'] };

test('createRole with explicit key sends that Idempotency-Key; without key generates a UUID', async () => {
  await withHeaderCapture(async (headers) => {
    // 显式传入：使用调用方的 key（同意图重试路径）
    await org.createRole(body, 'fixed-intent-key-123');
    assert.equal(headers[0]!.get('Idempotency-Key'), 'fixed-intent-key-123', 'explicit key must be used verbatim');

    // 不传 key：自动生成 UUID 格式
    await org.createRole(body);
    const auto = headers[1]!.get('Idempotency-Key') ?? '';
    assert.match(auto, /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/, 'auto-generated key must be a UUID');
    assert.notEqual(auto, 'fixed-intent-key-123', 'auto key must differ from explicit key');
  });
});

test('same-intent retry sends the same Idempotency-Key; new intent gets a different key', async () => {
  await withHeaderCapture(async (headers) => {
    // 首次提交：生成 key A
    const keyA = idempotencyKey();
    await org.createRole(body, keyA);

    // OPERATION_UNKNOWN 后同意图重试：复用 key A
    await org.createRole(body, keyA);
    assert.equal(headers[0]!.get('Idempotency-Key'), keyA);
    assert.equal(headers[1]!.get('Idempotency-Key'), keyA, 'retry with same intent must reuse the same key');

    // 新意图（改了 body 或重开对话框）：生成 key B
    const keyB = idempotencyKey();
    await org.createRole({ ...body, name: '新意图角色' }, keyB);
    const sentKeyB = headers[2]!.get('Idempotency-Key');
    assert.equal(sentKeyB, keyB);
    assert.notEqual(keyA, keyB, 'different intents must use different keys');
  });
});

test('idempotencyKey generates unique UUIDs on successive calls (no key collision)', () => {
  const keys = new Set<string>();
  for (let i = 0; i < 100; i++) keys.add(idempotencyKey());
  assert.equal(keys.size, 100, '100 successive calls must produce 100 unique keys');
});
