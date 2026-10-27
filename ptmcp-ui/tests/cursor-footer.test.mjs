import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { compileScript, parse } from 'vue/compiler-sfc';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';

async function footer(props) {
  const { descriptor } = parse(await readFile(new URL('../src/components/common/CursorFooter.vue', import.meta.url), 'utf8'));
  const code = compileScript(descriptor, { id: 'cursor-footer', inlineTemplate: true }).content.replace(
    /from\s+['"]vue['"]/g,
    `from ${JSON.stringify(import.meta.resolve('vue'))}`,
  );
  const component = (await import(`data:text/javascript,${encodeURIComponent(stripTypeScriptTypes(code, { mode: 'transform' }))}`)).default;
  const app = createSSRApp({ render: () => h(component, props) });
  app.component('ElButton', {
    props: ['loading', 'disabled'],
    setup:
      (props, { slots }) =>
      () =>
        h('button', { disabled: props.disabled }, slots.default?.()),
  });
  return renderToString(app);
}

test('游标底栏只报告已加载数量，无后续游标不提供下一页且不伪造总数', async () => {
  const html = await footer({ count: 23 });
  assert.match(html, /已加载 23 条/);
  assert.match(html, /role="status" aria-atomic="true"/);
  assert.doesNotMatch(html, /<button/);
  assert.doesNotMatch(html, /总数|总页|下一页/);
});

test('加载更多期间按钮锁定并报告忙碌，恢复后允许用户继续读取', async () => {
  const loading = await footer({ count: 20, hasMore: true, loading: true });
  assert.match(loading, /aria-busy="true"/);
  assert.match(loading, /<button disabled>加载更多<\/button>/);
  const ready = await footer({ count: 20, hasMore: true });
  assert.match(ready, /aria-busy="false"/);
  assert.match(ready, /<button>加载更多<\/button>/);
  assert.match(await footer({ count: 20, hasMore: true, disabled: true }), /<button disabled>/);
});
