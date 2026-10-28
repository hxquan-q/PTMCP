import type { AdminAuditSummary } from '../../../services/controlPlane';

export interface AuditQueryMemory {
  filters: { userId: string; platformId: string; datasourceId: string; tool: string; result: string; verdict: string; errorCode: string };
  from: number | null;
  to: number | null;
  rows: AdminAuditSummary[];
  cursor: string | null;
  loaded: boolean;
  quickId: string;
  scrollY: number;
}

let remembered: AuditQueryMemory | null = null;

export function rememberAuditQuery(state: AuditQueryMemory): void {
  remembered = { ...state, filters: { ...state.filters }, rows: [...state.rows] };
}

export function recallAuditQuery(): AuditQueryMemory | null {
  return remembered ? { ...remembered, filters: { ...remembered.filters }, rows: [...remembered.rows] } : null;
}

export function forgetAuditQuery(): void {
  remembered = null;
}
