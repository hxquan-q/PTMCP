import { useEffect, useState } from 'react';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Badge, EmptyState, PageHeader, Select, StatusDot, TextInput } from '../../../components/common';
import { listMyHistory, type AuditRow } from '../../../services/access';
import { formatTaipeiTime as fmt } from '../../../services/dateTime';

export function HistoryPage() {
  const [rows, setRows] = useState<AuditRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [from, setFrom] = useState(() => new Date(Date.now() - 7 * 86400_000).toISOString().slice(0, 10));
  const [to, setTo] = useState(() => new Date().toISOString().slice(0, 10));
  const [tool, setTool] = useState('');
  const [result, setResult] = useState('');
  const [verdict, setVerdict] = useState('');
  const [queryTick, setQueryTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listMyHistory({
      from: from ? `${from}T00:00:00Z` : undefined,
      to: to ? `${to}T23:59:59Z` : undefined,
      tool: tool || undefined,
      result: result || undefined,
      verdict: verdict || undefined,
      pageSize: 20,
    })
      .then((p) => {
        if (cancelled) return;
        setRows(p.items);
        setNextCursor(p.nextCursor);
      })
      .catch(() => {
        if (!cancelled) {
          setRows([]);
          setNextCursor(null);
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [queryTick, from, to, tool, result, verdict]);

  const loadMore = () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    listMyHistory({
      from: from ? `${from}T00:00:00Z` : undefined,
      to: to ? `${to}T23:59:59Z` : undefined,
      tool: tool || undefined,
      result: result || undefined,
      verdict: verdict || undefined,
      cursor: nextCursor,
      pageSize: 20,
    })
      .then((p) => {
        setRows((prev) => [...prev, ...p.items]);
        setNextCursor(p.nextCursor);
      })
      .catch(() => undefined)
      .finally(() => setLoadingMore(false));
  };

  const columns: ColumnsType<AuditRow> = [
    {
      title: '时间',
      dataIndex: 'startedAt',
      render: (value: string, r) => (
        <Button
          type="link"
          size="small"
          style={{ padding: 0, whiteSpace: 'nowrap' }}
          onClick={() => (window.location.hash = `/me/history/${encodeURIComponent(r.requestId)}`)}
          aria-label={`查看请求 ${r.requestId} 详情：${fmt(r.startedAt)}`}
        >
          {fmt(value)}
        </Button>
      ),
    },
    {
      title: '工具',
      dataIndex: 'tool',
      render: (value: string) => <Badge tone="neutral">{value}</Badge>,
    },
    {
      title: 'SQL(脱敏)',
      dataIndex: 'sqlMasked',
      render: (value: string | undefined) => (
        <span style={{ color: 'var(--ptmcp-text-muted)', maxWidth: 360, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          <code style={{ fontSize: 12 }}>{value ?? '—'}</code>
        </span>
      ),
    },
    {
      title: '行数',
      dataIndex: 'rowsReturned',
      render: (value: number | null | undefined) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{value ?? '—'}</span>,
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      render: (value: number | null | undefined) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{value != null ? `${value} ms` : '—'}</span>,
    },
    {
      title: '结果',
      dataIndex: 'result',
      render: (value: string) => (
        <StatusDot tone={value === 'SUCCESS' ? 'success' : value === 'ERROR' ? 'danger' : 'warning'}>
          {value === 'SUCCESS' ? '成功' : value === 'ERROR' ? '失败' : '进行中'}
        </StatusDot>
      ),
    },
  ];

  return (
    <>
      <PageHeader title="查询历史" description="你的 MCP 查询与工具调用记录。" />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
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
          <TextInput label="开始日期" value={from} onChange={setFrom} type="date" width={150} />
          <span style={{ color: 'var(--ptmcp-text-faint)' }}>→</span>
          <TextInput label="结束日期" value={to} onChange={setTo} type="date" width={150} />
          <Select
            value={tool}
            onChange={setTool}
            label="工具"
            options={[
              { value: '', label: '全部工具' },
              { value: 'query', label: 'query' },
              { value: 'catalog', label: 'catalog' },
              { value: 'schema', label: 'schema' },
            ]}
          />
          <Select
            value={result}
            onChange={setResult}
            label="结果"
            options={[
              { value: '', label: '全部结果' },
              { value: 'SUCCESS', label: '成功' },
              { value: 'ERROR', label: '失败' },
              { value: 'UNKNOWN', label: '未知' },
            ]}
          />
          <Select
            value={verdict}
            onChange={setVerdict}
            label="判定"
            options={[
              { value: '', label: '全部判定' },
              { value: 'ALLOW', label: '允许' },
              { value: 'DENY', label: '拒绝' },
            ]}
          />
          <Button onClick={() => setQueryTick((t) => t + 1)}>查询</Button>
          <Button
            onClick={() => {
              setFrom(new Date(Date.now() - 7 * 86400_000).toISOString().slice(0, 10));
              setTo(new Date().toISOString().slice(0, 10));
              setTool('');
              setResult('');
              setVerdict('');
              setQueryTick((t) => t + 1);
            }}
          >
            重置
          </Button>
        </div>
        <div className="table-scroll" role="region" aria-label="查询历史列表" tabIndex={0}>
          <Table<AuditRow>
            rowKey="requestId"
            size="middle"
            loading={loading}
            dataSource={rows}
            columns={columns}
            pagination={false}
            locale={{ emptyText: <span /> }}
          />
          {!loading && rows.length === 0 ? <EmptyState title="暂无查询历史" description="调整时间范围或筛选条件后重试。" /> : null}
        </div>
        <div className="table-footer" style={{ justifyContent: 'space-between' }}>
          <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
            已加载 {rows.length} 条{nextCursor ? ' · 可能还有更多' : ''}
          </span>
          {loading ? null : nextCursor ? (
            <Button onClick={loadMore} disabled={loadingMore}>
              {loadingMore ? '加载中…' : '加载更多'}
            </Button>
          ) : (
            <span style={{ fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>没有更多了</span>
          )}
        </div>
      </div>
    </>
  );
}
