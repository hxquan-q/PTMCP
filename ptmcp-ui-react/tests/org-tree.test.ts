import assert from 'node:assert/strict';
import test from 'node:test';
import { buildDepartmentTree, departmentPath } from '../src/views/admin/orgTree.ts';

type Dept = { id: string; parentId: string | null; name: string };

const dept = (id: string, parentId: string | null, name: string): Dept => ({ id, parentId, name });

test('buildDepartmentTree nests children, sorts siblings by name and reports depth', () => {
  /* 名称用 ASCII：Node 的 small-icu 下中文排序依赖运行环境，测试不应绑定 ICU 数据。 */
  const tree = buildDepartmentTree([
    dept('b', null, 'Beta'),
    dept('a', null, 'Alpha'),
    dept('a2', 'a', 'Alpha Two'),
    dept('a1', 'a', 'Alpha One'),
    dept('a1x', 'a1', 'Alpha One A'),
  ]);
  assert.deepEqual(
    tree.map((node) => node.item.id),
    ['a', 'b'],
  );
  const data = tree[0];
  assert.equal(data.depth, 0);
  assert.deepEqual(
    data.children.map((node) => node.item.id),
    ['a1', 'a2'],
    'siblings sort by name',
  );
  assert.equal(data.children[0].depth, 1);
  assert.equal(data.children[0].children[0].item.id, 'a1x');
  assert.equal(data.children[0].children[0].depth, 2);
});

test('buildDepartmentTree keeps orphans and self-references at root', () => {
  const tree = buildDepartmentTree([dept('lost', 'missing', '孤立部门'), dept('self', 'self', '自引用部门')]);
  assert.equal(tree.length, 2);
  assert.deepEqual(
    tree.map((node) => node.depth),
    [0, 0],
  );
  assert.deepEqual(tree.map((node) => node.item.id).sort(), ['lost', 'self']);
});

test('buildDepartmentTree exposes every node in a pure cycle exactly once', () => {
  const tree = buildDepartmentTree([dept('x', 'y', '甲'), dept('y', 'x', '乙')]);
  const ids: string[] = [];
  const walk = (nodes: ReturnType<typeof buildDepartmentTree<Dept>>) => {
    for (const node of nodes) {
      ids.push(node.item.id);
      walk(node.children);
    }
  };
  walk(tree);
  assert.deepEqual(ids.sort(), ['x', 'y']);
});

test('departmentPath returns the root-to-node chain and stops on broken links', () => {
  const all = [dept('root', null, '总部'), dept('mid', 'root', '研发中心'), dept('leaf', 'mid', '平台组')];
  assert.deepEqual(departmentPath(all, 'leaf'), ['总部', '研发中心', '平台组']);
  assert.deepEqual(departmentPath(all, 'root'), ['总部']);
  assert.deepEqual(departmentPath(all, null), []);
  assert.deepEqual(departmentPath(all, 'unknown'), []);
  assert.deepEqual(departmentPath([dept('a', 'b', '甲'), dept('b', 'a', '乙')], 'a'), ['乙', '甲']);
});
