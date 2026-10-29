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
const data = await import('../src/services/data.ts');
const dsFormModule = await import('../src/views/admin/dsForm.ts');
const { dsCreateValidate, dsValidate, emptyDsForm } = dsFormModule;
type DsForm = dsFormModule.DsForm;
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

test('createDatasource persists with derived state and never echoes the credential', async () => {
  await withPreviewServer(async () => {
    const body = {
      code: 'order-mysql',
      name: '订单库',
      engine: 'MYSQL',
      host: 'db.internal',
      port: 3306,
      databaseName: 'orders',
      username: 'reader',
      credential: 'secret-value',
      tlsMode: 'VERIFY_FULL' as const,
      poolMax: 4,
      status: 'ACTIVE',
    };
    const { data: created, etag } = await data.createDatasource(body);
    assert.ok(created.id);
    assert.equal(created.status, 'ACTIVE');
    assert.equal(created.health, 'UNKNOWN', 'new source starts unknown until first test/sync');
    assert.equal((created as unknown as Record<string, unknown>).credential, undefined, 'write-only credential must not be echoed');
    assert.equal(created.credentialConfigured, true);
    assert.ok(etag);

    const { data: page } = await data.listDatasources({ pageSize: 100 });
    const stored = page.items.find((d) => d.id === created.id);
    assert.ok(stored, 'created source appears in the catalogue');
    assert.equal((stored as unknown as Record<string, unknown>).credential, undefined);
  });
});

test('dsValidate keeps edit semantics; dsCreateValidate adds code and credential rules', () => {
  const base: DsForm = { ...emptyDsForm(), name: '订单库', host: 'db', databaseName: 'orders', username: 'reader', credential: '' };
  // 编辑：凭据可留空（不回读、不强制重填）
  assert.deepEqual(dsValidate(base), {});
  // 新建：编码 + 凭据必填
  const createErrs = dsCreateValidate(base);
  assert.match(createErrs.code!, /请输入编码/);
  assert.match(createErrs.credential!, /仅创建时提供/);
  const okForm = { ...base, code: 'order-mysql', credential: 'secret' };
  assert.deepEqual(dsCreateValidate(okForm), {});
  // 编码格式
  assert.match(dsCreateValidate({ ...okForm, code: 'Bad_Code' }).code!, /小写字母/);
  assert.match(dsCreateValidate({ ...okForm, port: 70000 }).port!, /1～65535/);
  assert.match(dsCreateValidate({ ...okForm, poolMax: 9 }).poolMax!, /1～8/);
});
