import assert from 'node:assert/strict';
import test from 'node:test';
import { OPERATION_AGGREGATE_TYPES, operationActionText, operationAggregateText } from '../src/services/operationLabels.ts';

// 后端写入 admin_operation_log 的全部动作码（源码字面量清单）；新增动作必须同步补label，
// 否则这里会失败——防止管理台显示裸码的回归。
const BACKEND_ACTIONS = [
  'DELEGATION_CREATE',
  'DELEGATION_REVOKE',
  'PLATFORM_CREATE',
  'PLATFORM_UPDATE',
  'PLATFORM_DELETE',
  'PLATFORM_SCOPE_SAVE',
  'CLIENT_CREATE',
  'CLIENT_UPDATE',
  'CLIENT_DELETE',
  'USER_CREATE',
  'USER_UPDATE',
  'USER_PASSWORD_RESET',
  'USER_REVOKED',
  'PASSWORD_CHANGED',
  'DEPARTMENT_CREATE',
  'DEPARTMENT_UPDATE',
  'DEPARTMENT_MOVE',
  'DEPARTMENT_DELETE',
  'ROLE_CREATE',
  'ROLE_UPDATE',
  'ROLE_DELETE',
];

test('every backend action code has a Chinese label', () => {
  for (const action of BACKEND_ACTIONS) {
    const text = operationActionText(action);
    assert.notEqual(text, action, `action ${action} has no Chinese label`);
    assert.match(text, /[\u4e00-\u9fa5]/, `action ${action} label has no Chinese characters`);
  }
});

test('every aggregate type in the filter list has a label and aggregate ids stay readable', () => {
  for (const type of OPERATION_AGGREGATE_TYPES) {
    const text = operationAggregateText(type);
    assert.notEqual(text, type, `aggregate ${type} has no Chinese label`);
    assert.match(text, /[\u4e00-\u9fa5]/);
  }
  // 过滤下拉必须覆盖全部后端聚合类型（含部门/角色，此前缺失）。
  assert.deepEqual([...OPERATION_AGGREGATE_TYPES].sort(), ['ADMIN_DELEGATION', 'DEPARTMENT', 'OAUTH_CLIENT', 'PLATFORM', 'ROLE', 'USER']);
});

test('unknown action and aggregate codes fall back to the raw code', () => {
  assert.equal(operationActionText('FUTURE_ACTION_X'), 'FUTURE_ACTION_X');
  assert.equal(operationAggregateText('FUTURE_AGGREGATE_X'), 'FUTURE_AGGREGATE_X');
});
