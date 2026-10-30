import assert from 'node:assert/strict';
import test from 'node:test';
import { ME_HOME_PATH, filterNavGroups, firstAccessibleNavPath, resolveRoute, safeReturnTo, type PermissionedNavGroup } from '../src/navigation.ts';
import { ADMIN_ROUTE_PERMISSIONS } from '../src/navigation.ts';

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

test('firstAccessibleNavPath follows nav order and skips denied or path-less items', () => {
  const paths = { a: '/admin/a', b: '/admin/b', c: '/me/c', d: '/admin/d', noRoute: '' };
  const all = () => true;
  assert.equal(firstAccessibleNavPath(groups, paths, all), '/admin/a');
  assert.equal(firstAccessibleNavPath(groups, { ...paths, a: '' }, all), '/admin/b', 'item without a path is skipped');
  assert.equal(
    firstAccessibleNavPath(groups, paths, (code) => code === 'ptmcp:metadata:list'),
    '/admin/b',
  );
  assert.equal(
    firstAccessibleNavPath(groups, paths, () => false),
    '/me/c',
    'permission-free item is accessible',
  );
  assert.equal(firstAccessibleNavPath([], paths, all), null);
  assert.equal(ME_HOME_PATH, '/me/dictionary');
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

test('safeReturnTo only accepts in-app admin/me paths without control characters', () => {
  assert.equal(safeReturnTo('/admin/datasources/3'), '/admin/datasources/3');
  assert.equal(safeReturnTo('/me/tokens?tab=2'), '/me/tokens?tab=2');
  assert.equal(safeReturnTo('/me'), '/me');
  assert.equal(safeReturnTo('https://evil.example/phish'), '/');
  assert.equal(safeReturnTo('\\/admin'), '/');
  assert.equal(safeReturnTo('/admin/a\u0000b'), '/');
  assert.equal(safeReturnTo('/other'), '/');
  assert.equal(safeReturnTo('/admin/x', '/me/integrations'), '/admin/x');
});

test('admin detail routes resolve with read permissions', () => {
  for (const [basePath, perms] of Object.entries(ADMIN_ROUTE_PERMISSIONS)) {
    const routes = [{ path: basePath }, { path: basePath, prefix: true, permission: perms.read }];
    const list = resolveRoute(basePath, routes);
    assert.equal(list.match, 'exact');
    const detail = resolveRoute(`${basePath}/42`, routes);
    assert.equal(detail.match, 'prefix', `${basePath}/42 must resolve as a detail route`);
    assert.equal(detail.route?.permission, perms.read);
  }
});
