import assert from 'node:assert/strict';
import test from 'node:test';
import { forgetAuditQuery, recallAuditQuery, rememberAuditQuery } from '../src/views/admin/auditQueryMemory.ts';

test('audit query memory round-trips state with mutation isolation, never persists plaintext SQL', () => {
  forgetAuditQuery();
  rememberAuditQuery({
    filters: {
      userId: '',
      platformId: '1',
      datasourceId: '2',
      tool: 'query',
      result: 'ERROR',
      verdict: '',
      errorCode: '',
      from: '2026-10-01',
      to: '2026-10-30',
    },
    rows: [
      {
        requestId: 'r1',
        userId: '1',
        platformId: '1',
        datasourceId: '2',
        tool: 'query',
        startedAt: '2026-10-02T00:00:00Z',
        completedAt: null,
        result: 'ERROR',
        verdict: 'DENY',
        errorCode: 'X',
        sqlMasked: 'SELECT 1',
      },
    ],
    nextCursor: 'next',
    quickId: 'r1',
    scrollTop: 240,
  });
  const state = recallAuditQuery();
  assert.equal(state?.filters.tool, 'query');
  assert.equal(state?.filters.from, '2026-10-01');
  assert.equal(state?.nextCursor, 'next');
  assert.equal(state?.quickId, 'r1');
  assert.equal(state?.scrollTop, 240);
  assert.equal(JSON.stringify(state).includes('sqlRaw'), false, 'memory only holds masked SQL');

  // 变异隔离：外部改 recall 结果不得污染记忆
  if (state) state.rows.push({ requestId: 'r2' } as never);
  state!.filters.tool = 'mutated';
  const again = recallAuditQuery();
  assert.equal(again?.rows.length, 1);
  assert.equal(again?.filters.tool, 'query');

  // remember 同样做深拷贝：存入后再改原对象不影响记忆
  const original = {
    filters: { userId: '', platformId: '', datasourceId: '', tool: 'catalog', result: '', verdict: '', errorCode: '', from: '', to: '' },
    rows: [],
    nextCursor: null,
    quickId: '',
    scrollTop: 0,
  };
  rememberAuditQuery(original);
  original.filters.tool = 'changed';
  assert.equal(recallAuditQuery()?.filters.tool, 'catalog');

  forgetAuditQuery();
  assert.equal(recallAuditQuery(), null);
});
