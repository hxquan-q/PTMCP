/** 敏感操作的权限门控 — 纯函数 + 常量，供 App 上下文与测试共用。 */

/** 需要独立权限码才能执行的 UI 动作（与 Vue session.can 调用点对齐）。 */
export const SENSITIVE_ACTIONS = {
  auditDecrypt: 'ptmcp:audit:decrypt',
  datasourceCreate: 'ptmcp:datasource:create',
  datasourceDelete: 'ptmcp:datasource:delete',
  datasourceSync: 'ptmcp:datasource:sync',
  datasourceTest: 'ptmcp:datasource:test',
  userCreate: 'ptmcp:user:create',
  userDelete: 'ptmcp:user:delete',
  roleCreate: 'ptmcp:role:create',
  roleDelete: 'ptmcp:role:delete',
  delegationCreate: 'ptmcp:delegation:create',
  aclGrant: 'ptmcp:acl:create',
} as const;

export type SensitiveAction = keyof typeof SENSITIVE_ACTIONS;

/** 会话是否允许执行敏感动作；无会话或权限列表缺失时一律拒绝。 */
export function canPerform(permissions: string[] | null | undefined, action: SensitiveAction): boolean {
  if (!permissions || !Array.isArray(permissions)) return false;
  return permissions.includes(SENSITIVE_ACTIONS[action]);
}

/** 批量判断：返回动作→是否允许的映射（减少组件内重复调用）。 */
export function canPerformAll(permissions: string[] | null | undefined): Record<SensitiveAction, boolean> {
  const result = {} as Record<SensitiveAction, boolean>;
  for (const action of Object.keys(SENSITIVE_ACTIONS) as SensitiveAction[]) {
    result[action] = canPerform(permissions, action);
  }
  return result;
}
