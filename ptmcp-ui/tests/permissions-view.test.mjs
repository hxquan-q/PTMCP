import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { compileScript, parse } from 'vue/compiler-sfc';
import { createRenderer, nextTick } from 'vue';

// Execute actual view setup; subject catalog is a controlled fixture, not an authorization claim.
test('主体面板按名称或精确字符串ID筛选，切换主体类型清除旧搜索但不混入其他目录', async () => {
  const { descriptor } = parse(await readFile(new URL('../src/views/admin/access/PermissionsView.vue', import.meta.url), 'utf8'));
  const url = (code) => `data:text/javascript,${encodeURIComponent(code)}`;
  const vue = JSON.stringify(import.meta.resolve('vue'));
  const workspace = url(
    `import {ref,reactive} from ${vue};export const useAccessWorkspace=()=>{const selectResource=(node)=>{globalThis.__selectedResource=node;};return {subjectType:ref('USER'),subjectId:ref(''),subjectOptions:reactive({USER:[{id:'9007199254740993',label:'合成分析员 · Analyst'},{id:'2',label:'合成管理员'}],ROLE:[{id:'r-1',label:'只读角色'}],DEPT:[]}),resourceTree:ref([{key:'DATASOURCE:1',id:'1',label:'source',type:'DATASOURCE',generation:'1',children:[{key:'OBJECT:3',id:'3',label:'orders',type:'OBJECT',generation:'2'}]}]),selectResource};};`,
  );
  const code = compileScript(descriptor, { id: 'permissions-view-test' }).content.replace(/from\s+(['"])([^'"]+)\1/g, (original, _q, specifier) => {
    if (specifier === 'vue') return `from ${vue}`;
    if (specifier === './useAccessWorkspace') return `from ${JSON.stringify(workspace)}`;
    if (specifier === './resource-tree') return `from ${JSON.stringify(new URL('../src/views/admin/access/resource-tree.ts', import.meta.url).href)}`;
    if (specifier === '../../portalUtils') return `from ${JSON.stringify(url('export const formatDate=(value)=>String(value);'))}`;
    if (specifier.endsWith('.vue')) return `from ${JSON.stringify(url('export default {};'))}`;
    if (specifier.startsWith('~icons/')) return `from ${JSON.stringify(url('export default {};'))}`;
    return original;
  });
  const component = (await import(url(stripTypeScriptTypes(code, { mode: 'strip' })))).default;
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
  const state = app._instance.setupState;
  try {
    state.subjectSearch = 'ANALYST';
    assert.equal(state.filteredSubjects.length, 1);
    assert.equal(state.filteredSubjects[0].id, '9007199254740993');
    state.subjectSearch = '9007199254740993';
    assert.equal(state.filteredSubjects[0].label, '合成分析员 · Analyst');
    state.subjectType = 'ROLE';
    await nextTick();
    assert.equal(state.subjectSearch, '');
    assert.deepEqual(
      state.filteredSubjects.map((item) => item.id),
      ['r-1'],
    );
    state.subjectType = 'DEPT';
    await nextTick();
    assert.deepEqual(state.filteredSubjects, []);
    let prevented = false;
    state.activateTreeNode({
      key: 'Enter',
      target: { closest: () => ({ dataset: { key: 'OBJECT:3' } }) },
      preventDefault: () => {
        prevented = true;
      },
    });
    assert.equal(prevented, true);
    assert.equal(globalThis.__selectedResource.key, 'OBJECT:3');
    state.activateTreeNode({ key: 'Tab', target: { closest: () => ({ dataset: { key: 'OBJECT:3' } }) }, preventDefault: () => {} });
    assert.equal(globalThis.__selectedResource.key, 'OBJECT:3');
  } finally {
    app.unmount();
    delete globalThis.__selectedResource;
  }
});
