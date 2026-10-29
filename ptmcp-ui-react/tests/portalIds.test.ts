import assert from 'node:assert/strict';
import test from 'node:test';
import { normalizeMeResponse, normalizeOrgResponse } from '../src/services/portalIds.ts';

test('known DTO identities match select values while business JSON stays unchanged', () => {
  const roles = normalizeOrgResponse('/api/v1/admin/users', [{ id: 7, deptId: 9, roleIds: [3, '9007199254740993'], displayName: '工号 001' }]);
  assert.deepEqual(roles, [{ id: '7', deptId: '9', roleIds: ['3', '9007199254740993'], displayName: '工号 001' }]);
  const detail = normalizeMeResponse('/api/v1/me/history/a', { id: 'a', platformId: 4, resourceId: '9007199254740993' });
  assert.deepEqual(detail, { id: 'a', platformId: '4', resourceId: '9007199254740993' });
});

test('created tokens return the one-time secret plus normalized metadata', () => {
  const created = normalizeMeResponse('/api/v1/me/tokens', { token: 'synthetic-once-value', metadata: { id: 1, platformId: 4 } });
  assert.deepEqual(created, { token: 'synthetic-once-value', metadata: { id: '1', platformId: '4' } });
});

test('non-target paths and non-object payloads pass through untouched', () => {
  assert.deepEqual(normalizeOrgResponse('/api/v1/admin/unknown', [{ id: 1 }]), [{ id: 1 }]);
  assert.equal(normalizeOrgResponse('/api/v1/admin/users', null), null);
  assert.deepEqual(normalizeMeResponse('/api/v1/me/unknown', { id: 3 }), { id: 3 });
});
