import type { ScopeRule } from '../../../services/controlPlane';

export interface ScopeChangeSet {
  added: ScopeRule[];
  removed: ScopeRule[];
  modified: Array<{ before: ScopeRule; after: ScopeRule }>;
}

export const scopeKey = (row: ScopeRule): string => `${row.resourceType}:${row.resourceId}:${row.action}`;

/** 整组替换前的本地差异；key 含 action，改动作按“删旧增新”呈现，不伪造字段级合并。 */
export function scopeChanges(before: ScopeRule[], after: ScopeRule[]): ScopeChangeSet {
  const beforeByKey = new Map(before.map((row) => [scopeKey(row), row]));
  const afterKeys = new Set(after.map((row) => scopeKey(row)));
  return {
    added: after.filter((row) => !beforeByKey.has(scopeKey(row))),
    removed: before.filter((row) => !afterKeys.has(scopeKey(row))),
    modified: after
      .filter((row) => {
        const prior = beforeByKey.get(scopeKey(row));
        return Boolean(prior) && (prior!.includeDescendants !== row.includeDescendants || prior!.resourceGeneration !== row.resourceGeneration);
      })
      .map((afterRow) => ({ before: beforeByKey.get(scopeKey(afterRow))!, after: afterRow })),
  };
}

function descendants(value: boolean): string {
  return value ? '含子级' : '仅此资源';
}

/** 字段级旧→新文本；未变化字段省略，精度按原字符串保留。 */
export function scopeChangeText(change: { before: ScopeRule; after: ScopeRule }): string {
  const parts: string[] = [];
  if (change.before.resourceGeneration !== change.after.resourceGeneration)
    parts.push(`generation ${change.before.resourceGeneration} → ${change.after.resourceGeneration}`);
  if (change.before.includeDescendants !== change.after.includeDescendants)
    parts.push(`${descendants(change.before.includeDescendants)} → ${descendants(change.after.includeDescendants)}`);
  return parts.join('；');
}

export function scopeRuleText(row: ScopeRule): string {
  return `${row.resourceType}:${row.resourceId}@${row.resourceGeneration} · ${row.action} · ${descendants(row.includeDescendants)}`;
}
