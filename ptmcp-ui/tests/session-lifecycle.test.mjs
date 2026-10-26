import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';
import { createPinia, setActivePinia } from 'pinia';
import { deferred } from './helpers/mount-access-workspace.mjs';

const sourceImports = registerHooks({
  resolve(specifier, context, nextResolve) {
    const extensionless = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionless ? `${specifier}.ts` : specifier, context);
  }
});
const { useSessionStore } = await import('../src/stores/session.ts');
const { request, refreshCsrf } = await import('../src/services/http.ts');
sourceImports.deregister();

const user = (id) => ({
  userId: id,
  username: id,
  displayName: id,
  department: null,
  roles: [],
  permissions: ['ptmcp:datasource:list'],
  managedScopes: [],
  expiresAt: '2099-01-01T00:00:00Z'
});
const response = (body, status = 200) => new Response(JSON.stringify(body), { status });
async function fixture(run) {
  const priorFetch = globalThis.fetch;
  const priorWindow = globalThis.window;
  globalThis.window = new EventTarget();
  setActivePinia(createPinia());
  const session = useSessionStore();
  session.clear();
  try {
    await run(session);
  } finally {
    session.clear();
    globalThis.fetch = priorFetch;
    globalThis.window = priorWindow;
  }
}
const settle = (promise) =>
  promise.then(
    () => undefined,
    () => undefined
  );

test('a late session read cannot restore identity or permissions after clear', () =>
  fixture(async (session) => {
    const pending = deferred();
    globalThis.fetch = () => pending.promise;
    const refreshing = settle(session.refresh());
    session.clear();
    pending.resolve(response(user('alice')));
    await refreshing;
    assert.equal(session.user, null);
    assert.equal(session.isAuthenticated, false);
    assert.equal(session.can('ptmcp:datasource:list'), false);
  }));

test('an old 401 cannot clear a newer authenticated identity', () =>
  fixture(async (session) => {
    const pending = deferred();
    globalThis.fetch = () => pending.promise;
    const old = settle(request('/api/v1/admin/old-context'));
    session.clear();
    globalThis.fetch = async () => response(user('bob'));
    await session.refresh();
    pending.resolve(response({ code: 'SESSION_EXPIRED' }, 401));
    await old;
    assert.equal(session.user?.username, 'bob');
  }));

test('invalidating a pending login stops its remaining session bootstrap', () =>
  fixture(async (session) => {
    const pending = deferred();
    const calls = [];
    globalThis.fetch = async (input) => {
      calls.push(String(input));
      if (String(input).endsWith('/csrf')) return response({ csrfToken: 'synthetic-csrf' });
      if (String(input).endsWith('/login')) return pending.promise;
      return response(user('alice'));
    };
    const login = settle(session.login('alice', 'synthetic-password'));
    await new Promise((resolve) => setImmediate(resolve));
    session.clear();
    pending.resolve(response({ loggedIn: true }));
    await login;
    assert.equal(session.user, null);
    assert.equal(
      calls.some((path) => path.endsWith('/me')),
      false
    );
  }));

test('the latest session refresh wins when reads complete out of order', () =>
  fixture(async (session) => {
    const pending = deferred();
    globalThis.fetch = () => pending.promise;
    const old = settle(session.refresh());
    globalThis.fetch = async () => response(user('new-profile'));
    await session.refresh();
    pending.resolve(response(user('old-profile')));
    await old;
    assert.equal(session.user?.username, 'new-profile');
  }));

test('a late CSRF bootstrap neither replaces the new token nor sends an old write', () =>
  fixture(async (session) => {
    const pending = deferred();
    const writes = [];
    globalThis.fetch = (input) => {
      if (String(input).endsWith('/csrf')) return pending.promise;
      writes.push(String(input));
      return Promise.resolve(response({ saved: true }));
    };
    const oldWrite = settle(request('/api/v1/admin/old-write', { method: 'POST' }));
    session.clear();
    globalThis.fetch = async (input, init) => {
      if (String(input).endsWith('/csrf')) return response({ csrfToken: 'new-context-csrf' });
      writes.push(String(input));
      assert.equal(new Headers(init.headers).get('X-CSRF-Token'), 'new-context-csrf');
      return response({ saved: true });
    };
    const bootstrap = refreshCsrf();
    pending.resolve(response({ csrfToken: 'old-context-csrf' }));
    await bootstrap;
    await oldWrite;
    await request('/api/v1/admin/new-write', { method: 'POST' });
    assert.deepEqual(writes, ['/backend/admin/api/v1/admin/new-write']);
  }));
