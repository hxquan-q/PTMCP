import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { createRenderer } from 'vue';
import { compileScript, parse } from 'vue/compiler-sfc';

let fixtureId = 0;
const dataUrl = (code) => `data:text/javascript,${encodeURIComponent(code)}`;

// Execute the real page setup and request cancellation; this host does not render a DOM.
export async function mountHistoryPage(list) {
  const key = `__historyFixture${++fixtureId}`;
  globalThis[key] = { list };
  const prefix = `const f=globalThis[${JSON.stringify(key)}];`;
  const utils = (await readFile(new URL('../../src/views/portalUtils.ts', import.meta.url), 'utf8'))
    .replace("'vue'", JSON.stringify(import.meta.resolve('vue')))
    .replace("'../services/portalRequest'", JSON.stringify(new URL('../../src/services/portalRequest.ts', import.meta.url).href));
  const urls = {
    '../../components/common/CursorFooter.vue': dataUrl('export default {};'),
    '../../components/common/PageHeader.vue': dataUrl('export default {};'),
    vue: import.meta.resolve('vue'),
    'vue-router': dataUrl('export const useRouter=()=>({push(){}});'),
    'element-plus': dataUrl('export const ElMessage={warning(){}};'),
    '../../services/mePortalApi': dataUrl(`${prefix} export const mePortalApi={history:{list:(...args)=>f.list(...args)}};`),
    '../../services/queryErrorText': dataUrl('export const queryErrorText=(code)=>code;'),
    '../portalUtils': dataUrl(stripTypeScriptTypes(utils, { mode: 'transform' })),
  };
  const { descriptor } = parse(await readFile(new URL('../../src/views/me/HistoryView.vue', import.meta.url), 'utf8'));
  const script = compileScript(descriptor, { id: key }).content.replace(/from\s+(['"])([^'"]+)\1/g, (statement, _quote, specifier) =>
    urls[specifier] ? `from ${JSON.stringify(urls[specifier])}` : statement,
  );
  const component = (await import(dataUrl(stripTypeScriptTypes(script, { mode: 'transform' })))).default;
  const priorWindow = globalThis.window;
  globalThis.window = new EventTarget();
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
  const app = renderer.createApp({ ...component, render: () => null });
  app.mount({});
  return {
    state: app._instance.setupState,
    clear: () => window.dispatchEvent(new Event('ptmcp:sensitive-clear')),
    close() {
      app.unmount();
      globalThis.window = priorWindow;
      delete globalThis[key];
    },
  };
}
