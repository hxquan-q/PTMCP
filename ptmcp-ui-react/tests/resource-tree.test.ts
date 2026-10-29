import assert from 'node:assert/strict';
import test from 'node:test';
import { buildTree, collectResourceIds, findResourceNode } from '../src/views/admin/resourceTree.ts';
import type { MetadataObject } from '../src/services/data.ts';

const object = (over: Partial<MetadataObject>): MetadataObject =>
  ({
    id: '1',
    datasourceId: '1',
    namespaceId: '1',
    databaseName: 'synthetic',
    schemaName: null,
    objectName: 't',
    tableName: 't',
    objectType: 'TABLE',
    generation: '1',
    ...over,
  }) as MetadataObject;

test('buildTree groups objects by schema with databaseName fallback', () => {
  const tree = buildTree([
    object({ id: '1', schemaName: 'public', objectName: 'orders', objectType: 'TABLE' }),
    object({ id: '2', schemaName: 'public', objectName: 'customers', objectType: 'TABLE' }),
    object({ id: '3', schemaName: null, databaseName: 'analytics', objectName: 'v_daily', objectType: 'VIEW' }),
    object({ id: '4', schemaName: null, databaseName: null, objectName: 'orphan', objectType: 'TABLE' }),
  ]);
  assert.deepEqual(
    tree.map((n) => n.label),
    ['public', 'analytics', 'default'],
  );
  const publicSchema = tree[0]!;
  assert.equal(publicSchema.kind, 'SCHEMA');
  assert.deepEqual(
    publicSchema.children?.map((c) => c.label),
    ['orders', 'customers'],
  );
  assert.equal(publicSchema.children?.[0]?.resourceId, '1');
  assert.equal(tree[2]!.children?.[0]?.label, 'orphan');
});

test('findResourceNode resolves deep keys and returns null for misses', () => {
  const tree = buildTree([object({ id: '9', schemaName: 'sales', objectName: 'deals' })]);
  assert.equal(findResourceNode(tree, 'schema:sales')?.kind, 'SCHEMA');
  assert.equal(findResourceNode(tree, 'obj:9')?.resourceId, '9');
  assert.equal(findResourceNode(tree, 'obj:404'), null);
  assert.equal(findResourceNode([], 'schema:sales'), null);
});

test('collectResourceIds flattens OBJECT ids across schemas in order', () => {
  const tree = buildTree([
    object({ id: '2', schemaName: 'a', objectName: 'x' }),
    object({ id: '5', schemaName: 'b', objectName: 'y' }),
    object({ id: '7', schemaName: 'b', objectName: 'z' }),
  ]);
  assert.deepEqual(collectResourceIds(tree), ['2', '5', '7']);
  assert.deepEqual(collectResourceIds([]), []);
});
