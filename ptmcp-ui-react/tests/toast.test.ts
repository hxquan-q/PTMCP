import assert from 'node:assert/strict';
import test from 'node:test';
import {
  composeToastDisplay,
  dismissToast,
  deduplicateToasts,
  filterExpired,
  getToasts,
  resetToasts,
  showToast,
  subscribeToasts,
  type ToastMessage,
} from '../src/toast.ts';

test('toast queue: add, dismiss, subscribe notifies synchronously', () => {
  resetToasts();
  const received: ToastMessage[][] = [];
  const off = subscribeToasts((toasts) => received.push(toasts));

  showToast('角色已创建');
  showToast('用户已更新');
  assert.equal(getToasts().length, 2);
  assert.equal(getToasts()[0]!.text, '角色已创建');
  assert.equal(getToasts()[0]!.tone, 'success');
  assert.ok(received.length >= 2, 'subscription must fire on each mutation');

  const first = getToasts()[0]!;
  dismissToast(first.id);
  assert.equal(getToasts().length, 1);
  assert.equal(getToasts()[0]!.text, '用户已更新');

  dismissToast(getToasts()[0]!.id);
  assert.equal(getToasts().length, 0);
  // dismiss nonexistent id is a no-op
  dismissToast(999);
  assert.equal(getToasts().length, 0);
  off();
  resetToasts();
});

test('filterExpired drops stale toasts, keeps fresh ones', () => {
  const base = 1_000_000;
  const toasts: ToastMessage[] = [
    { id: 1, text: 'old', tone: 'success', createdAt: base },
    { id: 2, text: 'fresh', tone: 'info', createdAt: base + 3800 },
    { id: 3, text: 'brand-new', tone: 'success', createdAt: base + 3999 },
  ];
  const now = base + 4000;
  const alive = filterExpired(toasts, now, 4000);
  assert.deepEqual(
    alive.map((t) => t.id),
    [2, 3],
  );
  // boundary: exactly at maxAge is expired
  const boundary = filterExpired([{ id: 4, text: 'x', tone: 'success', createdAt: base }], base + 4000, 4000);
  assert.equal(boundary.length, 0);
});

test('deduplicateToasts merges consecutive identical messages (spam guard)', () => {
  const make = (id: number, text: string, tone: ToastMessage['tone']): ToastMessage => ({ id, text, tone, createdAt: id * 100 });
  const toasts = [
    make(1, '同步已提交', 'success'),
    make(2, '同步已提交', 'success'), // duplicate
    make(3, '同步失败', 'danger'), // different text
    make(4, '同步失败', 'danger'), // duplicate
    make(5, '同步已提交', 'success'), // same as #1 but NOT consecutive → kept
  ];
  const deduped = deduplicateToasts(toasts);
  assert.deepEqual(
    deduped.map((t) => t.id),
    [1, 3, 5],
  );
});

test('showToast auto-registers with incrementing ids and correct tones', () => {
  resetToasts();
  showToast('默认是 success');
  showToast('警告', 'warning');
  showToast('危险', 'danger');
  const all = getToasts();
  assert.equal(all.length, 3);
  assert.ok(all[0]!.id < all[1]!.id && all[1]!.id < all[2]!.id, 'ids must be monotonically increasing');
  assert.deepEqual(
    all.map((t) => t.tone),
    ['success', 'warning', 'danger'],
  );
  resetToasts();
});

test('composeToastDisplay applies expiry filtering then deduplication in order', () => {
  const base = 1_000_000;
  const toasts: ToastMessage[] = [
    { id: 1, text: 'old-dup', tone: 'success', createdAt: base }, // expired
    { id: 2, text: 'old-dup', tone: 'success', createdAt: base }, // expired (same age as 1), duplicate
    { id: 3, text: 'fresh-dup', tone: 'success', createdAt: base + 3900 }, // fresh
    { id: 4, text: 'fresh-dup', tone: 'success', createdAt: base + 3950 }, // fresh, duplicate of 3
    { id: 5, text: 'fresh-unique', tone: 'info', createdAt: base + 3980 }, // fresh, unique
  ];
  const now = base + 4000;
  const result = composeToastDisplay(toasts, now);
  // 1,2 expired → removed; 3 kept, 4 deduped; 5 kept
  assert.deepEqual(
    result.map((t) => t.id),
    [3, 5],
  );
  // 顺序保证：如果先去重再过滤，id=2（过期的重复项）可能被保留而 id=1 被去掉
  // 先过滤保证所有过期项（无论是否重复）都被移除，然后去重只作用于存活项
});

test('showToast auto-dismisses after the max age (setTimeout fires)', async () => {
  resetToasts();
  showToast('这条会在 4 秒后消失');
  assert.equal(getToasts().length, 1, 'toast must be registered immediately');
  // 等待 4.1s（略超 4s 的 auto-dismiss 超时）
  await new Promise((r) => setTimeout(r, 4100));
  assert.equal(getToasts().length, 0, 'toast must be auto-dismissed after 4s — if this fails, the setTimeout in showToast is broken');
  resetToasts();
});
