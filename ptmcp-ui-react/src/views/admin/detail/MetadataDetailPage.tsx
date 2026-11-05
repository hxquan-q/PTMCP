import { useEffect, useState } from 'react';
import { Alert, Badge, DrawerFields, EmptyState, PageHeader, StatusDot } from '../../../components/common';
import { getMetadataObject, type MetadataObject } from '../../../services/data';

import { DictionaryEditor } from './DictionaryEditor';
import { BackBar, errorText, useDatasources } from './shared';

export function MetadataDetailPage({ id }: { id: string }) {
  const [obj, setObj] = useState<MetadataObject | null>(null);
  const [etag, setEtag] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [, dsName] = useDatasources();

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setObj(null);
    setError('');
    getMetadataObject(id)
      .then(({ data, etag }) => {
        if (cancelled) return;
        setObj(data);
        setEtag(etag);
      })
      .catch((e) => !cancelled && setError(errorText(e)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [id]);

  const dict = obj?.dictionary;

  return (
    /* 详情页阅读列：860px 在宽屏居中，标题/返回/卡片同列对齐。 */
    <div style={{ maxWidth: 860, marginInline: 'auto' }}>
      <BackBar to="/admin/metadata" label="返回元数据与字典" />
      <PageHeader
        title={obj ? (dict?.title ?? obj.objectName) : '元数据详情'}
        description={obj ? `${obj.databaseName}${obj.schemaName ? ` / ${obj.schemaName}` : ''} · ${obj.objectType}` : '元数据对象结构与业务字典。'}
      />
      {error ? (
        <Alert kind="danger" title="元数据详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载元数据详情…</Alert> : null}
      {obj ? (
        <>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860, marginBottom: 14 }}>
            {dict?.description ? <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--ptmcp-text)' }}>{dict.description}</p> : null}
            <DrawerFields
              fields={[
                { label: '对象名', value: <code>{obj.objectName}</code> },
                { label: '表名', value: <code>{obj.tableName}</code> },
                { label: '数据源', value: dsName(obj.datasourceId) },
                { label: '类型', value: <Badge tone={obj.objectType === 'TABLE' ? 'accent' : 'neutral'}>{obj.objectType}</Badge> },
                { label: '代次', value: `v${obj.generation}` },
                { label: '快照', value: obj.snapshotId ? `#${obj.snapshotId}` : '—' },
                {
                  label: '授权',
                  value:
                    typeof obj.authorized === 'boolean' ? (
                      <StatusDot tone={obj.authorized ? 'success' : 'neutral'}>{obj.authorized ? '已授权' : '未授权'}</StatusDot>
                    ) : (
                      <StatusDot tone="neutral">未知</StatusDot>
                    ),
                },
                {
                  label: '字典可见',
                  value: typeof obj.metadataVisible === 'boolean' ? (obj.metadataVisible ? '可见' : '隐藏') : '未知',
                },
                { label: '表注释', value: obj.tableComment ?? '—' },
              ]}
            />
          </div>
          <DictionaryEditor
            obj={obj}
            etag={etag}
            onSaved={(next, nextEtag) => {
              setObj(next);
              setEtag(nextEtag ?? etag);
            }}
          />
          <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
            <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--ptmcp-border)', fontSize: 13, fontWeight: 600 }}>
              列结构{' '}
              <span style={{ color: 'var(--ptmcp-text-faint)', fontWeight: 400 }}>{obj.columns?.length ? `${obj.columns.length} 列` : ''}</span>
            </div>
            <div className="table-scroll" role="region" aria-label="列结构" tabIndex={0}>
              {obj.columns?.length ? (
                <table className="data-table">
                  <thead>
                    <tr>
                      <th style={{ width: 40 }}>#</th>
                      <th>列名</th>
                      <th>类型</th>
                      <th>可空</th>
                      <th>键</th>
                      <th>注释</th>
                    </tr>
                  </thead>
                  <tbody>
                    {obj.columns.map((c) => {
                      const dictCol = dict?.columns?.find((d) => d.name === c.name);
                      return (
                        <tr key={c.ordinal}>
                          <td style={{ color: 'var(--ptmcp-text-faint)' }}>{c.ordinal}</td>
                          <td>
                            <code style={{ fontSize: 12 }}>{c.name}</code>
                            {dictCol?.displayName ? (
                              <span style={{ marginLeft: 6, fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{dictCol.displayName}</span>
                            ) : null}
                          </td>
                          <td>
                            <Badge tone="neutral">
                              {c.databaseType}
                              {c.precision != null ? `(${c.precision}${c.scale != null ? `,${c.scale}` : ''})` : ''}
                            </Badge>
                          </td>
                          <td style={{ color: 'var(--ptmcp-text-muted)' }}>{c.nullable ? '是' : '否'}</td>
                          <td>
                            {c.primaryKey ? <Badge tone="accent">PK</Badge> : null}
                            {c.foreignKey ? <Badge tone="neutral">FK</Badge> : null}
                          </td>
                          <td style={{ color: 'var(--ptmcp-text-muted)' }}>
                            {dictCol?.description ?? c.comment ?? '—'}
                            {dictCol?.sensitiveTags?.length ? (
                              <span style={{ marginLeft: 6 }}>
                                {dictCol.sensitiveTags.map((t) => (
                                  <Badge key={t} tone="warning">
                                    {t}
                                  </Badge>
                                ))}
                              </span>
                            ) : null}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              ) : (
                <EmptyState title="该对象没有列信息" />
              )}
            </div>
          </div>
        </>
      ) : null}
    </div>
  );
}

/* ─── 数据源详情:/admin/datasources/{id} — 编辑表单 + 乐观并发 ─── */
