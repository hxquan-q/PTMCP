import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { createRenderer, nextTick } from 'vue';

export class AccessError extends Error {
  constructor(status) {
    super(`HTTP ${status}`);
    this.status = status;
  }
}

let fixtureId = 0;
const dataUrl = (code) => `data:text/javascript,${encodeURIComponent(code)}`;

/** Execute the production composable and its Vue lifecycle, without claiming DOM coverage. */
export async function mountAccessWorkspace(api = {}) {
  const key = `__ptmcpAccessFixture${++fixtureId}`;
  const calls = [];
  const fixture = {
    api: {
      listGrants: async () => [],
      listDatasources: async () => ({ data: { items: [], nextCursor: null } }),
      listMetadata: async () => ({ data: { items: [], nextCursor: null } }),
      getMetadata: async () => ({ data: { generation: '1' } }),
      getDatasource: async () => ({ data: { id: '1' } }),
      ...api,
    },
    Error: AccessError,
    confirm: async (...args) => {
      calls.push(args);
    },
    can: () => true,
  };
  globalThis[key] = fixture;
  const prefix = `const f=globalThis[${JSON.stringify(key)}];`;
  const exportsFor = (names) => dataUrl(prefix + names.map((name) => `export const ${name}=(...args)=>f.api.${name}(...args);`).join('\n'));
  const urls = {
    vue: import.meta.resolve('vue'),
    'element-plus': dataUrl(
      `${prefix} export const ElMessage={success(){},warning(){}}; export const ElMessageBox={confirm:(...args)=>f.confirm(...args)};`,
    ),
    '../../../stores/session': dataUrl(`${prefix} export const useSessionStore=()=>({can:(...args)=>f.can(...args)});`),
    '../../../services/http': dataUrl(
      `${prefix} export const ApiError=f.Error; export const errorMessage=e=>e.message; export const idempotencyKey=()=>crypto.randomUUID();`,
    ),
    '../../../services/access': exportsFor(['getGrant', 'listGrants', 'previewAccess', 'revokeGrantApi', 'saveGrant']),
    '../../../services/data': exportsFor(['getDatasource', 'getMetadata', 'listDatasources', 'listMetadata']),
    '../../../services/orgPortalApi': dataUrl(
      'export const orgPortalApi={users:{list:async()=>[]},roles:{list:async()=>[]},departments:{list:async()=>[]}};',
    ),
    '../../../services/mePortalApi': dataUrl('export const mePortalApi={accessContext:{platforms:async()=>({items:[],nextCursor:null})}};'),
    '../../../adapters/access-resource': new URL('../../src/adapters/access-resource.ts', import.meta.url).href,
  };
  const file = new URL('../../src/views/admin/access/useAccessWorkspace.ts', import.meta.url);
  const code = stripTypeScriptTypes(await readFile(file, 'utf8'), { mode: 'strip' }).replace(
    /from\s+(['"])([^'"]+)\1/g,
    (statement, _quote, specifier) => (urls[specifier] ? `from ${JSON.stringify(urls[specifier])}` : statement),
  );
  const { useAccessWorkspace } = await import(dataUrl(code));
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
  let state;
  const app = renderer.createApp({
    setup() {
      state = useAccessWorkspace();
      return () => null;
    },
  });
  app.mount({});
  await flushAccess();
  return {
    state,
    calls,
    fixture,
    clear: () => globalThis.window.dispatchEvent(new Event('ptmcp:sensitive-clear')),
    close() {
      app.unmount();
      globalThis.window = priorWindow;
      delete globalThis[key];
    },
  };
}

export async function flushAccess() {
  await new Promise((resolve) => setImmediate(resolve));
  await nextTick();
}

export function deferred() {
  let resolve, reject;
  const promise = new Promise((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
