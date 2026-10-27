import assert from 'node:assert/strict';
import test from 'node:test';
import { scopeChangeText, scopeChanges, scopeKey, scopeRuleText } from '../src/views/admin/integrations/scopeDiff.ts';

const rule = (resourceId: string, action: string, includeDescendants: boolean, resourceGeneration = '3') => ({
  resourceType: 'DATASOURCE',
  resourceId,
  resourceGeneration,
  action,
  includeDescendants,
});

test('差异仅报告字段级修改，动作变化按删旧增新呈现，不改写键语义', () => {
  const before = [rule('12', 'META', true)];
  const after = [rule('12', 'META', false)];
  const changes = scopeChanges(before, after);
  assert.deepEqual(changes.added, []);
  assert.deepEqual(changes.removed, []);
  assert.equal(changes.modified.length, 1);
  assert.equal(scopeChangeText(changes.modified[0]), '含子级 → 仅此资源');
  const actionFlip = scopeChanges(before, [rule('12', 'READ', true)]);
  assert.equal(actionFlip.modified.length, 0);
  assert.equal(actionFlip.removed.length, 1);
  assert.equal(actionFlip.added.length, 1);
});

test('同键代次变化报告 generation 旧→新，组合变化用分号连接且字符串ID精度保留', () => {
  const changes = scopeChanges([rule('9007199254740993', 'READ', false, '3')], [rule('9007199254740993', 'READ', true, '4')]);
  assert.equal(changes.modified.length, 1);
  assert.equal(scopeChangeText(changes.modified[0]), 'generation 3 → 4；仅此资源 → 含子级');
  assert.match(scopeRuleText(changes.modified[0].after), /9007199254740993/);
  assert.equal(scopeKey(changes.modified[0].after), 'DATASOURCE:9007199254740993:READ');
});

test('空差异不产生任何分组，未变化行不计入修改', () => {
  const rows = [rule('7', 'META', true), rule('8', 'READ', false)];
  const changes = scopeChanges(
    rows,
    rows.map((row) => ({ ...row })),
  );
  assert.deepEqual(changes, { added: [], removed: [], modified: [] });
});
