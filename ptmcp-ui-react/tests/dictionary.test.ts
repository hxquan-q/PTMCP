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
const data = await import('../src/services/data.ts');
const conflict = await import('../src/adapters/edit-conflict.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
const { createPreviewServer } = await import('../src/preview/server.ts');
sourceImports.deregister();

const { dictionaryDraft, dictionaryDifferences, rebaseDictionaryDraft } = conflict;

async function withPreviewServer(fn: () => Promise<void>): Promise<void> {
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
    await fn();
  } finally {
    globalThis.fetch = originalFetch;
  }
}

async function firstObjectId(): Promise<string> {
  const { data: page } = await data.listMetadata({ datasourceId: '1', pageSize: 100 });
  return page.items[0]!.id;
}

test('dictionary edit persists via If-Match and stale versions are rejected with 412', async () => {
  await withPreviewServer(async () => {
    const id = await firstObjectId();
    const { data: before, etag } = await data.getMetadataObject(id);
    assert.ok(etag);
    const draft = dictionaryDraft(before);
    draft.title = '订单事实表';
    draft.description = '按日汇总的订单事实数据';
    draft.ownerUserId = '2';
    const column = draft.columns[0]!;
    column.title = '订单号';
    column.description = '业务主键';

    await assert.rejects(
      () => data.updateDictionary(id, draft, '"0"'),
      (error: unknown) => (error as { status?: number }).status === 412,
    );

    const { data: after, etag: nextEtag } = await data.updateDictionary(id, draft, etag!);
    assert.equal(after.dictionary?.title, '订单事实表');
    assert.equal(after.dictionary?.columns?.[0]?.displayName, '订单号');
    assert.ok(nextEtag && nextEtag !== etag, 'dictionary version must advance');

    // 旧 etag 再写必须继续 412
    await assert.rejects(
      () => data.updateDictionary(id, draft, etag!),
      (error: unknown) => (error as { status?: number }).status === 412,
    );
  });
});

test('dictionaryDifferences flags server changes, draft changes and structural drift', () => {
  const base = {
    id: '1',
    datasourceId: '1',
    namespaceId: '1',
    databaseName: 'synthetic',
    schemaName: 'public',
    objectName: 't1',
    tableName: 't1',
    objectType: 'TABLE',
    generation: '3',
    snapshotVersion: '6',
    columns: [
      { ordinal: 1, name: 'id', databaseType: 'BIGINT', nullable: false, primaryKey: true },
      { ordinal: 2, name: 'amount', databaseType: 'DECIMAL', nullable: true },
    ],
    dictionary: { title: '旧标题', description: '', ownerUserId: null, columns: [{ name: 'id', displayName: '编号' }] },
  };
  const latest = JSON.parse(JSON.stringify(base)) as typeof base;
  latest.generation = '4';
  latest.columns = [base.columns[0]!]; // amount 被服务端删除
  latest.dictionary = { ...latest.dictionary, title: '服务端新标题' };
  const draft = dictionaryDraft(base);
  draft.title = '我的标题';
  draft.columns[0]!.title = '编号（本地改）';

  const rows = dictionaryDifferences(base, latest, draft);
  const byLabel = new Map(rows.map((r) => [r.label, r]));
  assert.equal(byLabel.get('业务标题')?.serverChanged, true);
  assert.equal(byLabel.get('业务标题')?.draftChanged, true);
  assert.equal(byLabel.get('资源代次（只读）')?.serverChanged, true);
  const structural = rows.find((r) => r.key === 'structure:amount');
  assert.ok(structural, 'removed column structure must surface');
  const columnTitle = rows.find((r) => r.key === 'column:["id","title"]');
  assert.ok(columnTitle);
  assert.equal(columnTitle.draftChanged, true);
});

test('rebaseDictionaryDraft keeps annotations for surviving columns and reports removed ones', () => {
  const base = {
    id: '1',
    datasourceId: '1',
    namespaceId: '1',
    databaseName: 'synthetic',
    schemaName: null,
    objectName: 't1',
    tableName: 't1',
    objectType: 'TABLE',
    generation: '3',
    columns: [
      { ordinal: 1, name: 'id', databaseType: 'BIGINT', nullable: false },
      { ordinal: 2, name: 'memo', databaseType: 'TEXT', nullable: true },
    ],
    dictionary: {},
  };
  const latest = JSON.parse(JSON.stringify(base)) as typeof base;
  latest.columns = [base.columns[0]!]; // memo 消失
  const draft = dictionaryDraft(base);
  draft.columns[0]!.title = '编号';
  draft.columns[1]!.description = '备忘';

  const { draft: rebased, removed } = rebaseDictionaryDraft(draft, latest);
  assert.equal(rebased.columns.length, 1);
  assert.equal(rebased.columns[0]!.title, '编号');
  assert.deepEqual(
    removed.map((c) => c.columnName),
    ['memo'],
  );
  assert.equal(removed[0]!.description, '备忘', 'removed annotations are returned, never silently dropped');
});
