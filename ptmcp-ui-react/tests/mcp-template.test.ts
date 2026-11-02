import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

// Vite resolves extensionless source imports; mirror that only while loading these TS modules.
const sourceImports = registerHooks({
  resolve(specifier: string, context: { parentURL?: string }, nextResolve: (s: string, c: unknown) => unknown) {
    const extensionlessSource = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionlessSource ? `${specifier}.ts` : specifier, context);
  },
});
const { authAvailability, preferredChoice, templatesFromConfig, transportAvailability, wizardCanAdvance } = await import(
  '../src/views/me/mcpTemplate.ts'
);
const { mePortalApi } = await import('../src/services/mePortalApi.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

test('templatesFromConfig pretty-prints JSON and derives readable YAML', () => {
  const raw = '{"mcpServers":{"synthetic":{"url":"https://mcp.example.test/mcp","headers":{"Authorization":"Bearer abc"}}}}';
  const t = templatesFromConfig(raw);
  assert.ok(t);
  assert.match(t.json, /"mcpServers": \{/);
  assert.match(t.json, /"url": "https:\/\/mcp.example.test\/mcp"/);
  assert.match(t.yaml, /^mcpServers:/);
  assert.match(t.yaml, /url: https:\/\/mcp\.example\.test\/mcp/);
  // 含空格/引号的字符串值必须加引号，避免生成不可解析的 YAML
  assert.match(t.yaml, /Authorization: "Bearer abc"/);
});

test('templatesFromConfig keeps arrays, numbers and empty containers; rejects non-objects', () => {
  const t = templatesFromConfig('{"a":[1,2],"b":{},"c":null,"d":true,"e":"x y"}');
  assert.ok(t);
  assert.match(t.yaml, /- 1/);
  assert.match(t.yaml, /b: \{\}/);
  assert.match(t.yaml, /c: null/);
  assert.match(t.yaml, /d: true/);
  assert.match(t.yaml, /e: "x y"/);
  assert.equal(templatesFromConfig('not json'), null);
  assert.equal(templatesFromConfig('"scalar"'), null);
  assert.equal(templatesFromConfig('42'), null);
});

test('authAvailability gates cards by what the platform actually supports', () => {
  // 服务端未返回认证元数据时 fail-closed：未知不等于支持。
  assert.deepEqual(authAvailability(null), { 'oauth-pat': false, apikey: false, none: false });
  assert.deepEqual(authAvailability([]), { 'oauth-pat': false, apikey: false, none: false });
  assert.deepEqual(authAvailability(undefined), { 'oauth-pat': false, apikey: false, none: false });
  assert.deepEqual(authAvailability(['unknown-method']), { 'oauth-pat': false, apikey: false, none: false });
  assert.deepEqual(authAvailability(['OAuth', 'PAT']), { 'oauth-pat': true, apikey: false, none: false });
  assert.deepEqual(authAvailability(['oauth']), { 'oauth-pat': true, apikey: false, none: false });
  assert.deepEqual(authAvailability(['API_KEY']), { 'oauth-pat': false, apikey: true, none: false });
  assert.deepEqual(authAvailability(['none']), { 'oauth-pat': false, apikey: false, none: true });
});

test('wizard follows the design choices but only advances on server-declared transport and auth', () => {
  const httpOnly = transportAvailability('streamable-http');
  const apiOnly = authAvailability(['API_KEY']);
  assert.deepEqual(httpOnly, { 'streamable-http': true, stdio: false });
  assert.equal(transportAvailability('HTTP')['streamable-http'], true);
  assert.deepEqual(transportAvailability('stdio'), { 'streamable-http': false, stdio: true });
  assert.deepEqual(transportAvailability('websocket'), { 'streamable-http': false, stdio: false });
  assert.equal(preferredChoice(apiOnly, ['oauth-pat', 'apikey', 'none']), 'apikey');
  assert.equal(preferredChoice(authAvailability(['none']), ['oauth-pat', 'apikey', 'none']), 'none');
  assert.equal(preferredChoice(authAvailability(null), ['oauth-pat', 'apikey', 'none']), '');

  const base = {
    platform: 'p1',
    client: 'CURSOR',
    transport: 'streamable-http',
    auth: 'oauth-pat',
    transports: httpOnly,
    auths: authAvailability(['OAuth', 'PAT']),
    authKnown: true,
    templateReady: true,
  };
  assert.equal(wizardCanAdvance(0, { ...base, platform: ' ' }), false);
  assert.equal(wizardCanAdvance(1, { ...base, client: '' }), false);
  assert.equal(wizardCanAdvance(2, base), true);
  // 设计稿上的 stdio / API Key 在服务端未声明时不能当作已选通过。
  assert.equal(wizardCanAdvance(2, { ...base, transport: 'stdio' }), false);
  assert.equal(wizardCanAdvance(2, { ...base, auth: 'apikey' }), false);
  assert.equal(wizardCanAdvance(2, { ...base, authKnown: false }), false);
  assert.equal(wizardCanAdvance(2, { ...base, auth: 'apikey', auths: apiOnly }), true);
  assert.equal(wizardCanAdvance(3, { ...base, templateReady: false }), false);
  assert.equal(wizardCanAdvance(3, base), true);
  assert.equal(wizardCanAdvance(4, base), false);
});

test('mcp-config contract: server template drives the wizard, auth list comes from server', async () => {
  const server = createPreviewServer();
  const originalFetch = globalThis.fetch;
  setCsrfToken('test-csrf');
  globalThis.fetch = (async (input: RequestInfo | URL, init?: RequestInit) =>
    server(String(input).replace('/backend/admin', ''), {
      method: init?.method,
      headers: init?.headers,
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
      signal: init?.signal,
    })) as typeof fetch;
  try {
    const cfg = await mePortalApi.mcpConfig('1', 'GENERIC');
    assert.equal(cfg.transport, 'streamable-http');
    assert.ok(cfg.authentication.includes('PAT'));
    assert.equal(cfg.compatibilityStatus, 'UNVERIFIED');
    const t = templatesFromConfig(cfg.configTemplate);
    assert.ok(t, 'server configTemplate must be valid JSON');
    assert.match(t.json, /mcpServers/);
    assert.match(t.json, /Bearer/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
