import { useEffect, useState } from 'react';
import { Button } from 'antd';
import { Badge, EmptyState, PageHeader, Select, StatusDot } from '../../../components/common';
import { PlusIcon } from '../../../components/Icons';
import { listMyTokens, revokeToken, type TokenRow } from '../../../services/access';
import { showToast } from '../../../toast';
import { formatTaipeiTime as fmt } from '../../../services/dateTime';

import { TokenDialog } from './TokenDialog';

export function TokensPage() {
  const [rows, setRows] = useState<TokenRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [revokingId, setRevokingId] = useState('');
  const [creating, setCreating] = useState(false);
  const [statusFilter, setStatusFilter] = useState('');
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);

  const load = () => {
    setLoading(true);
    listMyTokens({ status: statusFilter || undefined, pageSize: 20 })
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
  useEffect(load, [statusFilter]);

  const revoke = (id: string) => {
    setRevokingId(id);
    revokeToken(id)
      .then(() => {
        load();
        showToast('令牌已撤销');
      })
      .catch(() => undefined)
      .finally(() => setRevokingId(''));
  };

  return (
    <>
      <PageHeader
        title="个人令牌"
        description="用于脚本与工具访问的访问令牌，可随时撤销。"
        actions={
          <Button type="primary" onClick={() => setCreating(true)}>
            <PlusIcon /> 新建令牌
          </Button>
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
          }}
        >
          <Select
            value={statusFilter}
            onChange={setStatusFilter}
            label="状态"
            options={[
              { value: '', label: '全部状态' },
              { value: 'ACTIVE', label: '生效中' },
              { value: 'REVOKED', label: '已撤销' },
            ]}
          />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>已加载 {rows.length} 个令牌</span>
        </div>
        <div className="table-scroll" role="region" aria-label="个人令牌列表" tabIndex={0}>
          <table className="data-table">
            <thead>
              <tr>
                <th>令牌</th>
                <th>范围</th>
                <th>过期时间</th>
                <th>最近使用</th>
                <th>状态</th>
                <th style={{ width: 80 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <span className="cell-stack">
                      <strong>{r.name}</strong>
                      <span>
                        <code style={{ fontSize: 12 }}>{r.prefix ?? ''}•••</code> · ID {r.id}
                      </span>
                    </span>
                  </td>
                  <td>
                    <span style={{ display: 'inline-flex', gap: 4, flexWrap: 'wrap' }}>
                      {(r.scopes ?? []).map((s) => (
                        <Badge key={s} tone="neutral">
                          {s}
                        </Badge>
                      ))}
                    </span>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.expiresAt)}</td>
                  <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{fmt(r.lastUsedAt)}</td>
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
          {!loading && rows.length === 0 ? <EmptyState title="暂无个人令牌" description="切换状态筛选或新建令牌。" /> : null}
        </div>
        <div className="table-footer" style={{ justifyContent: 'space-between' }}>
          <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
            已加载 {rows.length} 个{nextCursor ? ' · 可能还有更多' : ''}
          </span>
          {loading ? null : nextCursor ? (
            <Button
              disabled={loadingMore}
              onClick={() => {
                if (!nextCursor || loadingMore) return;
                setLoadingMore(true);
                listMyTokens({ status: statusFilter || undefined, cursor: nextCursor, pageSize: 20 })
                  .then((p) => {
                    setRows((prev) => [...prev, ...p.items]);
                    setNextCursor(p.nextCursor);
                  })
                  .catch(() => undefined)
                  .finally(() => setLoadingMore(false));
              }}
            >
              {loadingMore ? '加载中…' : '加载更多'}
            </Button>
          ) : (
            <span style={{ fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>没有更多了</span>
          )}
        </div>
      </div>
      {creating ? (
        <TokenDialog
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            load();
          }}
        />
      ) : null}
    </>
  );
}
