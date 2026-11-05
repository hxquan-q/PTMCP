import { useCallback, useEffect, useMemo, useState } from 'react';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Alert, Badge, Dialog, EmptyState, PageHeader, StatusDot, Tabs } from '../../components/common';
import { CloseIcon, CopyIcon, EditIcon, PlusIcon } from '../../components/Icons';
import {
  deleteClient,
  deletePlatform,
  listClients,
  listPlatforms,
  type ClientCreated,
  type ClientRow,
  type PlatformRow,
} from '../../services/access';
import { useAdminGate } from '../../adminGateContext';
import { showToast } from '../../toast';
import { PlatformDialog } from './platforms/PlatformDialog';
import { ClientDialog } from './platforms/ClientDialog';
import { ScopeDialog } from './platforms/ScopeDialog';
import { TIER_LABEL } from './platforms/shared';

export function PlatformsPage() {
  const isAdmin = useAdminGate();
  const [platforms, setPlatforms] = useState<PlatformRow[]>([]);
  const [clients, setClients] = useState<ClientRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('platforms');
  const [reloadTick, setReloadTick] = useState(0);
  const [platformDialog, setPlatformDialog] = useState<{ mode: 'create' } | { mode: 'edit'; row: PlatformRow } | null>(null);
  const [clientDialog, setClientDialog] = useState<{ mode: 'create' } | { mode: 'edit'; row: ClientRow } | null>(null);
  const [deleting, setDeleting] = useState<{ kind: 'platform' | 'client'; row: PlatformRow | ClientRow } | null>(null);
  const [busy, setBusy] = useState(false);
  const [secretCreated, setSecretCreated] = useState<ClientCreated | null>(null);
  const [scopePlatform, setScopePlatform] = useState<PlatformRow | null>(null);

  const reload = useCallback(() => {
    let cancelled = false;
    setLoading(true);
    setPlatforms([]);
    listPlatforms({ pageSize: 100 })
      .then(async (page) => {
        if (cancelled) return;
        setPlatforms(page.items);
        const all = await Promise.all(
          page.items.map((p) =>
            listClients(p.id)
              .then((c) => c.items)
              .catch(() => [] as ClientRow[]),
          ),
        );
        if (!cancelled) setClients(all.flat());
      })
      .catch(() => {
        if (!cancelled) {
          setPlatforms([]);
          setClients([]);
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(reload, [reload, reloadTick]);

  const clientCount = useMemo(() => {
    const m = new Map<string, number>();
    for (const c of clients) m.set(c.platformId, (m.get(c.platformId) ?? 0) + 1);
    return m;
  }, [clients]);

  const platformColumns: ColumnsType<PlatformRow> = [
    {
      title: '平台',
      dataIndex: 'name',
      render: (_: unknown, p) => (
        <span className="cell-stack">
          <Button
            type="link"
            size="small"
            style={{ padding: 0, height: 'auto' }}
            onClick={() => (window.location.hash = `/admin/platforms/${encodeURIComponent(p.id)}`)}
          >
            {p.name}
          </Button>
          <span>
            {p.code} · ID {p.id}
          </span>
        </span>
      ),
    },
    {
      title: '层级',
      dataIndex: 'tier',
      render: (tier: PlatformRow['tier']) => <Badge tone="neutral">{TIER_LABEL[tier] ?? `T${tier}`}</Badge>,
    },
    { title: '目录模式', dataIndex: 'catalogMode', render: (v: string) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{v}</span> },
    {
      title: '凭证',
      key: 'proof',
      render: (_: unknown, p) => (
        <span style={{ color: 'var(--ptmcp-text-muted)' }}>{p.proofConfigured ? `${p.proofMode}（已配置）` : p.proofMode}</span>
      ),
    },
    {
      title: '客户端数',
      key: 'clientCount',
      render: (_: unknown, p) => <Badge tone="accent">{clientCount.get(p.id) ?? 0}</Badge>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      render: (_: unknown, p) => (
        <StatusDot tone={p.status === 'ACTIVE' ? 'success' : 'neutral'}>{p.status === 'ACTIVE' ? '启用' : '停用'}</StatusDot>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_: unknown, p) => (
        <span className="row-actions">
          <Button
            type="link"
            size="small"
            style={{ padding: 0 }}
            onClick={() => setScopePlatform(p)}
            disabled={!isAdmin}
            aria-label={`Scope 授权范围：${p.name}`}
          >
            Scope
          </Button>
          <Button
            type="link"
            size="small"
            icon={<EditIcon />}
            style={{ padding: 0 }}
            disabled={loading}
            onClick={() => setPlatformDialog({ mode: 'edit', row: p })}
          >
            编辑
          </Button>
          <Button
            type="link"
            size="small"
            danger
            icon={<CloseIcon />}
            style={{ padding: 0 }}
            onClick={() => setDeleting({ kind: 'platform', row: p })}
            aria-label={`删除平台 ${p.name}`}
          >
            删除
          </Button>
        </span>
      ),
    },
  ];

  const clientColumns: ColumnsType<ClientRow> = [
    {
      title: '客户端',
      dataIndex: 'name',
      render: (_: unknown, c) => (
        <span>
          <strong>{c.name}</strong>
          <span style={{ marginLeft: 8, color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>ID {c.id}</span>
        </span>
      ),
    },
    {
      title: 'Client ID',
      dataIndex: 'clientId',
      render: (v: string) => <code style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{v}</code>,
    },
    {
      title: '所属平台',
      dataIndex: 'platformId',
      render: (_: unknown, c) => (
        <span style={{ color: 'var(--ptmcp-text-muted)' }}>{platforms.find((p) => p.id === c.platformId)?.name ?? c.platformId}</span>
      ),
    },
    { title: '类型', dataIndex: 'type', render: (v: string) => <Badge tone="neutral">{v}</Badge> },
    {
      title: '状态',
      dataIndex: 'status',
      render: (_: unknown, c) => (
        <StatusDot tone={c.status === 'ACTIVE' ? 'success' : 'neutral'}>{c.status === 'ACTIVE' ? '启用' : '停用'}</StatusDot>
      ),
    },
    {
      title: '操作',
      key: 'action',
      width: 150,
      render: (_: unknown, c) => (
        <span className="row-actions">
          <Button type="link" size="small" icon={<EditIcon />} style={{ padding: 0 }} onClick={() => setClientDialog({ mode: 'edit', row: c })}>
            编辑
          </Button>
          <Button
            type="link"
            size="small"
            danger
            icon={<CloseIcon />}
            style={{ padding: 0 }}
            onClick={() => setDeleting({ kind: 'client', row: c })}
            aria-label={`删除客户端 ${c.name}`}
          >
            删除
          </Button>
        </span>
      ),
    },
  ];

  return (
    <>
      <PageHeader
        title="平台与客户端"
        description="接入平台的层级、目录模式与关联 OAuth 客户端。"
        actions={
          tab === 'platforms' ? (
            <Button type="primary" onClick={() => setPlatformDialog({ mode: 'create' })}>
              <PlusIcon /> 新建平台
            </Button>
          ) : (
            <Button type="primary" onClick={() => setClientDialog({ mode: 'create' })}>
              <PlusIcon /> 新建客户端
            </Button>
          )
        }
      />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div style={{ padding: '0 14px', flex: 'none' }}>
          <Tabs
            tabs={[
              { key: 'platforms', label: `平台 ${platforms.length}` },
              { key: 'clients', label: `客户端 ${clients.length}` },
            ]}
            active={tab}
            onChange={setTab}
          />
        </div>
        <div className="table-scroll" role="region" aria-label="平台列表" tabIndex={0}>
          {tab === 'platforms' ? (
            <Table<PlatformRow>
              rowKey="id"
              size="middle"
              loading={loading}
              dataSource={platforms}
              columns={platformColumns}
              pagination={false}
              locale={{ emptyText: <span /> }}
            />
          ) : (
            <Table<ClientRow>
              rowKey="id"
              size="middle"
              loading={loading}
              dataSource={clients}
              columns={clientColumns}
              pagination={false}
              locale={{ emptyText: <span /> }}
            />
          )}
          {!loading && tab === 'platforms' && platforms.length === 0 ? (
            <EmptyState title="暂无平台" description="点击右上角「新建平台」创建。" />
          ) : null}
          {!loading && tab === 'clients' && clients.length === 0 ? (
            <EmptyState title="暂无客户端" description="切换到客户端页签后点击「新建客户端」。" />
          ) : null}
        </div>
      </div>

      {platformDialog ? (
        <PlatformDialog
          state={platformDialog}
          onClose={() => {
            setPlatformDialog(null);
            setReloadTick((t) => t + 1);
          }}
          onSaved={() => {
            setPlatformDialog(null);
            setReloadTick((t) => t + 1);
          }}
        />
      ) : null}
      {clientDialog ? (
        <ClientDialog
          state={clientDialog}
          platforms={platforms}
          onClose={() => setClientDialog(null)}
          onSaved={() => {
            setClientDialog(null);
            setReloadTick((t) => t + 1);
          }}
          onSecret={(created) => setSecretCreated(created)}
        />
      ) : null}

      <Dialog
        open={!!deleting}
        title={deleting?.kind === 'platform' ? '删除平台' : '删除客户端'}
        onClose={() => setDeleting(null)}
        width={440}
        footer={
          <>
            <Button onClick={() => setDeleting(null)} disabled={busy}>
              取消
            </Button>
            <Button
              type="primary"
              style={{ background: 'var(--ptmcp-red)' }}
              disabled={busy}
              onClick={async () => {
                if (!deleting) return;
                setBusy(true);
                try {
                  if (deleting.kind === 'platform') {
                    await deletePlatform(deleting.row.id, deleting.row.version);
                    showToast('平台已删除');
                  } else {
                    await deleteClient(deleting.row.id, deleting.row.version);
                    showToast('客户端已删除');
                  }
                  setDeleting(null);
                  setReloadTick((t) => t + 1);
                } finally {
                  setBusy(false);
                }
              }}
            >
              {busy ? '删除中…' : '确认删除'}
            </Button>
          </>
        }
      >
        <p style={{ margin: 0, fontSize: 13 }}>
          {deleting?.kind === 'platform'
            ? `确认删除平台「${deleting.row.name}」？其下客户端将一并失效，该操作不可撤销。`
            : `确认删除客户端「${deleting?.row.name}」？使用该客户端的会话将立即失效。`}
        </p>
      </Dialog>

      {scopePlatform ? <ScopeDialog platform={scopePlatform} onClose={() => setScopePlatform(null)} onSaved={() => undefined} /> : null}

      {/* 一次性 secret 展示：关闭后不可再取 */}
      <Dialog
        open={!!secretCreated}
        title="客户端密钥已生成"
        onClose={() => setSecretCreated(null)}
        width={520}
        footer={
          <Button type="primary" onClick={() => setSecretCreated(null)}>
            我已保存，关闭
          </Button>
        }
      >
        <Alert kind="warning" title="密钥仅此一次展示">
          关闭后无法再次查看，请立即复制并妥善保管（对照 PAT 的发放模式）。
        </Alert>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 10 }}>
          <code
            style={{
              flex: 1,
              padding: '10px 12px',
              background: 'var(--ptmcp-canvas)',
              border: '1px solid var(--ptmcp-border)',
              borderRadius: 'var(--ptmcp-radius-control)',
              fontSize: 12.5,
              wordBreak: 'break-all',
              userSelect: 'all',
            }}
          >
            {secretCreated?.clientSecret}
          </code>
          <Button onClick={() => navigator.clipboard?.writeText(secretCreated?.clientSecret ?? '').catch(() => undefined)}>
            <CopyIcon /> 复制
          </Button>
        </div>
      </Dialog>
    </>
  );
}
