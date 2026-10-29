import assert from 'node:assert/strict';
import test from 'node:test';
import { createPreviewServer } from '../src/preview/server.ts';

test('preview is isolated, versioned and never returns a persisted secret', async () => {
  const server = createPreviewServer();
  const read = async (path: string, method = 'GET', body?: unknown, version?: string) => {
    const response = await server(path, { method, body, headers: version ? { 'If-Match': `"${version}"` } : {} });
    return { status: response.status, data: await response.json() };
  };
  const created = await read('/api/v1/admin/platforms', 'POST', { code: 'preview', name: '合成平台', tier: 3, status: 'DISABLED' });
  assert.equal(created.status, 201);
  const id = created.data.id;
  assert.equal((await read(`/api/v1/admin/platforms/${id}`, 'PUT', { name: 'changed' }, '0')).status, 412);
  const updated = await read(`/api/v1/admin/platforms/${id}`, 'PUT', { name: 'changed' }, created.data.version);
  assert.equal(updated.data.name, 'changed');
  const client = await read(`/api/v1/admin/platforms/${id}/clients`, 'POST', {
    name: '演示客户端',
    type: 'CONFIDENTIAL',
    authMethods: ['client_secret_basic'],
  });
  assert.ok(client.data.clientSecret);
  const detail = await read(`/api/v1/admin/clients/${client.data.client.id}`);
  assert.equal(detail.data.clientSecret, undefined);
  assert.equal((await read('/api/v1/admin/not-a-real-endpoint', 'POST')).status, 501);
  const otherServer = createPreviewServer();
  assert.equal((await otherServer(`/api/v1/admin/platforms/${id}`, {})).status, 404);
});

test('preview respects abort signals on in-flight requests', async () => {
  const server = createPreviewServer();
  const abort = new AbortController();
  abort.abort();
  await assert.rejects(server('/api/v1/admin/audit', { signal: abort.signal }), { name: 'AbortError' });
});

test('datasource catalogue covers engines, statuses, sync outcomes and timestamps', async () => {
  const server = createPreviewServer();
  const page = await (await server('/api/v1/admin/datasources', {})).json();
  const rows = page.items as Array<Record<string, unknown>>;
  assert.ok(rows.length >= 6, 'catalogue must keep its demo variety');
  const engines = new Set(rows.map((row) => row.engine));
  for (const engine of ['MYSQL', 'POSTGRESQL', 'CLICKHOUSE', 'SQLITE']) assert.ok(engines.has(engine), `${engine} seed missing`);
  const statuses = new Set(rows.map((row) => row.status));
  for (const status of ['ACTIVE', 'DRAFT', 'DISABLED']) assert.ok(statuses.has(status), `${status} seed missing`);
  const healths = new Set(rows.map((row) => row.health));
  assert.ok(healths.has('DEGRADED'), 'DEGRADED seed missing');
  assert.ok(healths.has('UNKNOWN'), 'UNKNOWN seed missing');
  assert.ok(
    rows.some((row) => row.lastSyncResult === 'FAILED'),
    'failed-sync seed missing',
  );
  assert.ok(
    rows.some((row) => row.lastSyncResult === 'SUCCEEDED'),
    'succeeded-sync seed missing',
  );
  for (const row of rows) {
    assert.match(String(row.createdAt), /^\d{4}-\d{2}-\d{2}T/, 'createdAt missing');
    assert.match(String(row.updatedAt), /^\d{4}-\d{2}-\d{2}T/, 'updatedAt missing');
  }
});
