import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

const sourceImports = registerHooks({
  resolve(specifier: string, context: { parentURL?: string }, nextResolve: (s: string, c: unknown) => unknown) {
    const extensionlessSource = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionlessSource ? `${specifier}.ts` : specifier, context);
  },
});
const data = await import('../src/services/data.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
sourceImports.deregister();

test('metadata list accepts the admin summary shape without datasourceId', async () => {
  const originalFetch = globalThis.fetch;
  setCsrfToken('test-csrf');
  globalThis.fetch = (async () =>
    new Response(
      JSON.stringify({
        items: [
          {
            id: '7',
            namespaceId: '3',
            databaseName: 'app',
            schemaName: '',
            objectName: 'orders',
            objectType: 'TABLE',
            tableComment: '',
            generation: '4',
            snapshotId: '2',
            snapshotVersion: '9',
          },
        ],
        nextCursor: null,
      }),
      { status: 200, headers: { 'Content-Type': 'application/json' } },
    )) as typeof fetch;
  try {
    const { data: page } = await data.listMetadata({ datasourceId: '1', pageSize: 20 });
    assert.equal(page.items.length, 1);
    assert.equal(page.items[0]?.id, '7');
    assert.equal(page.items[0]?.namespaceId, '3');
    assert.equal(page.items[0]?.objectName, 'orders');
    assert.equal(page.items[0]?.datasourceId, undefined);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
