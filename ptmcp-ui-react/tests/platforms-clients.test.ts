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

interface CapturedRequest {
  path: string;
  method: string;
  headers: Headers;
  body: unknown;
}

async function withPreviewServer(fn: (requests: CapturedRequest[]) => Promise<void>): Promise<void> {
  const server = createPreviewServer();
  const originalFetch = globalThis.fetch;
  const requests: CapturedRequest[] = [];
  setCsrfToken('test-csrf');
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input).replace('/backend/admin', '');
    const body = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
    requests.push({ path, method: init?.method ?? 'GET', headers: new Headers(init?.headers), body });
    return server(path, {
      method: init?.method,
      headers: init?.headers,
      body,
      signal: init?.signal,
    });
  }) as typeof fetch;
  try {
    await fn(requests);
  } finally {
    globalThis.fetch = originalFetch;
  }
}

const platformBody = {
  code: `p${Date.now() % 100000}`,
  name: '联测平台',
  tier: 2,
  catalogMode: 'BLACKLIST',
  proofMode: 'PKCE_S256',
  status: 'ACTIVE',
};

/** 与服务端 ClientWrite 完全一致的写请求体（旧 name/type/authMethods 三字段会被真实后端 400）。 */
const confidentialClientBody = {
  name: '机密客户端',
  type: 'CONFIDENTIAL',
  authMethods: ['client_secret_basic'],
  redirectUris: ['https://client.example.test/oauth/callback'],
  allowedScopes: ['mcp:catalog', 'mcp:query'],
  jwksUri: null,
  status: 'ACTIVE',
};
const CLIENT_WRITE_KEYS = ['name', 'type', 'authMethods', 'redirectUris', 'allowedScopes', 'jwksUri', 'status'];

function clientWriteBodies(requests: CapturedRequest[]): CapturedRequest[] {
  return requests.filter((r) => /\/clients(\/|$)/.test(r.path) && (r.method === 'POST' || r.method === 'PUT'));
}

test('platform CRUD keeps versioning and never resurrects deleted rows', async () => {
  await withPreviewServer(async () => {
    const created = await access.createPlatform(platformBody);
    assert.equal(created.version, '1');
    assert.equal(created.tier, 2);

    await assert.rejects(
      () => access.updatePlatform(created.id, { ...platformBody, name: 'stale' }, '0'),
      (error: unknown) => (error as { status?: number }).status === 412,
    );
    const updated = await access.updatePlatform(created.id, { ...platformBody, name: '联测平台二期', status: 'DISABLED' }, created.version);
    assert.equal(updated.name, '联测平台二期');
    assert.equal(updated.status, 'DISABLED');

    await access.deletePlatform(updated.id, updated.version);
    const after = await access.listPlatforms({ pageSize: 100 });
    assert.ok(!after.items.some((p) => p.id === created.id));
  });
});

test('client writes send the full ClientWrite body, not the legacy three-field payload', async () => {
  await withPreviewServer(async (requests) => {
    const platform = await access.createPlatform(platformBody);
    const created = await access.createClient(platform.id, confidentialClientBody);
    assert.ok(created.client.id);
    assert.ok(created.clientSecret, 'CONFIDENTIAL + client_secret_basic must hand out the secret exactly once');
    assert.match(created.client.clientId, /^synthetic-client-/);

    const [post] = clientWriteBodies(requests);
    assert.ok(post, 'createClient must issue a POST');
    assert.deepEqual(post.body, confidentialClientBody);
    assert.deepEqual(Object.keys(post.body as Record<string, unknown>).sort(), [...CLIENT_WRITE_KEYS].sort());
    assert.equal(post.headers.get('Idempotency-Key') !== null, true);

    // 旧三字段请求体在预览也必须被拒绝，避免测试因宽松预览而通过。
    await assert.rejects(
      () =>
        access.createClient(platform.id, {
          name: '旧契约客户端',
          type: 'CONFIDENTIAL',
          authMethods: ['client_secret_basic'],
        } as unknown as Parameters<typeof access.createClient>[1]),
      (error: unknown) => (error as { status?: number }).status === 400,
    );

    // 再读详情：secret 不出现
    const page = await access.listClients(platform.id);
    const stored = page.items.find((c) => c.id === created.client.id);
    assert.ok(stored);
    assert.equal((stored as unknown as Record<string, unknown>).clientSecret, undefined);

    // 编辑走 If-Match；旧版本 412
    await assert.rejects(
      () => access.updateClient(created.client.id, { ...confidentialClientBody, name: '改名' }, '0'),
      (error: unknown) => (error as { status?: number }).status === 412,
    );
    const renamed = await access.updateClient(created.client.id, { ...confidentialClientBody, name: '改名' }, created.client.version);
    assert.equal(renamed.name, '改名');
    assert.deepEqual(renamed.allowedScopes, ['mcp:catalog', 'mcp:query']);

    const put = clientWriteBodies(requests).find((r) => r.method === 'PUT' && r.headers.get('If-Match') === `"${created.client.version}"`);
    assert.ok(put, 'updateClient must send If-Match with the row version');
    assert.deepEqual(put.body, { ...confidentialClientBody, name: '改名' });

    await access.deletePlatform(platform.id, platform.version).catch(() => undefined);
  });
});

test('public client creation omits the secret entirely', async () => {
  await withPreviewServer(async () => {
    const platform = await access.createPlatform(platformBody);
    const created = await access.createClient(platform.id, {
      name: '公开客户端',
      type: 'PUBLIC',
      authMethods: ['none'],
      redirectUris: ['http://127.0.0.1:5175/callback'],
      allowedScopes: ['mcp:catalog'],
      jwksUri: null,
      status: 'ACTIVE',
    });
    assert.equal(created.clientSecret, undefined);
    await access.deletePlatform(platform.id, platform.version).catch(() => undefined);
  });
});
