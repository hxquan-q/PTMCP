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
const session = await import('../src/services/session.ts');
const http = await import('../src/services/http.ts');
const data = await import('../src/services/data.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

async function withPreviewServer(fn: () => Promise<void>): Promise<void> {
  const server = createPreviewServer();
  const originalFetch = globalThis.fetch;
  http.setCsrfToken('test-csrf');
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

test('session lifecycle: logout gates admin calls with 401, login restores access', async () => {
  await withPreviewServer(async () => {
    // 初始已登录：探测与列表可用
    const info = await session.getSession();
    assert.equal(info.username, 'preview-admin');
    assert.ok(Array.isArray(info.permissions) && info.permissions.length > 0);
    await data.listDatasources({ pageSize: 5 });

    // 退出后：管理面 401，探测也 401（App 据此切登录页）
    await session.logout();
    await assert.rejects(
      () => data.listDatasources({ pageSize: 5 }),
      (error: unknown) => (error as { status?: number }).status === 401,
    );
    await assert.rejects(
      () => session.getSession(),
      (error: unknown) => (error as { status?: number }).status === 401,
    );

    // 重新登录恢复访问
    await session.login('preview-admin', 'any-credential-in-preview');
    const restored = await session.getSession();
    assert.equal(restored.username, 'preview-admin');
    await data.listDatasources({ pageSize: 5 });
  });
});

test('onSessionExpired fires on 401 but not for the login probe itself', async () => {
  await withPreviewServer(async () => {
    const fired: string[] = [];
    const off = http.onSessionExpired(() => fired.push('expired'));
    await session.logout();
    await data.listDatasources({ pageSize: 5 }).catch(() => undefined);
    assert.equal(fired.length, 1, 'admin 401 must notify exactly once');
    // 登录端点自身的 401/失败不应触发会话过期广播
    await http.request('/api/v1/session/login', { method: 'POST', body: { username: 'x', password: 'y' }, csrf: false }).catch(() => undefined);
    assert.equal(fired.length, 1);
    off();
    await session.login('preview-admin', 'any');
    await data.listDatasources({ pageSize: 5 });
  });
});
