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
const { authAvailability, templatesFromConfig } = await import('../src/views/me/mcpTemplate.ts');
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
  assert.deepEqual(authAvailability(null), { 'oauth-pat': true, apikey: true, none: true });
  assert.deepEqual(authAvailability([]), { 'oauth-pat': true, apikey: true, none: true });
  assert.deepEqual(authAvailability(['OAuth', 'PAT']), { 'oauth-pat': true, apikey: false, none: false });
  assert.deepEqual(authAvailability(['oauth']), { 'oauth-pat': true, apikey: false, none: false });
  assert.deepEqual(authAvailability(['API_KEY']), { 'oauth-pat': false, apikey: true, none: false });
  assert.deepEqual(authAvailability(['none']), { 'oauth-pat': false, apikey: false, none: true });
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
    assert.equal(cfg.compatibilityStatus, 'NOT_VALIDATED');
    const t = templatesFromConfig(cfg.configTemplate);
    assert.ok(t, 'server configTemplate must be valid JSON');
    assert.match(t.json, /mcpServers/);
    assert.match(t.json, /Bearer/);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
