import assert from 'node:assert/strict';
import test from 'node:test';
import { deferred, FixtureDataError, flushPage, mountDataPage } from './helpers/mount-data-page.ts';

const source = {
  id: '12',
  code: 'synthetic',
  name: 'Source',
  engine: 'MYSQL',
  host: 'example.invalid',
  port: 3306,
  databaseName: 'catalog',
  username: 'reader',
  tlsMode: 'VERIFY_FULL',
  poolMax: 2,
  status: 'ACTIVE',
  configVersion: '1',
  resourceVersion: '1',
  activeSnapshotId: null,
  lastSyncAt: null,
  credentialConfigured: true,
  health: 'UNKNOWN',
};

test('a pending create validation prevents duplicate submissions', async () => {
  const validation = deferred<boolean>();
  let validations = 0;
  let creates = 0;
  const page = await mountDataPage('admin/data/DatasourcesView.vue', {
    listDatasources: async () => ({ data: { items: [], nextCursor: null }, etag: null }),
    createDatasource: async () => {
      creates += 1;
      return { data: source, etag: '"1"' };
    },
  });
  try {
    page.state.openCreate();
    page.state.formRef = {
      validate: () => {
        validations += 1;
        return validation.promise;
      },
    };
    const first = page.state.submitCreate();
    const second = page.state.submitCreate();
    assert.equal(validations, 1);
    validation.resolve(true);
    await Promise.all([first, second]);
    assert.equal(creates, 1);
  } finally {
    validation.resolve(true);
    page.close();
  }
});

test('metadata source responses cannot restore cleared resource state', async () => {
  const pendingSources = deferred<unknown>();
  const page = await mountDataPage('admin/metadata/MetadataView.vue', {
    listDatasources: () => pendingSources.promise,
    listMetadata: async () => ({ data: { items: [], nextCursor: null } }),
  });
  try {
    page.state.clearSensitive();
    pendingSources.resolve({ data: { items: [source], nextCursor: null } });
    await flushPage();
    assert.deepEqual(page.state.datasources, []);
  } finally {
    page.close();
  }
});

test('missing metadata snapshots are represented as pending sync rather than empty catalogues', async () => {
  const page = await mountDataPage('admin/metadata/MetadataView.vue', {
    listDatasources: async () => ({ data: { items: [source], nextCursor: null } }),
    listMetadata: async () => {
      throw new FixtureDataError(503, 'SCHEMA_STALE');
    },
  });
  try {
    assert.equal(page.state.awaitingSnapshot, true);
    assert.equal(page.state.nextCursor, null);
  } finally {
    page.close();
  }
});

test('metadata source selection can append the next cursor page', async () => {
  const cursors: unknown[] = [];
  const page = await mountDataPage('admin/metadata/MetadataView.vue', {
    listDatasources: async (params) => {
      const cursor = (params as { cursor: string | null }).cursor;
      cursors.push(cursor);
      return { data: { items: [{ ...source, id: cursor ? '13' : '12' }], nextCursor: cursor ? null : 'source-page-2' } };
    },
    listMetadata: async () => ({ data: { items: [], nextCursor: null } }),
  });
  try {
    page.state.moreSources();
    await flushPage();
    assert.deepEqual(cursors, [undefined, 'source-page-2']);
    assert.deepEqual(
      page.state.datasources.map((item) => (item as { id: string }).id),
      ['12', '13'],
    );
  } finally {
    page.close();
  }
});

test('stale metadata cursors restart at the first page without replaying a write', async () => {
  const cursors: unknown[] = [];
  const page = await mountDataPage('admin/metadata/MetadataView.vue', {
    listDatasources: async () => ({ data: { items: [source], nextCursor: null } }),
    listMetadata: async (params) => {
      const cursor = (params as { cursor: string | null }).cursor;
      cursors.push(cursor);
      if (cursor) throw new FixtureDataError(409, 'CURSOR_STALE');
      return { data: { items: [{ id: 'current-object' }], nextCursor: 'stale-next' } };
    },
  });
  try {
    page.state.nextPage();
    await flushPage();
    assert.deepEqual(cursors, [undefined, 'stale-next', undefined]);
    assert.deepEqual(page.state.rows, [{ id: 'current-object' }]);
  } finally {
    page.close();
  }
});

test('a datasource conflict preserves the draft, clears its credential and requires an explicit ETag adoption', async () => {
  let reads = 0;
  let writes = 0;
  const page = await mountDataPage('admin/data/DatasourceDetailView.vue', {
    getDatasource: async () => {
      reads += 1;
      return { data: { ...source, configVersion: String(reads) }, etag: `"${reads}"` };
    },
    updateDatasource: async () => {
      writes += 1;
      throw new FixtureDataError(409, 'VERSION_CONFLICT');
    },
  });
  try {
    page.state.formRef = { validate: async () => true };
    page.state.form.name = 'Local draft';
    page.state.form.credential = 'synthetic-secret';
    await page.state.save();
    assert.equal(reads, 2);
    assert.equal(writes, 1);
    assert.equal(page.state.etag, '"1"');
    assert.equal(page.state.form.name, 'Local draft');
    assert.equal(page.state.form.credential, '');
    assert.equal(page.state.conflictRequired, true);
    await page.state.save();
    assert.equal(writes, 1);
    page.state.resolveConflict(true);
    assert.equal(page.state.etag, '"2"');
    assert.equal(page.state.conflictRequired, false);
    assert.equal(page.state.form.name, 'Local draft');
    assert.equal(writes, 1);
  } finally {
    page.close();
  }
});

test('a late save response cannot clear a credential entered on another datasource route', async () => {
  const pendingWrite = deferred<unknown>();
  const page = await mountDataPage('admin/data/DatasourceDetailView.vue', {
    getDatasource: async (id) => ({ data: { ...source, id }, etag: '"1"' }),
    updateDatasource: () => pendingWrite.promise,
  });
  try {
    page.state.formRef = { validate: async () => true };
    const saving = page.state.save();
    await flushPage();
    page.route.params.id = '13';
    await flushPage();
    page.state.form.credential = 'new-route-synthetic-secret';
    pendingWrite.reject(new FixtureDataError(409, 'VERSION_CONFLICT'));
    await saving;
    assert.equal(page.state.datasource?.id, '13');
    assert.equal(page.state.form.credential, 'new-route-synthetic-secret');
    assert.equal(page.state.conflict, null);
  } finally {
    page.close();
  }
});

test('a late conflict read cannot replace the state of a new datasource route', async () => {
  const pendingConflict = deferred<unknown>();
  let reads = 0;
  const page = await mountDataPage('admin/data/DatasourceDetailView.vue', {
    getDatasource: async (id) => {
      reads += 1;
      if (reads === 2) return pendingConflict.promise;
      return { data: { ...source, id, configVersion: String(id) }, etag: `"${String(id)}"` };
    },
    updateDatasource: async () => {
      throw new FixtureDataError(409, 'VERSION_CONFLICT');
    },
  });
  try {
    page.state.formRef = { validate: async () => true };
    const saving = page.state.save();
    await flushPage();
    page.route.params.id = '13';
    await flushPage();
    pendingConflict.resolve({ data: { ...source, configVersion: '999' }, etag: '"999"' });
    await saving;
    assert.equal(page.state.datasource?.id, '13');
    assert.equal(page.state.etag, '"13"');
    assert.equal(page.state.conflict, null);
  } finally {
    page.close();
  }
});

test('dictionary conflict adoption retains removed-column drafts and blocks writes until explicitly handled', async () => {
  const column = {
    ordinal: 1,
    name: 'old',
    databaseType: 'int',
    jdbcType: 4,
    nullable: false,
    precision: 10,
    scale: 0,
    maxLength: null,
    comment: null,
    primaryKey: true,
    foreignKey: null,
  };
  const original = {
    id: '31',
    namespaceId: '22',
    databaseName: 'catalog',
    schemaName: null,
    objectName: 'synthetic',
    objectType: 'TABLE',
    tableComment: null,
    generation: '1',
    snapshotId: '15',
    snapshotVersion: '1',
    physicalIdentity: 'synthetic-id',
    columns: [column],
    dictionary: {
      title: 'Original',
      description: null,
      ownerUserId: null,
      version: '1',
      columns: [{ columnName: 'old', title: 'Old column', description: null }],
    },
  };
  let reads = 0;
  let writes = 0;
  const page = await mountDataPage('admin/metadata/MetadataDetailView.vue', {
    getMetadata: async () => {
      reads += 1;
      return reads === 1
        ? { data: original, etag: '"1"' }
        : {
            data: { ...original, columns: [{ ...column, name: 'new' }], dictionary: { ...original.dictionary, version: '2', columns: [] } },
            etag: '"2"',
          };
    },
    updateDictionary: async () => {
      writes += 1;
      throw new FixtureDataError(409, 'VERSION_CONFLICT');
    },
  });
  try {
    page.state.formRef = { validate: async () => true };
    page.state.form.title = 'Local title';
    const columns = page.state.form.columns as Array<{ description: string }>;
    columns[0]!.description = 'Preserve this draft';
    await page.state.save();
    assert.equal(page.state.etag, '"1"');
    assert.equal(page.state.form.title, 'Local title');
    page.state.resolveConflict(true);
    assert.equal(page.state.etag, '"2"');
    assert.equal(page.state.form.title, 'Local title');
    assert.deepEqual(page.state.removedDraft, [{ columnName: 'old', title: 'Old column', description: 'Preserve this draft' }]);
    await page.state.save();
    assert.equal(writes, 1);
    page.state.removedDraft = [];
    await page.state.save();
    assert.equal(writes, 2);
  } finally {
    page.close();
  }
});
