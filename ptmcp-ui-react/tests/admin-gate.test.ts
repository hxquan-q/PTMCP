import assert from 'node:assert/strict';
import test from 'node:test';
import { createAdminGateValue, useAdminGate } from '../src/adminGateContext.ts';

test('createAdminGateValue gates strictly on boolean true', () => {
  assert.equal(createAdminGateValue(true), true);
  assert.equal(createAdminGateValue(false), false);
  // 会话缺字段或非布尔一律拒绝——管理门只有显式 true 才开
  assert.equal(createAdminGateValue(undefined), false);
  assert.equal(createAdminGateValue(null), false);
  assert.equal(createAdminGateValue('true' as unknown as boolean), false);
});

test('useAdminGate is exported for consumers (compile-shape check)', () => {
  assert.equal(typeof useAdminGate, 'function');
});
