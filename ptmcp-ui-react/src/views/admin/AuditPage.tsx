import { useEffect, useMemo, useRef, useState } from 'react';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Badge, EmptyState, PageHeader, Select, StatusDot, Tabs, TextInput } from '../../components/common';
import { listAudit, listOperations, type AuditRow, type OperationRow } from '../../services/access';
import { OPERATION_AGGREGATE_TYPES, operationActionText, operationAggregateText } from '../../services/operationLabels';
import { forgetAuditQuery, recallAuditQuery, rememberAuditQuery } from './auditQueryMemory';
import { listDatasources, type DatasourceRead } from '../../services/data';
import { mePortalApi, type PlatformItem } from '../../services/mePortalApi';

import { formatTaipeiTime as fmt } from '../../services/dateTime';

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

  /* 游标分页：表格固定 pagination=false，「加载更多」按真实 nextCursor 续拉，不伪造总页数。 */
  const auditColumns: ColumnsType<AuditRow> = [
    { title: '时间', render: (_, r) => <span style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.startedAt)}</span> },
    { title: '用户', render: (_, r) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>用户 {r.userId}</span> },
    { title: '数据源', render: (_, r) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{dsName(r.datasourceId)}</span> },
    { title: '工具', render: (_, r) => <Badge tone="neutral">{r.tool}</Badge> },
    {
      title: '耗时',
      render: (_, r) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{r.durationMs != null ? `${r.durationMs} ms` : '—'}</span>,
    },
    {
      title: '结果',
      render: (_, r) => (
        <StatusDot tone={r.result === 'SUCCESS' ? 'success' : r.result === 'ERROR' ? 'danger' : 'warning'}>
          {r.result === 'SUCCESS' ? '成功' : r.result === 'ERROR' ? '失败' : '进行中'}
        </StatusDot>
      ),
    },
    {
      title: '判定',
      render: (_, r) =>
        r.verdict ? (
          <Badge tone={r.verdict === 'ALLOW' ? 'success' : 'danger'}>{r.verdict}</Badge>
        ) : (
          <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>—</span>
        ),
    },
    {
      title: '操作',
      width: 60,
      render: (_, r) => (
        <Button
          type="link"
          size="small"
          style={{ padding: 0 }}
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
        </Button>
      ),
    },
  ];

  const opsColumns: ColumnsType<OperationRow> = [
    { title: '时间', render: (_, r) => <span style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.occurredAt)}</span> },
    { title: '操作者', render: (_, r) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>用户 {r.actorId}</span> },
    { title: '动作', render: (_, r) => <strong style={{ color: 'var(--ptmcp-heading)' }}>{operationActionText(r.action)}</strong> },
    {
      title: '聚合对象',
      render: (_, r) => (
        <Badge tone="neutral">
          {operationAggregateText(r.aggregateType)} · {r.aggregateId}
        </Badge>
      ),
    },
    {
      title: '动作码',
      render: (_, r) => (
        <span style={{ color: 'var(--ptmcp-text-muted)', fontSize: 12 }}>
          <code>{r.action}</code>
        </span>
      ),
    },
    {
      title: '请求 ID',
      render: (_, r) => (
        <span title={r.requestId} style={{ color: 'var(--ptmcp-text-muted)', fontSize: 12 }}>
          {r.requestId.slice(0, 10)}…
        </span>
      ),
    },
  ];

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
              padding: '12px 16px',
              borderBottom: '1px solid var(--ptmcp-border)',
              flex: 'none',
              flexWrap: 'wrap',
            }}
          >
            <TextInput value={filters.userId} onChange={setF('userId')} placeholder="用户 ID" width={120} />
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
            <TextInput value={filters.errorCode} onChange={setF('errorCode')} placeholder="错误码" width={140} />
            <span className="filter-field">
              <TextInput value={filters.from} onChange={setF('from')} type="date" width={150} label="起" />
              <span style={{ color: 'var(--ptmcp-text-faint)' }}>→</span>
              <TextInput value={filters.to} onChange={setF('to')} type="date" width={150} label="止" />
            </span>
            <Button color="primary" variant="filled" onClick={() => setQueryTick((t) => t + 1)}>
              查询
            </Button>
            <Button
              variant="text"
              onClick={() => {
                setFilters({ userId: '', platformId: '', datasourceId: '', tool: '', result: '', verdict: '', errorCode: '', from: '', to: '' });
                setQueryTick((t) => t + 1);
              }}
            >
              重置
            </Button>
            <span style={{ flex: 1 }} />
            <TextInput value={quickId} onChange={setQuickId} placeholder="按 requestId 定位" width={280} />
            <Button
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
            </Button>
          </div>
        ) : null}
        <div className="table-scroll" role="region" aria-label="审计记录列表" tabIndex={0} ref={tableScrollRef}>
          {tab === 'audit' ? (
            <Table<AuditRow>
              rowKey="requestId"
              size="middle"
              loading={loading}
              dataSource={audit}
              columns={auditColumns}
              pagination={false}
              locale={{ emptyText: <span /> }}
            />
          ) : (
            <Table<OperationRow>
              rowKey="id"
              size="middle"
              loading={loading}
              dataSource={operations}
              columns={opsColumns}
              pagination={false}
              locale={{ emptyText: <span /> }}
            />
          )}
          {!loading && tab === 'jobs' && operations.length === 0 ? (
            opsUnavailable ? (
              <EmptyState kind="unavailable" title="操作日志端点不可用" description="管理操作日志接口暂时不可用，请稍后重试或联系管理员。" />
            ) : (
              <EmptyState kind="filtered" title="暂无操作记录" description="调整聚合类型筛选后重试。" />
            )
          ) : null}
          {!loading && tab === 'audit' && audit.length === 0 ? (
            <EmptyState kind="filtered" title="暂无审计记录" description="调整筛选条件后重试。" />
          ) : null}
        </div>
        {tab === 'audit' ? (
          <div className="table-footer" style={{ justifyContent: 'space-between' }}>
            <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
              已加载 {audit.length} 条{nextCursor ? ' · 可能还有更多' : ''}
            </span>
            {loading ? null : nextCursor ? (
              <Button
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
              </Button>
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
              <Button
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
              </Button>
            ) : (
              <span style={{ fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>没有更多了</span>
            )}
          </div>
        ) : null}
      </div>
    </>
  );
}
