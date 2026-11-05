import { useEffect, useState } from 'react';
import { Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Alert, Badge, DrawerFields, EmptyState, PageHeader } from '../../../components/common';
import type { MetadataColumn, MetadataObject } from '../../../services/data';
import { mePortalApi } from '../../../services/mePortalApi';
import { BackBar } from '../../admin/DetailPages';

export function DictionaryDetailPage({ objectId }: { objectId: string }) {
  const [obj, setObj] = useState<MetadataObject | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    setObj(null);
    mePortalApi
      .dictionaryDetail<MetadataObject>(objectId, '1')
      .then((data) => !cancelled && setObj(data))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '读取失败'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [objectId]);

  const dict = obj?.dictionary;

  const columnDefs: ColumnsType<MetadataColumn> = [
    { title: '#', dataIndex: 'ordinal', width: 40, render: (v: number) => <span style={{ color: 'var(--ptmcp-text-faint)' }}>{v}</span> },
    { title: '列名', dataIndex: 'name', render: (v: string) => <code style={{ fontSize: 12 }}>{v}</code> },
    { title: '类型', dataIndex: 'databaseType', render: (v: string) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{v}</span> },
    {
      title: '业务标题',
      dataIndex: 'name',
      render: (_v: string, c) => dict?.columns?.find((d) => (d.name ?? '').toLowerCase() === c.name.toLowerCase())?.displayName ?? '—',
    },
    {
      title: '业务说明',
      dataIndex: 'name',
      render: (_v: string, c) => {
        const description = dict?.columns?.find((d) => (d.name ?? '').toLowerCase() === c.name.toLowerCase())?.description;
        return <span style={{ color: 'var(--ptmcp-text-muted)' }}>{description ?? '—'}</span>;
      },
    },
  ];

  return (
    /* 详情页阅读列：860px 在宽屏居中，标题/返回/卡片同列对齐。 */
    <div style={{ maxWidth: 860, marginInline: 'auto' }}>
      <BackBar to="/me/dictionary" label="返回我的数据字典" />
      <PageHeader
        title={obj ? (dict?.title ?? obj.objectName) : '字典详情'}
        description={
          obj ? `${obj.databaseName}${obj.schemaName ? ` / ${obj.schemaName}` : ''} · ${obj.objectType} · 个人视角只读` : '对象结构与业务字典。'
        }
      />
      {error ? (
        <Alert kind="danger" title="字典详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载字典详情…</Alert> : null}
      {obj ? (
        <>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860, marginBottom: 14 }}>
            {dict?.description ? <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--ptmcp-text)' }}>{dict.description}</p> : null}
            <DrawerFields
              fields={[
                { label: '对象名', value: <code>{obj.objectName}</code> },
                { label: '类型', value: <Badge tone={obj.objectType === 'TABLE' ? 'accent' : 'neutral'}>{obj.objectType}</Badge> },
                { label: '代次', value: `v${obj.generation}` },
                { label: '表注释', value: obj.tableComment ?? '—' },
              ]}
            />
          </div>
          <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
            <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--ptmcp-border)', fontSize: 13, fontWeight: 600 }}>
              列结构{' '}
              <span style={{ color: 'var(--ptmcp-text-faint)', fontWeight: 400 }}>{obj.columns?.length ? `${obj.columns.length} 列` : ''}</span>
            </div>
            <div className="table-scroll" role="region" aria-label="列结构" tabIndex={0}>
              {obj.columns?.length ? (
                <Table<MetadataColumn>
                  rowKey="ordinal"
                  size="middle"
                  dataSource={obj.columns}
                  columns={columnDefs}
                  pagination={false}
                  locale={{ emptyText: <span /> }}
                />
              ) : (
                <EmptyState title="无列结构信息" description="该对象未同步列结构，请联系管理员触发元数据同步。" />
              )}
            </div>
          </div>
        </>
      ) : null}
    </div>
  );
}
