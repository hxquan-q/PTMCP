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
const { ApiError, errorText, parseApiJson, queryString, request, setCsrfToken } = await import('../src/services/http.ts');
sourceImports.deregister();

function withFetch(impl: typeof fetch, fn: () => Promise<void>): Promise<void> {
  const original = globalThis.fetch;
  globalThis.fetch = impl as typeof fetch;
  return fn().finally(() => {
    globalThis.fetch = original;
  });
}

test('preserves unsafe integer identifiers as strings before JSON parsing', () => {
  const value = parseApiJson('{"id":9223372036854775807,"nested":{"version":18446744073709551615}}') as { id: string; nested: { version: string } };
  assert.equal(value.id, '9223372036854775807');
  assert.equal(value.nested.version, '18446744073709551615');
});

test('normal integers and floats stay numeric, string contents stay untouched', () => {
  const value = parseApiJson('{"id":12,"score":3.5,"note":"id=9223372036854775807 done","neg":-42}') as Record<string, unknown>;
  assert.equal(value.id, 12);
  assert.equal(value.score, 3.5);
  assert.equal(value.note, 'id=9223372036854775807 done');
  assert.equal(value.neg, -42);
});

test('adds CSRF to writes and does not retry failed writes', async () => {
  let calls = 0;
  let csrf = '';
  setCsrfToken('test-csrf');
  await withFetch(
    async (_input, init) => {
      calls += 1;
      csrf = new Headers(init?.headers).get('X-CSRF-Token') ?? '';
      return new Response(JSON.stringify({ code: 'TEMPORARY_FAILURE' }), { status: 503, headers: { 'Content-Type': 'application/json' } });
    },
    async () => {
      await assert.rejects(
        () => request('/api/v1/admin/test', { method: 'POST', body: { value: 'x' } }),
        (error: unknown) => error instanceof ApiError && error.status === 503,
      );
    },
  );
  assert.equal(calls, 1);
  assert.equal(csrf, 'test-csrf');
});

test('bootstraps CSRF once before a write when no token is in memory', async () => {
  const calls: string[] = [];
  setCsrfToken(null);
  await withFetch(
    async (input, init) => {
      const path = String(input);
      const method = init?.method ?? 'GET';
      calls.push(`${method} ${path}`);
      if (path.endsWith('/api/v1/session/csrf')) {
        return new Response(JSON.stringify({ csrfToken: 'boot-csrf' }), { status: 200, headers: { 'Content-Type': 'application/json' } });
      }
      assert.equal(new Headers(init?.headers).get('X-CSRF-Token'), 'boot-csrf');
      return new Response(JSON.stringify({ ok: true }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    },
    async () => {
      const first = await request('/api/v1/admin/things', { method: 'POST', body: {} });
      assert.deepEqual(first.data, { ok: true });
      const second = await request('/api/v1/admin/things', { method: 'PUT', body: {} });
      assert.deepEqual(second.data, { ok: true });
    },
  );
  assert.deepEqual(calls, [
    'GET /backend/admin/api/v1/session/csrf',
    'POST /backend/admin/api/v1/admin/things',
    'PUT /backend/admin/api/v1/admin/things',
  ]);
});

test('unwraps the standard envelope and falls back to the legacy nested shape', async () => {
  await withFetch(
    async () =>
      new Response(JSON.stringify({ code: 200, data: { value: 7 } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json', ETag: 'W/"v3"', 'X-Request-Id': 'hdr-1' },
      }),
    async () => {
      const result = await request<{ value: number }>('/api/v1/admin/things/7');
      assert.deepEqual(result.data, { value: 7 });
      assert.equal(result.etag, 'W/"v3"');
      assert.equal(result.requestId, 'hdr-1');
    },
  );
  await withFetch(
    async () => new Response(JSON.stringify({ value: 9 }), { status: 200, headers: { 'Content-Type': 'application/json' } }),
    async () => {
      const raw = await request<{ value: number }>('/api/v1/admin/things/9');
      assert.deepEqual(raw.data, { value: 9 });
    },
  );
});

test('maps transport failures to typed ApiError with actionable messages', async () => {
  setCsrfToken('csrf');
  await withFetch(
    async () => new Response(JSON.stringify({ code: 'EDIT_CONFLICT' }), { status: 412, headers: { 'Content-Type': 'application/json' } }),
    async () => {
      await assert.rejects(
        () => request('/api/v1/admin/things/7', { method: 'PUT', body: {} }),
        (error: unknown) =>
          error instanceof ApiError && error.status === 412 && error.code === 'EDIT_CONFLICT' && error.message === '数据已被他人修改，请刷新后重试。',
      );
    },
  );
  await withFetch(
    async () => new Response(JSON.stringify({}), { status: 409, headers: { 'Content-Type': 'application/json' } }),
    async () => {
      await assert.rejects(
        () => request('/api/v1/admin/things/7', { method: 'PUT', body: {} }),
        (error: unknown) => error instanceof ApiError && error.message === '数据已被他人修改，请刷新后重试。',
      );
    },
  );
  await withFetch(
    async () => new Response('not json', { status: 200, headers: { 'Content-Type': 'application/json' } }),
    async () => {
      await assert.rejects(
        () => request('/api/v1/admin/things/7'),
        (error: unknown) => error instanceof ApiError && error.code === 'INVALID_RESPONSE',
      );
    },
  );
});

test('network failure becomes a typed ApiError and aborts stay AbortError', async () => {
  setCsrfToken('csrf');
  await withFetch(
    async () => {
      throw new TypeError('fetch failed');
    },
    async () => {
      await assert.rejects(
        () => request('/api/v1/admin/things'),
        (error: unknown) => error instanceof ApiError && error.code === 'NETWORK_ERROR' && errorText(error).includes('无法连接服务'),
      );
    },
  );
  const abort = new AbortController();
  abort.abort();
  assert.equal(errorText(new DOMException('Aborted', 'AbortError')), '请求已取消。');
});

test('queryString drops empty values and keeps meaningful ones', () => {
  assert.equal(queryString({ keyword: '', status: null, page: 2, draft: false }), '?page=2&draft=false');
  assert.equal(queryString({}), '');
});

test('rejects non-REST paths before touching the network', async () => {
  await assert.rejects(() => request('https://evil.example/api'), /same-origin/);
});
