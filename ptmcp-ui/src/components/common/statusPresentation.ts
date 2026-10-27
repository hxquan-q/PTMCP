export type StatusDomain = 'lifecycle' | 'delegation' | 'pat' | 'connection' | 'verdict' | 'execution';
export type StatusTone = 'success' | 'danger' | 'warning' | 'info';
export interface StatusPresentation {
  label: string;
  tone: StatusTone;
  icon: 'check' | 'close' | 'warning' | 'clock' | 'question';
}

const statuses: Record<StatusDomain, Record<string, StatusPresentation>> = {
  lifecycle: {
    ACTIVE: { label: '启用', tone: 'success', icon: 'check' },
    DISABLED: { label: '停用', tone: 'info', icon: 'close' },
    DRAFT: { label: '草稿', tone: 'info', icon: 'clock' },
  },
  pat: {
    ACTIVE: { label: '活跃', tone: 'success', icon: 'check' },
    REVOKED: { label: '已撤销', tone: 'info', icon: 'close' },
    EXPIRED: { label: '已过期', tone: 'warning', icon: 'clock' },
  },
  delegation: {
    ACTIVE: { label: '生效中', tone: 'success', icon: 'check' },
    EXPIRED: { label: '已到期', tone: 'warning', icon: 'clock' },
    REVOKED: { label: '已撤销', tone: 'info', icon: 'close' },
  },
  connection: {
    HEALTHY: { label: '连接正常', tone: 'success', icon: 'check' },
    UNHEALTHY: { label: '连接异常', tone: 'danger', icon: 'warning' },
    UNKNOWN: { label: '连接未知', tone: 'info', icon: 'question' },
  },
  verdict: {
    ALLOW: { label: '允许', tone: 'success', icon: 'check' },
    DENY: { label: '拒绝', tone: 'warning', icon: 'close' },
    ERROR: { label: '判定错误', tone: 'danger', icon: 'warning' },
    UNKNOWN: { label: '判定未知', tone: 'info', icon: 'question' },
  },
  execution: {
    SUCCESS: { label: '成功', tone: 'success', icon: 'check' },
    ERROR: { label: '执行错误', tone: 'danger', icon: 'warning' },
    RUNNING: { label: '执行中', tone: 'info', icon: 'clock' },
    UNKNOWN: { label: '结局未知', tone: 'info', icon: 'question' },
  },
};

/** 未识别的服务端状态保留原码，绝不推断成功或失败。 */
export function statusPresentation(domain: StatusDomain, value: string | null | undefined): StatusPresentation {
  const entries = statuses[domain];
  if (value && Object.hasOwn(entries, value)) return entries[value]!;
  return { label: value ? `未知状态（${value}）` : '未提供', tone: 'info', icon: 'question' };
}
