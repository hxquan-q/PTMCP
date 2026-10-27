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
hooks.deregister();
function mount(read) {
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
      state = useCursorPage(read);
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
