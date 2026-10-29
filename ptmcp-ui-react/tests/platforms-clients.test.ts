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

const platformBody = {
  code: `p${Date.now() % 100000}`,
  name: '联测平台',
  tier: 2,
  catalogMode: 'BLACKLIST',
  proofMode: 'PKCE_S256',
  status: 'ACTIVE',
};

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

test('confidential client creation returns a one-time secret that never reappears', async () => {
  await withPreviewServer(async () => {
    const platform = await access.createPlatform(platformBody);
    const created = await access.createClient(platform.id, { name: '机密客户端', type: 'CONFIDENTIAL', authMethods: ['client_secret_basic'] });
    assert.ok(created.client.id);
    assert.ok(created.clientSecret, 'CONFIDENTIAL + client_secret_basic must hand out the secret exactly once');
    assert.match(created.client.clientId, /^synthetic-client-/);

    // 再读详情：secret 不出现
    const page = await access.listClients(platform.id);
    const stored = page.items.find((c) => c.id === created.client.id);
    assert.ok(stored);
    assert.equal((stored as unknown as Record<string, unknown>).clientSecret, undefined);

    // 编辑走 If-Match；旧版本 412
    await assert.rejects(
      () => access.updateClient(created.client.id, { name: '改名', type: 'CONFIDENTIAL', authMethods: ['client_secret_basic'] }, '0'),
      (error: unknown) => (error as { status?: number }).status === 412,
    );
    const renamed = await access.updateClient(
      created.client.id,
      { name: '改名', type: 'CONFIDENTIAL', authMethods: ['client_secret_basic'] },
      created.client.version,
    );
    assert.equal(renamed.name, '改名');

    await access.deleteClient(renamed.id, renamed.version);
    const after = await access.listClients(platform.id);
    assert.ok(!after.items.some((c) => c.id === created.client.id));
    await access.deletePlatform(platform.id, platform.version).catch(() => undefined);
  });
});

test('public client creation omits the secret entirely', async () => {
  await withPreviewServer(async () => {
    const platform = await access.createPlatform(platformBody);
    const created = await access.createClient(platform.id, { name: '公开客户端', type: 'PUBLIC', authMethods: ['none'] });
    assert.equal(created.clientSecret, undefined);
    await access.deletePlatform(platform.id, platform.version).catch(() => undefined);
  });
});
