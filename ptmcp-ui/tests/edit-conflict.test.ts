import assert from 'node:assert/strict';
import test from 'node:test';
import {
  datasourceDifferences,
  dictionaryDifferences,
  dictionaryDraft,
  isVersionConflict,
  rebaseDictionaryDraft,
} from '../src/adapters/edit-conflict.ts';
import type { DatasourceRead, MetadataDetail } from '../src/services/data.ts';

const source: DatasourceRead = {
  id: '1',
  code: 'synthetic',
  name: 'Source',
  engine: 'MYSQL',
  host: 'old.invalid',
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
const detail: MetadataDetail = {
  id: '31',
  namespaceId: '22',
  databaseName: 'catalog',
  schemaName: null,
  objectName: 'table',
  objectType: 'TABLE',
  tableComment: null,
  generation: '1',
  snapshotId: '15',
  snapshotVersion: '1',
  physicalIdentity: 'identity',
  columns: [
    {
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
    },
  ],
  dictionary: {
    title: 'Original title',
    description: null,
    ownerUserId: '9223372036854775807',
    version: '1',
    columns: [{ columnName: 'old', title: 'Original column', description: null }],
  },
};

test('source comparison separates server and draft changes and never retains a credential', () => {
  const latest = { ...source, host: 'new.invalid', poolMax: 4, configVersion: '2' };
  const draft = { ...source, name: 'Local name', poolMax: 3, credential: 'synthetic-secret', tlsMode: 'VERIFY_FULL' as const };
  const rows = datasourceDifferences(source, latest, draft);
  assert.deepEqual(
    rows.map((row) => row.key),
    ['name', 'host', 'poolMax'],
  );
  assert.deepEqual(
    rows.find((row) => row.key === 'host'),
    { key: 'host', label: '主机', base: 'old.invalid', latest: 'new.invalid', draft: 'old.invalid', serverChanged: true, draftChanged: false },
  );
  assert.equal(rows.find((row) => row.key === 'poolMax')?.draftChanged, true);
  assert.equal(JSON.stringify(rows).includes('synthetic-secret'), false);
});

test('dictionary differences include structure, server annotations and local edits with precise owner IDs', () => {
  const latest = {
    ...detail,
    snapshotVersion: '2',
    columns: [{ ...detail.columns[0]!, databaseType: 'bigint' }],
    dictionary: { ...detail.dictionary, title: 'Server title', ownerUserId: null, version: '2' },
  };
  const draft = { ...dictionaryDraft(detail), title: 'Draft title' };
  const rows = dictionaryDifferences(detail, latest, draft);
  assert.equal(rows.find((row) => row.key === 'ownerUserId')?.base, '9223372036854775807');
  assert.equal(rows.find((row) => row.key === 'title')?.latest, 'Server title');
  assert.equal(rows.find((row) => row.key === 'title')?.draft, 'Draft title');
  assert.equal(rows.find((row) => row.key === 'snapshotVersion')?.latest, '2');
  assert.match(rows.find((row) => row.key === 'structure:old')?.latest ?? '', /bigint/);
});

test('adopting latest structure keeps editable drafts and preserves removed annotations for explicit handling', () => {
  const draft = dictionaryDraft(detail);
  draft.title = 'Draft title';
  draft.columns[0]!.description = 'Keep this text';
  const latest = {
    ...detail,
    columns: [{ ...detail.columns[0]!, name: 'new' }],
    dictionary: { ...detail.dictionary, columns: [{ columnName: 'new', title: 'Server column', description: null }] },
  };
  const result = rebaseDictionaryDraft(draft, latest);
  assert.equal(result.draft.title, 'Draft title');
  assert.deepEqual(result.draft.columns, [{ columnName: 'new', title: 'Server column', description: '' }]);
  assert.deepEqual(result.removed, [{ columnName: 'old', title: 'Original column', description: 'Keep this text' }]);
  assert.equal(draft.columns[0]?.columnName, 'old');
  const unchanged = rebaseDictionaryDraft(draft, detail);
  assert.deepEqual(unchanged.draft.columns, draft.columns);
  assert.deepEqual(unchanged.removed, []);
});

test('dependency and idempotency conflicts do not enter the version-merge flow', () => {
  assert.equal(isVersionConflict({ status: 409, code: 'VERSION_CONFLICT' }), true);
  assert.equal(isVersionConflict({ status: 412 }), true);
  assert.equal(isVersionConflict({ status: 409, code: 'REFERENCES_EXIST' }), false);
  assert.equal(isVersionConflict({ status: 409, code: 'IDEMPOTENCY_CONFLICT' }), false);
});

test('an actual empty-marker title remains distinct from a missing annotation', () => {
  const base = { ...detail, dictionary: { ...detail.dictionary, title: '（空）' } };
  const latest = { ...base, dictionary: { ...base.dictionary, title: null } };
  assert.equal(dictionaryDifferences(base, latest, dictionaryDraft(base)).find((row) => row.key === 'title')?.serverChanged, true);
});
