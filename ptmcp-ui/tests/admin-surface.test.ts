import assert from 'node:assert/strict';
import test from 'node:test';
import { validateAllowedScopes, validateDateRange, validateRedirectUris, validateWindow } from '../src/views/admin/integrations/adminSurface.ts';

test('accepts exact secure redirect URIs and rejects wildcard or insecure web callbacks', () => {
  assert.equal(validateRedirectUris('https://agent.example/callback\nworkbuddy://oauth/callback'), null);
  assert.match(validateRedirectUris('https://*.example/callback') ?? '', /精确/);
  assert.match(validateRedirectUris('http://agent.example/callback') ?? '', /安全规则/);
});

test('rejects unsafe schemes, embedded credentials, fragments and unapproved custom callbacks', () => {
  for (const uri of [
    'file://local/callback',
    'javascript://callback',
    'https://user:password@agent.example/callback',
    'https://agent.example/callback#token',
    'arbitrary://callback',
    'https://agent.example/callback?next=https://other.example',
  ])
    assert.notEqual(validateRedirectUris(uri), null, uri);
  assert.equal(validateRedirectUris('http://127.0.0.1:35125/callback'), null);
  assert.equal(validateRedirectUris('http://[::1]:35125/callback'), null);
  assert.notEqual(validateRedirectUris('http://localhost/callback'), null);
  assert.notEqual(validateRedirectUris('https:agent.example/callback'), null);
  assert.notEqual(validateRedirectUris('https://agent.example/call\tback'), null);
  assert.equal(validateRedirectUris('https://agent.example/callback?value=a,b'), null);
});

test('enforces a closed valid window and the audit 31-day boundary', () => {
  assert.equal(validateWindow('2026-10-01T00:00:00Z', '2026-10-02T00:00:00Z'), null);
  assert.match(validateWindow('2026-10-02T00:00:00Z', '2026-10-01T00:00:00Z') ?? '', /晚于/);
  assert.equal(validateDateRange('2026-10-01T00:00:00Z', '2026-11-01T00:00:00Z'), null);
  assert.match(validateDateRange('2026-10-01T00:00:00Z', '2026-11-02T00:00:00Z') ?? '', /31天/);
});

test('allows only the approved client scope vocabulary', () => {
  assert.equal(validateAllowedScopes(['mcp:catalog', 'mcp:query']), null);
  assert.match(validateAllowedScopes([]) ?? '', /至少选择/);
  assert.match(validateAllowedScopes(['mcp:admin']), /白名单/);
});

test('委派提交失败区分 DUPLICATE_GRANT 与通用冲突，未识别错误透传', async () => {
  const { delegationSubmitError } = await import('../src/views/admin/integrations/adminSurface.ts');
  const fallback = (error: unknown): string => `fallback:${String((error as { message?: string }).message ?? error)}`;
  const duplicate = delegationSubmitError({ code: 'DUPLICATE_GRANT', status: 409, message: '服务端资源已变化' }, fallback);
  assert.match(duplicate, /DUPLICATE_GRANT/);
  assert.match(duplicate, /撤销旧委派/);
  assert.doesNotMatch(duplicate, /合并/);
  assert.equal(delegationSubmitError({ code: 'VERSION_CONFLICT', status: 409, message: '服务端资源已变化' }, fallback), 'fallback:服务端资源已变化');
  assert.equal(delegationSubmitError(new Error('x'), fallback), 'fallback:x');
});

test('跨范围共享角色写门控：委派管理员持功能码仍只读并给出解释', async () => {
  const { roleWriteGate } = await import('../src/views/admin/integrations/adminSurface.ts');
  const delegated = { can: (c) => c === 'ptmcp:role:update' || c === 'ptmcp:role:list', platformAdmin: false };
  const g1 = roleWriteGate(delegated);
  assert.equal(g1.canWrite, false);
  assert.match(g1.hint, /仅平台管理员可修改/);
  const platform = { can: () => true, platformAdmin: true };
  const g2 = roleWriteGate(platform);
  assert.equal(g2.canWrite, true);
  assert.equal(g2.hint, null);
  const noCatalog = { can: () => false, platformAdmin: false };
  assert.equal(roleWriteGate(noCatalog).hint, null);
});
