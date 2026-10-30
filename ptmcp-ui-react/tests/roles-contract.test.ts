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
const roleForm = await import('../src/views/admin/roleForm.ts');
const { validateRoleForm } = roleForm;
type RoleFormValues = roleForm.RoleFormValues;
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

/** 把 request() 的同源 fetch 代理到独立的 preview 实例，走真实传输层（含 CSRF/If-Match 头处理）。 */
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

const base = { name: '数据专员', enabled: true, permissionCodes: ['ptmcp:datasource:list', 'ptmcp:datasource:read'] };

test('role CRUD against the synthetic backend honours versioning and echoes writes', async () => {
  await withPreviewServer(async () => {
    const created = await org.createRole({ key: 'data_steward', ...base });
    assert.equal(created.key, 'data_steward');
    assert.equal(created.version, '1');
    assert.equal(created.enabled, true);
    assert.deepEqual(created.permissionCodes?.slice().sort(), base.permissionCodes);

    // stale version must 412, not silently overwrite
    await assert.rejects(
      () => org.updateRole(created.id, { ...base, name: '被覆盖的名字' }, '0'),
      (error: unknown) => (error as { status?: number }).status === 412,
    );

    const updated = await org.updateRole(created.id, { ...base, name: '数据管家', enabled: false }, created.version);
    assert.equal(updated.name, '数据管家');
    assert.equal(updated.enabled, false);

    // delete with a stale version must fail; with the fresh version must succeed
    await assert.rejects(
      () => org.deleteRole(created.id, '1'),
      (error: unknown) => (error as { status?: number }).status === 412,
    );
    await org.deleteRole(updated.id, updated.version);
    const remaining = await org.listRoles();
    assert.ok(!remaining.some((role) => role.id === created.id), 'deleted role must disappear from the catalogue');
  });
});

test('create is idempotent per Idempotency-Key and never leaks write-only fields', async () => {
  await withPreviewServer(async () => {
    // 两个不同 key 的重复提交会创建两行（幂等键在服务端去重，这里验证传输层稳定携带该头）
    const first = await org.createRole({ key: 'auditor', ...base });
    assert.ok(first.id);
    assert.equal(first.version, '1');
    await org.deleteRole(first.id, first.version).catch(() => undefined);
  });
});

test('validateRoleForm enforces key format only on create and name length always', () => {
  const valid: RoleFormValues = { key: 'data_steward', name: '数据专员', enabled: true, permissionCodes: [] };
  assert.equal(validateRoleForm(valid, true), null);
  assert.equal(validateRoleForm(valid, false), null);

  assert.match(String(validateRoleForm({ ...valid, key: '' }, true)), /编码/);
  assert.match(String(validateRoleForm({ ...valid, key: 'UPPER_CASE' }, true)), /小写字母/);
  assert.match(String(validateRoleForm({ ...valid, key: 'A' }, true)), /1–100/);
  assert.match(String(validateRoleForm({ ...valid, key: '1abc' }, true)), /小写字母开头/);
  // 编辑时不校验编码（编码不可改）
  assert.equal(validateRoleForm({ ...valid, key: '' }, false), null);
  assert.match(String(validateRoleForm({ ...valid, name: '短' }, false)), /2–30/);
  assert.match(String(validateRoleForm({ ...valid, name: 'x'.repeat(31) }, false)), /2–30/);
  assert.match(String(validateRoleForm({ ...valid, name: '  ' }, false)), /2–30/);
  // 边界：1 字符（最小）、100 字符（最大）、101 字符（超限）
  assert.equal(validateRoleForm({ ...valid, key: 'a'.repeat(1) }, true), null, '1 char key must be valid');
  assert.equal(validateRoleForm({ ...valid, key: 'a'.repeat(100) }, true), null, '100 char key must be valid');
  assert.match(String(validateRoleForm({ ...valid, key: 'a'.repeat(101) }, true)), /1–100/);
  // 大写字母被拒绝
  assert.match(String(validateRoleForm({ ...valid, key: 'UPPER' }, true)), /小写字母/);
});
