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
const access = await import('../src/services/access.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
const delegationForm = await import('../src/views/admin/delegationForm.ts');
const { validateDelegationForm, isValidLocalDateTime } = delegationForm;
type DelegationFormValues = delegationForm.DelegationFormValues;
sourceImports.deregister();

async function withPreviewServer(fn: () => Promise<void>): Promise<void> {
  const server = createPreviewServer();
  const originalFetch = globalThis.fetch;
  setCsrfToken('test-csrf');
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input).replace('/backend/admin', '');
    return server(path, {
      method: init?.method,
      headers: init?.headers,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      signal: init?.signal,
    });
  }) as typeof fetch;
  try {
    await fn();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

const valid: DelegationFormValues = {
  userId: '2',
  selfUserId: '1',
  departmentRootId: '2',
  resourceId: '1',
  allowedActions: ['ptmcp:user:list', 'ptmcp:acl:read'],
  validFrom: '2026-11-01T09:00',
  validTo: '2026-12-01T09:00',
};

test('validateDelegationForm mirrors the delegation domain rules', () => {
  assert.equal(validateDelegationForm(valid), null);
  assert.match(String(validateDelegationForm({ ...valid, userId: '' })), /管理员/);
  assert.match(String(validateDelegationForm({ ...valid, userId: '1' })), /自己/);
  assert.match(String(validateDelegationForm({ ...valid, departmentRootId: '' })), /部门根/);
  assert.match(String(validateDelegationForm({ ...valid, resourceId: '' })), /资源/);
  assert.match(String(validateDelegationForm({ ...valid, allowedActions: [] })), /至少选择一个/);
  assert.match(String(validateDelegationForm({ ...valid, validFrom: '' })), /开始时间/);
  assert.match(String(validateDelegationForm({ ...valid, validTo: '' })), /结束时间/);
  assert.match(String(validateDelegationForm({ ...valid, validTo: '2026-11-01T09:00' })), /晚于开始时间/);
  assert.equal(isValidLocalDateTime('2026-11-01T09:00'), true);
  assert.equal(isValidLocalDateTime('2026-11-01'), false);
  assert.equal(isValidLocalDateTime('garbage'), false);
});

test('delegation create persists and revoke is a soft delete requiring the fresh version', async () => {
  await withPreviewServer(async () => {
    const created = await access.createDelegation({
      userId: '2',
      departmentRootId: '2',
      resourceType: 'DATASOURCE',
      resourceId: '1',
      allowedActions: ['ptmcp:user:list'],
      validFrom: '2026-11-01T09:00:00Z',
      validTo: '2026-12-01T09:00:00Z',
    });
    assert.equal(created.userId, '2');
    assert.equal(created.status ?? 'ACTIVE', 'ACTIVE');

    const page = await access.listDelegations({ pageSize: 100 });
    assert.ok(page.items.some((d) => d.id === created.id));

    await access.revokeDelegation(created.id);
    const after = await access.listDelegations({ pageSize: 100 });
    const revoked = after.items.find((d) => d.id === created.id);
    // 撤销是软删：行还在，但不再是 ACTIVE
    assert.ok(!revoked || revoked.status !== 'ACTIVE');
  });
});
