import { useEffect, useState } from 'react';
import { Alert, Badge, EmptyState, PageHeader, RequestIdText, SearchInput, Select, StatusDot, useDebounced } from '../../components/common';
import { listDatasources, listMetadata, type DatasourceRead, type MetadataObject } from '../../services/data';
import { readFailure, type Failure } from '../../services/http';

/** 服务端未返回该字段时不能声称「未授权」或「隐藏」，只能显示未知。 */
function StateDot({ value, yes, no, noTone = 'neutral' }: { value?: boolean | null; yes: string; no: string; noTone?: 'neutral' | 'warning' }) {
  if (typeof value !== 'boolean') return <StatusDot tone="neutral">未知</StatusDot>;
  return <StatusDot tone={value ? 'success' : noTone}>{value ? yes : no}</StatusDot>;
}

export function MetadataPage() {
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  const [dsLoading, setDsLoading] = useState(true);
  const [dsFailure, setDsFailure] = useState<Failure | null>(null);
  const [dsRetry, setDsRetry] = useState(0);
  const [datasourceId, setDatasourceId] = useState('');

  const [rows, setRows] = useState<MetadataObject[]>([]);
  const [loading, setLoading] = useState(false);
  const [failure, setFailure] = useState<Failure | null>(null);
  const [metaRetry, setMetaRetry] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [moreLoading, setMoreLoading] = useState(false);
  const [moreFailure, setMoreFailure] = useState<Failure | null>(null);
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    setDsLoading(true);
    setDsFailure(null);
    listDatasources({ pageSize: 100 })
      .then(({ data }) => {
        if (cancelled) return;
        setDatasources(data.items);
        setDatasourceId((cur) => cur || data.items[0]?.id || '');
      })
      .catch((error: unknown) => !cancelled && setDsFailure(readFailure(error, '数据源目录读取失败')))
      .finally(() => !cancelled && setDsLoading(false));
    return () => {
      cancelled = true;
    };
  }, [dsRetry]);

  useEffect(() => {
    if (!datasourceId) {
      setRows([]);
      setNextCursor(null);
      setFailure(null);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setFailure(null);
    setMoreFailure(null);
    setNextCursor(null);
    /* 切换数据源或关键字后旧行属于另一个集合，先清空，避免把上一批结果显示成新数据源的容量。 */
    setRows([]);
    listMetadata({ datasourceId, keyword: debounced.trim() || undefined, pageSize: 20 })
      .then(({ data }) => {
        if (cancelled) return;
        setRows(data.items);
        setNextCursor(data.nextCursor);
      })
      .catch((error: unknown) => !cancelled && setFailure(readFailure(error, '元数据读取失败')))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [datasourceId, debounced, metaRetry]);

  const statusText = dsLoading
    ? '正在读取数据源目录…'
    : dsFailure
      ? '数据源目录读取失败'
      : loading
        ? '正在读取元数据对象…'
        : failure
          ? '元数据读取失败'
          : `已加载 ${rows.length} 个元数据对象`;

  return (
    <>
      <PageHeader title="元数据与字典" description="按数据源浏览元数据对象；授权与可见状态来自服务端交集判定。" />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div className="table-toolbar">
          <Select
            value={datasourceId}
            onChange={setDatasourceId}
            options={datasources.map((d) => ({ value: d.id, label: d.name }))}
            label="数据源"
            width={220}
            disabled={dsLoading || datasources.length === 0 || !!dsFailure}
          />
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索对象名或表名…" label="搜索对象名或表名" width={240} />
          <span className="toolbar-status">已加载 {rows.length} 个对象</span>
        </div>
        <p className="sr-only" aria-live="polite">
          {statusText}
        </p>

        <div className="table-scroll" role="region" aria-label="元数据对象列表" tabIndex={0}>
          {dsLoading ? <p className="table-state">正在读取数据源目录…</p> : null}
          {dsFailure ? (
            <Alert kind="danger" title="数据源目录读取失败">
              {dsFailure.message}
              <RequestIdText requestId={dsFailure.requestId} />
              <button className="btn btn--secondary" style={{ marginTop: 8 }} onClick={() => setDsRetry((n) => n + 1)}>
                重新读取
              </button>
            </Alert>
          ) : null}
          {!dsLoading && !dsFailure && datasources.length === 0 ? (
            <EmptyState
              title="还没有登记的数据源"
              description="元数据按数据源浏览；请先在数据源页登记数据源并完成同步。"
              action={
                <button className="btn btn--secondary" onClick={() => (window.location.hash = '/admin/datasources')}>
                  前往数据源页
                </button>
              }
            />
          ) : null}

          {datasourceId && loading ? <p className="table-state">正在读取元数据对象…</p> : null}
          {datasourceId && failure ? (
            <Alert kind="danger" title="元数据读取失败">
              列表当前不可信，请勿据此判断对象是否已授权。{failure.message}
              <RequestIdText requestId={failure.requestId} />
              <button className="btn btn--secondary" style={{ marginTop: 8 }} onClick={() => setMetaRetry((n) => n + 1)}>
                重新读取
              </button>
            </Alert>
          ) : null}
          {datasourceId && !loading && !failure && rows.length === 0 ? (
            debounced.trim() ? (
              <EmptyState
                title={`没有匹配「${debounced.trim()}」的元数据对象`}
                description="搜索在服务端按对象名或表名过滤；清空关键字可回到该数据源的完整列表。"
                action={
                  <button className="btn btn--secondary" onClick={() => setKeyword('')}>
                    清空搜索
                  </button>
                }
              />
            ) : (
              <EmptyState
                title="该数据源暂无元数据对象"
                description="可能尚未同步或快照未发布；本页面不代替服务端触发同步。"
                action={
                  <button className="btn btn--secondary" onClick={() => (window.location.hash = '/admin/datasources')}>
                    前往数据源页同步
                  </button>
                }
              />
            )
          ) : null}

          {rows.length > 0 ? (
            <table className="data-table">
              <thead>
                <tr>
                  <th scope="col">对象</th>
                  <th scope="col">库 / Schema</th>
                  <th scope="col">类型</th>
                  <th scope="col">代次</th>
                  <th scope="col">授权</th>
                  <th scope="col">可见</th>
                  <th scope="col" style={{ width: 60 }}>
                    操作
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id}>
                    <td>
                      <span className="cell-stack">
                        <strong>{r.objectName ?? r.tableName}</strong>
                        <span>{r.tableComment || `ID ${r.id}`}</span>
                      </span>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>
                      {r.databaseName}
                      {r.schemaName ? ` / ${r.schemaName}` : ''}
                    </td>
                    <td>
                      <Badge tone={r.objectType === 'TABLE' ? 'accent' : 'neutral'}>{r.objectType}</Badge>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>v{r.generation}</td>
                    <td>
                      <StateDot value={r.authorized} yes="已授权" no="未授权" />
                    </td>
                    <td>
                      <StateDot value={r.metadataVisible} yes="可见" no="隐藏" noTone="warning" />
                    </td>
                    <td>
                      <button
                        className="link"
                        onClick={() => (window.location.hash = `/admin/metadata/${r.id}`)}
                        aria-label={`查看 ${r.objectName ?? r.tableName} 详情`}
                      >
                        详情
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </div>

        <div className="table-footer">
          <span className="footer-note">
            {nextCursor ? `已加载 ${rows.length} 个 · 服务端仍有更多` : `已加载 ${rows.length} 个 · 已到末尾`}（契约未返回总数）
          </span>
          {moreFailure ? (
            <span className="footer-error">
              续读失败：{moreFailure.message}
              <RequestIdText requestId={moreFailure.requestId} />
            </span>
          ) : null}
          {datasourceId && !loading && !failure && nextCursor ? (
            <button
              className="btn btn--secondary"
              disabled={moreLoading}
              onClick={() => {
                if (!nextCursor || moreLoading) return;
                setMoreLoading(true);
                setMoreFailure(null);
                listMetadata({ datasourceId, keyword: debounced.trim() || undefined, cursor: nextCursor, pageSize: 20 })
                  .then(({ data }) => {
                    setRows((prev) => [...prev, ...data.items]);
                    setNextCursor(data.nextCursor);
                  })
                  .catch((error: unknown) => setMoreFailure(readFailure(error, '续读失败，可再次点击重试')))
                  .finally(() => setMoreLoading(false));
              }}
            >
              {moreLoading ? '加载中…' : moreFailure ? '重试续读' : '加载更多'}
            </button>
          ) : null}
        </div>
      </div>
    </>
  );
}
