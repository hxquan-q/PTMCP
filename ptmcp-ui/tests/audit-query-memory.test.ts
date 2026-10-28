import assert from 'node:assert/strict';
import test from 'node:test';
import { forgetAuditQuery, recallAuditQuery, rememberAuditQuery } from '../src/views/admin/audit/auditQueryMemory.ts';

test('audit query memory survives a detail round trip but never stores plaintext', () => {
  forgetAuditQuery();
  rememberAuditQuery({
    filters: { userId: '', platformId: '1', datasourceId: '2', tool: 'query', result: 'ERROR', verdict: '', errorCode: '' },
    from: 1,
    to: 2,
    rows: [{ requestId: 'r1', tool: 'query' }] as never,
    cursor: 'next',
    loaded: true,
    quickId: 'r1',
    scrollY: 240,
  });
  const state = recallAuditQuery();
  assert.equal(state?.filters.tool, 'query');
  assert.equal(state?.cursor, 'next');
  assert.equal(state?.quickId, 'r1');
  assert.equal(state?.scrollY, 240);
  assert.equal(JSON.stringify(state).includes('sqlRaw'), false);
  if (state) state.rows.push({ requestId: 'r2' } as never);
  assert.equal(recallAuditQuery()?.rows.length, 1);
  forgetAuditQuery();
  assert.equal(recallAuditQuery(), null);
});
