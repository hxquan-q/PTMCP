import assert from 'node:assert/strict';
import test from 'node:test';
import { canPerform, canPerformAll, SENSITIVE_ACTIONS, type SensitiveAction } from '../src/permissions.ts';

test('canPerform denies without session or permissions array', () => {
  assert.equal(canPerform(null, 'auditDecrypt'), false);
  assert.equal(canPerform(undefined, 'auditDecrypt'), false);
  assert.equal(canPerform([], 'auditDecrypt'), false);
  // 非 Array 也拒绝（防伪造）
  assert.equal(canPerform('ptmcp:audit:decrypt' as unknown as string[], 'auditDecrypt'), false);
});

test('canPerform grants only the exact permission code for the action', () => {
  const perms = ['ptmcp:audit:decrypt', 'ptmcp:datasource:create'];
  assert.equal(canPerform(perms, 'auditDecrypt'), true);
  assert.equal(canPerform(perms, 'datasourceCreate'), true);
  assert.equal(canPerform(perms, 'datasourceDelete'), false);
  assert.equal(canPerform(perms, 'userCreate'), false);
  assert.equal(canPerform(perms, 'aclGrant'), false);
});

test('SENSITIVE_ACTIONS covers all write domains; codes align with backend permission naming', () => {
  const actions = Object.keys(SENSITIVE_ACTIONS) as SensitiveAction[];
  assert.ok(actions.length >= 11);
  for (const action of actions) {
    const code = SENSITIVE_ACTIONS[action];
    assert.match(code, /^ptmcp:[a-z]+:[a-z-]+$/, `${action} code must follow ptmcp:domain:action pattern`);
  }
  // 与 Vue session.can 的关键调用点对齐
  assert.equal(SENSITIVE_ACTIONS.auditDecrypt, 'ptmcp:audit:decrypt');
  assert.equal(SENSITIVE_ACTIONS.datasourceSync, 'ptmcp:datasource:sync');
});

test('canPerformAll returns a complete boolean map', () => {
  const perms = ['ptmcp:audit:decrypt'];
  const map = canPerformAll(perms);
  assert.equal(map.auditDecrypt, true);
  assert.equal(map.datasourceCreate, false);
  assert.equal(map.delegationCreate, false);
  const actions = Object.keys(SENSITIVE_ACTIONS) as SensitiveAction[];
  for (const a of actions) assert.ok(typeof map[a] === 'boolean');
  // null → 全 false
  const empty = canPerformAll(null);
  for (const a of actions) assert.equal(empty[a], false);
});

test('datasourceTest is a distinct permission from sync; CRUD actions map to unique codes', () => {
  const codes = Object.values(SENSITIVE_ACTIONS);
  assert.equal(new Set(codes).size, codes.length, 'every action must map to a unique permission code');
  assert.notEqual(SENSITIVE_ACTIONS.datasourceTest, SENSITIVE_ACTIONS.datasourceSync);
  assert.equal(SENSITIVE_ACTIONS.datasourceTest, 'ptmcp:datasource:test');
  // 门控的写操作覆盖所有破坏性域
  for (const domain of ['datasource', 'user', 'role', 'delegation', 'acl', 'audit']) {
    assert.ok(
      codes.some((c) => c.startsWith(`ptmcp:${domain}:`)),
      `${domain} domain must be covered`,
    );
  }
});
