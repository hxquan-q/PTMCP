import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { compileScript, parse } from 'vue/compiler-sfc';
import { createRenderer, nextTick } from 'vue';

const dataUrl = (code) => `data:text/javascript,${encodeURIComponent(code)}`;

test('审计详情首次进入即加载访问记录，初始化完成后才执行立即 watcher', async () => {
  const { descriptor } = parse(await readFile(new URL('../src/views/admin/audit/AuditDetailView.vue', import.meta.url), 'utf8'));
  const calls = [];
  globalThis.__auditDetailFixture = {
    get: async (id) => {
      calls.push(['detail', id]);
      return { requestId: id, result: 'UNKNOWN' };
    },
    sensitiveAccess: async (id) => {
      calls.push(['trail', id]);
      return [{ id: '9', operatorId: '2', fields: ['sqlRaw'], reason: '合成测试理由', createdAt: '2026-10-27T00:00:00Z' }];
    },
  };
  const imports = {
    vue: import.meta.resolve('vue'),
    'vue-router': dataUrl('export const useRoute=()=>({params:{requestId:"fixture-id"}}); export const useRouter=()=>({push(){}});'),
    '../../../stores/session': dataUrl('export const useSessionStore=()=>({can:()=>true});'),
    '../../../services/controlPlane': dataUrl('export const controlPlane={audit:globalThis.__auditDetailFixture};'),
    '../../../services/frontendMode': dataUrl('export const auditApiReady=true;'),
    '../../portalUtils': dataUrl(
      'export const errorText=e=>String(e);export const formatDate=v=>v;export const usePortalTask=()=>({cancel(){},run:fn=>fn()});',
    ),
  };
  const code = compileScript(descriptor, { id: 'audit-detail-test' }).content.replace(/from\s+(['"])([^'"]+)\1/g, (statement, _q, specifier) => {
    if (imports[specifier]) return `from ${JSON.stringify(imports[specifier])}`;
    if (specifier.endsWith('.vue')) return `from ${JSON.stringify(dataUrl('export default {};'))}`;
    return statement;
  });
  const component = (await import(dataUrl(stripTypeScriptTypes(code, { mode: 'strip' })))).default;
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
  const errors = [];
  const app = renderer.createApp({ ...component, render: () => null });
  app.config.errorHandler = (error) => errors.push(error);
  try {
    app.mount({});
    await nextTick();
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepEqual(errors, []);
    assert.deepEqual(calls, [
      ['detail', 'fixture-id'],
      ['trail', 'fixture-id'],
    ]);
    assert.equal(app._instance.setupState.trail.length, 1);
    assert.equal(app._instance.setupState.trailLoading, false);
    assert.equal(app._instance.setupState.trailError, '');
  } finally {
    app.unmount();
    delete globalThis.__auditDetailFixture;
  }
});
