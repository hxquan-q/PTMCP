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
const { OPERATION_AGGREGATE_TYPES, operationActionText, operationAggregateText } = await import('../src/services/operationLabels.ts');
const access = await import('../src/services/access.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

test('operation labels map known codes and fall back to raw code for new ones', () => {
  assert.equal(operationActionText('DELEGATION_CREATE'), '创建委派');
  assert.equal(operationActionText('USER_PASSWORD_RESET'), '重置用户密码');
  assert.equal(operationActionText('PASSWORD_CHANGED'), '修改本人密码');
  assert.equal(operationActionText('FUTURE_NEW_ACTION'), 'FUTURE_NEW_ACTION', '未知动作码必须原样透出，不能静默吞掉');
  assert.equal(operationAggregateText('ADMIN_DELEGATION'), '委派');
  assert.equal(operationAggregateText('NEW_AGGREGATE'), 'NEW_AGGREGATE');
  // 筛选下拉取值与已知聚合类型一致且有序
  assert.deepEqual(OPERATION_AGGREGATE_TYPES, ['ADMIN_DELEGATION', 'PLATFORM', 'OAUTH_CLIENT', 'USER', 'DEPARTMENT', 'ROLE']);
});

test('operations endpoint is a real-backend domain: preview rejects so UI shows a controlled empty state', async () => {
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
    await assert.rejects(
      () => access.listOperations({ pageSize: 20 }),
      (error: unknown) => {
        const status = (error as { status?: number }).status ?? 0;
        return status === 501 || status === 404 || status === 503;
      },
      'preview must reject the operations endpoint; the page renders its unavailable state instead of fake rows',
    );
  } finally {
    globalThis.fetch = originalFetch;
  }
});
