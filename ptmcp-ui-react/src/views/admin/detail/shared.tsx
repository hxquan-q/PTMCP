import { useCallback, useEffect, useState } from 'react';
import { Button } from 'antd';
import { listDatasources, type DatasourceRead } from '../../../services/data';
import { ApiError } from '../../../services/http';

export function useDatasources(): [DatasourceRead[], (id: string | null | undefined) => string] {
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  useEffect(() => {
    let cancelled = false;
    listDatasources({ pageSize: 100 })
      .then(({ data }) => !cancelled && setDatasources(data.items))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);
  const name = useCallback(
    (id: string | null | undefined) => (id ? (datasources.find((d) => d.id === id)?.name ?? `数据源 ${id}`) : '—'),
    [datasources],
  );
  return [datasources, name];
}

export function BackBar({ to, label }: { to: string; label: string }) {
  return (
    <div style={{ marginBottom: 10 }}>
      <Button type="link" style={{ padding: 0 }} onClick={() => (window.location.hash = to)}>
        ‹ {label}
      </Button>
    </div>
  );
}

export function errorText(e: unknown): string {
  if (e instanceof ApiError) return `${e.message}(${e.code})`;
  return e instanceof Error ? e.message : '读取失败';
}

/* ─── 任务详情:/admin/jobs/{id},非终态每 2s 轮询 ─── */
/** 任务/审计的终态集合：非终态才继续轮询。 */
export const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);
export function stateTone(state: string): 'success' | 'warning' | 'danger' | 'neutral' {
  return state === 'SUCCEEDED' ? 'success' : state === 'FAILED' ? 'danger' : 'warning';
}
export function stateLabel(state: string): string {
  return (
    ({ QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' } as Record<string, string>)[state] ?? state
  );
}

export function dsStatusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  const s = status.toUpperCase();
  return s === 'ACTIVE' || s === 'ENABLED' ? 'success' : s === 'DISABLED' ? 'danger' : 'neutral';
}
export function dsStatusLabel(status: string): string {
  return ({ ACTIVE: '运行中', ENABLED: '运行中', DRAFT: '草稿', DISABLED: '已停用' } as Record<string, string>)[status.toUpperCase()] ?? status;
}
export function dsHealthLabel(health: string): string {
  const h = health.toUpperCase();
  return h === 'HEALTHY' || h === 'UP' || h === 'OK'
    ? '连接正常'
    : h === 'DEGRADED' || h === 'WARN'
      ? '连接异常'
      : h === 'DOWN' || h === 'FAIL'
        ? '连接失败'
        : '连接未知';
}
