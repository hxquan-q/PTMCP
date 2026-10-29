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
const { OPERATION_AGGREGATE_TYPES, operationActionText, operationAggregateText } = await import('../src/services/operationLabels.ts');
const access = await import('../src/services/access.ts');
const org = await import('../src/services/orgPortalApi.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

test('operation labels map known codes and fall back to raw code for new ones', () => {
  assert.equal(operationActionText('DELEGATION_CREATE'), '创建委派');
  assert.equal(operationActionText('USER_PASSWORD_RESET'), '重置用户密码');
  assert.equal(operationActionText('PASSWORD_CHANGED'), '修改本人密码');
  assert.equal(operationActionText('FUTURE_NEW_ACTION'), 'FUTURE_NEW_ACTION', '未知动作码必须原样透出，不能静默吞掉');
  assert.equal(operationAggregateText('ADMIN_DELEGATION'), '委派');
  assert.equal(operationAggregateText('NEW_AGGREGATE'), 'NEW_AGGREGATE');
  // 筛选下拉取值与已知聚合类型一致且有序
  assert.deepEqual(OPERATION_AGGREGATE_TYPES, ['ADMIN_DELEGATION', 'PLATFORM', 'OAUTH_CLIENT', 'USER', 'DEPARTMENT', 'ROLE']);
});

test('operations are recorded on CRUD and filterable by aggregate type', async () => {
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
    // seed entries exist
    const initial = await access.listOperations({ pageSize: 20 });
    assert.ok(initial.items.length >= 2, 'seed operations must be present');
    assert.ok(initial.items.some((o) => o.action === 'PLATFORM_CREATE'));

    // CRUD generates new operations
    const created = await access.createPlatform({
      code: 'ops-test',
      name: '操作联测平台',
      tier: 1,
      catalogMode: 'BLACKLIST',
      proofMode: 'NONE',
      status: 'ACTIVE',
    });
    const after = await access.listOperations({ aggregateType: 'PLATFORM', pageSize: 20 });
    assert.ok(
      after.items.some((o) => o.action === 'PLATFORM_CREATE' && o.aggregateId === created.id),
      'creating a platform must append PLATFORM_CREATE',
    );

    // update records too
    await access.updatePlatform(
      created.id,
      { code: 'ops-test', name: '改名后', tier: 1, catalogMode: 'BLACKLIST', proofMode: 'NONE', status: 'ACTIVE' },
      created.version,
    );
    const afterUpdate = await access.listOperations({ aggregateType: 'PLATFORM', pageSize: 20 });
    assert.ok(
      afterUpdate.items.some((o) => o.action === 'PLATFORM_UPDATE' && o.aggregateId === created.id),
      'updating a platform must append PLATFORM_UPDATE',
    );

    // delete records
    const latest = await access.listOperations({ aggregateType: 'PLATFORM', pageSize: 100 });
    const latestRow = latest.items.find((o) => o.aggregateId === created.id && o.action === 'PLATFORM_UPDATE');
    // get fresh version
    const plats = await access.listPlatforms({ pageSize: 100 });
    const fresh = plats.items.find((p) => p.id === created.id);
    if (fresh) {
      await access.deletePlatform(created.id, fresh.version);
      const afterDelete = await access.listOperations({ aggregateType: 'PLATFORM', pageSize: 100 });
      assert.ok(
        afterDelete.items.some((o) => o.action === 'PLATFORM_DELETE' && o.aggregateId === created.id),
        'deleting a platform must append PLATFORM_DELETE',
      );
    }

    // actorId filter works
    const byActor = await access.listOperations({ actorId: '1', pageSize: 5 });
    assert.ok(byActor.items.every((o) => o.actorId === '1'));

    // seed ROLE operation is filterable
    const roles = await access.listOperations({ aggregateType: 'ROLE', pageSize: 10 });
    assert.ok(roles.items.some((o) => o.action === 'ROLE_CREATE'));
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('scope save and role CRUD generate operations with correct aggregate types', async () => {
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
    // Scope save records PLATFORM_SCOPE_SAVE
    const scopes = await access.getPlatformScopes('1');
    await access.savePlatformScopes(
      '1',
      [...scopes.items, { resourceType: 'DATASOURCE', resourceId: '2', resourceGeneration: '1', action: 'META', includeDescendants: true }],
      scopes.version,
    );
    const ops = await access.listOperations({ aggregateType: 'PLATFORM', pageSize: 100 });
    assert.ok(
      ops.items.some((o) => o.action === 'PLATFORM_SCOPE_SAVE' && o.aggregateId === '1'),
      'saving scopes must append PLATFORM_SCOPE_SAVE',
    );

    // Role CRUD records ROLE_CREATE / ROLE_DELETE
    const role = await org.createRole({ name: '操作联测角色', enabled: true, permissionCodes: ['ptmcp:user:list'] });
    await org.deleteRole(role.id, role.version).catch(() => undefined);
    const roleOps = await access.listOperations({ aggregateType: 'ROLE', pageSize: 100 });
    assert.ok(roleOps.items.some((o) => o.action === 'ROLE_CREATE' && o.aggregateId === role.id));
    assert.ok(roleOps.items.some((o) => o.action === 'ROLE_DELETE' && o.aggregateId === role.id));
  } finally {
    globalThis.fetch = originalFetch;
  }
});
