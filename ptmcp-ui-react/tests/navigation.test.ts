import assert from 'node:assert/strict';
import test from 'node:test';
import { ME_HOME_PATH, firstAccessibleNavPath, resolveRoute, routeAllowed, safeReturnTo, type AdminNavGroup } from '../src/navigation.ts';

const groups: AdminNavGroup[] = [
  {
    title: '数据管理',
    items: [
      { key: 'a', label: 'A' },
      { key: 'b', label: 'B' },
    ],
  },
  { title: '个人', items: [{ key: 'c', label: 'C' }] },
];

test('firstAccessibleNavPath follows nav order for admins and falls back to ME_HOME for users', () => {
  const paths = { a: '/admin/a', b: '/admin/b', c: '/me/c' };
  assert.equal(firstAccessibleNavPath(groups, paths, true), '/admin/a');
  assert.equal(firstAccessibleNavPath(groups, { ...paths, a: '' }, true), '/admin/b', 'item without a path is skipped');
  assert.equal(firstAccessibleNavPath(groups, paths, false), ME_HOME_PATH, 'non-admin never lands in the console');
  assert.equal(firstAccessibleNavPath([], paths, true), ME_HOME_PATH, 'empty nav falls back to ME_HOME');
  assert.equal(ME_HOME_PATH, '/me/dictionary');
});

test('routeAllowed gates every console route on the platformAdmin boolean alone', () => {
  const route = { path: '/admin/roles' };
  assert.equal(routeAllowed(route, true), true);
  assert.equal(routeAllowed(route, false), false);
  assert.equal(routeAllowed({ path: '/me/tokens' }, false), false, 'routeAllowed is console-scoped; App decides per realm');
});

test('resolveRoute prefers exact, then prefix with decoded id, else none', () => {
  const routes = [{ path: '/me/tokens' }, { path: '/admin/audit' }, { path: '/admin/audit', prefix: true }];
  assert.equal(resolveRoute('/me/tokens', routes).match, 'exact');
  assert.equal(resolveRoute('/admin/audit', routes).match, 'exact');
  const prefixed = resolveRoute('/admin/audit/req%20x', routes);
  assert.equal(prefixed.match, 'prefix');
  assert.equal(prefixed.match === 'prefix' ? prefixed.id : '', 'req x');
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
