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
const { scopeChangeText, scopeChanges, scopeKey, scopeRuleText } = await import('../src/views/admin/scopeDiff.ts');
const access = await import('../src/services/access.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

const rule = (resourceId: string, action: string, includeDescendants: boolean, resourceGeneration = '3') => ({
  resourceType: 'DATASOURCE',
  resourceId,
  resourceGeneration,
  action,
  includeDescendants,
});

test('差异仅报告字段级修改，动作变化按删旧增新呈现，不改写键语义', () => {
  const before = [rule('12', 'META', true)];
  const after = [rule('12', 'META', false)];
  const changes = scopeChanges(before, after);
  assert.deepEqual(changes.added, []);
  assert.deepEqual(changes.removed, []);
  assert.equal(changes.modified.length, 1);
  assert.equal(scopeChangeText(changes.modified[0]!), '含子级 → 仅此资源');
  const actionFlip = scopeChanges(before, [rule('12', 'READ', true)]);
  assert.equal(actionFlip.modified.length, 0);
  assert.equal(actionFlip.removed.length, 1);
  assert.equal(actionFlip.added.length, 1);
});

test('同键代次变化报告 generation 旧→新，组合变化用分号连接且字符串ID精度保留', () => {
  const changes = scopeChanges([rule('9007199254740993', 'READ', false, '3')], [rule('9007199254740993', 'READ', true, '4')]);
  assert.equal(changes.modified.length, 1);
  assert.equal(scopeChangeText(changes.modified[0]!), 'generation 3 → 4；仅此资源 → 含子级');
  assert.match(scopeRuleText(changes.modified[0]!.after), /9007199254740993/);
  assert.equal(scopeKey(changes.modified[0]!.after), 'DATASOURCE:9007199254740993:READ');
});

test('空差异不产生任何分组，未变化行不计入修改', () => {
  const rows = [rule('7', 'META', true), rule('8', 'READ', false)];
  const changes = scopeChanges(
    rows,
    rows.map((row) => ({ ...row })),
  );
  assert.deepEqual(changes, { added: [], removed: [], modified: [] });
});

test('platform scopes: GET seed, stale PUT 412, fresh PUT bumps both versions', async () => {
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
    const read = await access.getPlatformScopes('1');
    assert.ok(read.items.length > 0, 'seed scope rule must exist for platform 1');
    assert.equal(read.version, '1');

    await assert.rejects(
      () => access.savePlatformScopes('1', read.items, '0'),
      (error: unknown) => (error as { status?: number }).status === 412,
    );

    const nextItems = [
      ...read.items,
      { resourceType: 'DATASOURCE', resourceId: '2', resourceGeneration: '1', action: 'META', includeDescendants: true },
    ];
    const saved = await access.savePlatformScopes('1', nextItems, read.version);
    assert.equal(saved.items.length, nextItems.length);
    assert.equal(saved.version, '2');
    assert.equal(saved.scopeVersion, '2');

    const reread = await access.getPlatformScopes('1');
    assert.deepEqual(reread.items.map(scopeKey), nextItems.map(scopeKey));
  } finally {
    globalThis.fetch = originalFetch;
  }
});
