import assert from 'node:assert/strict';
import test from 'node:test';
import { encodePreviewResource, validGrantWindow } from '../src/adapters/access-resource.ts';

test('preview IDs keep resource types separate with full integer precision', () => {
  assert.equal(encodePreviewResource('DATASOURCE', '1'), '281474976710657');
  assert.equal(encodePreviewResource('NAMESPACE', '1'), '562949953421313');
  assert.equal(encodePreviewResource('OBJECT', '1'), '844424930131969');
  assert.equal(encodePreviewResource('OBJECT', '281474976710655'), '1125899906842623');
  for (const invalid of ['0', '-1', '1.5', '1e2', '281474976710656']) assert.throws(() => encodePreviewResource('OBJECT', invalid));
});

test('grant validity has an exclusive end and preserves the selected instant', () => {
  const selected = new Date('2026-10-25T12:00:00+08:00');
  assert.equal(selected.toISOString(), '2026-10-25T04:00:00.000Z');
  assert.equal(validGrantWindow(selected, null), true);
  assert.equal(validGrantWindow(selected, new Date(selected)), false);
  assert.equal(validGrantWindow(selected, new Date('2026-10-25T12:00:01+08:00')), true);
  assert.equal(validGrantWindow(new Date('invalid'), null), false);
});
