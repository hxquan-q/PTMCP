import { readFile } from 'node:fs/promises';
import { registerHooks, stripTypeScriptTypes } from 'node:module';
import { createRenderer, nextTick, reactive } from 'vue';
import { compileScript, parse } from 'vue/compiler-sfc';

export class FixtureDataError extends Error {
  readonly status: number;
  readonly code: string;
  constructor(status: number, code: string) {
    super(code);
    this.status = status;
    this.code = code;
  }
}

let fixtureId = 0;
type Api = Record<string, (...args: unknown[]) => unknown>;
interface PageState {
  form: Record<string, unknown>;
  formRef: { validate: () => Promise<boolean> };
  datasources: unknown[];
  rows: unknown[];
  nextCursor: string | null;
  awaitingSnapshot: boolean;
  etag: string;
  conflictRequired: boolean;
  conflict: unknown;
  datasource: { id: string } | null;
  removedDraft: Array<{ columnName: string; title: string; description: string }>;
  openCreate: () => void;
  submitCreate: () => Promise<void>;
  clearSensitive: () => void;
  save: () => Promise<void>;
  resolveConflict: (keepDraft: boolean) => void;
  moreSources: () => void;
  nextPage: () => void;
}
const dataUrl = (code: string): string => `data:text/javascript,${encodeURIComponent(code)}`;

/** Allow extensionless relative imports (match app resolution) when loading real modules in tests. */
export function resolveSourceExtensions<C>(specifier: string, context: C, next: (specifier: string, context: C) => unknown): unknown {
  return next(
    context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier) ? `${specifier}.ts` : specifier,
    context,
  );
}

/** Run the actual SFC setup and lifecycle with a Vue host renderer; no browser or DOM claims. */
export async function mountDataPage(path: string, api: Api) {
  const hooks = registerHooks({ resolve: (specifier: string, context, next) => resolveSourceExtensions(specifier, context, next) });
  const key = `__ptmcpPageFixture${++fixtureId}`;
  const navigations: unknown[] = [];
  const route = reactive({ params: { id: '12', objectId: '31' }, query: { datasourceId: '12' } });
  const globals = globalThis as unknown as Record<string, unknown>;
  globals[key] = {
    api,
    Error: FixtureDataError,
    route,
    router: {
      push: async (value: unknown) => {
        navigations.push(value);
      },
      replace: async (value: unknown) => {
        navigations.push(value);
      },
      back: () => {},
    },
    session: { can: () => true },
  };
  const prefix = `const f=globalThis[${JSON.stringify(key)}];`;
  const serviceNames = [
    'createDatasource',
    'deleteDatasource',
    'getDatasource',
    'getMetadata',
    'getJob',
    'listDatasources',
    'listMetadata',
    'syncDatasource',
    'testDatasource',
    'updateDatasource',
    'updateDictionary',
  ];
  const services = dataUrl(
    `${prefix} export const DataRequestError=f.Error; ${serviceNames.map((name) => `export const ${name}=(...args)=>f.api.${name}(...args);`).join('\n')}`,
  );
  const urls: Record<string, string> = {
    vue: import.meta.resolve('vue'),
    'vue-router': dataUrl(`${prefix} export const useRoute=()=>f.route; export const useRouter=()=>f.router;`),
    'element-plus': dataUrl('export const ElMessage={success(){}}; export const ElMessageBox={confirm:async()=>{}};'),
    '../../../services/data': services,
    '../../../services/uuid': new URL('../../src/services/uuid.ts', import.meta.url).href,
    '../../../stores/session': dataUrl(`${prefix} export const useSessionStore=()=>f.session;`),
    '../../../adapters/edit-conflict': new URL('../../src/adapters/edit-conflict.ts', import.meta.url).href,
    '../../../composables/useCursorPage': new URL('../../src/composables/useCursorPage.ts', import.meta.url).href,
    '../../../components/common/CursorFooter.vue': dataUrl('export default {};'),
    '../../../components/common/DetailDrawer.vue': dataUrl('export default {};'),
    '../../../components/common/PageHeader.vue': dataUrl('export default {};'),
    '../../../components/common/PageState.vue': dataUrl('export default {};'),
    '../../../components/common/StatusBadge.vue': dataUrl('export default {};'),
    '../../../components/data/VersionConflictPanel.vue': dataUrl('export default {};'),
    '../../portalUtils': dataUrl('export const formatDate=(value)=>String(value);'),
    '../portalUtils': dataUrl('export const formatDate=(value)=>String(value);'),
  };
  const file = new URL(`../../src/views/${path}`, import.meta.url);
  const { descriptor } = parse(await readFile(file, 'utf8'));
  const script = compileScript(descriptor, { id: key });
  const code = script.content.replace(/from\s+(['"])([^'"]+)\1/g, (statement, _quote: string, specifier: string) =>
    urls[specifier] ? `from ${JSON.stringify(urls[specifier])}` : statement,
  );
  const component = (await import(dataUrl(stripTypeScriptTypes(code, { mode: 'transform' })))).default;
  hooks.deregister();
  const priorWindow = globals.window;
  globals.window = new EventTarget();
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
  const state = (app._instance as unknown as { setupState: PageState }).setupState;
  await flushPage();
  return {
    state,
    route,
    navigations,
    close() {
      app.unmount();
      globals.window = priorWindow;
      delete globals[key];
    },
  };
}

export async function flushPage(): Promise<void> {
  await new Promise<void>((resolve) => setImmediate(resolve));
  await nextTick();
}

export function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}
