import { useEffect, useMemo, useRef, useState } from 'react';
import { Badge, EmptyState, PageHeader, Select, StatusDot, Tabs, TextInput } from '../../components/common';
import { listAudit, listOperations, type AuditRow, type OperationRow } from '../../services/access';
import { OPERATION_AGGREGATE_TYPES, operationActionText, operationAggregateText } from '../../services/operationLabels';
import { forgetAuditQuery, recallAuditQuery, rememberAuditQuery } from './auditQueryMemory';
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
  const [initialMemory] = useState(() => {
    const memory = recallAuditQuery();
    forgetAuditQuery();
    return memory;
  });

  const [operations, setOperations] = useState<OperationRow[]>([]);
  const [opsCursor, setOpsCursor] = useState<string | null>(null);
  const [opsUnavailable, setOpsUnavailable] = useState(false);
  const [aggregateFilter, setAggregateFilter] = useState('');
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  const [loading, setLoading] = useState(!initialMemory);
  const [platforms, setPlatforms] = useState<PlatformItem[]>([]);
  const [filters, setFilters] = useState(
    initialMemory?.filters ?? { userId: '', platformId: '', datasourceId: '', tool: '', result: '', verdict: '', errorCode: '', from: '', to: '' },
  );
  const [quickId, setQuickId] = useState(initialMemory?.quickId ?? '');
  const [nextCursor, setNextCursor] = useState<string | null>(initialMemory?.nextCursor ?? null);
  const [audit, setAudit] = useState<AuditRow[]>(initialMemory?.rows ?? []);
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

  const tableScrollRef = useRef<HTMLDivElement | null>(null);
  const firstRunRef = useRef(true);
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
    // 有恢复记忆（详情往返）则首跑跳过取数，行/筛选已在初始 state 中
    if (firstRunRef.current) {
      firstRunRef.current = false;
      if (initialMemory) {
        if (initialMemory.scrollTop > 0)
          window.setTimeout(() => {
            if (tableScrollRef.current) tableScrollRef.current.scrollTop = initialMemory.scrollTop;
          }, 0);
        return;
      }
    }
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
      listOperations({ aggregateType: aggregateFilter || undefined, pageSize: 20 })
        .then((p) => {
          if (cancelled) return;
          setOperations(p.items);
          setOpsCursor(p.nextCursor);
          setOpsUnavailable(false);
        })
        .catch(() => {
          if (!cancelled) {
            setOperations([]);
            setOpsCursor(null);
            setOpsUnavailable(true);
          }
        })
        .finally(() => !cancelled && setLoading(false));
    }
    return () => {
      cancelled = true;
    };
  }, [tab, queryTick, aggregateFilter, initialMemory]);

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
              { key: 'jobs', label: '操作日志' },
            ]}
            active={tab}
            onChange={setTab}
          />
          {tab === 'jobs' ? (
            <Select
              value={aggregateFilter}
              onChange={setAggregateFilter}
              label="聚合类型"
              width={140}
              options={[{ value: '', label: '全部类型' }, ...OPERATION_AGGREGATE_TYPES.map((t) => ({ value: t, label: operationAggregateText(t) }))]}
            />
          ) : null}
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
              onClick={() => {
                rememberAuditQuery({
                  filters: { ...filters },
                  rows: audit,
                  nextCursor,
                  quickId: quickId.trim(),
                  scrollTop: tableScrollRef.current?.scrollTop ?? 0,
                });
                window.location.hash = `/admin/audit/${encodeURIComponent(quickId.trim())}`;
              }}
            >
              查看
            </button>
          </div>
        ) : null}
        <div className="table-scroll" ref={tableScrollRef}>
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
                      <button
                        className="link"
                        onClick={() => {
                          rememberAuditQuery({
                            filters: { ...filters },
                            rows: audit,
                            nextCursor,
                            quickId,
                            scrollTop: tableScrollRef.current?.scrollTop ?? 0,
                          });
                          window.location.hash = `/admin/audit/${encodeURIComponent(r.requestId)}`;
                        }}
                      >
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
                  <th>时间</th>
                  <th>操作者</th>
                  <th>动作</th>
                  <th>聚合对象</th>
                  <th>动作码</th>
                  <th>请求 ID</th>
                </tr>
              </thead>
              <tbody>
                {operations.map((r) => (
                  <tr key={r.id}>
                    <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.occurredAt)}</td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>用户 {r.actorId}</td>
                    <td>
                      <strong style={{ color: 'var(--ptmcp-heading)' }}>{operationActionText(r.action)}</strong>
                    </td>
                    <td>
                      <Badge tone="neutral">
                        {operationAggregateText(r.aggregateType)} · {r.aggregateId}
                      </Badge>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)', fontSize: 12 }}>
                      <code>{r.action}</code>
                    </td>
                    <td title={r.requestId} style={{ color: 'var(--ptmcp-text-muted)', fontSize: 12 }}>
                      {r.requestId.slice(0, 10)}…
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {!loading && tab === 'jobs' && operations.length === 0 ? (
            opsUnavailable ? (
              <EmptyState title="操作日志端点不可用" description="管理操作日志接口暂时不可用，请稍后重试或联系管理员。" />
            ) : (
              <EmptyState title="暂无操作记录" description="调整聚合类型筛选后重试。" />
            )
          ) : null}
          {!loading && tab === 'audit' && audit.length === 0 ? <EmptyState title="暂无审计记录" description="调整筛选条件后重试。" /> : null}
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
        {tab === 'jobs' && !opsUnavailable ? (
          <div className="table-footer" style={{ justifyContent: 'space-between' }}>
            <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
              已加载 {operations.length} 条{opsCursor ? ' · 可能还有更多' : ''}
            </span>
            {opsCursor ? (
              <button
                className="btn btn--secondary"
                onClick={() => {
                  listOperations({ aggregateType: aggregateFilter || undefined, cursor: opsCursor, pageSize: 20 })
                    .then((p) => {
                      setOperations((prev) => [...prev, ...p.items]);
                      setOpsCursor(p.nextCursor);
                    })
                    .catch(() => undefined);
                }}
              >
                加载更多
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
