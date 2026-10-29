import { useEffect, useMemo, useState } from 'react';
import {
  Badge,
  CellIcon,
  Drawer,
  DrawerFields,
  EmptyState,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatusDot,
  Tabs,
  useDebounced,
} from '../../components/common';
import { CheckboxIcon, DatabaseIcon, DotsIcon, EditIcon, FilterIcon, PlusIcon } from '../../components/Icons';
import { fmtTime as fmt, healthOf, statusOf } from './datasourceStatus';
import { listDatasources, type DatasourceRead } from '../../services/data';

const ENGINE_OPTIONS = [
  { value: '', label: '全部引擎' },
  { value: 'MYSQL', label: 'MySQL' },
  { value: 'POSTGRESQL', label: 'PostgreSQL' },
  { value: 'CLICKHOUSE', label: 'ClickHouse' },
  { value: 'SQLITE', label: 'SQLite' },
  { value: 'ORACLE', label: 'Oracle' },
  { value: 'MONGODB', label: 'MongoDB' },
];
const STATUS_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: 'ENABLED', label: '启用' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'DISABLED', label: '禁用' },
];

export function DatasourcesPage() {
  const [rows, setRows] = useState<DatasourceRead[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [engine, setEngine] = useState('');
  const [status, setStatus] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [page, setPage] = useState(1);
  const [detail, setDetail] = useState<DatasourceRead | null>(null);
  const [detailTab, setDetailTab] = useState('base');
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listDatasources({ keyword: debounced || undefined, pageSize: 100 })
      .then(({ data }) => !cancelled && setRows(data.items))
      .catch(() => !cancelled && setRows([]))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [debounced]);

  const filtered = useMemo(
    () => rows.filter((r) => (!engine || r.engine === engine) && (!status || (r.status ?? '').toUpperCase() === status)),
    [rows, engine, status],
  );
  const pageSize = 20;
  const pageRows = filtered.slice((page - 1) * pageSize, page * pageSize);
  const toggle = (id: string) =>
    setSelected((s) => {
      const next = new Set(s);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const toggleAll = () => setSelected((s) => (s.size === pageRows.length ? new Set() : new Set(pageRows.map((r) => r.id))));

  return (
    <>
      <PageHeader
        title="数据源"
        description="管理受控数据连接、健康状态和元数据同步。"
        actions={
          <button className="btn btn--primary">
            <PlusIcon /> 新建数据源
          </button>
        }
      />
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
          <SearchInput
            value={keyword}
            onChange={(v) => {
              setKeyword(v);
              setPage(1);
            }}
            placeholder="搜索数据源名称、编码、数据库、引擎…"
            width={280}
          />
          <Select label="引擎" value={engine} onChange={setEngine} options={ENGINE_OPTIONS} width={130} />
          <Select label="状态" value={status} onChange={setStatus} options={STATUS_OPTIONS} width={120} />
          <button className="btn btn--primary" style={{ padding: '7px 14px' }}>
            查询
          </button>
          <button
            className="btn btn--secondary"
            onClick={() => {
              setKeyword('');
              setEngine('');
              setStatus('');
            }}
          >
            重置
          </button>
          <button className="btn btn--secondary">
            <FilterIcon /> 更多筛选
          </button>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '10px 14px 4px', flex: 'none' }}>
          <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--ptmcp-text-strong)' }}>
            数据源列表 <span style={{ fontWeight: 400, color: 'var(--ptmcp-text-muted)' }}>共 {filtered.length} 个数据源</span>
          </span>
          <span style={{ display: 'inline-flex', gap: 8 }}>
            <button className="btn btn--secondary">
              <CheckboxIcon /> 批量操作
            </button>
            <button className="btn btn--link" style={{ color: 'var(--ptmcp-text-muted)' }}>
              <DotsIcon />
            </button>
          </span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th style={{ width: 34 }}>
                  <input type="checkbox" checked={pageRows.length > 0 && selected.size === pageRows.length} onChange={toggleAll} aria-label="全选" />
                </th>
                <th>数据源名称 / 编码</th>
                <th>引擎</th>
                <th>数据库</th>
                <th>状态</th>
                <th>健康</th>
                <th>最近同步</th>
                <th>连接池上限</th>
                <th>活动快照</th>
                <th style={{ width: 80 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {pageRows.map((r) => {
                const st = statusOf(r.status);
                const hl = healthOf(r.health);
                return (
                  <tr key={r.id} className={selected.has(r.id) ? 'is-selected' : undefined}>
                    <td>
                      <input type="checkbox" checked={selected.has(r.id)} onChange={() => toggle(r.id)} aria-label={`选择 ${r.name}`} />
                    </td>
                    <td>
                      <span className="cell-lead">
                        <CellIcon icon={DatabaseIcon} engine={r.engine} />
                        <span className="cell-stack">
                          <button
                            className="link"
                            onClick={() => {
                              setDetail(r);
                              setDetailTab('base');
                            }}
                          >
                            {r.name}
                          </button>
                          <span>
                            {r.code} · ID {r.id}
                          </span>
                        </span>
                      </span>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{r.engine}</td>
                    <td>{r.databaseName}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <StatusDot tone={st.tone}>{st.label}</StatusDot>
                    </td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <StatusDot tone={hl.tone}>{hl.label}</StatusDot>
                    </td>
                    <td>
                      <span className="cell-stack">
                        <span style={{ fontVariantNumeric: 'tabular-nums', color: 'var(--ptmcp-text-strong)', fontSize: 12 }}>
                          {fmt(r.lastSyncAt)}
                        </span>
                        {r.lastSyncResult ? (
                          <span style={{ color: r.lastSyncResult === 'FAILED' ? 'var(--ptmcp-red)' : 'var(--ptmcp-green)' }}>
                            {r.lastSyncResult === 'FAILED' ? '失败' : '成功'}
                          </span>
                        ) : null}
                      </span>
                    </td>
                    <td style={{ fontVariantNumeric: 'tabular-nums' }}>{r.poolMax}</td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{r.activeSnapshotId ?? '待同步'}</td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <button
                        className="link"
                        onClick={() => {
                          setDetail(r);
                          setDetailTab('base');
                        }}
                      >
                        查看
                      </button>
                      <button
                        className="link"
                        aria-label={`打开 ${r.name} 详情页`}
                        style={{ marginLeft: 10, color: 'var(--ptmcp-text-muted)' }}
                        onClick={() => (window.location.hash = `/admin/datasources/${encodeURIComponent(r.id)}`)}
                      >
                        <DotsIcon />
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {!loading && pageRows.length === 0 ? <EmptyState title="暂无数据源" description="调整筛选条件，或新建一个数据源连接。" /> : null}
        </div>
        <div className="table-footer">
          <span>{selected.size > 0 ? `已选择 ${selected.size} 项` : ' '}</span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 14 }}>
            <span>共 {filtered.length} 条</span>
            <Pagination total={filtered.length} pageSize={pageSize} page={page} onPage={setPage} />
          </span>
        </div>
      </div>
      <Drawer
        open={!!detail}
        onClose={() => setDetail(null)}
        width={460}
        title={detail?.name}
        subtitle={detail ? `${detail.code} · ID ${detail.id}` : ''}
        icon={detail ? <CellIcon icon={DatabaseIcon} engine={detail.engine} large /> : null}
        badge={
          detail ? (
            <Badge tone={statusOf(detail.status).tone === 'neutral' ? 'neutral' : statusOf(detail.status).tone}>
              {statusOf(detail.status).label}
            </Badge>
          ) : null
        }
        footer={
          <>
            <button className="btn btn--secondary" onClick={() => setDetail(null)}>
              关闭
            </button>
            <button className="btn btn--primary">
              <EditIcon /> 编辑
            </button>
          </>
        }
      >
        {detail ? (
          <>
            <Tabs
              active={detailTab}
              onChange={setDetailTab}
              tabs={[
                { key: 'base', label: '基本信息' },
                { key: 'conn', label: '连接配置' },
                { key: 'sync', label: '同步状态' },
                { key: 'usage', label: '使用情况' },
              ]}
            />
            {detailTab === 'base' ? (
              <DrawerFields
                fields={[
                  { label: '数据源名称', value: detail.name },
                  { label: '编码', value: detail.code },
                  { label: '引擎', value: detail.engine },
                  { label: '数据库', value: detail.databaseName },
                  { label: '状态', value: <StatusDot tone={statusOf(detail.status).tone}>{statusOf(detail.status).label}</StatusDot> },
                  { label: '健康状态', value: <StatusDot tone={healthOf(detail.health).tone}>{healthOf(detail.health).label}</StatusDot> },
                  { label: '最近同步', value: fmt(detail.lastSyncAt) },
                  { label: '连接池上限', value: detail.poolMax },
                  { label: '活动快照', value: detail.activeSnapshotId ?? '待同步' },
                  { label: '创建时间', value: fmt(detail.createdAt ?? null) },
                  { label: '更新时间', value: fmt(detail.updatedAt ?? detail.lastSyncAt ?? null) },
                ]}
              />
            ) : null}
            {detailTab === 'conn' ? (
              <DrawerFields
                fields={[
                  { label: '主机', value: detail.host },
                  { label: '端口', value: detail.port },
                  { label: '数据库', value: detail.databaseName },
                  { label: '用户名', value: detail.username },
                  { label: 'TLS 模式', value: detail.tlsMode },
                  { label: '凭证', value: detail.credentialConfigured ? '已配置' : '未配置' },
                  { label: '连接池上限', value: detail.poolMax },
                ]}
              />
            ) : null}
            {detailTab === 'sync' ? (
              <DrawerFields
                fields={[
                  { label: '最近同步', value: fmt(detail.lastSyncAt) },
                  { label: '同步结果', value: detail.lastSyncResult ? (detail.lastSyncResult === 'FAILED' ? '失败' : '成功') : '—' },
                  { label: '活动快照', value: detail.activeSnapshotId ?? '待同步' },
                  { label: '配置版本', value: detail.configVersion ?? '—' },
                  { label: '资源版本', value: detail.resourceVersion ?? '—' },
                ]}
              />
            ) : null}
            {detailTab === 'usage' ? (
              <DrawerFields
                fields={[
                  { label: '连接池上限', value: detail.poolMax },
                  { label: '活动快照', value: detail.activeSnapshotId ?? '待同步' },
                  { label: '版本', value: detail.version ?? '—' },
                ]}
              />
            ) : null}
          </>
        ) : null}
      </Drawer>
    </>
  );
}
