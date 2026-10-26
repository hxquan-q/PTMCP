import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

const sourceImports = registerHooks({
  resolve(specifier, context, nextResolve) {
    const extensionlessSource = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionlessSource ? `${specifier}.ts` : specifier, context);
  },
});
const { ApiError, onSessionExpired, parseApiJson, request, setCsrfToken } = await import('../src/services/http.ts');
sourceImports.deregister();

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
  await assert.rejects(
    () => request('/api/v1/admin/test', { method: 'POST', body: { value: 'x' } }),
    (error: unknown) => error instanceof ApiError && error.status === 503,
  );
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
  const unsubscribe = onSessionExpired(() => {
    notified = true;
  });
  globalThis.fetch = async () => {
    count += 1;
    return new Response(JSON.stringify({ code: 'SESSION_EXPIRED', codeNumber: 401001 }), { status: 401 });
  };
  try {
    await assert.rejects(
      () => request('/api/v1/session/me'),
      (error: unknown) => error instanceof ApiError && error.status === 401 && error.code === 'SESSION_EXPIRED',
    );
    assert.equal(notified, true);
    assert.equal(count, 1);
  } finally {
    unsubscribe();
    globalThis.fetch = originalFetch;
  }
});

test('CSRF_INVALID clears stale token without replay; next explicit submit refreshes it', async () => {
  const originalFetch = globalThis.fetch;
  const calls: { path: string; token: string | null }[] = [];
  setCsrfToken('stale-csrf');
  globalThis.fetch = async (input, init) => {
    const path = String(input);
    calls.push({ path, token: new Headers(init?.headers).get('X-CSRF-Token') });
    if (path.endsWith('/session/csrf')) return new Response(JSON.stringify({ csrfToken: 'fresh-csrf' }), { status: 200 });
    if (calls.length === 1) return new Response(JSON.stringify({ code: 'CSRF_INVALID' }), { status: 403 });
    return new Response(JSON.stringify({ saved: true }), { status: 200 });
  };
  try {
    await assert.rejects(
      () => request('/api/v1/admin/test', { method: 'POST', body: { value: 'x' } }),
      (error: unknown) => error instanceof ApiError && error.code === 'CSRF_INVALID',
    );
    assert.equal(calls.length, 1);
    assert.equal(calls[0]?.token, 'stale-csrf');
    // A new user-initiated call is a separate intent; transport never replays the first call.
    await request('/api/v1/admin/test', { method: 'POST', body: { value: 'x' } });
    assert.deepEqual(
      calls.map((call) => call.path),
      ['/backend/admin/api/v1/admin/test', '/backend/admin/api/v1/session/csrf', '/backend/admin/api/v1/admin/test'],
    );
    assert.equal(calls[2]?.token, 'fresh-csrf');
  } finally {
    setCsrfToken(null);
    globalThis.fetch = originalFetch;
  }
});
