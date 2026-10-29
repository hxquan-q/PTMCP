import { useEffect, useMemo, useState } from 'react';
import { Badge, EmptyState, PageHeader, StatusDot, Tabs } from '../../components/common';
import { listClients, listPlatforms, type ClientRow, type PlatformRow } from '../../services/access';

const TIER_LABEL: Record<number, string> = { 1: '一级', 2: '二级', 3: '三级' };

export function PlatformsPage() {
  const [platforms, setPlatforms] = useState<PlatformRow[]>([]);
  const [clients, setClients] = useState<ClientRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('platforms');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
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

  const clientCount = useMemo(() => {
    const m = new Map<string, number>();
    for (const c of clients) m.set(c.platformId, (m.get(c.platformId) ?? 0) + 1);
    return m;
  }, [clients]);

  return (
    <>
      <PageHeader title="平台与客户端" description="接入平台的层级、目录模式与关联 OAuth 客户端。" />
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
        <div className="table-scroll">
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
                </tr>
              </thead>
              <tbody>
                {platforms.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <span className="cell-stack">
                        <strong>{p.name}</strong>
                        <span>
                          {p.code} · ID {p.id}
                        </span>
                      </span>
                    </td>
                    <td>
                      <Badge tone="neutral">{TIER_LABEL[p.tier] ?? `T${p.tier}`}</Badge>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{p.catalogMode}</td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>
                      {p.proofMode}
                      {p.proofConfigured ? ' ✓' : ''}
                    </td>
                    <td>
                      <Badge tone="accent">{clientCount.get(p.id) ?? 0}</Badge>
                    </td>
                    <td>
                      <StatusDot tone={p.status === 'ACTIVE' ? 'success' : 'neutral'}>{p.status === 'ACTIVE' ? '启用' : '停用'}</StatusDot>
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
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {!loading && tab === 'platforms' && platforms.length === 0 ? <EmptyState title="暂无平台" description="创建平台后在此管理。" /> : null}
          {!loading && tab === 'clients' && clients.length === 0 ? <EmptyState title="暂无客户端" description="为平台注册 OAuth 客户端。" /> : null}
        </div>
      </div>
    </>
  );
}
