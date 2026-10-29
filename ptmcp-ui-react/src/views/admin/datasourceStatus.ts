/** 数据源状态/健康/时间的展示映射 — 纯函数，供 DatasourcesPage 与测试共用。 */
export type Tone = 'success' | 'warning' | 'danger' | 'neutral';

export interface StatusTone {
  label: string;
  tone: Tone;
}

export function statusOf(status: string | null | undefined): StatusTone {
  const s = (status ?? '').toUpperCase();
  if (s === 'ENABLED' || s === 'ACTIVE') return { label: '启用', tone: 'success' };
  if (s === 'DISABLED') return { label: '禁用', tone: 'danger' };
  if (s === 'DRAFT') return { label: '草稿', tone: 'neutral' };
  return { label: status || '未知', tone: 'neutral' };
}

export function healthOf(health: string | null | undefined): StatusTone {
  const h = (health ?? '').toUpperCase();
  if (h === 'HEALTHY' || h === 'UP' || h === 'OK') return { label: '连接正常', tone: 'success' };
  if (h === 'DEGRADED' || h === 'WARN') return { label: '连接异常', tone: 'warning' };
  if (h === 'DOWN' || h === 'FAIL') return { label: '连接失败', tone: 'danger' };
  return { label: '连接未知', tone: 'neutral' };
}

export function syncResultOf(result: string | null | undefined): StatusTone | null {
  if (!result) return null;
  const r = result.toUpperCase();
  if (r === 'FAILED' || r === 'ERROR') return { label: '失败', tone: 'danger' };
  return { label: '成功', tone: 'success' };
}

/** ISO 时间 → 本地展示格式 YYYY-MM-DD HH:mm；空值与无效值返回 —。 */
export function fmtTime(at: string | null | undefined): string {
  if (!at) return '—';
  const sliced = at.slice(0, 16).replace('T', ' ');
  return /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/.test(sliced) ? sliced : '—';
}
