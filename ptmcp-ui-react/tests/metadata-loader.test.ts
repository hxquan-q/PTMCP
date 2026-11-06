import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

const sourceImports = registerHooks({
  resolve(specifier: string, context: { parentURL?: string }, nextResolve: (s: string, c: unknown) => unknown) {
    const extensionlessSource = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionlessSource ? `${specifier}.ts` : specifier, context);
  },
});
const { listAllMetadata } = await import('../src/views/admin/permissions/metadataLoader.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
sourceImports.deregister();

function item(id: string) {
  return {
    id,
    namespaceId: '3',
    databaseName: 'app',
    schemaName: '',
    objectName: `t${id}`,
    objectType: 'TABLE',
    tableComment: '',
    generation: '1',
    snapshotId: '2',
    snapshotVersion: '9',
  };
}

async function withPages(pages: (cursor: string | null) => { items: unknown[]; nextCursor: string | null }, run: (urls: URL[]) => Promise<void>) {
  const originalFetch = globalThis.fetch;
  const urls: URL[] = [];
  setCsrfToken('test-csrf');
  globalThis.fetch = (async (input: RequestInfo | URL) => {
    const url = new URL(String(input), 'http://ui.test');
    urls.push(url);
    return new Response(JSON.stringify(pages(url.searchParams.get('cursor'))), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    });
  }) as typeof fetch;
  try {
    await run(urls);
  } finally {
    globalThis.fetch = originalFetch;
  }
}

/* 回归：资源树曾以 pageSize=200 请求，超出后端 MAX_PAGE=100 整页 400，且只看得到首页。 */
test('resource tree metadata stays within the backend page limit and follows the cursor to the end', async () => {
  await withPages(
    (cursor) => (cursor === null ? { items: [item('1'), item('2')], nextCursor: 'c1' } : { items: [item('3')], nextCursor: null }),
    async (urls) => {
      const items = await listAllMetadata('5', new AbortController().signal);
      assert.deepEqual(
        items.map((o) => o.id),
        ['1', '2', '3'],
      );
      assert.equal(urls.length, 2);
      for (const url of urls) {
        assert.equal(url.searchParams.get('datasourceId'), '5');
        assert.ok(Number(url.searchParams.get('pageSize')) <= 100);
      }
      assert.equal(urls[1]?.searchParams.get('cursor'), 'c1');
    },
  );
});

test('a cursor that never terminates fails instead of looping forever', async () => {
  await withPages(
    () => ({ items: [], nextCursor: 'again' }),
    async () => {
      await assert.rejects(() => listAllMetadata('5', new AbortController().signal), /did not terminate/);
    },
  );
});
