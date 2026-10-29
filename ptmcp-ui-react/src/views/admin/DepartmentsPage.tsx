import { useEffect, useMemo, useState } from 'react';
import { Badge, EmptyState, PageHeader, SearchInput, StatusDot, useDebounced } from '../../components/common';
import { listDepartments, listUsers, type DepartmentRow, type UserRow } from '../../services/orgPortalApi';

export function DepartmentsPage() {
  const [rows, setRows] = useState<DepartmentRow[]>([]);
  const [users, setUsers] = useState<UserRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    Promise.all([listDepartments(), listUsers()])
      .then(([depts, us]) => {
        if (cancelled) return;
        setRows(depts);
        setUsers(us);
      })
      .catch(() => {
        if (!cancelled) {
          setRows([]);
          setUsers([]);
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const memberCount = useMemo(() => {
    const m = new Map<string, number>();
    for (const u of users) if (u.deptId) m.set(u.deptId, (m.get(u.deptId) ?? 0) + 1);
    return m;
  }, [users]);

  const filtered = useMemo(() => rows.filter((r) => !debounced || r.name.toLowerCase().includes(debounced.toLowerCase())), [rows, debounced]);

  return (
    <>
      <PageHeader title="部门" description="组织部门结构与人员归属。" />
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
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索部门名称…" width={260} />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {filtered.length} 个部门</span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>部门名称</th>
                <th>上级部门</th>
                <th>成员数</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => {
                const parent = rows.find((p) => p.id === r.parentId);
                return (
                  <tr key={r.id}>
                    <td>
                      <strong>{r.name}</strong>
                      <span style={{ marginLeft: 8, color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>ID {r.id}</span>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{parent?.name ?? '—'}</td>
                    <td>
                      <Badge tone="neutral">{memberCount.get(r.id) ?? 0} 人</Badge>
                    </td>
                    <td>
                      <StatusDot tone={r.enabled ? 'success' : 'neutral'}>{r.enabled ? '启用' : '停用'}</StatusDot>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {!loading && filtered.length === 0 ? <EmptyState title="没有匹配的部门" description="调整搜索关键字。" /> : null}
        </div>
      </div>
    </>
  );
}
