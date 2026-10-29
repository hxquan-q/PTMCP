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
const forms = await import('../src/views/admin/orgForms.ts');
sourceImports.deregister();

const { validateUserForm, validateDeptForm, departmentDescendants, departmentDepth } = forms;

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

test('user lifecycle: create, split subresource updates, status toggle, delete', async () => {
  await withPreviewServer(async () => {
    const [depts, roles] = await Promise.all([org.listDepartments(), org.listRoles()]);
    const created = await org.createUser({
      username: 'li.si',
      displayName: '李四',
      deptId: depts[0].id,
      roleIds: [roles[0].id],
      enabled: true,
    });
    assert.equal(created.username, 'li.si');
    assert.equal(created.version, '1');
    assert.deepEqual(created.roleIds, [roles[0].id]);

    // 分离子资源：改部门、改角色、停用；每次写后版本递增，链式携带最新 version
    let version = created.version;
    const moved = await org.setUserDepartment(created.id, depts[1]!.id, version);
    version = moved.version ?? version;
    const reassigned = await org.setUserRoles(created.id, [roles[1]!.id], version);
    version = reassigned.version ?? version;
    const disabled = await org.setUserStatus(created.id, false, version);
    assert.equal(disabled.enabled, false);

    // 每次写后 version 递增，旧版本再写必须 412
    await assert.rejects(
      () => org.setUserStatus(created.id, true, created.version),
      (error: unknown) => (error as { status?: number }).status === 412,
    );

    // 删除使用最新版本
    const latest = await org.getUser(created.id);
    await org.deleteUser(created.id, latest.version);
    const remaining = await org.listUsers();
    assert.ok(!remaining.some((u) => u.id === created.id));
  });
});

test('department CRUD prevents stale overwrites and member-visible hierarchy', async () => {
  await withPreviewServer(async () => {
    const existing = await org.listDepartments();
    const root = existing[0]!;
    const created = await org.createDepartment({ parentId: root.id, name: '平台工程组', enabled: true });
    assert.equal(created.parentId, root.id);
    assert.equal(created.version, '1');

    await assert.rejects(
      () => org.updateDepartment(created.id, { parentId: root.id, name: '被覆盖', enabled: true }, '0'),
      (error: unknown) => (error as { status?: number }).status === 412,
    );
    const renamed = await org.updateDepartment(created.id, { parentId: null, name: '独立组', enabled: false }, created.version);
    assert.equal(renamed.name, '独立组');
    assert.equal(renamed.parentId, null);

    await org.deleteDepartment(renamed.id, renamed.version);
    const after = await org.listDepartments();
    assert.ok(!after.some((d) => d.id === created.id));
  });
});

test('validateUserForm requires the essentials and locks username to create mode', () => {
  const valid = { username: 'li.si', displayName: '李四', deptId: '1', roleIds: ['1'], enabled: true };
  assert.equal(validateUserForm(valid, true), null);
  assert.equal(validateUserForm({ ...valid, username: '' }, false), null);
  assert.match(String(validateUserForm({ ...valid, username: '' }, true)), /用户名/);
  assert.match(String(validateUserForm({ ...valid, username: 'a' }, true)), /2–100/);
  assert.match(String(validateUserForm({ ...valid, displayName: '' }, false)), /显示名/);
  assert.match(String(validateUserForm({ ...valid, displayName: 'x'.repeat(129) }, false)), /2–128/);
  assert.match(String(validateUserForm({ ...valid, deptId: '' }, false)), /部门/);
});

test('validateDeptForm guards empty and over-long names', () => {
  assert.equal(validateDeptForm({ name: '平台工程组', parentId: null, enabled: true }), null);
  assert.match(String(validateDeptForm({ name: ' ', parentId: null, enabled: true })), /部门名称/);
  assert.match(String(validateDeptForm({ name: '组'.repeat(129), parentId: null, enabled: true })), /2–128/);
});

test('departmentDescendants includes the subtree and blocks cycles in the parent picker', () => {
  const tree = [
    { id: '1', parentId: null },
    { id: '2', parentId: '1' },
    { id: '3', parentId: '2' },
    { id: '4', parentId: null },
  ];
  assert.deepEqual([...departmentDescendants(tree, '1')].sort(), ['1', '2', '3']);
  assert.deepEqual([...departmentDescendants(tree, '3')], ['3']);
  assert.deepEqual([...departmentDescendants(tree, '4')], ['4']);
  assert.equal(departmentDepth(tree, '3'), 2);
  assert.equal(departmentDepth(tree, '1'), 0);
});
