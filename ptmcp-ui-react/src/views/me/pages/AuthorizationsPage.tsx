import { useEffect, useState } from 'react';
import { EmptyState, PageHeader, StatusDot } from '../../../components/common';
import { listMyAuthorizations, revokeAuthorization, type AuthorizationRow } from '../../../services/access';
import { showToast } from '../../../toast';
import { formatTaipeiTime as fmt } from '../../../services/dateTime';

export function AuthorizationsPage() {
  const [rows, setRows] = useState<AuthorizationRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [revokingId, setRevokingId] = useState('');
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const load = () => {
    setLoading(true);
    listMyAuthorizations({ pageSize: 20 })
      .then((p) => {
        setRows(p.items);
        setNextCursor(p.nextCursor);
      })
      .catch(() => {
        setRows([]);
        setNextCursor(null);
      })
      .finally(() => setLoading(false));
  };
  useEffect(load, []);

  const revoke = (id: string) => {
    setRevokingId(id);
    revokeAuthorization(id)
      .then(() => {
        load();
        showToast('授权已撤销');
      })
      .catch(() => undefined)
      .finally(() => setRevokingId(''));
  };

  return (
    <>
      <PageHeader title="授权管理" description="第三方客户端对你的账号授权,可随时收回。" />
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
          <span style={{ fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {rows.length} 条授权</span>
        </div>
        <div className="table-scroll" role="region" aria-label="授权列表" tabIndex={0}>
          <table className="data-table">
            <thead>
              <tr>
                <th>客户端</th>
                <th>签发时间</th>
                <th>最近使用</th>
                <th>过期时间</th>
                <th>状态</th>
                <th style={{ width: 80 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <strong>{r.clientName}</strong>
                    <span style={{ marginLeft: 8, color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>ID {r.id}</span>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.issuedAt)}</td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.lastUsedAt)}</td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.absoluteExpiresAt)}</td>
                  <td>
                    <StatusDot tone={r.status === 'ACTIVE' ? 'success' : 'neutral'}>{r.status === 'ACTIVE' ? '生效中' : '已撤销'}</StatusDot>
                  </td>
                  <td>
                    {r.status === 'ACTIVE' ? (
                      <button className="link" disabled={revokingId === r.id} onClick={() => revoke(r.id)}>
                        {revokingId === r.id ? '撤销中…' : '撤销'}
                      </button>
                    ) : (
                      <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && rows.length === 0 ? <EmptyState title="暂无授权记录" description="授权由客户端 OAuth 流程产生，可在客户端发起。" /> : null}
        </div>
        <div className="table-footer" style={{ justifyContent: 'space-between' }}>
          <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
            已加载 {rows.length} 条{nextCursor ? ' · 可能还有更多' : ''}
          </span>
          {loading ? null : nextCursor ? (
            <button
              className="btn btn--secondary"
              disabled={loadingMore}
              onClick={() => {
                if (!nextCursor || loadingMore) return;
                setLoadingMore(true);
                listMyAuthorizations({ cursor: nextCursor, pageSize: 20 })
                  .then((p) => {
                    setRows((prev) => [...prev, ...p.items]);
                    setNextCursor(p.nextCursor);
                  })
                  .catch(() => undefined)
                  .finally(() => setLoadingMore(false));
              }}
            >
              {loadingMore ? '加载中…' : '加载更多'}
            </button>
          ) : (
            <span style={{ fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>没有更多了</span>
          )}
        </div>
      </div>
    </>
  );
}
