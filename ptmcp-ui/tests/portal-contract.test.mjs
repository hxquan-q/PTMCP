import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeMeResponse, normalizeOrgResponse } from '../src/services/portalIds.ts';
import { PortalRequest } from '../src/services/portalRequest.ts';
import { PortalWriteIntent, departmentDescendants, userChangePlan } from '../src/services/portalMutations.ts';

test('known DTO identities match select values while business JSON stays unchanged', () => {
  const roles = normalizeOrgResponse('/api/v1/admin/users', [{ id: 7, deptId: 9, roleIds: [3, '9007199254740993'], displayName: '工号 001' }]);
  assert.deepEqual(roles, [{ id: '7', deptId: '9', roleIds: ['3', '9007199254740993'], displayName: '工号 001' }]);
  const detail = normalizeMeResponse('/api/v1/me/history/a', {
    platformId: 5,
    datasourceId: null,
    resourceRefs: { id: 123, value: 12.3 },
    sqlMasked: 'id = ?',
  });
  assert.deepEqual(detail, { platformId: '5', datasourceId: null, resourceRefs: { id: 123, value: 12.3 }, sqlMasked: 'id = ?' });
  const created = normalizeMeResponse('/api/v1/me/tokens', { token: 'synthetic-once-value', metadata: { id: 1, platformId: 4 } });
  assert.deepEqual(created, { token: 'synthetic-once-value', metadata: { id: '1', platformId: '4' } });
});

test('switching context aborts the former read and ignores a late response', async () => {
  const task = new PortalRequest();
  let oldSignal;
  let resolveOld;
  const old = task.run((signal) => {
    oldSignal = signal;
    return new Promise((resolve) => {
      resolveOld = resolve;
    });
  });
  const current = await task.run(async () => 'new-platform-data');
  assert.equal(oldSignal.aborted, true);
  resolveOld('old-platform-data');
  assert.equal(await old, undefined);
  assert.equal(current, 'new-platform-data');
});

test('clearing or leaving a page prevents pending results and future requests', async () => {
  const task = new PortalRequest();
  let resolvePending;
  const pending = task.run(
    () =>
      new Promise((resolve) => {
        resolvePending = resolve;
      }),
  );
  task.dispose();
  resolvePending({ name: 'stale-private-data' });
  assert.equal(await pending, undefined);
  let called = false;
  await task.run(async () => {
    called = true;
    return 'should-not-run';
  });
  assert.equal(called, false);
});

test('PAT creation allows one pending write and rejects plaintext after account invalidation', () => {
  const intent = new PortalWriteIntent();
  const current = intent.begin();
  assert.equal(current, 0);
  assert.equal(intent.begin(), null, 'a double click must not start a second create request');
  assert.equal(intent.accepts(current), true);
  intent.dispose();
  assert.equal(intent.accepts(current), false, 'a late create response must never show plaintext after clear');
  assert.equal(intent.begin(), null, 'a disposed page must not start another write');
});

test('a profile permission cannot change roles, department, or status indirectly', () => {
  const user = { displayName: 'Alice', deptId: '2', roleIds: ['7'], enabled: true };
  const profileOnly = (permission) => permission === 'ptmcp:user:update';
  assert.deepEqual(userChangePlan(user, { ...user, displayName: 'Alice A' }, profileOnly, true), ['profile']);
  assert.throws(() => userChangePlan(user, { ...user, roleIds: ['8'] }, profileOnly, true), /角色/);
  assert.throws(() => userChangePlan(user, { ...user, deptId: '3' }, profileOnly, true), /部门/);
  assert.throws(() => userChangePlan(user, { ...user, enabled: false }, profileOnly, true), /状态/);
  assert.throws(() => userChangePlan(user, { ...user, roleIds: [] }, () => true, false), /加载失败/);
  assert.deepEqual(
    userChangePlan(user, { ...user, roleIds: ['7'] }, () => false, false),
    [],
  );
});

test('department parent candidates exclude descendants at every depth', () => {
  const departments = [
    { id: 'a', parentId: null },
    { id: 'b', parentId: 'a' },
    { id: 'c', parentId: 'b' },
    { id: 'd', parentId: null },
  ];
  assert.deepEqual([...departmentDescendants(departments, 'a')].sort(), ['a', 'b', 'c']);
  assert.equal(departmentDescendants(departments, 'b').has('a'), false);
});

test('委派响应标识规范化：数字id/版本转字符串，撤销查找不再静默失配', async () => {
  const { normalizeOrgResponse } = await import('../src/services/portalIds.ts');
  const page = normalizeOrgResponse('/api/v1/admin/delegations', {
    items: [
      { id: 2, userId: 7, departmentRootId: 1, resourceType: 'DATASOURCE', resourceId: 1, resourceGeneration: 3, version: 1, status: 'ACTIVE' },
    ],
    nextCursor: null,
  });
  const row = page.items[0];
  assert.equal(row.id, '2');
  assert.equal(row.userId, '7');
  assert.equal(row.version, '1');
  assert.ok(page.items.some((item) => String(item.id) === String('2')));
  const single = normalizeOrgResponse('/api/v1/admin/delegations', { id: 9, userId: 2, departmentRootId: 1, resourceId: 1, version: 4 });
  assert.equal(single.id, '9');
  assert.equal(single.version, '4');
  assert.equal(row.resourceType, 'DATASOURCE');
});
