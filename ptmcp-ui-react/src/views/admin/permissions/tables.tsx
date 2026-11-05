import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Badge, CellIcon, StatusDot } from '../../../components/common';
import { BuildingIcon, DatabaseIcon, DocumentIcon } from '../../../components/Icons';
import { type Grant, type Preview } from '../../../services/access';
import { type MetadataObject } from '../../../services/data';
import { decodeResourceId } from '../../../services/resourceIds';
import { validityLabel } from './parts';

type PreviewDecision = Preview['decisions'][number];

/** 授权规则表 — 分页由 Pane footer 控制，表格只渲染当前页。 */
export function GrantsTable({
  rows,
  metadataObjects,
  revokingId,
  onRevoke,
}: {
  rows: Grant[];
  metadataObjects: MetadataObject[];
  revokingId: string;
  onRevoke: (id: string) => void;
}) {
  const columns: ColumnsType<Grant> = [
    {
      title: '资源名称',
      dataIndex: 'resourceId',
      render: (_: unknown, g) => {
        const obj = metadataObjects.find((o) => o.id === g.resourceId);
        const display = obj?.objectName ?? obj?.tableName ?? g.resourceId;
        return (
          <span className="cell-lead">
            <CellIcon icon={g.resourceType === 'OBJECT' ? DocumentIcon : g.resourceType === 'NAMESPACE' ? BuildingIcon : DatabaseIcon} />
            <span className="cell-stack">
              <strong>{display}</strong>
              <span>
                ID {g.id} · 资源 {g.resourceId}
              </span>
            </span>
          </span>
        );
      },
    },
    {
      title: '类型',
      dataIndex: 'resourceType',
      render: (v: string) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{v}</span>,
    },
    {
      title: '权限',
      dataIndex: 'action',
      render: (v: Grant['action']) => <Badge tone={v === 'READ' ? 'accent' : 'neutral'}>{v}</Badge>,
    },
    {
      title: '效果',
      dataIndex: 'effect',
      render: (v: Grant['effect']) => <StatusDot tone={v === 'ALLOW' ? 'success' : 'danger'}>{v === 'ALLOW' ? '允许' : '拒绝'}</StatusDot>,
    },
    {
      title: '含下级',
      dataIndex: 'includeDescendants',
      render: (v: boolean) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{v ? '是' : '否'}</span>,
    },
    {
      title: '有效期',
      dataIndex: 'validTo',
      render: (v: string | null) => <span style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{validityLabel(v)}</span>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: (v: Grant['status']) => <StatusDot tone={v === 'ACTIVE' ? 'success' : 'neutral'}>{v === 'ACTIVE' ? '生效中' : '已撤销'}</StatusDot>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 90,
      render: (_: unknown, g) =>
        g.status === 'ACTIVE' ? (
          <Button type="link" size="small" style={{ padding: 0 }} disabled={revokingId === g.id} onClick={() => onRevoke(g.id)}>
            {revokingId === g.id ? '撤销中…' : '撤销'}
          </Button>
        ) : (
          <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>—</span>
        ),
    },
  ];

  return <Table<Grant> rowKey="id" size="middle" dataSource={rows} columns={columns} pagination={false} locale={{ emptyText: <span /> }} />;
}

/** 访问预览决策表 — 服务端按资源逐项给出 META/READ 判定与命中依据。 */
export function PreviewTable({ decisions, metadataObjects }: { decisions: PreviewDecision[]; metadataObjects: MetadataObject[] }) {
  const columns: ColumnsType<PreviewDecision> = [
    {
      title: '资源',
      dataIndex: 'resourceId',
      render: (v: string) => {
        const decoded = decodeResourceId(v);
        const obj = decoded?.type === 'OBJECT' ? metadataObjects.find((o) => o.id === decoded.tableId) : undefined;
        const label = obj?.objectName ?? obj?.tableName ?? `${decoded?.type ?? 'RESOURCE'} #${decoded?.tableId ?? v}`;
        return <span style={{ fontWeight: 500 }}>{label}</span>;
      },
    },
    {
      title: 'META',
      dataIndex: 'meta',
      width: 90,
      align: 'center',
      render: (v: boolean) => <StatusDot tone={v ? 'success' : 'danger'}>{v ? '允许' : '拒绝'}</StatusDot>,
    },
    {
      title: 'READ',
      dataIndex: 'read',
      width: 90,
      align: 'center',
      render: (v: boolean) => <StatusDot tone={v ? 'success' : 'danger'}>{v ? '允许' : '拒绝'}</StatusDot>,
    },
    {
      title: '依据',
      dataIndex: 'sourceGrantIds',
      render: (v: string[]) => (
        <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{v.length ? `授权 ${v.join(', ')}` : '无命中规则'}</span>
      ),
    },
  ];

  return (
    <Table<PreviewDecision>
      rowKey="resourceId"
      size="middle"
      dataSource={decisions}
      columns={columns}
      pagination={false}
      scroll={{ x: 480 }}
      locale={{ emptyText: <span /> }}
    />
  );
}
