import assert from 'node:assert/strict';
import test from 'node:test';
import { passwordError, userProfileError } from '../src/services/formValidation.ts';
test('password constraints retain Unicode character and UTF-8 byte boundaries', () => {
  assert.equal(passwordError('a'.repeat(12)), null);
  assert.equal(passwordError('a'.repeat(64)), null);
  assert.ok(passwordError('a'.repeat(65)));
  assert.equal(passwordError('字'.repeat(24)), null);
  assert.ok(passwordError('字'.repeat(25)));
  assert.equal(passwordError('😀'.repeat(12)), null);
  assert.ok(passwordError('😀'.repeat(19)));
});
test('profile fields reject malformed and oversized values', () => {
  assert.equal(userProfileError({ email: 'demo@example.test', phoneNumber: '12345678901', remark: '' }), null);
  assert.ok(userProfileError({ email: 'wrong', phoneNumber: '', remark: '' }));
  assert.ok(userProfileError({ email: '', phoneNumber: '123456789012', remark: '' }));
  assert.ok(userProfileError({ email: '', phoneNumber: '', remark: 'x'.repeat(501) }));
});
