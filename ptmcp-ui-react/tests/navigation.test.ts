import assert from 'node:assert/strict';
import test from 'node:test';
import { filterNavGroups, resolveRoute, type PermissionedNavGroup } from '../src/navigation.ts';

const groups: PermissionedNavGroup[] = [
  {
    title: '数据管理',
    items: [
      { key: 'a', label: 'A', permission: 'ptmcp:datasource:list' },
      { key: 'b', label: 'B', permission: 'ptmcp:metadata:list' },
    ],
  },
  { title: '个人', items: [{ key: 'c', label: 'C' }] },
  { title: '空组将消失', items: [{ key: 'd', label: 'D', permission: 'ptmcp:none:list' }] },
];

test('filterNavGroups removes denied items, drops empty groups, keeps permission-free items', () => {
  const all = () => true;
  assert.deepEqual(
    filterNavGroups(groups, all).map((g) => g.items.map((i) => i.key)),
    [['a', 'b'], ['c'], ['d']],
  );

  const onlyDatasource = (code: string) => code === 'ptmcp:datasource:list';
  const filtered = filterNavGroups(groups, onlyDatasource);
  assert.deepEqual(
    filtered.map((g) => g.items.map((i) => i.key)),
    [['a'], ['c']],
  );
  assert.equal(filtered.length, 2, 'group with all items denied must disappear');

  const none = () => false;
  assert.deepEqual(
    filterNavGroups(groups, none).map((g) => g.items.map((i) => i.key)),
    [['c']],
  );
});

test('resolveRoute prefers exact, then prefix with decoded id, else none', () => {
  const routes = [{ path: '/me/tokens' }, { path: '/admin/audit' }, { path: '/admin/audit', prefix: true, permission: 'ptmcp:audit:read' }];
  assert.equal(resolveRoute('/me/tokens', routes).match, 'exact');
  assert.equal(resolveRoute('/admin/audit', routes).match, 'exact');
  const prefixed = resolveRoute('/admin/audit/req%20x', routes);
  assert.equal(prefixed.match, 'prefix');
  assert.equal(prefixed.match === 'prefix' ? prefixed.id : '', 'req x');
  assert.equal(prefixed.route?.permission, 'ptmcp:audit:read');
  assert.equal(resolveRoute('/no/such/path', routes).match, 'none');
  // 前缀必须整段匹配：/admin/auditory 不应命中 /admin/audit 前缀
  assert.equal(resolveRoute('/admin/auditory', routes).match, 'none');
});
