import { useEffect, useState } from 'react';
import { Badge, EmptyState, PageHeader, StatusDot } from '../../components/common';
import { listDelegations, revokeDelegation, type DelegationRow } from '../../services/access';
import { listDepartments, listUsers, type DepartmentRow, type UserRow } from '../../services/orgPortalApi';

function fmt(at: string | null): string {
  return at ? at.slice(0, 16).replace('T', ' ') : '—';
}

export function DelegationsPage() {
  const [rows, setRows] = useState<DelegationRow[]>([]);
  const [users, setUsers] = useState<UserRow[]>([]);
  const [depts, setDepts] = useState<DepartmentRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [revokingId, setRevokingId] = useState('');

  const load = () => {
    setLoading(true);
    Promise.all([listDelegations({ pageSize: 200 }), listUsers(), listDepartments()])
      .then(([page, us, ds]) => {
        setRows(page.items);
        setUsers(us);
        setDepts(ds);
      })
      .catch(() => {
        setRows([]);
        setUsers([]);
        setDepts([]);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const revoke = (id: string) => {
    setRevokingId(id);
    revokeDelegation(id)
      .then(() => load())
      .catch(() => undefined)
      .finally(() => setRevokingId(''));
  };

  return (
    <>
      <PageHeader title="管理委派" description="把资源管理权限临时委派给指定用户,到期自动失效。" />
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
          <span style={{ fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {rows.length} 条委派</span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>被委派用户</th>
                <th>部门根</th>
                <th>资源</th>
                <th>允许操作</th>
                <th>有效期</th>
                <th>状态</th>
                <th style={{ width: 80 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => {
                const u = users.find((x) => x.id === r.userId);
                const d = depts.find((x) => x.id === r.departmentRootId);
                return (
                  <tr key={r.id}>
                    <td>
                      <span className="cell-stack">
                        <strong>{u?.displayName || u?.username || `用户 ${r.userId}`}</strong>
                        <span>ID {r.id}</span>
                      </span>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{d?.name ?? `部门 ${r.departmentRootId}`}</td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>
                      {r.resourceType} #{r.resourceId}
                    </td>
                    <td>
                      <span style={{ display: 'inline-flex', gap: 4, flexWrap: 'wrap' }}>
                        {(r.allowedActions ?? []).map((a) => (
                          <Badge key={a} tone="neutral">
                            {a.replace('ptmcp:', '')}
                          </Badge>
                        ))}
                      </span>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>
                      {fmt(r.validFrom)} → {fmt(r.validTo)}
                    </td>
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
                );
              })}
            </tbody>
          </table>
          {!loading && rows.length === 0 ? <EmptyState title="暂无委派" description="创建管理委派后在此管理。" /> : null}
        </div>
      </div>
    </>
  );
}
