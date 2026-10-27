import { readFile } from 'node:fs/promises';
import { registerHooks, stripTypeScriptTypes } from 'node:module';
import { compileScript, parse } from 'vue/compiler-sfc';
import { createRenderer, nextTick } from 'vue';

let serial = 0;
const dataUrl = (code) => `data:text/javascript,${encodeURIComponent(code)}`;
export async function settlePortal() {
  await nextTick();
  await new Promise((resolve) => setImmediate(resolve));
}

/** Run real page setup and public composables; this host makes no DOM/render claims. */
export async function mountPortalPage(name, api) {
  const key = `__portalFixture${++serial}`;
  const copied = [];
  globalThis[key] = { api, copied };
  const prefix = `const f=globalThis[${JSON.stringify(key)}];`;
  const imports = {
    vue: import.meta.resolve('vue'),
    'vue-router': dataUrl('export const useRouter=()=>({push(){}});'),
    'element-plus': dataUrl('export const ElMessage={success(){},warning(){},error(){}};'),
    '../../services/mePortalApi': dataUrl(`${prefix}export const mePortalApi=f.api;`),
    '../../services/clipboard': dataUrl(`${prefix}export const copyText=async text=>f.copied.push(text);`),
    '../../composables/useCursorPage': new URL('../../src/composables/useCursorPage.ts', import.meta.url).href,
    '../portalUtils': new URL('../../src/views/portalUtils.ts', import.meta.url).href,
  };
  const { descriptor } = parse(await readFile(new URL(`../../src/views/me/${name}.vue`, import.meta.url), 'utf8'));
  const code = compileScript(descriptor, { id: key }).content.replace(/from\s+(['"])([^'"]+)\1/g, (original, _q, specifier) => {
    if (imports[specifier]) return `from ${JSON.stringify(imports[specifier])}`;
    if (specifier.endsWith('.vue')) return `from ${JSON.stringify(dataUrl('export default {};'))}`;
    return original;
  });
  const hooks = registerHooks({
    resolve(specifier, context, nextResolve) {
      return nextResolve(
        context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier) ? `${specifier}.ts` : specifier,
        context,
      );
    },
  });
  let component;
  try {
    component = (await import(dataUrl(stripTypeScriptTypes(code, { mode: 'transform' })))).default;
  } finally {
    hooks.deregister();
  }
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
    copied,
    clear: () => window.dispatchEvent(new Event('ptmcp:sensitive-clear')),
    close() {
      app.unmount();
      globalThis.window = priorWindow;
      delete globalThis[key];
    },
  };
}
