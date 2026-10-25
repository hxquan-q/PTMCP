import assert from 'node:assert/strict';
import test from 'node:test';
import { ApiError, parseApiJson, request, setCsrfToken } from '../src/services/http.ts';

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
