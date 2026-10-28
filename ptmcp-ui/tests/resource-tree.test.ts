import assert from 'node:assert/strict';
import test from 'node:test';
import { findResourceSelection } from '../src/views/admin/access/resource-tree.ts';
import type { ResourceSelection } from '../src/views/admin/access/useAccessWorkspace.ts';

const tree: ResourceSelection[] = [
  {
    key: 'DATASOURCE:1',
    id: '1',
    label: 'source',
    type: 'DATASOURCE',
    generation: '1',
    children: [
      {
        key: 'NAMESPACE:2',
        id: '2',
        label: 'ns',
        type: 'NAMESPACE',
        generation: null,
        children: [{ key: 'OBJECT:3', id: '3', label: 'orders', type: 'OBJECT', generation: '2' }],
      },
    ],
  },
];

test('keyboard activation resolves datasource, namespace and object nodes by key', () => {
  assert.equal(findResourceSelection(tree, 'DATASOURCE:1')?.type, 'DATASOURCE');
  assert.equal(findResourceSelection(tree, 'NAMESPACE:2')?.type, 'NAMESPACE');
  assert.equal(findResourceSelection(tree, 'OBJECT:3')?.generation, '2');
  assert.equal(findResourceSelection(tree, 'OBJECT:404'), null);
});
