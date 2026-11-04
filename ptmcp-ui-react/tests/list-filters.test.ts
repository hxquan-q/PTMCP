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
const data = await import('../src/services/data.ts');
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

test('users list filters by department and keyword server-side', async () => {
  await withPreviewServer(async () => {
    const all = await org.listUsers();
    assert.ok(all.length >= 2);
    const depts = await org.listDepartments();
    const target = all[0]!;
    const targetDept = depts.find((d) => d.id === target.deptId);
    assert.ok(targetDept, 'seed users must reference seeded departments');

    const byDept = await org.listUsers({ departmentId: targetDept.id });
    assert.ok(byDept.every((u) => u.deptId === targetDept.id));
    if (new Set(all.map((u) => u.deptId)).size > 1) assert.ok(byDept.length < all.length);

    const byKeyword = await org.listUsers({ keyword: target.username });
    assert.ok(byKeyword.some((u) => u.id === target.id));
  });
});

test('delegations list filters by user/status and pages by cursor', async () => {
  await withPreviewServer(async () => {
    const all = await access.listDelegations({ pageSize: 100 });
    assert.ok(all.items.length > 0);
    const first = all.items[0]!;
    const byUser = await access.listDelegations({ userId: first.userId, pageSize: 100 });
    assert.ok(byUser.items.every((d) => d.userId === first.userId));
    assert.ok(byUser.items.some((d) => d.id === first.id));

    const active = await access.listDelegations({ status: 'ACTIVE', pageSize: 100 });
    assert.ok(active.items.every((d) => (d.status ?? 'ACTIVE') === 'ACTIVE'));

    const paged = await access.listDelegations({ pageSize: 1 });
    if (paged.nextCursor) {
      const next = await access.listDelegations({ pageSize: 1, cursor: paged.nextCursor });
      assert.notEqual(next.items[0]?.id, paged.items[0]?.id);
    }
  });
});

test('metadata list applies keyword server-side within a datasource', async () => {
  await withPreviewServer(async () => {
    const { data: dsPage } = await data.listDatasources({ pageSize: 10 });
    const ds = dsPage.items[0]!;
    const all = await data.listMetadata({ datasourceId: ds.id, pageSize: 100 });
    assert.ok(all.data.items.length > 0);
    const first = all.data.items[0]!;
    const hit = await data.listMetadata({ datasourceId: ds.id, keyword: first.objectName.slice(0, 5), pageSize: 100 });
    assert.ok(
      hit.data.items.some((o) => o.id === first.id),
      'keyword must match objectName server-side',
    );
  });
});

test('datasources list filters by engine/status server-side, not on the fetched page', async () => {
  await withPreviewServer(async () => {
    const { data: all } = await data.listDatasources({ pageSize: 100 });
    assert.ok(all.items.length >= 2, 'seed must contain several datasources');
    const target = all.items[0]!;

    const byEngine = await data.listDatasources({ engine: target.engine, pageSize: 100 });
    assert.ok(byEngine.data.items.length > 0, 'engine filter must return the seed row');
    assert.ok(
      byEngine.data.items.every((row) => row.engine === target.engine),
      '服务端必须只回所选引擎的行',
    );
    assert.ok(byEngine.data.items.length <= all.items.length, '过滤后的集合不能比全量还大');

    const byStatus = await data.listDatasources({ status: target.status, pageSize: 100 });
    assert.ok(byStatus.data.items.length > 0);
    assert.ok(
      byStatus.data.items.every((row) => (row.status ?? '').toUpperCase() === target.status),
      '服务端必须只回所选状态的行',
    );

    // 两个条件同时给：交集，而不是任一命中。
    const both = await data.listDatasources({ engine: target.engine, status: target.status, pageSize: 100 });
    assert.ok(both.data.items.some((row) => row.id === target.id));
    assert.ok(
      both.data.items.every((row) => row.engine === target.engine && (row.status ?? '').toUpperCase() === target.status),
      'engine 与 status 必须按交集生效',
    );
  });
});

test('datasources 列表在服务端还有下一页时给出 nextCursor', async () => {
  await withPreviewServer(async () => {
    const first = await data.listDatasources({ pageSize: 1 });
    assert.equal(first.data.items.length, 1);
    assert.ok(first.data.nextCursor, '还有更多行时必须给 nextCursor，前端据此提示“仅显示前 N 条”');

    const last = await data.listDatasources({ pageSize: 100 });
    assert.equal(last.data.nextCursor, null, '拉全量后不应再有 nextCursor');
  });
});
