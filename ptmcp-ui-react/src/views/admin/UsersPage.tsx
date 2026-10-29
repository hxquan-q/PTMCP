import { useEffect, useMemo, useState } from 'react';
import { Badge, EmptyState, PageHeader, SearchInput, StatusDot, useDebounced } from '../../components/common';
import { listDepartments, listRoles, listUsers, type DepartmentRow, type RoleRow, type UserRow } from '../../services/orgPortalApi';

export function UsersPage() {
  const [rows, setRows] = useState<UserRow[]>([]);
  const [roles, setRoles] = useState<RoleRow[]>([]);
  const [depts, setDepts] = useState<DepartmentRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([listUsers(), listRoles(), listDepartments()])
      .then(([us, rs, ds]) => {
        if (cancelled) return;
        setRows(us);
        setRoles(rs);
        setDepts(ds);
      })
      .catch(() => {
        if (!cancelled) {
          setRows([]);
          setRoles([]);
          setDepts([]);
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(
    () =>
      rows.filter(
        (r) =>
          !debounced || r.username.toLowerCase().includes(debounced.toLowerCase()) || r.displayName.toLowerCase().includes(debounced.toLowerCase()),
      ),
    [rows, debounced],
  );

  return (
    <>
      <PageHeader title="用户" description="账号、角色分配与启停状态。" />
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
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索用户名或显示名…" width={260} />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {filtered.length} 个用户</span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>用户</th>
                <th>部门</th>
                <th>角色</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.id}>
                  <td>
                    <span className="cell-stack">
                      <strong>{r.displayName || r.username}</strong>
                      <span>
                        {r.username} · ID {r.id}
                      </span>
                    </span>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)' }}>{depts.find((d) => d.id === r.deptId)?.name ?? '—'}</td>
                  <td>
                    <span style={{ display: 'inline-flex', gap: 4, flexWrap: 'wrap' }}>
                      {r.roleIds.map((rid) => (
                        <Badge key={rid} tone="accent">
                          {roles.find((x) => x.id === rid)?.name ?? `角色 ${rid}`}
                        </Badge>
                      ))}
                      {r.roleIds.length === 0 ? <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>未分配</span> : null}
                    </span>
                  </td>
                  <td>
                    <StatusDot tone={r.enabled ? 'success' : 'neutral'}>{r.enabled ? '启用' : '停用'}</StatusDot>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && filtered.length === 0 ? <EmptyState title="没有匹配的用户" description="调整搜索关键字。" /> : null}
        </div>
      </div>
    </>
  );
}
