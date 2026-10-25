import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError, onSessionExpired, parseApiJson, request, setCsrfToken } from '../src/services/http.ts';

test('preserves unsafe integer identifiers as strings before JSON parsing', () => {
  const value = parseApiJson('{"id":9223372036854775807,"nested":{"version":18446744073709551615}}') as { id: string; nested: { version: string } };
  assert.equal(value.id, '9223372036854775807');
  assert.equal(value.nested.version, '18446744073709551615');
});

test('adds CSRF to writes and does not retry failed writes', async () => {
  const originalFetch = globalThis.fetch;
  let calls = 0;
  let csrf = '';
  globalThis.fetch = async (_input, init) => {
    calls += 1;
    csrf = new Headers(init?.headers).get('X-CSRF-Token') ?? '';
    return new Response(JSON.stringify({ code: 'TEMPORARY_FAILURE' }), { status: 503, headers: { 'Content-Type': 'application/json' } });
  };
  setCsrfToken('test-csrf');
  await assert.rejects(() => request('/api/v1/admin/test', { method: 'POST', body: { value: 'x' } }), (error: unknown) => error instanceof ApiError && error.status === 503);
  assert.equal(calls, 1);
  assert.equal(csrf, 'test-csrf');
  globalThis.fetch = originalFetch;
});

test('bootstraps CSRF once before a write when no token is in memory', async () => {
  const originalFetch = globalThis.fetch;
  const calls: string[] = [];
  setCsrfToken(null);
  globalThis.fetch = async (input, init) => {
    calls.push(String(input));
    if (String(input).endsWith('/session/csrf')) return new Response(JSON.stringify({ csrfToken: 'new-csrf' }), { status: 200 });
    assert.equal(new Headers(init?.headers).get('X-CSRF-Token'), 'new-csrf');
    return new Response(JSON.stringify({ saved: true }), { status: 200 });
  };
  try {
    await request('/api/v1/admin/test', { method: 'PUT', body: { value: 'x' } });
    assert.deepEqual(calls, ['/backend/admin/api/v1/session/csrf', '/backend/admin/api/v1/admin/test']);
  } finally {
    setCsrfToken(null);
    globalThis.fetch = originalFetch;
  }
});

test('401 notifies all session consumers and never replays the request', async () => {
  const originalFetch = globalThis.fetch;
  let count = 0;
  let notified = false;
  const unsubscribe = onSessionExpired(() => { notified = true; });
  globalThis.fetch = async () => {
    count += 1;
    return new Response(JSON.stringify({ code: 'SESSION_EXPIRED', codeNumber: 401001 }), { status: 401 });
  };
  try {
    await assert.rejects(() => request('/api/v1/session/me'), (error: unknown) => error instanceof ApiError && error.status === 401 && error.code === 'SESSION_EXPIRED');
    assert.equal(notified, true);
    assert.equal(count, 1);
  } finally {
    unsubscribe();
    globalThis.fetch = originalFetch;
  }
});
