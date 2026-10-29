import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { compileScript, parse } from 'vue/compiler-sfc';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';

async function pageHeader() {
  const { descriptor } = parse(await readFile(new URL('../src/components/common/PageHeader.vue', import.meta.url), 'utf8'));
  const compiled = compileScript(descriptor, { id: 'page-header', inlineTemplate: true }).content.replace(
    /from\s+['"]vue['"]/g,
    `from ${JSON.stringify(import.meta.resolve('vue'))}`,
  );
  return (await import(`data:text/javascript,${encodeURIComponent(stripTypeScriptTypes(compiled, { mode: 'strip' }))}`)).default;
}

test('公共页面头部保留区域标题关联、状态、返回与操作插槽并转义外部标题', async () => {
  const header = await pageHeader();
  const html = await renderToString(
    createSSRApp({
      render: () =>
        h(
          header,
          { title: '<script>untrusted</script>', titleId: 'resource-title', description: '对象说明' },
          {
            before: () => h('button', '返回'),
            status: () => h('span', '已停用'),
            actions: () => h('button', '同步'),
          },
        ),
    }),
  );
  assert.match(html, /<h1 id="resource-title">&lt;script&gt;untrusted&lt;\/script&gt;<\/h1>/);
  assert.ok(html.indexOf('返回') < html.indexOf('<h1'));
  assert.match(html, /已停用/);
  assert.match(html, /对象说明/);
  assert.match(html, /page-header__actions/);
  assert.match(html, /同步/);
});

test('无操作和描述的页面头部不输出空操作区或描述段落', async () => {
  const header = await pageHeader();
  const html = await renderToString(createSSRApp({ render: () => h(header, { title: '数据源' }) }));
  assert.doesNotMatch(html, /page-header__actions/);
  assert.doesNotMatch(html, /<p/);
  assert.match(html, /<h1>数据源<\/h1>/);
});
