import test from 'node:test';
import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import { createRenderer, nextTick } from 'vue';
import { deferred } from './helpers/mount-access-workspace.mjs';
const hooks = registerHooks({
  resolve(specifier, context, nextResolve) {
    return nextResolve(
      context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier) ? `${specifier}.ts` : specifier,
      context,
    );
  },
});
const { useCursorPage } = await import('../src/composables/useCursorPage.ts');
const { useRecordPreview } = await import('../src/composables/useRecordPreview.ts');
hooks.deregister();
function mount(read, createState = useCursorPage) {
  const prior = globalThis.window;
  globalThis.window = new EventTarget();
  let state;
  const renderer = createRenderer({
    insert() {},
    remove() {},
    patchProp() {},
    setElementText() {},
    setText() {},
    createElement: () => ({}),
    createText: () => ({}),
    createComment: () => ({}),
    parentNode: () => null,
    nextSibling: () => null,
  });
  const app = renderer.createApp({
    setup() {
      state = createState(read);
      return () => null;
    },
  });
  app.mount({});
  return {
    state,
    close() {
      app.unmount();
      globalThis.window = prior;
    },
  };
}
test('速览快速切换取消旧读取，迟到失败不能清空新记录或加载状态', async () => {
  const old = deferred();
  const current = deferred();
  const signals = [];
  const page = mount((id, signal) => {
    signals.push(signal);
    return id === 'old' ? old.promise : current.promise;
  }, useRecordPreview);
  try {
    const first = page.state.select('old');
    const second = page.state.select('current');
    assert.equal(signals[0].aborted, true);
    old.reject(new Error('old failure'));
    await first;
    assert.equal(page.state.loading.value, true);
    assert.equal(page.state.error.value, '');
    current.resolve({ requestId: 'current', sqlMasked: 'synthetic ?' });
    await second;
    assert.equal(page.state.selectedId.value, 'current');
    assert.equal(page.state.item.value.requestId, 'current');
  } finally {
    page.close();
  }
});

test('速览失效清除已选ID及详情，错误重试只读取当前记录', async () => {
  const pending = deferred();
  let calls = 0;
  const page = mount(async () => {
    calls++;
    if (calls === 1) throw Object.assign(new Error('unavailable'), { requestId: 'fixture-read' });
    return pending.promise;
  }, useRecordPreview);
  try {
    await page.state.select('record-1');
    assert.equal(page.state.item.value, null);
    assert.equal(page.state.requestId.value, 'fixture-read');
    const retry = page.state.retry();
    assert.equal(page.state.requestId.value, null);
    globalThis.window.dispatchEvent(new Event('ptmcp:sensitive-clear'));
    pending.resolve({ requestId: 'record-1' });
    await retry;
    assert.equal(page.state.selectedId.value, '');
    assert.equal(page.state.item.value, null);
    assert.equal(page.state.loading.value, false);
    assert.equal(page.state.error.value, '');
  } finally {
    page.close();
  }
});

test('cursor stale restarts a read at page one without appending obsolete rows', async () => {
  const calls = [];
  const page = mount(async (cursor) => {
    calls.push(cursor);
    if (cursor) throw Object.assign(new Error('stale'), { code: 'CURSOR_STALE', status: 409 });
    return { items: [calls.length], nextCursor: 'stale-page' };
  });
  try {
    await page.state.load();
    await page.state.load(true);
    assert.deepEqual(calls, [undefined, 'stale-page', undefined]);
    assert.deepEqual(page.state.rows.value, [3]);
    assert.equal(page.state.error.value, '');
  } finally {
    page.close();
  }
});
test('错误请求ID随当前失败显示，重试/重置/失效响应不能留下旧排障编号', async () => {
  let fails = true;
  const page = mount(async () => {
    if (fails) throw Object.assign(new Error('fixture unavailable'), { requestId: 'fixture-request-1' });
    return { items: ['current'], nextCursor: null };
  });
  try {
    await page.state.load();
    assert.equal(page.state.requestId.value, 'fixture-request-1');
    assert.equal(page.state.error.value, 'fixture unavailable');
    fails = false;
    await page.state.retry();
    assert.equal(page.state.requestId.value, null);
    assert.equal(page.state.error.value, '');
    fails = true;
    await page.state.load();
    page.state.reset();
    assert.equal(page.state.requestId.value, null);
  } finally {
    page.close();
  }
});

test('reset cannot let a late request clear the new loading state or restore rows', async () => {
  const old = deferred();
  const current = deferred();
  let count = 0;
  const page = mount(() => (++count === 1 ? old.promise : current.promise));
  try {
    const first = page.state.load();
    page.state.reset();
    const second = page.state.load();
    old.resolve({ items: ['old'], nextCursor: 'old' });
    await first;
    assert.equal(page.state.loading.value, true);
    current.resolve({ items: ['current'], nextCursor: null });
    await second;
    assert.deepEqual(page.state.rows.value, ['current']);
    globalThis.window.dispatchEvent(new Event('ptmcp:sensitive-clear'));
    await nextTick();
    assert.deepEqual(page.state.rows.value, []);
  } finally {
    page.close();
  }
});
