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
    'https://agent.example/callback?next=https://other.example'
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
