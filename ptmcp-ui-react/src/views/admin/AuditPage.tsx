import { useEffect, useMemo, useState } from 'react';
import { Badge, EmptyState, PageHeader, Select, StatusDot, Tabs, TextInput } from '../../components/common';
import { listAudit, listJobs, type AuditRow, type JobRow } from '../../services/access';
import { listDatasources, type DatasourceRead } from '../../services/data';
import { mePortalApi, type PlatformItem } from '../../services/mePortalApi';

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
  const [platforms, setPlatforms] = useState<PlatformItem[]>([]);
  const [filters, setFilters] = useState({
    userId: '',
    platformId: '',
    datasourceId: '',
    tool: '',
    result: '',
    verdict: '',
    errorCode: '',
    from: '',
    to: '',
  });
  const [quickId, setQuickId] = useState('');
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const [queryTick, setQueryTick] = useState(0);
  const setF = (key: keyof typeof filters) => (v: string) => setFilters((f) => ({ ...f, [key]: v }));
  const queryParams = () => ({
    userId: filters.userId || undefined,
    platformId: filters.platformId || undefined,
    datasourceId: filters.datasourceId || undefined,
    tool: filters.tool || undefined,
    result: filters.result || undefined,
    verdict: filters.verdict || undefined,
    errorCode: filters.errorCode || undefined,
    from: filters.from ? `${filters.from}T00:00:00Z` : undefined,
    to: filters.to ? `${filters.to}T23:59:59Z` : undefined,
    pageSize: 20,
  });

  useEffect(() => {
    let cancelled = false;
    listDatasources({ pageSize: 100 })
      .then(({ data }) => !cancelled && setDatasources(data.items))
      .catch(() => undefined);
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => !cancelled && setPlatforms(ctx.items))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    if (tab === 'audit') {
      listAudit(queryParams())
        .then((p) => {
          if (cancelled) return;
          setAudit(p.items);
          setNextCursor(p.nextCursor);
        })
        .catch(() => {
          if (!cancelled) {
            setAudit([]);
            setNextCursor(null);
          }
        })
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
  }, [tab, queryTick]);

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
        </div>
        {tab === 'audit' ? (
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              padding: '12px 14px',
              borderBottom: '1px solid var(--ptmcp-border)',
              flex: 'none',
              flexWrap: 'wrap',
            }}
          >
            <TextInput value={filters.userId} onChange={setF('userId')} placeholder="用户 ID" />
            <Select
              value={filters.platformId}
              onChange={setF('platformId')}
              label="平台"
              width={130}
              options={[{ value: '', label: '全部平台' }, ...platforms.map((p) => ({ value: p.id, label: p.name }))]}
            />
            <Select
              value={filters.datasourceId}
              onChange={setF('datasourceId')}
              label="数据源"
              width={130}
              options={[{ value: '', label: '全部数据源' }, ...datasources.map((d) => ({ value: d.id, label: d.name }))]}
            />
            <Select
              value={filters.tool}
              onChange={setF('tool')}
              label="工具"
              width={110}
              options={[
                { value: '', label: '全部工具' },
                { value: 'query', label: 'query' },
                { value: 'catalog', label: 'catalog' },
                { value: 'schema', label: 'schema' },
              ]}
            />
            <Select
              value={filters.result}
              onChange={setF('result')}
              label="结果"
              width={110}
              options={[
                { value: '', label: '全部结果' },
                { value: 'SUCCESS', label: '成功' },
                { value: 'UNKNOWN', label: '未知' },
                { value: 'ERROR', label: '失败' },
              ]}
            />
            <Select
              value={filters.verdict}
              onChange={setF('verdict')}
              label="判定"
              width={110}
              options={[
                { value: '', label: '全部判定' },
                { value: 'ALLOW', label: '允许' },
                { value: 'DENY', label: '拒绝' },
              ]}
            />
            <TextInput value={filters.errorCode} onChange={setF('errorCode')} placeholder="错误码" />
            <TextInput value={filters.from} onChange={setF('from')} type="date" />
            <span style={{ color: 'var(--ptmcp-text-faint)' }}>→</span>
            <TextInput value={filters.to} onChange={setF('to')} type="date" />
            <button className="btn btn--secondary" onClick={() => setQueryTick((t) => t + 1)}>
              查询
            </button>
            <button
              className="btn btn--secondary"
              onClick={() => {
                setFilters({ userId: '', platformId: '', datasourceId: '', tool: '', result: '', verdict: '', errorCode: '', from: '', to: '' });
                setQueryTick((t) => t + 1);
              }}
            >
              重置
            </button>
            <span style={{ flex: 1 }} />
            <TextInput value={quickId} onChange={setQuickId} placeholder="按 requestId 定位" />
            <button
              className="btn btn--secondary"
              disabled={!quickId.trim()}
              onClick={() => (window.location.hash = `/admin/audit/${encodeURIComponent(quickId.trim())}`)}
            >
              查看
            </button>
          </div>
        ) : null}
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
          {!loading && tab === 'audit' && audit.length === 0 ? <EmptyState title="暂无审计记录" description="调整筛选条件后重试。" /> : null}
          {!loading && tab === 'jobs' && jobs.length === 0 ? <EmptyState title="暂无任务记录" /> : null}
        </div>
        {tab === 'audit' ? (
          <div className="table-footer" style={{ justifyContent: 'space-between' }}>
            <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
              已加载 {audit.length} 条{nextCursor ? ' · 可能还有更多' : ''}
            </span>
            {loading ? null : nextCursor ? (
              <button
                className="btn btn--secondary"
                disabled={loadingMore}
                onClick={() => {
                  if (!nextCursor || loadingMore) return;
                  setLoadingMore(true);
                  listAudit({ ...queryParams(), cursor: nextCursor })
                    .then((p) => {
                      setAudit((prev) => [...prev, ...p.items]);
                      setNextCursor(p.nextCursor);
                    })
                    .catch(() => undefined)
                    .finally(() => setLoadingMore(false));
                }}
              >
                {loadingMore ? '加载中…' : '加载更多'}
              </button>
            ) : (
              <span style={{ fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>没有更多了</span>
            )}
          </div>
        ) : null}
      </div>
    </>
  );
}
