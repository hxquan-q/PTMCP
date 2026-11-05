import { useEffect, useState } from 'react';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Badge, EmptyState, PageHeader, SearchInput, Select, StatusDot, useDebounced } from '../../../components/common';
import { mePortalApi, type DatasourceItem, type DictionaryItem, type PlatformItem } from '../../../services/mePortalApi';

export function DictionaryPage() {
  const [platforms, setPlatforms] = useState<PlatformItem[]>([]);
  const [datasources, setDatasources] = useState<DatasourceItem[]>([]);
  const [platformId, setPlatformId] = useState('');
  const [datasourceId, setDatasourceId] = useState('');
  const [keyword, setKeyword] = useState('');
  const [rows, setRows] = useState<DictionaryItem[]>([]);
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const debounced = useDebounced(keyword, 350);

  useEffect(() => {
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => setPlatforms(ctx.items.filter((p) => p.tier === 3)))
      .catch(() => setPlatforms([]));
  }, []);

  useEffect(() => {
    if (!platformId) {
      setDatasources([]);
      setDatasourceId('');
      return;
    }
    let cancelled = false;
    mePortalApi.accessContext
      .datasources(platformId)
      .then((ctx) => {
        if (cancelled) return;
        setDatasources(ctx.items);
        setDatasourceId((cur) => (ctx.items.some((d) => d.id === cur) ? cur : ''));
      })
      .catch(() => !cancelled && setDatasources([]));
    return () => {
      cancelled = true;
    };
  }, [platformId]);

  useEffect(() => {
    /* 服务端必选 platformId/datasourceId：未选择时不发请求，避免无意义的 400。 */
    if (!platformId || !datasourceId) {
      setRows([]);
      setNextCursor(null);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    mePortalApi
      .dictionaryList({
        platformId,
        datasourceId,
        keyword: debounced.trim() || undefined,
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
  }, [platformId, datasourceId, debounced]);

  const loadMore = () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    mePortalApi
      .dictionaryList({
        platformId,
        datasourceId,
        keyword: debounced.trim() || undefined,
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

  const openDetail = (id: string) => (window.location.hash = `/me/dictionary/${encodeURIComponent(id)}`);

  const columns: ColumnsType<DictionaryItem> = [
    {
      title: '对象',
      dataIndex: 'objectName',
      render: (_: unknown, o) => (
        <span className="cell-stack">
          <Button type="link" size="small" style={{ padding: 0 }} onClick={() => openDetail(o.id)}>
            {o.objectName}
          </Button>
          <span>v{o.generation}</span>
        </span>
      ),
    },
    {
      title: '库 / Schema',
      key: 'database',
      render: (_: unknown, o) => (
        <span style={{ color: 'var(--ptmcp-text-muted)' }}>
          {o.databaseName}
          {o.schemaName ? ` / ${o.schemaName}` : ''}
        </span>
      ),
    },
    {
      title: '类型',
      dataIndex: 'objectType',
      render: (value: string) => <Badge tone={value === 'TABLE' ? 'accent' : 'neutral'}>{value}</Badge>,
    },
    {
      title: '业务标题',
      dataIndex: 'dictionaryTitle',
      render: (value: string | undefined) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{value ?? '—'}</span>,
    },
    {
      title: '授权',
      dataIndex: 'authorized',
      render: (value: boolean) => <StatusDot tone={value ? 'success' : 'neutral'}>{value ? '已授权' : '未授权'}</StatusDot>,
    },
    {
      title: '操作',
      key: 'action',
      width: 70,
      render: (_: unknown, o) => (
        <Button type="link" size="small" style={{ padding: 0 }} onClick={() => openDetail(o.id)}>
          详情
        </Button>
      ),
    },
  ];

  return (
    <>
      <PageHeader title="我的数据字典" description="当前账号可见的库表结构与业务字典。" />
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
          <Select
            value={platformId}
            onChange={(v) => {
              setPlatformId(v);
              setDatasourceId('');
            }}
            label="平台"
            width={150}
            options={[{ value: '', label: '请选择平台' }, ...platforms.map((p) => ({ value: p.id, label: p.name }))]}
          />
          <Select
            value={datasourceId}
            onChange={setDatasourceId}
            label="数据源"
            width={150}
            options={[{ value: '', label: platformId ? '请选择数据源' : '先选平台' }, ...datasources.map((d) => ({ value: d.id, label: d.name }))]}
          />
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索对象名…" width={220} />
          <span style={{ marginLeft: 'auto', fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>已加载 {rows.length} 个对象</span>
        </div>
        <div className="table-scroll" role="region" aria-label="我的数据字典对象列表" tabIndex={0}>
          <Table<DictionaryItem>
            rowKey="id"
            size="middle"
            loading={loading}
            dataSource={rows}
            columns={columns}
            pagination={false}
            locale={{ emptyText: <span /> }}
          />
          {!loading && rows.length === 0 ? (
            <EmptyState
              title={platformId && datasourceId ? '暂无可见对象' : '请选择平台与数据源'}
              description={platformId && datasourceId ? '调整数据源或关键词后重试。' : '数据字典按平台与数据源加载；选择前不请求服务端。'}
            />
          ) : null}
        </div>
        <div className="table-footer" style={{ justifyContent: 'space-between' }}>
          <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
            已加载 {rows.length} 个{nextCursor ? ' · 可能还有更多' : ''}
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
