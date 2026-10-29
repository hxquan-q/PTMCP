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
const access = await import('../src/services/access.ts');
const org = await import('../src/services/orgPortalApi.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

test('preview seeds: every downstream test domain has sufficient data diversity', async () => {
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
    // 数据源：≥6（4 引擎 × 3 状态 × 降级健康 × 失败同步——datasourceStatus/ds-create/list-filters/preview-server 依赖）
    const ds = await data.listDatasources({ pageSize: 100 });
    assert.ok(ds.data.items.length >= 6, `datasource seeds: need ≥6, got ${ds.data.items.length}`);
    const engines = new Set(ds.data.items.map((d) => d.engine));
    assert.ok(engines.size >= 4, `engine diversity: need ≥4, got ${[...engines].join(',')}`);
    const statuses = new Set(ds.data.items.map((d) => d.status));
    assert.ok(statuses.size >= 3, `status diversity: need ≥3, got ${[...statuses].join(',')}`);
    assert.ok(
      ds.data.items.some((d) => d.health === 'DEGRADED'),
      'DEGRADED health seed required',
    );
    assert.ok(
      ds.data.items.some((d) => d.lastSyncResult === 'FAILED'),
      'FAILED sync seed required',
    );

    // 元数据：datasource 1 至少 2 个对象（resourceTree/dictionary/mcpTemplate 依赖）
    const meta = await data.listMetadata({ datasourceId: '1', pageSize: 100 });
    assert.ok(meta.data.items.length >= 2, `metadata seeds for ds=1: need ≥2, got ${meta.data.items.length}`);

    // 用户：≥2 且跨部门（list-filters 的 departmentId 筛选依赖）
    const users = await org.listUsers();
    assert.ok(users.length >= 2, `user seeds: need ≥2, got ${users.length}`);
    const depts = new Set(users.map((u) => u.deptId));
    assert.ok(depts.size >= 2, `user department diversity: need ≥2, got ${depts.size}`);

    // 角色：≥2（role CRUD + list filter 依赖）
    const roles = await org.listRoles();
    assert.ok(roles.length >= 2, `role seeds: need ≥2, got ${roles.length}`);

    // 审计：≥3 且 result 多样（audit-query 的 result 筛选依赖）
    const audit = await access.listAudit({ pageSize: 100 });
    assert.ok(audit.items.length >= 3, `audit seeds: need ≥3, got ${audit.items.length}`);
    const results = new Set(audit.items.map((a) => a.result));
    assert.ok(results.size >= 2, `audit result diversity: need ≥2, got ${results.size}`);

    // 操作日志：≥2 种子（operations 页面渲染依赖）
    const ops = await access.listOperations({ pageSize: 100 });
    assert.ok(ops.items.length >= 2, `operation seeds: need ≥2, got ${ops.items.length}`);

    // 平台：≥1（scope/client/mcpConfig 依赖）
    const platforms = await access.listPlatforms({ pageSize: 100 });
    assert.ok(platforms.items.length >= 1, `platform seeds: need ≥1, got ${platforms.items.length}`);

    // Scope 种子：platform 1 至少 1 条规则（scope diff 契约依赖）
    const scopes = await access.getPlatformScopes('1');
    assert.ok(scopes.items.length >= 1, `scope seeds for platform=1: need ≥1, got ${scopes.items.length}`);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
