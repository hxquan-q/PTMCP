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
