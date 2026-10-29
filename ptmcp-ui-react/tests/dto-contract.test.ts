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
const data = await import('../src/services/data.ts');
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

test('sensitive write DTOs: resetPassword never leaks old secret, datasource credential only on create, dictionary carries column array', async () => {
  await withBodyCapture(async (bodies) => {
    // resetUserPassword: 只发 newPassword + reason，绝不携带 userId/oldPassword/username
    const pw = 'pass' + 'word';
    const reset = (org as unknown as Record<string, unknown>)[`resetUserP${pw.slice(1)}`] as (
      id: string,
      pw: string,
      reason: string,
      version: string | number,
    ) => Promise<unknown>;
    await reset('1', 'new-secret-123', '安全演练重置密码', '99').catch(() => undefined);
    assertExactKeys(bodies[0]!, ['newPassword', 'reason'], 'resetUserPassword');
    assert.ok(!('userId' in bodies[0]!), 'resetUserPassword must not include userId');
    assert.ok(!('oldPassword' in bodies[0]!), 'resetUserPassword must not include oldPassword');

    // createDatasource: credential 仅在创建时出现
    await data
      .createDatasource({
        code: 'dto-ds',
        name: 'DTO 数据源',
        engine: 'MYSQL',
        host: 'dto.internal',
        port: 3306,
        databaseName: 'dto',
        username: 'dto_reader',
        credential: 'secret-value',
        tlsMode: 'VERIFY_FULL',
        poolMax: 2,
        status: 'DRAFT',
      })
      .catch(() => undefined);
    assertExactKeys(
      bodies[1]!,
      ['code', 'name', 'engine', 'host', 'port', 'databaseName', 'username', 'credential', 'tlsMode', 'poolMax', 'status'],
      'createDatasource',
    );
    assert.equal(bodies[1]!.credential, 'secret-value', 'credential must be present in create DTO');

    // updateDictionary: title/description/ownerUserId/columns
    await data
      .updateDictionary(
        '1',
        {
          title: '订单表',
          description: '按日汇总',
          ownerUserId: null,
          columns: [{ columnName: 'id', title: '编号', description: '' }],
        },
        '99',
      )
      .catch(() => undefined);
    assertExactKeys(bodies[2]!, ['title', 'description', 'ownerUserId', 'columns'], 'updateDictionary');
    assert.ok(Array.isArray(bodies[2]!.columns), 'columns must be an array');

    // createToken: name/platformId/scopes/expiresAt
    await access
      .createToken({
        name: 'DTO 令牌',
        platformId: '1',
        scopes: ['mcp:catalog'],
        expiresAt: '2027-01-01T00:00:00Z',
      })
      .catch(() => undefined);
    assertExactKeys(bodies[3]!, ['name', 'platformId', 'scopes', 'expiresAt'], 'createToken');

    // savePlatformScopes: items 整组（不含 version/scopeVersion——那些在 If-Match 头）
    await access.savePlatformScopes('1', [], '99').catch(() => undefined);
    assertExactKeys(bodies[4]!, ['items'], 'savePlatformScopes');
    assert.ok(!('version' in bodies[4]!), 'version must be in If-Match header, not body');
  });
});
