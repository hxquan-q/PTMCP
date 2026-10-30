import assert from 'node:assert/strict';
import test from 'node:test';
import {
  classifySubmitError,
  shouldPreserveIntent,
  submitErrorMessage,
  uncertainActionLabel,
  uncertainNotice,
  type SubmitOutcome,
} from '../src/views/admin/submitError.ts';

test('uncertain: unknown codes win over HTTP status (real http.ts path keeps the original status)', () => {
  // http.ts 在响应体解析失败时带原 HTTP 状态抛 INVALID_RESPONSE；不能按 400/422 当 validation 丢意图。
  const unknownCodes = ['OPERATION_UNKNOWN', 'NETWORK_ERROR', 'INVALID_RESPONSE', 'OPERATION_IN_PROGRESS'];
  for (const code of unknownCodes) {
    for (const status of [undefined, 400, 409, 412, 422, 500, 503]) {
      assert.equal(classifySubmitError({ status, code }), 'uncertain', `${code} with status ${status} must stay uncertain`);
    }
  }
  assert.equal(shouldPreserveIntent('uncertain'), true);
});

test('uncertain: 5xx and no-status local errors preserve intent (no evidence write was not submitted)', () => {
  assert.equal(classifySubmitError({ status: 500 }), 'uncertain');
  assert.equal(classifySubmitError({ status: 502 }), 'uncertain');
  assert.equal(classifySubmitError({ status: 503 }), 'uncertain');
  assert.equal(classifySubmitError({}), 'uncertain'); // no status at all
  assert.equal(shouldPreserveIntent('uncertain'), true);
});

test('uncertain: 409 without explicit duplicate code preserves intent (could be OPERATION_IN_PROGRESS)', () => {
  assert.equal(classifySubmitError({ status: 409 }), 'uncertain', 'bare 409 must NOT be assumed duplicate');
  assert.equal(classifySubmitError({ status: 409, code: 'SOME_OTHER_409' }), 'uncertain');
});

test('conflict: 412 and 409+VERSION_CONFLICT are definite conflicts, do not preserve intent', () => {
  assert.equal(classifySubmitError({ status: 412, code: 'VERSION_CONFLICT' }), 'conflict');
  assert.equal(classifySubmitError({ status: 412 }), 'conflict');
  assert.equal(classifySubmitError({ status: 409, code: 'VERSION_CONFLICT' }), 'conflict');
  assert.equal(shouldPreserveIntent('conflict'), false);
  assert.match(submitErrorMessage('conflict', ''), /他人修改/);
});

test('duplicate: only 409 with explicit DUPLICATE_KEY or ALREADY_EXISTS code', () => {
  assert.equal(classifySubmitError({ status: 409, code: 'DUPLICATE_KEY' }), 'duplicate');
  assert.equal(classifySubmitError({ status: 409, code: 'ALREADY_EXISTS' }), 'duplicate');
  assert.equal(shouldPreserveIntent('duplicate'), false);
  assert.match(submitErrorMessage('duplicate', ''), /编码已存在/);
});

test('validation: 400/422 with a definite rejection code are the only validation outcomes', () => {
  assert.equal(classifySubmitError({ status: 400 }), 'validation');
  assert.equal(classifySubmitError({ status: 400, code: 'INVALID_ARGUMENT' }), 'validation');
  assert.equal(classifySubmitError({ status: 422, code: 'INVALID_REQUEST' }), 'validation');
  assert.equal(shouldPreserveIntent('validation'), false);
});

test('submitErrorMessage produces distinct messages for each definite outcome', () => {
  const outcomes: SubmitOutcome[] = ['conflict', 'duplicate', 'validation'];
  const messages = outcomes.map((o) => submitErrorMessage(o, ''));
  assert.equal(new Set(messages).size, 3, 'each definite outcome must have a distinct message');
  for (const msg of messages) assert.ok(msg.length > 5, 'messages must be user-readable');
});

test('uncertain copy differs by mode: create may promise idempotent retry, edit must not', () => {
  const create = submitErrorMessage('uncertain', '', 'create');
  const edit = submitErrorMessage('uncertain', '', 'edit');
  assert.notEqual(create, edit);
  assert.match(create, /不会重复创建/);
  assert.doesNotMatch(edit, /不会重复创建/);
  assert.match(edit, /刷新列表|不要直接重复提交/);

  assert.notEqual(uncertainNotice('create'), uncertainNotice('edit'));
  assert.match(uncertainNotice('create'), /不会重复创建/);
  assert.doesNotMatch(uncertainNotice('edit'), /不会重复创建/);

  assert.equal(uncertainActionLabel('create'), '用同一请求键重试');
  assert.notEqual(uncertainActionLabel('edit'), uncertainActionLabel('create'));
});
