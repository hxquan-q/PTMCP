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
const { SENSITIVE_ACTIONS } = await import('../src/permissions.ts');
const session = await import('../src/services/session.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

test('every SENSITIVE_ACTIONS code exists in the preview admin permission catalogue', async () => {
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
    const info = await session.getSession();
    assert.ok(info.permissions.length > 0, 'admin session must carry a permission catalogue');
    const permissionSet = new Set(info.permissions);
    const missing: string[] = [];
    for (const [action, code] of Object.entries(SENSITIVE_ACTIONS)) {
      if (!permissionSet.has(code)) missing.push(`${action} → ${code}`);
    }
    assert.deepEqual(missing, [], 'every gated UI action must reference a real backend permission code; drift means silent lockout or silent bypass');
  } finally {
    globalThis.fetch = originalFetch;
  }
});
