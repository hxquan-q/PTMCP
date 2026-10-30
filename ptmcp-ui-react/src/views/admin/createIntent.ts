/** 创建意图管理 — 纯函数，供对话框与测试共用。冻结 body 快照 + key 绑定。 */
import type { RoleWrite } from '../../services/orgPortalApi';

export interface CreateIntent {
  readonly key: string;
  readonly body: Readonly<RoleWrite & { key: string }>;
}

/** 首次提交：生成新 key + 冻结 body 快照（不可变）。 */
export function createIntent(body: RoleWrite & { key: string }, key: string): CreateIntent {
  return { key, body: Object.freeze({ ...body }) };
}

/** UNKNOWN 重试：返回原意图（同 key 同 body），不生成新意图。 */
export function retryIntent(intent: CreateIntent): CreateIntent {
  return intent;
}

/** 判断 body 是否与意图一致（用于检测用户是否在 UNKNOWN 后改了表单——理论上 UI 冻结了但作防御）。 */
export function intentMatches(intent: CreateIntent, currentBody: RoleWrite & { key: string }): boolean {
  return (
    intent.body.name === currentBody.name &&
    intent.body.enabled === currentBody.enabled &&
    intent.body.key === currentBody.key &&
    JSON.stringify([...intent.body.permissionCodes].sort()) === JSON.stringify([...currentBody.permissionCodes].sort())
  );
}
