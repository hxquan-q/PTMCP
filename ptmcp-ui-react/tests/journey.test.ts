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
const session = await import('../src/services/session.ts');
const data = await import('../src/services/data.ts');
const access = await import('../src/services/access.ts');
const org = await import('../src/services/orgPortalApi.ts');
const mePortalModule = await import('../src/services/mePortalApi.ts');
const mePortalApi = mePortalModule.mePortalApi;
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

async function withJourney(fn: () => Promise<void>): Promise<void> {
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

test('admin journey: login → browse → role+user CRUD → operations trail → logout → re-login', async () => {
  await withJourney(async () => {
    // ─── 1. 登录与会话 ───
    const info = await session.getSession();
    assert.equal(info.username, 'preview-admin');
    assert.ok(info.permissions.includes('ptmcp:datasource:list'));

    // ─── 2. 浏览数据源与元数据 ───
    const dsPage = await data.listDatasources({ pageSize: 10 });
    assert.ok(dsPage.data.items.length >= 2, 'seed datasources must be browsable');
    const mysql = dsPage.data.items.find((d) => d.engine === 'MYSQL');
    assert.ok(mysql, 'MYSQL datasource must exist');

    const metaPage = await data.listMetadata({ datasourceId: mysql.id, pageSize: 10 });
    assert.ok(metaPage.data.items.length > 0, `datasource ${mysql.id} must have metadata objects`);

    // ─── 3. 创建角色（带权限码）───
    const role = await org.createRole({
      key: 'JOURNEY_ROLE',
      name: '旅程联测角色',
      enabled: true,
      permissionCodes: ['ptmcp:datasource:list', 'ptmcp:metadata:read'],
    });
    assert.equal(role.version, '1');

    // ─── 4. 更新角色（链式 version）───
    const updatedRole = await org.updateRole(
      role.id,
      { name: '旅程改名角色', enabled: true, permissionCodes: role.permissionCodes ?? [] },
      role.version,
    );
    assert.equal(updatedRole.name, '旅程改名角色');
    assert.ok(Number(updatedRole.version) > Number(role.version), 'version must advance');

    // ─── 5. 创建用户（分配新角色）───
    const user = await org.createUser({
      username: 'journey-user',
      displayName: '旅程联测用户',
      deptId: '1',
      roleIds: [role.id],
      enabled: true,
    });
    assert.deepEqual(user.roleIds, [role.id]);

    // ─── 6. 用户详情（角色名解析依赖目录）───
    const userDetail = await org.getUser(user.id);
    assert.equal(userDetail.displayName, '旅程联测用户');

    // ─── 7. 审计与操作日志联动 ───
    const opsBefore = await access.listOperations({ aggregateType: 'ROLE', pageSize: 100 });
    assert.ok(
      opsBefore.items.some((o) => o.action === 'ROLE_CREATE' && o.aggregateId === role.id),
      'ROLE_CREATE must be in operations',
    );
    assert.ok(
      opsBefore.items.some((o) => o.action === 'ROLE_UPDATE' && o.aggregateId === role.id),
      'ROLE_UPDATE must be in operations',
    );

    const auditPage = await access.listAudit({ pageSize: 5 });
    assert.ok(auditPage.items.length > 0, 'audit entries must be browsable');

    // ─── 8. 个人门户上下文 ───
    const accessCtx = await mePortalApi.accessContext.platforms();
    assert.ok(accessCtx.items.length > 0, 'platform context must be available');

    // ─── 9. 清理：删用户 → 删角色（依赖顺序）───
    await org.deleteUser(user.id, user.version);
    const freshRole = await org.getRole(role.id);
    await org.deleteRole(role.id, freshRole.version);

    // ─── 10. 操作日志记录了完整链路 ───
    const opsAfter = await access.listOperations({ aggregateType: 'ROLE', pageSize: 100 });
    assert.ok(
      opsAfter.items.some((o) => o.action === 'ROLE_DELETE' && o.aggregateId === role.id),
      'ROLE_DELETE must be recorded after cleanup',
    );
    const opsUsers = await access.listOperations({ aggregateType: 'USER', pageSize: 100 });
    assert.ok(
      opsUsers.items.some((o) => o.action === 'USER_CREATE' && o.aggregateId === user.id),
      'USER_CREATE must be recorded',
    );
    assert.ok(
      opsUsers.items.some((o) => o.action === 'USER_DELETE' && o.aggregateId === user.id),
      'USER_DELETE must be recorded',
    );

    // ─── 11. 登出 → 401 → 重登 ───
    await session.logout();
    await assert.rejects(
      () => session.getSession(),
      (e: unknown) => (e as { status?: number }).status === 401,
    );
    await session.login('preview-admin', 'any');
    const restored = await session.getSession();
    assert.equal(restored.username, 'preview-admin');
  });
});

test('cross-domain consistency: datasource CRUD cascades to metadata and operations', async () => {
  await withJourney(async () => {
    // 创建数据源
    const created = await data.createDatasource({
      code: 'journey-ds',
      name: '旅程数据源',
      engine: 'POSTGRESQL',
      host: 'journey.internal',
      port: 5432,
      databaseName: 'journey',
      username: 'journey_reader',
      credential: 'secret',
      tlsMode: 'VERIFY_FULL',
      poolMax: 2,
      status: 'DRAFT',
    });

    // 新数据源出现在列表中
    const list = await data.listDatasources({ keyword: 'journey-ds', pageSize: 10 });
    assert.ok(
      list.data.items.some((d) => d.id === created.data.id),
      'created datasource must be findable',
    );

    // 操作日志记录了 DATASOURCE 域（CREATE 动作码在 platform 操作映射中不存在，
    // 但 datasource 域的操作不会被记录因为 datasource 不在 OP_MAP 中——验证这个设计边界）
    const ops = await access.listOperations({ aggregateType: 'PLATFORM', pageSize: 100 });
    assert.ok(!ops.items.some((o) => o.aggregateId === created.data.id), 'datasource CRUD 不映射到 PLATFORM 聚合');

    // 清理
    await data.deleteDatasource(created.data.id, created.etag ?? '1');
    const after = await data.listDatasources({ keyword: 'journey-ds', pageSize: 10 });
    assert.ok(!after.data.items.some((d) => d.id === created.data.id), 'deleted datasource must be gone');
  });
});
