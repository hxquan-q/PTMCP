import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { stripTypeScriptTypes } from 'node:module';
import { createRenderer } from 'vue';
import { parse, compileScript } from 'vue/compiler-sfc';
const url = (code) => `data:text/javascript,${encodeURIComponent(code)}`;
let serial = 0;
async function mount(name, props) {
  const { descriptor } = parse(await readFile(new URL(`../src/views/admin/integrations/${name}.vue`, import.meta.url), 'utf8'));
  const code = compileScript(descriptor, { id: `form-${++serial}` }).content;
  const imports = {
    vue: import.meta.resolve('vue'),
    'element-plus': url(
      'export const ElAlert={},ElButton={},ElForm={},ElFormItem={},ElInput={},ElOption={},ElSelect={},ElCheckbox={},ElCheckboxGroup={};',
    ),
    './adminSurface': new URL('../src/views/admin/integrations/adminSurface.ts', import.meta.url).href,
    '../../../services/frontendMode': url('export const controlPlaneAvailable=true; export const platformApiReady=true;'),
    '../../portalUtils': url('export const errorText=e=>e.message;'),
  };
  const script = stripTypeScriptTypes(code, { mode: 'transform' }).replace(/from\s+(['"])([^'"]+)\1/g, (s, q, p) =>
    imports[p] ? `from ${JSON.stringify(imports[p])}` : s,
  );
  const component = (await import(url(script))).default;
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
  const app = renderer.createApp({ ...component, render: () => null }, props);
  app.mount({});
  return { state: app._instance.setupState, close: () => app.unmount() };
}
test('platform edit emits only write DTO fields and retains the original code', async () => {
  let body;
  const page = await mount('PlatformBasicsForm', {
    editing: true,
    initial: {
      id: '1',
      version: '3',
      securityVersion: '4',
      scopeVersion: '2',
      proofConfigured: false,
      code: 'original',
      name: '合成平台',
      tier: 3,
      catalogMode: 'HIDE',
      proofMode: 'NONE',
      status: 'DISABLED',
    },
    submit: async (value) => {
      body = value;
    },
  });
  try {
    await page.state.save();
    assert.ok(body);
    assert.equal(body.code, 'original');
    assert.equal(body.id, undefined);
    assert.equal(body.version, undefined);
    assert.equal(body.securityVersion, undefined);
    assert.equal(body.proofConfigured, undefined);
  } finally {
    page.close();
  }
});
test('client edit emits write fields without identities or read-only secret state', async () => {
  let body;
  const page = await mount('ClientConfigurationForm', {
    initial: {
      id: '1',
      clientId: 'original-client',
      platformId: '2',
      version: '3',
      secretConfigured: true,
      name: '合成客户端',
      type: 'CONFIDENTIAL',
      authMethods: ['client_secret_basic'],
      redirectUris: ['https://example.test/callback'],
      allowedScopes: ['mcp:catalog'],
      jwksUri: '',
      status: 'ACTIVE',
    },
    submit: async (value) => {
      body = value;
    },
  });
  try {
    await page.state.save();
    assert.ok(body);
    assert.deepEqual(body.authMethods, ['client_secret_basic']);
    assert.equal(body.clientId, undefined);
    assert.equal(body.version, undefined);
    assert.equal(body.secretConfigured, undefined);
  } finally {
    page.close();
  }
});
