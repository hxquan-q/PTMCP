import assert from 'node:assert/strict';
import test from 'node:test';
import { datasourceDifferences, isVersionConflict } from '../src/adapters/edit-conflict.ts';
import type { DatasourceRead, DatasourceWrite } from '../src/services/data.ts';

const base: DatasourceRead = {
  id: '1',
  code: 'preview-mysql',
  name: '合成订单库',
  engine: 'MYSQL',
  host: 'db.example.test',
  port: 3306,
  databaseName: 'synthetic',
  username: 'reader',
  tlsMode: 'VERIFY_FULL',
  poolMax: 2,
  status: 'ACTIVE',
  activeSnapshotId: '1',
  lastSyncAt: null,
  credentialConfigured: true,
  health: 'HEALTHY',
};

const draftOf = (over: Partial<DatasourceWrite>): DatasourceWrite => ({
  code: base.code,
  name: base.name,
  engine: base.engine,
  host: base.host,
  port: base.port,
  databaseName: base.databaseName,
  username: base.username,
  credential: '',
  tlsMode: 'VERIFY_FULL',
  poolMax: base.poolMax,
  status: base.status,
  ...over,
});

const latestOf = (over: Partial<DatasourceRead>): DatasourceRead => ({ ...base, ...over });

test('datasourceDifferences reports server-only, draft-only and both-changed fields', () => {
  const draft = draftOf({ name: '我的改名', host: 'new-host.internal' });
  const latest = latestOf({ name: '服务端改名', poolMax: 10 });

  const rows = datasourceDifferences(base, latest, draft);
  const byKey = new Map(rows.map((r) => [r.key, r]));

  // name: both changed
  const name = byKey.get('name');
  assert.ok(name, 'name must appear when both sides changed');
  assert.equal(name.serverChanged, true);
  assert.equal(name.draftChanged, true);
  assert.equal(name.base, '合成订单库');
  assert.equal(name.latest, '服务端改名');
  assert.equal(name.draft, '我的改名');

  // host: draft-only change
  const host = byKey.get('host');
  assert.ok(host, 'host must appear when only draft changed');
  assert.equal(host.serverChanged, false);
  assert.equal(host.draftChanged, true);

  // poolMax: server-only change
  const pool = byKey.get('poolMax');
  assert.ok(pool, 'poolMax must appear when only server changed');
  assert.equal(pool.serverChanged, true);
  assert.equal(pool.draftChanged, false);
  assert.equal(pool.draft, '2', 'unchanged draft field echoes the base value');

  // unchanged fields are omitted
  assert.ok(!byKey.has('port'), 'unchanged port must not appear');
  assert.ok(!byKey.has('databaseName'), 'unchanged databaseName must not appear');
  assert.ok(!byKey.has('username'), 'unchanged username must not appear');
  assert.ok(!byKey.has('tlsMode'), 'unchanged tlsMode must not appear');
  assert.ok(!byKey.has('status'), 'unchanged status must not appear');
});

test('datasourceDifferences returns empty for identical state (no false-positive conflicts)', () => {
  const noChange = datasourceDifferences(base, base, draftOf({}));
  assert.deepEqual(noChange, []);
});

test('isVersionConflict accepts only 412 + VERSION_CONFLICT, not other 4xx', () => {
  assert.equal(isVersionConflict({ status: 412, code: 'VERSION_CONFLICT' }), true);
  assert.equal(isVersionConflict({ status: 412, code: 'OTHER_CODE' }), false);
  assert.equal(isVersionConflict({ status: 409, code: 'VERSION_CONFLICT' }), false);
  assert.equal(isVersionConflict({ status: 400, code: 'VERSION_CONFLICT' }), false);
  assert.equal(isVersionConflict({}), false);
  assert.equal(isVersionConflict({ status: undefined, code: undefined }), false);
});
