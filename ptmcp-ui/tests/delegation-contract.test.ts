import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { createPreviewServer } from '../src/preview/server.ts';

const dataUrl = (code) => `data:text/javascript,${encodeURIComponent(code)}`;

test('delegation preview flow mirrors the real API contract: list, create, revoke, filters', async () => {
  const server = createPreviewServer();
  const call = async (path, method = 'GET', body = undefined, version) => {
    const response = await server(path, {
      method,
      body,
      headers: { ...(version ? { 'If-Match': `"${version}"` } : {}), 'Idempotency-Key': 'preview-key' },
    });
    return { status: response.status, data: await response.json() };
  };

  const page = await call('/api/v1/admin/delegations');
  assert.equal(page.status, 200);
  assert.ok(Array.isArray(page.data.items));
  assert.equal(typeof page.data.items[0].version, 'string');

  const created = await call('/api/v1/admin/delegations', 'POST', {
    userId: '2',
    departmentRootId: '2',
    resourceType: 'DATASOURCE',
    resourceId: '1',
    allowedActions: ['ptmcp:acl:list'],
    validFrom: new Date().toISOString(),
    validTo: new Date(Date.now() + 86400000).toISOString(),
  });
  assert.equal(created.status, 201);
  assert.equal(created.data.status, 'ACTIVE');
  assert.ok(created.data.id);

  const revoked = await call(`/api/v1/admin/delegations/${created.data.id}`, 'DELETE', undefined, created.data.version);
  assert.equal(revoked.status, 200);
  assert.equal(revoked.data.status, 'REVOKED');

  const revokedOnly = await call('/api/v1/admin/delegations?status=REVOKED');
  assert.equal(revokedOnly.status, 200);
  assert.ok(revokedOnly.data.items.some((row) => row.id === created.data.id));
});

test('normal mode: delegation domain reaches the network while unimplemented domains stay gated', async () => {
  const calls = [];
  const httpStub = dataUrl(
    [
      'export class ApiError extends Error {',
      '  constructor(status, code, message) { super(message); this.status = status; this.code = code; }',
      '}',
      'export const request = async (path, options) => {',
      '  const f = globalThis.__ptmcpHttpCalls;',
      '  f.push({ path, method: (options.method ?? "GET").toUpperCase() });',
      '  if (f.offline) throw new (f.OfflineError ?? Error)("network down");',
      '  return { data: { items: [], nextCursor: null }, etag: null, requestId: null };',
      '};',
      'export const setCsrfToken = () => {};',
    ].join('\n'),
  );
  globalThis.__ptmcpHttpCalls = calls;
  globalThis.__ptmcpHttpCalls.offline = true;
  const urls = {
    './http': httpStub,
    './uuid': dataUrl('export const newUuid = () => "fixed-uuid";'),
    './frontendMode': new URL('../src/services/frontendMode.ts', import.meta.url).href,
    './mePortalApi': dataUrl(
      ['export interface AuditSummary { requestId: string }', 'export interface AuditDetail extends AuditSummary {}'].join('\n'),
    ),
  };
  const source = stripTypeScriptTypes(await readFile(new URL('../src/services/controlPlane.ts', import.meta.url), 'utf8'), {
    mode: 'transform',
  }).replace(/from\s+(['"])([^'"]+)\1/g, (statement, _quote, specifier) => (urls[specifier] ? `from ${JSON.stringify(urls[specifier])}` : statement));
  const { controlPlane } = await import(dataUrl(source));

  // Delegations are open in normal mode: the request goes out and fails as a network error,
  // never as the "not integrated" gate.
  await assert.rejects(
    () => controlPlane.delegations.list({}),
    (error) => !(error instanceof Error) || !error.message.includes('尚未接入'),
  );
  const listCall = calls.find((call) => call.path === '/api/v1/admin/delegations');
  assert.equal(listCall?.method, 'GET');

  await assert.rejects(
    () => controlPlane.delegations.revoke({ id: '3', version: '2' }),
    (error) => !(error instanceof Error) || !error.message.includes('尚未接入'),
  );
  const revokeCall = calls.find((call) => call.path === '/api/v1/admin/delegations/3');
  assert.equal(revokeCall?.method, 'DELETE');

  // The implemented effective endpoint also reaches the network in normal mode.
  await assert.rejects(
    () => controlPlane.effective({ userId: '1', platformId: '1' }),
    (error) => !(error instanceof Error) || !error.message.includes('尚未接入'),
  );

  // Unimplemented domains keep the explicit gate in normal mode.
  await assert.rejects(() => controlPlane.platforms.list({}), /尚未接入/);
});
