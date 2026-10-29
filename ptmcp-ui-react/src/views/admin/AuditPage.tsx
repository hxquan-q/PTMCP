import { useEffect, useMemo, useState } from 'react';
import { Badge, EmptyState, PageHeader, Select, StatusDot, Tabs } from '../../components/common';
import { listAudit, listJobs, type AuditRow, type JobRow } from '../../services/access';
import { listDatasources, type DatasourceRead } from '../../services/data';

function fmt(at: string | null | undefined): string {
  return at ? at.slice(0, 19).replace('T', ' ') : '—';
}

export function AuditPage({
  initialTab = 'audit',
  title = '审计记录',
  description = 'MCP 请求审计与系统任务日志。',
}: {
  initialTab?: string;
  title?: string;
  description?: string;
}) {
  const [tab, setTab] = useState(initialTab);
  const [audit, setAudit] = useState<AuditRow[]>([]);
  const [jobs, setJobs] = useState<JobRow[]>([]);
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  const [loading, setLoading] = useState(true);
  const [resultFilter, setResultFilter] = useState('');

  useEffect(() => {
    let cancelled = false;
    listDatasources({ pageSize: 100 })
      .then(({ data }) => !cancelled && setDatasources(data.items))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    if (tab === 'audit') {
      listAudit({ result: resultFilter || undefined, pageSize: 200 })
        .then((p) => !cancelled && setAudit(p.items))
        .catch(() => !cancelled && setAudit([]))
        .finally(() => !cancelled && setLoading(false));
    } else {
      listJobs({ pageSize: 200 })
        .then((p) => !cancelled && setJobs(p.items))
        .catch(() => !cancelled && setJobs([]))
        .finally(() => !cancelled && setLoading(false));
    }
    return () => {
      cancelled = true;
    };
  }, [tab, resultFilter]);

  const dsName = useMemo(() => {
    const m = new Map(datasources.map((d) => [d.id, d.name]));
    return (id: string) => m.get(id) ?? `数据源 ${id}`;
  }, [datasources]);

  return (
    <>
      <PageHeader title={title} description={description} />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '0 14px', flex: 'none' }}>
          <Tabs
            tabs={[
              { key: 'audit', label: '访问审计' },
              { key: 'jobs', label: '任务日志' },
            ]}
            active={tab}
            onChange={setTab}
          />
          {tab === 'audit' ? (
            <Select
              value={resultFilter}
              onChange={setResultFilter}
              options={[
                { value: '', label: '全部结果' },
                { value: 'SUCCESS', label: '成功' },
                { value: 'UNKNOWN', label: '未知' },
                { value: 'ERROR', label: '失败' },
              ]}
            />
          ) : null}
        </div>
        <div className="table-scroll">
          {tab === 'audit' ? (
            <table className="data-table">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>用户</th>
                  <th>数据源</th>
                  <th>工具</th>
                  <th>耗时</th>
                  <th>结果</th>
                  <th>判定</th>
                  <th style={{ width: 60 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {audit.map((r) => (
                  <tr key={r.requestId}>
                    <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.startedAt)}</td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>用户 {r.userId}</td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{dsName(r.datasourceId)}</td>
                    <td>
                      <Badge tone="neutral">{r.tool}</Badge>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{r.durationMs != null ? `${r.durationMs} ms` : '—'}</td>
                    <td>
                      <StatusDot tone={r.result === 'SUCCESS' ? 'success' : r.result === 'ERROR' ? 'danger' : 'warning'}>
                        {r.result === 'SUCCESS' ? '成功' : r.result === 'ERROR' ? '失败' : '进行中'}
                      </StatusDot>
                    </td>
                    <td>
                      {r.verdict ? (
                        <Badge tone={r.verdict === 'ALLOW' ? 'success' : 'danger'}>{r.verdict}</Badge>
                      ) : (
                        <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>—</span>
                      )}
                    </td>
                    <td>
                      <button className="link" onClick={() => (window.location.hash = `/admin/audit/${encodeURIComponent(r.requestId)}`)}>
                        详情
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>任务</th>
                  <th>数据源</th>
                  <th>类型</th>
                  <th>进度</th>
                  <th>开始时间</th>
                  <th>状态</th>
                  <th style={{ width: 60 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {jobs.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <strong>任务 #{r.id}</strong>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{dsName(r.datasourceId)}</td>
                    <td>
                      <Badge tone="neutral">{r.type}</Badge>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{r.progress ? `${r.progress.processed}/${r.progress.total}` : '—'}</td>
                    <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.startedAt)}</td>
                    <td>
                      <StatusDot tone={r.state === 'SUCCEEDED' ? 'success' : r.state === 'FAILED' ? 'danger' : 'warning'}>
                        {r.state === 'SUCCEEDED' ? '成功' : r.state === 'FAILED' ? '失败' : '进行中'}
                      </StatusDot>
                    </td>
                    <td>
                      <button className="link" onClick={() => (window.location.hash = `/admin/jobs/${r.id}`)}>
                        详情
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {!loading && tab === 'audit' && audit.length === 0 ? <EmptyState title="暂无审计记录" /> : null}
          {!loading && tab === 'jobs' && jobs.length === 0 ? <EmptyState title="暂无任务记录" /> : null}
        </div>
      </div>
    </>
  );
}
