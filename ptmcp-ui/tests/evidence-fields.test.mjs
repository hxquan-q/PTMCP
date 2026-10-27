import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { compileScript, parse } from 'vue/compiler-sfc';
import { createSSRApp, h } from 'vue';
import { renderToString } from 'vue/server-renderer';

async function renderEvidence(value) {
  const { descriptor } = parse(await readFile(new URL('../src/components/common/EvidenceFields.vue', import.meta.url), 'utf8'), {
    filename: 'EvidenceFields.vue',
  });
  const code = compileScript(descriptor, { id: 'evidence-fields', inlineTemplate: true }).content.replace(
    /from\s+['"]vue['"]/g,
    `from ${JSON.stringify(import.meta.resolve('vue'))}`,
  );
  const component = (await import(`data:text/javascript,${encodeURIComponent(stripTypeScriptTypes(code, { mode: 'transform' }))}`)).default;
  return renderToString(createSSRApp({ render: () => h(component, { value }) }));
}

test('结构化证据保留嵌套字段、未知版本与大整数ID，不插入不可信HTML', async () => {
  const html = await renderEvidence([{ objectId: '9007199254740993', futureVersion: { scopeVersion: '42', note: '<script>fixture</script>' } }]);
  assert.match(html, /<ol/);
  assert.match(html, /<dl/);
  assert.match(html, /对象 ID/);
  assert.match(html, /9007199254740993/);
  assert.match(html, /futureVersion/);
  assert.match(html, /平台范围版本/);
  assert.match(html, /42/);
  assert.match(html, /&lt;script&gt;fixture&lt;\/script&gt;/);
  assert.doesNotMatch(html, /<script>/);
});

test('证据空值、零、false与空集合明确区分，未知键不命中对象原型', async () => {
  assert.match(await renderEvidence(null), /未提供/);
  assert.match(await renderEvidence([]), /无记录/);
  assert.match(await renderEvidence({}), /无记录/);
  assert.match(await renderEvidence(0), />0</);
  assert.match(await renderEvidence(false), />否</);
  assert.match(await renderEvidence({ toString: '保留原键' }), /<dt>toString<\/dt>/);
});
