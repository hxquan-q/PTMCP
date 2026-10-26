import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

// Vite resolves extensionless source imports; mirror that only while loading these TS modules.
const sourceImports = registerHooks({
  resolve(specifier, context, nextResolve) {
    const extensionlessSource = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionlessSource ? `${specifier}.ts` : specifier, context);
  },
});
const api = await import('../src/services/data.ts');
const { setCsrfToken } = await import('../src/services/http.ts');
sourceImports.deregister();

const datasource = {
  id: 12,
  version: 2,
  code: 'synthetic',
  name: 'Synthetic source',
  engine: 'POSTGRESQL',
  host: 'example.invalid',
  port: 5432,
  databaseName: 'catalog',
  username: 'reader',
  tlsMode: 'VERIFY_FULL',
  poolMax: 3,
  status: 'ACTIVE',
  configVersion: 2,
  resourceVersion: 7,
  activeSnapshotId: 15,
  lastSyncAt: null,
  credentialConfigured: true,
  health: 'UNKNOWN',
};
const draft = { ...datasource, credential: '', tlsMode: 'VERIFY_FULL' as const };
const job = {
  id: 8,
  datasourceId: 12,
  snapshotId: null,
  type: 'SYNC',
  state: 'RUNNING',
  progress: { processed: 2, total: null },
  startedAt: null,
  finishedAt: null,
  errorCode: null,
};
const foreignKey = { id: 99, nested: { generation: 4 }, rawColumn: '123' };
const metadata = {
  id: 31,
  namespaceId: 22,
  databaseName: 'catalog',
  schemaName: 'public',
  objectName: 'synthetic_table',
  objectType: 'TABLE',
  tableComment: null,
  generation: 3,
  snapshotId: 15,
  snapshotVersion: 6,
};
const detail = {
  ...metadata,
  physicalIdentity: 'synthetic-identity',
  columns: [
    {
      ordinal: 1,
      name: 'id',
      databaseType: 'bigint',
      jdbcType: -5,
      nullable: false,
      precision: 19,
      scale: 0,
      maxLength: null,
      comment: null,
      primaryKey: true,
      foreignKey,
    },
  ],
  dictionary: { title: 'Table', description: null, ownerUserId: 44, version: 5, columns: [{ columnName: 'id', title: 'ID', description: null }] },
};

async function withResponse<T>(body: unknown, action: () => Promise<T>): Promise<T> {
  const originalFetch = globalThis.fetch;
  setCsrfToken('synthetic-csrf');
  globalThis.fetch = async () => new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json', ETag: '"7"' } });
  try {
    return await action();
  } finally {
    globalThis.fetch = originalFetch;
    setCsrfToken(null);
  }
}

test('datasource list and writes normalize only known identifiers and retain ETag', async () => {
  const result = await withResponse({ items: [datasource], nextCursor: 'opaque-12' }, () => api.listDatasources());
  assert.equal(result.etag, '"7"');
  assert.equal(result.data.nextCursor, 'opaque-12');
  for (const invoke of [() => api.getDatasource('12'), () => api.createDatasource(draft), () => api.updateDatasource('12', draft, '"2"')]) {
    const { data } = await withResponse(datasource, invoke);
    assert.deepEqual([data.id, data.version, data.configVersion, data.resourceVersion, data.activeSnapshotId], ['12', '2', '2', '7', '15']);
    assert.equal(data.port, 5432);
    assert.equal(data.poolMax, 3);
  }
  assert.equal(result.data.items[0]?.id, '12');
  const { data: removed } = await withResponse({ deleted: true, id: 12, version: 3 }, () => api.deleteDatasource('12', '"2"'));
  assert.deepEqual(removed, { deleted: true, id: '12', version: '3' });
});

test('test, sync and job reads normalize identities while keeping progress numeric and nullable snapshot', async () => {
  for (const invoke of [() => api.testDatasource('12', draft), () => api.syncDatasource('12'), () => api.getJob('8')]) {
    const { data } = await withResponse(job, invoke);
    assert.deepEqual([data.id, data.datasourceId, data.snapshotId], ['8', '12', null]);
    assert.deepEqual(data.progress, { processed: 2, total: null });
  }
  const completed = await withResponse({ ...job, snapshotId: 15 }, () => api.getJob('8'));
  assert.equal(completed.data.snapshotId, '15');
});

test('metadata list, detail and dictionary writes normalize declared identities without walking column JSON', async () => {
  const page = await withResponse({ items: [metadata], nextCursor: null }, () => api.listMetadata({ datasourceId: '12' }));
  assert.deepEqual(
    [
      page.data.items[0]?.id,
      page.data.items[0]?.namespaceId,
      page.data.items[0]?.generation,
      page.data.items[0]?.snapshotId,
      page.data.items[0]?.snapshotVersion,
    ],
    ['31', '22', '3', '15', '6'],
  );
  const dictionary = { title: 'Table', description: '', ownerUserId: '44', columns: [] };
  for (const invoke of [() => api.getMetadata('31'), () => api.updateDictionary('31', dictionary, '"5"')]) {
    const { data } = await withResponse(detail, invoke);
    assert.deepEqual(
      [data.id, data.namespaceId, data.generation, data.snapshotId, data.snapshotVersion, data.dictionary.ownerUserId, data.dictionary.version],
      ['31', '22', '3', '15', '6', '44', '5'],
    );
    assert.equal(data.columns[0]?.ordinal, 1);
    assert.deepEqual(data.columns[0]?.foreignKey, foreignKey);
  }
});

test('string BIGINTs and nullable identifiers survive intact', async () => {
  const { data } = await withResponse(
    { ...detail, id: '9223372036854775807', dictionary: { ...detail.dictionary, ownerUserId: null, version: '9007199254740993' } },
    () => api.getMetadata('9223372036854775807'),
  );
  assert.equal(data.id, '9223372036854775807');
  assert.equal(data.dictionary.version, '9007199254740993');
  assert.equal(data.dictionary.ownerUserId, null);
  const source = await withResponse({ ...datasource, version: undefined, activeSnapshotId: null }, () => api.getDatasource('12'));
  assert.equal(source.data.version, undefined);
  assert.equal(source.data.activeSnapshotId, null);
});

test('an unsafe numeric identifier is rejected instead of returning a rounded identity', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response(JSON.stringify(datasource).replace('"id":12', '"id":9.007199254740993e15'), { headers: { 'Content-Type': 'application/json' } });
  try {
    await assert.rejects(() => api.getDatasource('12'));
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('numeric BIGINT literals retain their original digits through transport and the DTO adapter', async () => {
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async () =>
    new Response(JSON.stringify(datasource).replace('"id":12', '"id":9223372036854775807'), { headers: { 'Content-Type': 'application/json' } });
  try {
    assert.equal((await api.getDatasource('9223372036854775807')).data.id, '9223372036854775807');
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test('a version conflict is returned to the editor without replacing If-Match or replaying the write', async () => {
  const originalFetch = globalThis.fetch;
  const calls: Array<{ method: string | undefined; version: string | null }> = [];
  setCsrfToken('synthetic-csrf');
  globalThis.fetch = async (_input, init) => {
    calls.push({ method: init?.method, version: new Headers(init?.headers).get('If-Match') });
    return new Response(JSON.stringify({ code: 'VERSION_CONFLICT' }), { status: 409, headers: { ETag: '"3"' } });
  };
  try {
    await assert.rejects(
      () => api.updateDatasource('12', draft, '"2"'),
      (error: unknown) => error instanceof api.DataRequestError && error.status === 409 && error.code === 'VERSION_CONFLICT',
    );
    assert.deepEqual(calls, [{ method: 'PUT', version: '"2"' }]);
  } finally {
    globalThis.fetch = originalFetch;
    setCsrfToken(null);
  }
});
