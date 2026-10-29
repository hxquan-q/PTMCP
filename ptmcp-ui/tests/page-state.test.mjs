import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { compileScript, parse } from 'vue/compiler-sfc';
import { createRenderer, h, nextTick, ref } from 'vue';

async function mountState(writeText) {
  const prior = Object.getOwnPropertyDescriptor(globalThis, 'navigator');
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { clipboard: { writeText } } });
  const { descriptor } = parse(await readFile(new URL('../src/components/common/PageState.vue', import.meta.url), 'utf8'));
  const code = compileScript(descriptor, { id: 'page-state' }).content.replace(
    /from\s+['"]vue['"]/g,
    `from ${JSON.stringify(import.meta.resolve('vue'))}`,
  );
  const component = (await import(`data:text/javascript,${encodeURIComponent(stripTypeScriptTypes(code, { mode: 'strip' }))}`)).default;
  const id = ref('fixture-request-1');
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
  const view = { ...component, render: () => null };
  const app = renderer.createApp({ render: () => h(view, { requestId: id.value }) });
  app.mount({});
  const state = app._instance.subTree.component.setupState;
  return {
    state,
    id,
    close() {
      app.unmount();
      if (prior) Object.defineProperty(globalThis, 'navigator', prior);
      else delete globalThis.navigator;
    },
  };
}

test('请求ID复制只在明确触发时执行，复制失败保留可恢复提示', async () => {
  const writes = [];
  const page = await mountState(async (id) => {
    writes.push(id);
    throw new Error('clipboard blocked');
  });
  try {
    assert.deepEqual(writes, []);
    await page.state.copyRequestId();
    assert.deepEqual(writes, ['fixture-request-1']);
    assert.match(page.state.copyFeedback, /复制失败/);
    assert.equal(page.state.copying, false);
  } finally {
    page.close();
  }
});

test('重复复制锁定，切换请求ID使迟到复制反馈作废', async () => {
  let resolveCopy;
  let calls = 0;
  const page = await mountState(() => {
    calls++;
    return new Promise((resolve) => {
      resolveCopy = resolve;
    });
  });
  try {
    const pending = page.state.copyRequestId();
    await page.state.copyRequestId();
    assert.equal(calls, 1);
    page.id.value = 'fixture-request-2';
    await nextTick();
    resolveCopy();
    await pending;
    assert.equal(page.state.copyFeedback, '');
    assert.equal(page.state.copying, false);
  } finally {
    page.close();
  }
});
