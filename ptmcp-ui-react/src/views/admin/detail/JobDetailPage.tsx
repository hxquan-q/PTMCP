import { useEffect, useRef, useState } from 'react';
import { Alert, Badge, DrawerFields, PageHeader, StatusDot } from '../../../components/common';
import { getJob, type JobRow } from '../../../services/access';

import { BackBar, TERMINAL, errorText, stateLabel, stateTone, useDatasources } from './shared';
import { formatTaipeiTime as fmt } from '../../../services/dateTime';

export function JobDetailPage({ id }: { id: string }) {
  const [job, setJob] = useState<JobRow | null>(null);
  const [error, setError] = useState('');
  const [, dsName] = useDatasources();
  const seq = useRef(0);

  useEffect(() => {
    const own = ++seq.current;
    let timer: number | undefined;
    let cancelled = false;
    setJob(null);
    setError('');
    const load = () => {
      getJob(id)
        .then((j) => {
          if (cancelled || own !== seq.current) return;
          setJob(j);
          if (!TERMINAL.has(j.state) && !document.hidden) timer = window.setTimeout(load, 2000);
        })
        .catch((e) => !cancelled && own === seq.current && setError(errorText(e)));
    };
    load();
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [id]);

  return (
    <>
      <BackBar to="/admin/jobs" label="返回操作日志" />
      <PageHeader
        title={job ? `任务 #${job.id}` : '任务详情'}
        description={job ? `${job.type} · ${dsName(job.datasourceId)}` : '任务执行状态与进度。'}
      />
      {error ? (
        <Alert kind="danger" title="任务状态读取失败">
          {error}
        </Alert>
      ) : null}
      {!job && !error ? <Alert kind="info">加载任务详情…</Alert> : null}
      {job ? (
        <div className="table-card" style={{ padding: '16px 18px', maxWidth: 760 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
            <StatusDot tone={stateTone(job.state)}>{stateLabel(job.state)}</StatusDot>
            <Badge tone="neutral">{job.type}</Badge>
            {!TERMINAL.has(job.state) ? <span style={{ fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>每 2 秒自动刷新</span> : null}
          </div>
          {job.progress ? (
            <div style={{ marginBottom: 14 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--ptmcp-text-muted)', marginBottom: 4 }}>
                <span>处理进度</span>
                <span>
                  {job.progress.processed}/{job.progress.total}
                </span>
              </div>
              <div style={{ height: 6, borderRadius: 'var(--ptmcp-radius-xs)', background: 'var(--ptmcp-chrome)', overflow: 'hidden' }}>
                <div
                  style={{
                    height: '100%',
                    width: `${job.progress.total ? Math.min(100, Math.round((job.progress.processed / job.progress.total) * 100)) : 0}%`,
                    background: job.state === 'FAILED' ? 'var(--ptmcp-red)' : 'var(--ptmcp-blue)',
                    transition: 'width .3s ease',
                  }}
                />
              </div>
            </div>
          ) : null}
          <DrawerFields
            fields={[
              { label: '数据源', value: dsName(job.datasourceId) },
              { label: '开始时间', value: fmt(job.startedAt) },
              { label: '完成时间', value: fmt(job.finishedAt) },
              { label: '关联快照', value: job.snapshotId ? `快照 #${job.snapshotId}` : '—' },
              { label: '错误码', value: job.errorCode ?? '—' },
            ]}
          />
          {job.errorCode ? (
            <Alert kind="danger" title="任务失败">
              错误码 {job.errorCode}。请检查数据源连接与配置后重试。
            </Alert>
          ) : null}
        </div>
      ) : null}
    </>
  );
}

/* ─── 审计详情:/admin/audit/{requestId} ─── */
