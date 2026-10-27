import assert from 'node:assert/strict';
import test from 'node:test';
import { statusPresentation } from '../src/components/common/statusPresentation.ts';

test('审计拒绝不显示为执行故障，未知结局不推断失败', () => {
  const denied = statusPresentation('verdict', 'DENY');
  assert.equal(denied.label, '拒绝');
  assert.equal(denied.tone, 'warning');
  assert.notEqual(denied.tone, statusPresentation('execution', 'ERROR').tone);
  assert.equal(statusPresentation('execution', 'UNKNOWN').label, '结局未知');
  assert.equal(statusPresentation('execution', 'UNKNOWN').tone, 'info');
  assert.equal(statusPresentation('execution', 'RUNNING').label, '执行中');
});

test('启停与连接状态使用独立语义，不能把启用误报连接正常', () => {
  assert.equal(statusPresentation('lifecycle', 'ACTIVE').label, '启用');
  assert.equal(statusPresentation('lifecycle', 'DISABLED').label, '停用');
  assert.equal(statusPresentation('connection', 'HEALTHY').label, '连接正常');
  assert.equal(statusPresentation('connection', 'ACTIVE').tone, 'info');
  assert.match(statusPresentation('connection', 'ACTIVE').label, /未知状态/);
});

test('新状态和空值保守展示，保留原码供排障且不继承对象原型', () => {
  assert.deepEqual(statusPresentation('execution', 'FUTURE'), { label: '未知状态（FUTURE）', tone: 'info', icon: 'question' });
  assert.equal(statusPresentation('execution', null).label, '未提供');
  assert.equal(statusPresentation('execution', undefined).tone, 'info');
  assert.equal(statusPresentation('execution', 'toString').label, '未知状态（toString）');
});
