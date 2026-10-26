import assert from 'node:assert/strict';
import test from 'node:test';

import { newUuid } from '../src/services/uuid.ts';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function withCrypto<T>(value: unknown, fn: () => T): T {
  const original = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
  Object.defineProperty(globalThis, 'crypto', { configurable: true, value });
  try {
    return fn();
  } finally {
    if (original) Object.defineProperty(globalThis, 'crypto', original);
    else Reflect.deleteProperty(globalThis, 'crypto');
  }
}

test('newUuid uses randomUUID when the secure-context API exists', () => {
  const expected = '11111111-1111-4111-8111-111111111111';
  assert.equal(
    withCrypto({ randomUUID: () => expected }, () => newUuid()),
    expected,
  );
});

test('newUuid falls back to getRandomValues outside a secure context', () => {
  const fake = {
    getRandomValues(bytes: Uint8Array) {
      for (let index = 0; index < bytes.length; index += 1) bytes[index] = index;
      return bytes;
    },
  };
  const value = withCrypto(fake, () => newUuid());
  assert.match(value, UUID_V4);
  assert.equal(value, '00010203-0405-4607-8809-0a0b0c0d0e0f');
});
