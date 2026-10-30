import assert from 'node:assert/strict';
import test from 'node:test';
import { createIntent, intentMatches, retryIntent } from '../src/views/admin/createIntent.ts';

const body = { key: 'IDEM_ROLE', name: '联测角色', enabled: true, permissionCodes: ['ptmcp:user:list'] };

test('createIntent freezes body; mutation throws in strict mode', () => {
  const intent = createIntent(body, 'key-abc');
  assert.equal(intent.key, 'key-abc');
  assert.equal(intent.body.name, '联测角色');
  assert.ok(Object.isFrozen(intent.body), 'body must be frozen');
  // 尝试修改冻结对象在 strict mode 下抛 TypeError
  assert.throws(() => {
    'use strict';
    (intent.body as { name: string }).name = '篡改';
  }, TypeError);
});

test('retryIntent returns the same reference (same key + same body)', () => {
  const intent = createIntent(body, 'key-xyz');
  const retried = retryIntent(intent);
  assert.equal(retried, intent, 'retry must return the exact same object');
  assert.equal(retried.key, intent.key);
  assert.deepEqual(retried.body, intent.body);
});

test('intentMatches detects body drift after UNKNOWN (defense-in-depth)', () => {
  const intent = createIntent(body, 'key-123');
  // Same body → match
  assert.equal(intentMatches(intent, { ...body }), true);
  // Changed name → no match (new intent needed)
  assert.equal(intentMatches(intent, { ...body, name: '改名了' }), false);
  // Changed enabled → no match
  assert.equal(intentMatches(intent, { ...body, enabled: false }), false);
  // Changed permissionCodes order but same set → still match (sorted comparison)
  assert.equal(intentMatches(intent, { ...body, permissionCodes: ['ptmcp:user:list'] }), true);
  // Added a code → no match
  assert.equal(intentMatches(intent, { ...body, permissionCodes: ['ptmcp:user:list', 'ptmcp:role:read'] }), false);
  // Changed key → no match
  assert.equal(intentMatches(intent, { ...body, key: 'DIFFERENT_KEY' }), false);
});

test('two createIntent calls with different keys produce independent intents', () => {
  const a = createIntent(body, 'key-a');
  const b = createIntent({ ...body, name: '另一个角色' }, 'key-b');
  assert.notEqual(a.key, b.key);
  assert.notEqual(a.body.name, b.body.name);
  assert.equal(intentMatches(a, b.body as typeof body), false);
});

test('createIntent copies permissionCodes array (external mutation does not affect intent)', () => {
  const original = { key: 'test_role', name: '测试', enabled: true, permissionCodes: ['ptmcp:a', 'ptmcp:b'] };
  const intent = createIntent(original, 'key-copy');

  // Mutate the original array after intent creation
  original.permissionCodes.push('ptmcp:injected');

  // Intent's array is unaffected (was copied)
  assert.deepEqual([...intent.body.permissionCodes], ['ptmcp:a', 'ptmcp:b'], 'intent must have its own copy, not affected by external mutation');

  // Intent body property cannot be reassigned (frozen)
  assert.throws(() => {
    'use strict';
    (intent.body as { permissionCodes: string[] }).permissionCodes = ['ptmcp:hijacked'];
  }, TypeError);
});

test('intentMatches works with copied permissionCodes', () => {
  const original = { key: 'match_test', name: '匹配', enabled: true, permissionCodes: ['ptmcp:x', 'ptmcp:y'] };
  const intent = createIntent(original, 'key-match');

  // Same content different array reference → still matches (sorted comparison)
  assert.equal(intentMatches(intent, { ...original, permissionCodes: ['ptmcp:y', 'ptmcp:x'] }), true);

  // Different content → no match
  assert.equal(intentMatches(intent, { ...original, permissionCodes: ['ptmcp:x'] }), false);
});

test('permissionCodes array is frozen at runtime (push throws in strict mode)', () => {
  const intent = createIntent({ key: 'freeze_test', name: '冻结测试', enabled: true, permissionCodes: ['ptmcp:a'] }, 'key-freeze');
  // Object.freeze on the array prevents push/splice in strict mode
  assert.throws(
    () => {
      'use strict';
      intent.body.permissionCodes.push('ptmcp:injected');
    },
    TypeError,
    'frozen array must reject push',
  );
  assert.throws(
    () => {
      'use strict';
      (intent.body.permissionCodes as unknown as string[]).splice(0, 1);
    },
    TypeError,
    'frozen array must reject splice',
  );
  assert.deepEqual([...intent.body.permissionCodes], ['ptmcp:a'], 'contents unchanged after rejected mutations');
});
