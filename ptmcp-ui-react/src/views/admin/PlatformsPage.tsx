import { useCallback, useEffect, useMemo, useState } from 'react';
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

  return (
    <>
      <PageHeader
        title="平台与客户端"
        description="接入平台的层级、目录模式与关联 OAuth 客户端。"
        actions={
          tab === 'platforms' ? (
            <button className="btn btn--primary" onClick={() => setPlatformDialog({ mode: 'create' })}>
              <PlusIcon /> 新建平台
            </button>
          ) : (
            <button className="btn btn--primary" onClick={() => setClientDialog({ mode: 'create' })}>
              <PlusIcon /> 新建客户端
            </button>
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
            <table className="data-table">
              <thead>
                <tr>
                  <th>平台</th>
                  <th>层级</th>
                  <th>目录模式</th>
                  <th>凭证</th>
                  <th>客户端数</th>
                  <th>状态</th>
                  <th style={{ width: 150 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {platforms.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <span className="cell-stack">
                        <button className="link" onClick={() => (window.location.hash = `/admin/platforms/${encodeURIComponent(p.id)}`)}>
                          {p.name}
                        </button>
                        <span>
                          {p.code} · ID {p.id}
                        </span>
                      </span>
                    </td>
                    <td>
                      <Badge tone="neutral">{TIER_LABEL[p.tier] ?? `T${p.tier}`}</Badge>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{p.catalogMode}</td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{p.proofConfigured ? `${p.proofMode}（已配置）` : p.proofMode}</td>
                    <td>
                      <Badge tone="accent">{clientCount.get(p.id) ?? 0}</Badge>
                    </td>
                    <td>
                      <StatusDot tone={p.status === 'ACTIVE' ? 'success' : 'neutral'}>{p.status === 'ACTIVE' ? '启用' : '停用'}</StatusDot>
                    </td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <button
                        className="link"
                        onClick={() => isAdmin && setScopePlatform(p)}
                        disabled={!isAdmin}
                        aria-label={`Scope 授权范围：${p.name}`}
                      >
                        Scope
                      </button>
                      <button
                        className="link"
                        style={{ marginLeft: 10 }}
                        disabled={loading}
                        onClick={() => setPlatformDialog({ mode: 'edit', row: p })}
                      >
                        <EditIcon /> 编辑
                      </button>
                      <button
                        className="link"
                        style={{ marginLeft: 10, color: 'var(--ptmcp-red)' }}
                        onClick={() => setDeleting({ kind: 'platform', row: p })}
                        aria-label={`删除平台 ${p.name}`}
                      >
                        <CloseIcon /> 删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>客户端</th>
                  <th>Client ID</th>
                  <th>所属平台</th>
                  <th>类型</th>
                  <th>状态</th>
                  <th style={{ width: 150 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {clients.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <strong>{c.name}</strong>
                      <span style={{ marginLeft: 8, color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>ID {c.id}</span>
                    </td>
                    <td>
                      <code style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{c.clientId}</code>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{platforms.find((p) => p.id === c.platformId)?.name ?? c.platformId}</td>
                    <td>
                      <Badge tone="neutral">{c.type}</Badge>
                    </td>
                    <td>
                      <StatusDot tone={c.status === 'ACTIVE' ? 'success' : 'neutral'}>{c.status === 'ACTIVE' ? '启用' : '停用'}</StatusDot>
                    </td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <button className="link" onClick={() => setClientDialog({ mode: 'edit', row: c })}>
                        <EditIcon /> 编辑
                      </button>
                      <button
                        className="link"
                        style={{ marginLeft: 10, color: 'var(--ptmcp-red)' }}
                        onClick={() => setDeleting({ kind: 'client', row: c })}
                        aria-label={`删除客户端 ${c.name}`}
                      >
                        <CloseIcon /> 删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
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
            <button className="btn btn--secondary" onClick={() => setDeleting(null)} disabled={busy}>
              取消
            </button>
            <button
              className="btn btn--primary"
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
            </button>
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
          <button className="btn btn--primary" onClick={() => setSecretCreated(null)}>
            我已保存，关闭
          </button>
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
          <button
            className="btn btn--secondary"
            onClick={() => navigator.clipboard?.writeText(secretCreated?.clientSecret ?? '').catch(() => undefined)}
          >
            <CopyIcon /> 复制
          </button>
        </div>
      </Dialog>
    </>
  );
}
