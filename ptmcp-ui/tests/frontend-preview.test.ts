import assert from 'node:assert/strict';
import test from 'node:test';
import { createPreviewServer } from '../src/preview/server.ts';

test('frontend preview is isolated, versioned and never returns a persisted secret', async () => {
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
    authMethods: ['client_secret_basic']
  });
  assert.ok(client.data.clientSecret);
  const detail = await read(`/api/v1/admin/clients/${client.data.client.id}`);
  assert.equal(detail.data.clientSecret, undefined);
  assert.equal((await read('/api/v1/admin/not-a-real-endpoint', 'POST')).status, 501);
  const otherServer = createPreviewServer();
  assert.equal((await otherServer(`/api/v1/admin/platforms/${id}`, {})).status, 404);
});

test('preview respects abort and keeps masked audit separate from temporary plaintext', async () => {
  const server = createPreviewServer();
  const abort = new AbortController();
  abort.abort();
  await assert.rejects(server('/api/v1/admin/audit', { signal: abort.signal }), { name: 'AbortError' });
  const page = await (await server('/api/v1/admin/audit', {})).json();
  const id = page.items[0].requestId;
  const detail = await (await server(`/api/v1/admin/audit/${id}`, {})).json();
  assert.equal(detail.sqlRaw, undefined);
  const denied = await server(`/api/v1/admin/audit/${id}/decrypt`, { method: 'POST', body: { reason: 'short', fields: ['sqlRaw'] } });
  assert.equal(denied.status, 400);
  const revealed = await (
    await server(`/api/v1/admin/audit/${id}/decrypt`, { method: 'POST', body: { reason: '前端合成数据交互验证说明', fields: ['sqlRaw'] } })
  ).json();
  assert.match(revealed.sqlRaw, /synthetic/);
  assert.ok(revealed.expiresAt);
});

test('preview job and dictionary writes follow the actual frontend DTO and ETag contracts', async () => {
  const server = createPreviewServer();
  const job = await (await server('/api/v1/admin/datasources/1/test', { method: 'POST' })).json();
  assert.equal(job.datasourceId, '1');
  assert.equal(job.type, 'TEST');
  assert.equal(job.state, 'SUCCEEDED');
  assert.equal(job.snapshotId, null);
  const response = await server('/api/v1/admin/metadata/objects/1/dictionary', {
    method: 'PUT',
    body: { title: '合成新标题', description: '合成说明', columns: [] },
    headers: { 'If-Match': '"1"' }
  });
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('ETag'), '"2"');
  const detail = await response.json();
  assert.equal(detail.dictionary.title, '合成新标题');
  assert.equal(detail.dictionary.version, '2');
  assert.equal(
    (await server('/api/v1/admin/metadata/objects/1/dictionary', { method: 'PUT', body: { title: 'overwritten' }, headers: { 'If-Match': '"1"' } }))
      .status,
    412
  );
});
