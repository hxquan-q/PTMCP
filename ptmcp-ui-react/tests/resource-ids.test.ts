import assert from 'node:assert/strict';
import { registerHooks } from 'node:module';
import test from 'node:test';

// Vite resolves extensionless source imports; mirror that only while loading these TS modules.
const sourceImports = registerHooks({
  resolve(specifier: string, context: { parentURL?: string }, nextResolve: (s: string, c: unknown) => unknown) {
    const extensionlessSource = context.parentURL?.includes('/src/') && specifier.startsWith('.') && !/\.[a-z]+$/i.test(specifier);
    return nextResolve(extensionlessSource ? `${specifier}.ts` : specifier, context);
  },
});
const { decodeResourceId, encodeObjectIds, encodeResourceId } = await import('../src/services/resourceIds.ts');
sourceImports.deregister();

test('encodeResourceId matches ResourceIds.java: (typeCode << 48) | tableId', () => {
  assert.equal(encodeResourceId('DATASOURCE', '1'), ((1n << 48n) | 1n).toString());
  assert.equal(encodeResourceId('NAMESPACE', '1'), ((2n << 48n) | 1n).toString());
  assert.equal(encodeResourceId('OBJECT', '1'), ((3n << 48n) | 1n).toString());
  assert.equal(encodeResourceId('OBJECT', '281474976710655'), ((3n << 48n) | ((1n << 48n) - 1n)).toString());
});

test('decodeResourceId round-trips every type and rejects the unencoded space', () => {
  for (const type of ['DATASOURCE', 'NAMESPACE', 'OBJECT'] as const) {
    const encoded = encodeResourceId(type, '42');
    assert.deepEqual(decodeResourceId(encoded), { type, tableId: '42' });
  }
  // 原始元数据 id 属于未编码空间：type 位为 0，服务端 typeOf 会拒绝（不是 OBJECT 1）。
  assert.equal(decodeResourceId('1'), null);
  assert.equal(decodeResourceId(0), null);
  assert.equal(decodeResourceId(-1), null);
  assert.equal(decodeResourceId('not-a-number'), null);
  assert.equal(decodeResourceId((4n << 48n).toString()), null);
});

test('encodeResourceId rejects zero, signs, fractions and ids beyond 48 bits', () => {
  for (const bad of ['0', '-1', '1.5', '01', 'abc', '', ' ']) {
    assert.throws(() => encodeResourceId('OBJECT', bad), /invalid table id/);
  }
  assert.throws(() => encodeResourceId('OBJECT', (1n << 48n).toString()), /out of range/);
});

test('encodeObjectIds fails loudly instead of silently dropping invalid metadata ids', () => {
  assert.deepEqual(encodeObjectIds(['1', '2']), {
    ids: [encodeResourceId('OBJECT', '1'), encodeResourceId('OBJECT', '2')],
    error: '',
  });
  const failed = encodeObjectIds(['1', '0']);
  assert.deepEqual(failed.ids, []);
  assert.match(failed.error, /0/);
});
