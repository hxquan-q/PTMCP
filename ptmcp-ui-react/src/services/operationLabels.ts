/**
 * 管理操作日志的动作与聚合类型的中文label，与后端写入 admin_operation_log 的字面量对齐。
 * 未知码回退原码，避免静默吞掉新增动作。
 */
const OPERATION_ACTION_TEXT: Record<string, string> = {
  DELEGATION_CREATE: '创建委派',
  DELEGATION_REVOKE: '撤销委派',
  PLATFORM_CREATE: '创建平台',
  PLATFORM_UPDATE: '修改平台',
  PLATFORM_DELETE: '删除平台',
  PLATFORM_SCOPE_SAVE: '保存平台授权范围',
  CLIENT_CREATE: '创建客户端',
  CLIENT_UPDATE: '修改客户端',
  CLIENT_DELETE: '删除客户端',
  USER_CREATE: '创建用户',
  USER_UPDATE: '修改用户',
  USER_PASSWORD_RESET: '重置用户密码',
  USER_REVOKED: '撤销用户凭证',
  PASSWORD_CHANGED: '修改本人密码',
  DEPARTMENT_CREATE: '创建部门',
  DEPARTMENT_UPDATE: '修改部门',
  DEPARTMENT_MOVE: '移动部门',
  DEPARTMENT_DELETE: '删除部门',
  ROLE_CREATE: '创建角色',
  ROLE_UPDATE: '修改角色',
  ROLE_DELETE: '删除角色',
};

const OPERATION_AGGREGATE_TEXT: Record<string, string> = {
  ADMIN_DELEGATION: '委派',
  PLATFORM: '平台',
  OAUTH_CLIENT: '客户端',
  USER: '用户',
  DEPARTMENT: '部门',
  ROLE: '角色',
};

/** 聚合类型筛选下拉的取值顺序，与后端已知聚合类型一致。 */
export const OPERATION_AGGREGATE_TYPES: readonly string[] = ['ADMIN_DELEGATION', 'PLATFORM', 'OAUTH_CLIENT', 'USER', 'DEPARTMENT', 'ROLE'];

export function operationActionText(action: string): string {
  return OPERATION_ACTION_TEXT[action] ?? action;
}

export function operationAggregateText(aggregateType: string): string {
  return OPERATION_AGGREGATE_TEXT[aggregateType] ?? aggregateType;
}
