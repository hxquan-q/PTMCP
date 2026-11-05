import { useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Dialog,
  Field,
  CellIcon,
  Drawer,
  DrawerFields,
  EmptyState,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  TextInput,
  StatusDot,
  Tabs,
  useDebounced,
} from '../../components/common';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { CheckboxIcon, DatabaseIcon, DotsIcon, EditIcon, PlusIcon } from '../../components/Icons';
import { fmtTime as fmt, healthOf, statusOf } from './datasourceStatus';
import { DATASOURCE_ENGINES, DATASOURCE_STATUSES, dsCreateValidate, emptyDsForm, type DsForm } from './dsForm';
import { createDatasource } from '../../services/data';
import { useAdminGate } from '../../adminGateContext';
import { showToast } from '../../toast';
import { listDatasources, type DatasourceRead } from '../../services/data';

/* 单次拉取上限：超过这个条数时列表会提示「仅显示前 N 条」。 */
const PAGE_FETCH_SIZE = 100;

const ENGINE_OPTIONS = [{ value: '', label: '全部引擎' }, ...DATASOURCE_ENGINES];
const STATUS_OPTIONS = [{ value: '', label: '全部状态' }, ...DATASOURCE_STATUSES];

export function DatasourcesPage() {
  const isAdmin = useAdminGate();
  const [rows, setRows] = useState<DatasourceRead[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const [engine, setEngine] = useState('');
  const [status, setStatus] = useState('');
  const [selected, setSelected] = useState<Set<string>>(new Set());
  /* 服务端还有下一页时如实告知，而不是把前 N 条当成全部。 */
  const [truncated, setTruncated] = useState(false);
  const [page, setPage] = useState(1);
  const [detail, setDetail] = useState<DatasourceRead | null>(null);
  const [detailTab, setDetailTab] = useState('base');
  const [creating, setCreating] = useState(false);
  const debounced = useDebounced(keyword);

  const [reloadTick, setReloadTick] = useState(0);
  /* 引擎/状态由服务端过滤：客户端过滤只能看到已拉取的那一页，超出 pageSize 的匹配会被静默丢掉，
     列表头的「共 N 个」也会偏小。keyword 已经过 useDebounced，三个筛选条件共同决定一次请求。 */
  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listDatasources({ keyword: debounced || undefined, engine: engine || undefined, status: status || undefined, pageSize: PAGE_FETCH_SIZE })
      .then(({ data }) => {
        if (cancelled) return;
        setRows(data.items);
        setTruncated(data.nextCursor !== null);
      })
      .catch(() => {
        if (cancelled) return;
        setRows([]);
        setTruncated(false);
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [debounced, engine, status, reloadTick]);

  const filtered = rows;
  const pageSize = 20;
  const pageRows = filtered.slice((page - 1) * pageSize, page * pageSize);

  const columns: ColumnsType<DatasourceRead> = [
    {
      title: '数据源名称 / 编码',
      key: 'name',
      render: (_, r) => (
        <span className="cell-lead">
          <CellIcon icon={DatabaseIcon} engine={r.engine} />
          <span className="cell-stack">
            <Button
              type="link"
              size="small"
              style={{ padding: 0, height: 'auto' }}
              onClick={() => {
                setDetail(r);
                setDetailTab('base');
              }}
            >
              {r.name}
            </Button>
            <span>
              {r.code} · ID {r.id}
            </span>
          </span>
        </span>
      ),
    },
    { title: '引擎', dataIndex: 'engine', render: (v) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{v}</span> },
    { title: '数据库', dataIndex: 'databaseName' },
    {
      title: '状态',
      key: 'status',
      render: (_, r) => {
        const st = statusOf(r.status);
        return <StatusDot tone={st.tone}>{st.label}</StatusDot>;
      },
    },
    {
      title: '健康',
      key: 'health',
      render: (_, r) => {
        const hl = healthOf(r.health);
        return <StatusDot tone={hl.tone}>{hl.label}</StatusDot>;
      },
    },
    {
      title: '最近同步',
      key: 'lastSyncAt',
      render: (_, r) => (
        <span className="cell-stack">
          <span style={{ fontVariantNumeric: 'tabular-nums', color: 'var(--ptmcp-text-strong)', fontSize: 12 }}>{fmt(r.lastSyncAt)}</span>
          {r.lastSyncResult ? (
            <StatusDot tone={r.lastSyncResult === 'FAILED' ? 'danger' : 'success'}>{r.lastSyncResult === 'FAILED' ? '失败' : '成功'}</StatusDot>
          ) : null}
        </span>
      ),
    },
    { title: '连接池上限', dataIndex: 'poolMax', render: (v) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    {
      title: '活动快照',
      dataIndex: 'activeSnapshotId',
      render: (v) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{v ?? '待同步'}</span>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 96,
      render: (_, r) => (
        <span style={{ whiteSpace: 'nowrap', display: 'inline-flex', alignItems: 'center', gap: 10 }}>
          <Button
            type="link"
            size="small"
            style={{ padding: 0 }}
            onClick={() => {
              setDetail(r);
              setDetailTab('base');
            }}
          >
            查看
          </Button>
          <Button
            type="link"
            size="small"
            style={{ padding: 0, color: 'var(--ptmcp-text-muted)' }}
            aria-label={`打开 ${r.name} 详情页`}
            onClick={() => (window.location.hash = `/admin/datasources/${encodeURIComponent(r.id)}`)}
          >
            <DotsIcon />
          </Button>
        </span>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="数据源"
        description="管理受控数据连接、健康状态和元数据同步。"
        actions={
          <Button type="primary" icon={<PlusIcon />} onClick={() => isAdmin && setCreating(true)} disabled={!isAdmin}>
            新建数据源
          </Button>
        }
      />
      <div className="table-card filter-card">
        <div className="filter-grow">
          <SearchInput
            value={keyword}
            onChange={(v) => {
              setKeyword(v);
              setPage(1);
            }}
            placeholder="搜索数据源名称、编码、数据库、引擎…"
            width="100%"
          />
        </div>
        <Select
          label="引擎"
          value={engine}
          onChange={(v) => {
            setEngine(v);
            setPage(1);
          }}
          options={ENGINE_OPTIONS}
          width={120}
        />
        <Select
          label="状态"
          value={status}
          onChange={(v) => {
            setStatus(v);
            setPage(1);
          }}
          options={STATUS_OPTIONS}
          width={112}
        />
        <div className="filter-actions">
          {/* 筛选在输入时已即时生效，查询按钮重新向服务端取一次当前筛选下的列表。 */}
          <Button color="primary" variant="filled" onClick={() => setReloadTick((t) => t + 1)}>
            查询
          </Button>
          <Button
            variant="text"
            onClick={() => {
              setKeyword('');
              setEngine('');
              setStatus('');
              setPage(1);
            }}
          >
            重置
          </Button>
        </div>
      </div>
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div className="table-caption">
          <strong>
            数据源列表{' '}
            <span className="count">
              共 {filtered.length} 个数据源{truncated ? `（仅显示前 ${PAGE_FETCH_SIZE} 条，用搜索缩小范围）` : ''}
            </span>
          </strong>
          <span className="caption-actions">
            <Button icon={<CheckboxIcon />} disabled={selected.size === 0} onClick={() => setSelected(new Set())}>
              {selected.size > 0 ? `已选 ${selected.size} 项` : '批量操作'}
            </Button>
          </span>
        </div>
        <div className="table-scroll" role="region" aria-label="数据源列表" tabIndex={0}>
          <Table<DatasourceRead>
            rowKey="id"
            size="middle"
            loading={loading}
            dataSource={pageRows}
            columns={columns}
            pagination={false}
            rowSelection={{
              selectedRowKeys: [...selected],
              onChange: (keys) => setSelected(new Set(keys as string[])),
              columnWidth: 34,
              getCheckboxProps: (r) => ({ 'aria-label': `选择 ${r.name}` }),
            }}
            locale={{
              emptyText: <EmptyState kind="filtered" title="暂无数据源" description="调整筛选条件，或新建一个数据源连接。" />,
            }}
          />
        </div>
        <div className="table-footer">
          <span>{selected.size > 0 ? `已选择 ${selected.size} 项` : ' '}</span>
          <span style={{ display: 'inline-flex', alignItems: 'center', gap: 14 }}>
            <span>共 {filtered.length} 条</span>
            <Pagination total={filtered.length} pageSize={pageSize} page={page} onPage={setPage} />
          </span>
        </div>
      </div>
      {creating ? (
        <DsCreateDialog
          onClose={() => setCreating(false)}
          onCreated={(id) => {
            setCreating(false);
            setReloadTick((t) => t + 1);
            window.location.hash = `/admin/datasources/${encodeURIComponent(id)}`;
          }}
        />
      ) : null}

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
            <Button onClick={() => setDetail(null)}>关闭</Button>
            <Button type="primary" icon={<EditIcon />}>
              编辑
            </Button>
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

/** 新建数据源：写Only凭据仅在创建时输入，成功后回到列表并选中新行。 */
function DsCreateDialog({ onClose, onCreated }: { onClose: () => void; onCreated: (id: string) => void }) {
  const [form, setForm] = useState<DsForm>(emptyDsForm());
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [submitError, setSubmitError] = useState('');
  const set = <K extends keyof DsForm>(key: K, value: DsForm[K]) => setForm((f) => ({ ...f, [key]: value }));

  const submit = async () => {
    const errs = dsCreateValidate(form);
    setErrors(errs);
    if (Object.keys(errs).length) return;
    setSaving(true);
    setSubmitError('');
    try {
      const { data } = await createDatasource({ ...form, code: form.code.trim(), name: form.name.trim() });
      onCreated(data.id);
      showToast('数据源已创建');
    } catch (err) {
      const status = (err as { status?: number }).status;
      if (status === 409) setSubmitError('编码已存在，请更换数据源编码。');
      else setSubmitError(err instanceof Error ? err.message : '创建失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title="新建数据源" onClose={onClose} width={620}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <Field label="编码" hint="创建后不可修改">
          <TextInput value={form.code} onChange={(v) => set('code', v)} />
        </Field>
        <Field label="名称">
          <TextInput value={form.name} onChange={(v) => set('name', v)} />
        </Field>
        <Field label="引擎">
          <Select
            ariaLabel="引擎"
            value={form.engine}
            onChange={(v) => set('engine', v)}
            options={ENGINE_OPTIONS.filter((o) => o.value).map((o) => ({ value: o.value, label: o.label }))}
          />
        </Field>
        <Field label="状态" hint="草稿可先保存，验证后再启用">
          <Select
            ariaLabel="状态"
            value={form.status}
            onChange={(v) => set('status', v)}
            options={[
              { value: 'DRAFT', label: '草稿' },
              { value: 'ACTIVE', label: '启用' },
            ]}
          />
        </Field>
        <Field label="主机">
          <TextInput value={form.host} onChange={(v) => set('host', v)} />
        </Field>
        <Field label="端口">
          <TextInput value={String(form.port)} onChange={(v) => set('port', Number(v) || 0)} />
        </Field>
        <Field label="数据库">
          <TextInput value={form.databaseName} onChange={(v) => set('databaseName', v)} />
        </Field>
        <Field label="用户名">
          <TextInput value={form.username} onChange={(v) => set('username', v)} />
        </Field>
        <Field label="连接凭据" hint="仅创建时提供，保存后不可回读">
          <TextInput value={form.credential} onChange={(v) => set('credential', v)} type="password" />
        </Field>
        <Field label="连接池上限" hint="1–8">
          <TextInput value={String(form.poolMax)} onChange={(v) => set('poolMax', Number(v) || 0)} />
        </Field>
      </div>
      {Object.keys(errors).length ? (
        <Alert kind="danger" title="请修正以下问题">
          {Object.values(errors).map((e) => (
            <div key={e}>· {e}</div>
          ))}
        </Alert>
      ) : null}
      {submitError ? <Alert kind="danger">{submitError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <Button onClick={onClose} disabled={saving}>
          取消
        </Button>
        <Button type="primary" onClick={submit} disabled={saving}>
          {saving ? '创建中…' : '创建数据源'}
        </Button>
      </div>
    </Dialog>
  );
}
