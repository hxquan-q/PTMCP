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
const { validatePasswordForm, passwordError } = await import('../src/views/me/passwordForm.ts');
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

test('passwordError enforces length window and UTF-8 byte budget separately', () => {
  assert.equal(passwordError('a'.repeat(12)), null);
  assert.equal(passwordError('a'.repeat(11)), '密码需12～64个字符，且 UTF-8 不超过72字节。');
  assert.equal(passwordError('a'.repeat(65)), '密码需12～64个字符，且 UTF-8 不超过72字节。');
  // 24 个三字节汉字 = 24 字符（窗口内）但 72 字节恰好达标；25 个则 75 字节超限
  assert.equal(passwordError('密'.repeat(24)), null);
  assert.equal(passwordError('密'.repeat(25)), '密码需12～64个字符，且 UTF-8 不超过72字节。');
});

test('validatePasswordForm requires old password and matching confirmation', () => {
  assert.equal(validatePasswordForm({ oldPassword: 'o', newPassword: 'a'.repeat(12), confirmPassword: 'a'.repeat(12) }), null);
  assert.match(String(validatePasswordForm({ oldPassword: '', newPassword: 'a'.repeat(12), confirmPassword: 'a'.repeat(12) })), /当前密码/);
  assert.match(String(validatePasswordForm({ oldPassword: 'o', newPassword: 'short', confirmPassword: 'short' })), /12～64/);
  assert.match(String(validatePasswordForm({ oldPassword: 'o', newPassword: 'a'.repeat(12), confirmPassword: 'b'.repeat(12) })), /不一致/);
});

test('changePassword invalidates the session server-side (all sessions fall)', async () => {
  await withPreviewServer(async () => {
    await session.login('preview-admin', 'old-secret');
    const info = await session.getSession();
    assert.equal(info.username, 'preview-admin');

    await session.changePassword('old-secret', 'new-secret-123');
    await assert.rejects(
      () => session.getSession(),
      (error: unknown) => (error as { status?: number }).status === 401,
      'after a password change every session must be rejected until re-login',
    );

    await session.login('preview-admin', 'new-secret-123');
    const again = await session.getSession();
    assert.equal(again.username, 'preview-admin');
  });
});
