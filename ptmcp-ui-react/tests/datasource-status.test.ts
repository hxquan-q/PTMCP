import assert from 'node:assert/strict';
import test from 'node:test';
import { fmtTime, healthOf, statusOf, syncResultOf } from '../src/views/admin/datasourceStatus.ts';

test('statusOf maps every backend status to a stable label and tone', () => {
  assert.deepEqual(statusOf('ACTIVE'), { label: '启用', tone: 'success' });
  assert.deepEqual(statusOf('enabled'), { label: '启用', tone: 'success' });
  assert.deepEqual(statusOf('DISABLED'), { label: '禁用', tone: 'danger' });
  assert.deepEqual(statusOf('DRAFT'), { label: '草稿', tone: 'neutral' });
  assert.deepEqual(statusOf(''), { label: '未知', tone: 'neutral' });
  assert.deepEqual(statusOf(null), { label: '未知', tone: 'neutral' });
  assert.deepEqual(statusOf(undefined), { label: '未知', tone: 'neutral' });
  // 未知业务状态原样透出，不伪装成已知状态
  assert.deepEqual(statusOf('ARCHIVED'), { label: 'ARCHIVED', tone: 'neutral' });
});

test('healthOf separates healthy, degraded, down and unknown', () => {
  assert.deepEqual(healthOf('HEALTHY'), { label: '连接正常', tone: 'success' });
  assert.deepEqual(healthOf('UP'), { label: '连接正常', tone: 'success' });
  assert.deepEqual(healthOf('ok'), { label: '连接正常', tone: 'success' });
  assert.deepEqual(healthOf('DEGRADED'), { label: '连接异常', tone: 'warning' });
  assert.deepEqual(healthOf('WARN'), { label: '连接异常', tone: 'warning' });
  assert.deepEqual(healthOf('DOWN'), { label: '连接失败', tone: 'danger' });
  assert.deepEqual(healthOf('FAIL'), { label: '连接失败', tone: 'danger' });
  assert.deepEqual(healthOf('UNKNOWN'), { label: '连接未知', tone: 'neutral' });
});

test('syncResultOf keeps null for never-synced rows and distinguishes failure', () => {
  assert.equal(syncResultOf(null), null);
  assert.equal(syncResultOf(undefined), null);
  assert.equal(syncResultOf(''), null);
  assert.deepEqual(syncResultOf('SUCCEEDED'), { label: '成功', tone: 'success' });
  assert.deepEqual(syncResultOf('FAILED'), { label: '失败', tone: 'danger' });
  assert.deepEqual(syncResultOf('ERROR'), { label: '失败', tone: 'danger' });
});

test('fmtTime renders local YYYY-MM-DD HH:mm and degrades unknown input to —', () => {
  assert.equal(fmtTime('2026-10-29T16:09:00Z'), '2026-10-29 16:09');
  assert.equal(fmtTime('2026-10-29T16:09:00'), '2026-10-29 16:09');
  assert.equal(fmtTime(null), '—');
  assert.equal(fmtTime(undefined), '—');
  assert.equal(fmtTime(''), '—');
  assert.equal(fmtTime('garbage'), '—');
});
