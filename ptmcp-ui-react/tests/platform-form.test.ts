import assert from 'node:assert/strict';
import test from 'node:test';
import { platformForm, toPlatformWrite, validatePlatformForm } from '../src/views/admin/platformForm.ts';

const base = { ...platformForm(), code: 'desktop', name: '桌面平台' };
const configured = { ...base, id: '1', tier: 1, proofMode: 'MTLS', proofConfigured: true, version: '1' };

test('platform defaults and enums follow the backend contract, not OAuth proof methods', () => {
  assert.equal(base.catalogMode, 'DISCOVER');
  assert.equal(base.proofMode, 'NONE');
  assert.equal(validatePlatformForm(base), '');
  assert.match(validatePlatformForm({ ...base, catalogMode: 'BLACKLIST' }), /目录/);
  assert.match(validatePlatformForm({ ...base, proofMode: 'PKCE_S256' }), /证明/);
  assert.match(validatePlatformForm({ ...base, tier: '1' }), /Tier1/);
  assert.match(validatePlatformForm({ ...base, tier: '4' }), /层级/);
  assert.match(validatePlatformForm({ ...base, status: 'ENABLED' }), /状态/);
  assert.equal(validatePlatformForm({ ...base, code: 'A_B', name: '甲' }), '');
});

test('a failed replacement must require fresh material instead of silently retaining the old proof', () => {
  const cleared = platformForm(configured);
  assert.equal(validatePlatformForm(cleared, configured), '');
  assert.match(validatePlatformForm(cleared, configured, true), /指纹/);
  assert.equal(validatePlatformForm({ ...cleared, certificateFingerprint: 'ab'.repeat(32) }, configured, true), '');
  assert.match(validatePlatformForm({ ...cleared, tier: '2', proofMode: 'NONE' }, configured, true), /取消/);
  const proxy = { ...configured, proofMode: 'PROXY_SECRET' };
  assert.match(validatePlatformForm(platformForm(proxy), proxy, true), /网络/);
});

test('new MTLS and proxy platforms require complete mode-specific write-only material', () => {
  assert.match(validatePlatformForm({ ...base, tier: '1', proofMode: 'MTLS' }), /指纹/);
  const mtls = { ...base, tier: '1', proofMode: 'MTLS', certificateFingerprint: 'ab'.repeat(32) };
  assert.equal(validatePlatformForm(mtls), '');
  assert.deepEqual(toPlatformWrite(mtls).proofConfig, { certificateFingerprint: 'ab'.repeat(32) });
  const proxy = { ...base, tier: '1', proofMode: 'PROXY_SECRET', proxyNetwork: '192.0.2.0/24', secret: ' synthetic-only ' };
  assert.equal(validatePlatformForm(proxy), '');
  assert.deepEqual(toPlatformWrite(proxy).proofConfig, { proxyNetwork: '192.0.2.0/24', secret: ' synthetic-only ' });
  assert.match(validatePlatformForm({ ...proxy, secret: '' }), /密钥/);
  assert.match(validatePlatformForm({ ...proxy, proxyNetwork: '' }), /网络/);
});

test('editing never populates secrets; blank fields keep only the same configured proof mode', () => {
  const editing = platformForm(configured);
  assert.equal(editing.certificateFingerprint, '');
  assert.equal(editing.secret, '');
  assert.equal(editing.proxyNetwork, '');
  assert.equal(validatePlatformForm(editing, configured), '');
  assert.ok(!Object.hasOwn(toPlatformWrite(editing), 'proofConfig'));
  assert.match(validatePlatformForm({ ...editing, proofMode: 'PROXY_SECRET' }, configured), /网络/);
  assert.match(validatePlatformForm(editing, { ...configured, proofConfigured: false }), /指纹/);
  assert.match(
    validatePlatformForm({ ...editing, proofMode: 'PROXY_SECRET', proxyNetwork: '192.0.2.0/24' }, { ...configured, proofMode: 'PROXY_SECRET' }),
    /密钥/,
  );
});

test('NONE and mode switches never forward stale proof fields', () => {
  const stale = { ...base, certificateFingerprint: 'ab'.repeat(32), proxyNetwork: '192.0.2.0/24', secret: 'synthetic-only' };
  assert.ok(!Object.hasOwn(toPlatformWrite(stale), 'proofConfig'));
  assert.deepEqual(toPlatformWrite({ ...stale, proofMode: 'MTLS' }).proofConfig, { certificateFingerprint: 'ab'.repeat(32) });
  assert.deepEqual(toPlatformWrite({ ...stale, proofMode: 'PROXY_SECRET' }).proofConfig, { proxyNetwork: '192.0.2.0/24', secret: 'synthetic-only' });
});
