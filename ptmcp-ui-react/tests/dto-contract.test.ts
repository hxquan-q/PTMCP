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
const access = await import('../src/services/access.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

/** 捕获请求体 + 转发到 preview server 的双重代理。 */
async function withBodyCapture(fn: (bodies: Record<string, unknown>[]) => Promise<void>): Promise<void> {
  const server = createPreviewServer();
  const originalFetch = globalThis.fetch;
  const bodies: Record<string, unknown>[] = [];
  setCsrfToken('test-csrf');
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    if (init?.body && typeof init.body === 'string') bodies.push(JSON.parse(init.body));
    return server(String(input).replace('/backend/admin', ''), {
      method: init?.method,
      headers: init?.headers,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      signal: init?.signal,
    });
  }) as typeof fetch;
  try {
    await fn(bodies);
  } finally {
    globalThis.fetch = originalFetch;
  }
}

/** 断言请求体恰好包含期望键集（不缺失、不多余）。 */
function assertExactKeys(body: Record<string, unknown>, expected: string[], label: string): void {
  const actual = Object.keys(body).sort();
  assert.deepEqual(actual, [...expected].sort(), `${label}: DTO must contain exactly ${expected.join(', ')} — got ${actual.join(', ')}`);
}

test('user create/update DTOs carry only write fields, never id/version/credential', async () => {
  await withBodyCapture(async (bodies) => {
    await org.createUser({ username: 'dto-test', displayName: 'DTO联测', deptId: '1', roleIds: ['1'], enabled: true });
    assertExactKeys(bodies[0]!, ['username', 'displayName', 'deptId', 'roleIds', 'enabled'], 'createUser');

    // DTO 形状检查不依赖操作成功（412 版本冲突时请求体仍已捕获）
    await org.updateUser('1', { displayName: '改名', enabled: false }, '99').catch(() => undefined);
    assertExactKeys(bodies[1]!, ['displayName', 'enabled'], 'updateUser(profile)');

    await org.setUserStatus('1', false, '99').catch(() => undefined);
    assertExactKeys(bodies[2]!, ['status'], 'setUserStatus');

    await org.setUserRoles('1', ['2'], '99').catch(() => undefined);
    assertExactKeys(bodies[3]!, ['roleIds'], 'setUserRoles');

    await org.setUserDepartment('1', '2', '99').catch(() => undefined);
    assertExactKeys(bodies[4]!, ['departmentId'], 'setUserDepartment');
  });
});

test('role create/update DTOs carry only write fields; update never includes the immutable key', async () => {
  await withBodyCapture(async (bodies) => {
    await org.createRole({ key: 'DTO_TEST', name: 'DTO角色', enabled: true, permissionCodes: ['ptmcp:user:list'] });
    assertExactKeys(bodies[0]!, ['key', 'name', 'enabled', 'permissionCodes'], 'createRole');

    await org.updateRole('1', { name: '改名', enabled: false, permissionCodes: [] }, '1');
    assertExactKeys(bodies[1]!, ['name', 'enabled', 'permissionCodes'], 'updateRole');
    assert.ok(!('key' in bodies[1]!), 'updateRole must not include the immutable key');
  });
});

test('delegation create DTO carries only domain fields; sensitive ops DTOs are audit-shaped', async () => {
  await withBodyCapture(async (bodies) => {
    await access.createDelegation({
      userId: '2',
      departmentRootId: '1',
      resourceType: 'DATASOURCE',
      resourceId: '1',
      allowedActions: ['ptmcp:user:list'],
      validFrom: '2026-11-01T09:00:00Z',
      validTo: '2026-12-01T09:00:00Z',
    });
    assertExactKeys(
      bodies[0]!,
      ['userId', 'departmentRootId', 'resourceType', 'resourceId', 'allowedActions', 'validFrom', 'validTo'],
      'createDelegation',
    );

    await access.decryptAudit('preview-request-1', '排查工单 123 需要核对执行语句', ['sqlRaw']);
    assertExactKeys(bodies[1]!, ['reason', 'fields'], 'decryptAudit');
  });
});
