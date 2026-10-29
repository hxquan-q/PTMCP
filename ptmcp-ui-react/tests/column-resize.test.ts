import assert from 'node:assert/strict';
import test from 'node:test';
import { remainderColumn, resizeColumns } from '../src/views/admin/columnResize.ts';

test('divider 0 transfers width between columns 0 and 1, column 2 untouched', () => {
  assert.deepEqual(resizeColumns([25, 25], 0, 10), [35, 15]);
  assert.deepEqual(resizeColumns([25, 25], 0, -10), [15, 35]);
  // 栏2 取余始终不变
  assert.equal(remainderColumn(resizeColumns([25, 25], 0, 10)), 50);
});

test('divider 1 only moves column 1; column 2 absorbs via remainder', () => {
  assert.deepEqual(resizeColumns([25, 25], 1, 10), [25, 35]);
  assert.equal(remainderColumn(resizeColumns([25, 25], 1, 10)), 40);
  assert.deepEqual(resizeColumns([25, 25], 1, -5), [25, 20]);
});

test('both columns clamp to [14, 50] so no pane can collapse or dominate', () => {
  assert.deepEqual(resizeColumns([25, 25], 0, 999), [50, 14]);
  assert.deepEqual(resizeColumns([25, 25], 0, -999), [14, 50]);
  assert.deepEqual(resizeColumns([25, 25], 1, 999), [25, 50]);
  assert.deepEqual(resizeColumns([40, 40], 1, -999), [40, 14]);
  // 永远给第三栏留出空间：两栏之和上限 100-MIN
  const extreme = resizeColumns([25, 25], 0, 999);
  assert.ok(remainderColumn(extreme) >= 0);
});
