import { useEffect, useMemo, useState } from 'react';
import { Badge, EmptyState, PageHeader, SearchInput, Select, StatusDot, useDebounced } from '../../components/common';
import { listDatasources, listMetadata, type DatasourceRead, type MetadataObject } from '../../services/data';

export function MetadataPage() {
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  const [datasourceId, setDatasourceId] = useState('');
  const [rows, setRows] = useState<MetadataObject[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    listDatasources({ pageSize: 100 })
      .then(({ data }) => {
        if (cancelled) return;
        setDatasources(data.items);
        setDatasourceId((cur) => cur || data.items[0]?.id || '');
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!datasourceId) {
      setRows([]);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    listMetadata({ datasourceId, pageSize: 200 })
      .then(({ data }) => !cancelled && setRows(data.items))
      .catch(() => !cancelled && setRows([]))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [datasourceId]);

  const filtered = useMemo(
    () =>
      rows.filter(
        (r) =>
          !debounced ||
          (r.objectName ?? '').toLowerCase().includes(debounced.toLowerCase()) ||
          (r.tableName ?? '').toLowerCase().includes(debounced.toLowerCase()),
      ),
    [rows, debounced],
  );

  return (
    <>
      <PageHeader title="元数据与字典" description="按数据源浏览元数据对象与业务字典。" />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: '12px 14px',
            borderBottom: '1px solid var(--ptmcp-border)',
            flex: 'none',
          }}
        >
          <Select value={datasourceId} onChange={setDatasourceId} options={datasources.map((d) => ({ value: d.id, label: d.name }))} width={220} />
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索对象名或表名…" width={240} />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {filtered.length} 个对象</span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>对象</th>
                <th>库 / Schema</th>
                <th>类型</th>
                <th>代次</th>
                <th>授权</th>
                <th>可见</th>
                <th style={{ width: 60 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
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
                    <StatusDot tone={r.authorized ? 'success' : 'neutral'}>{r.authorized ? '已授权' : '未授权'}</StatusDot>
                  </td>
                  <td>
                    <StatusDot tone={r.metadataVisible ? 'success' : 'warning'}>{r.metadataVisible ? '可见' : '隐藏'}</StatusDot>
                  </td>
                  <td>
                    <button className="link" onClick={() => (window.location.hash = `/admin/metadata/${r.id}`)}>
                      详情
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && filtered.length === 0 ? <EmptyState title="没有匹配的元数据对象" description="调整搜索关键字或切换数据源。" /> : null}
        </div>
      </div>
    </>
  );
}
