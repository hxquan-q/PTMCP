import type { AuditRow } from '../../services/access';

/** 审计查询记忆：进入详情往返后恢复筛选/已加载行/游标/定位与滚动位置。模块级内存（不落存储），深拷贝隔离变异。 */
export interface AuditQueryMemory {
  filters: {
    userId: string;
    platformId: string;
    datasourceId: string;
    tool: string;
    result: string;
    verdict: string;
    errorCode: string;
    from: string;
    to: string;
  };
  rows: AuditRow[];
  nextCursor: string | null;
  quickId: string;
  scrollTop: number;
}

let remembered: AuditQueryMemory | null = null;

export function rememberAuditQuery(state: AuditQueryMemory): void {
  remembered = { ...state, filters: { ...state.filters }, rows: [...state.rows] };
}

export function recallAuditQuery(): AuditQueryMemory | null {
  return remembered ? { ...remembered, filters: { ...remembered.filters }, rows: [...remembered.rows] } : remembered;
}

export function forgetAuditQuery(): void {
  remembered = null;
}
