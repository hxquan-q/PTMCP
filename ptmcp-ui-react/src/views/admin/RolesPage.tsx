import { useEffect, useMemo, useState } from 'react';
import { Badge, EmptyState, PageHeader, SearchInput, StatusDot, useDebounced } from '../../components/common';
import { listRoles, type RoleRow } from '../../services/orgPortalApi';

export function RolesPage() {
  const [rows, setRows] = useState<RoleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [keyword, setKeyword] = useState('');
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    listRoles()
      .then((rs) => !cancelled && setRows(rs))
      .catch(() => !cancelled && setRows([]))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const filtered = useMemo(
    () =>
      rows.filter(
        (r) => !debounced || r.name.toLowerCase().includes(debounced.toLowerCase()) || r.key.toLowerCase().includes(debounced.toLowerCase()),
      ),
    [rows, debounced],
  );

  return (
    <>
      <PageHeader title="角色管理" description="角色定义、成员数与权限码。" />
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
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索角色名称或编码…" width={260} />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {filtered.length} 个角色</span>
        </div>
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>角色</th>
                <th>编码</th>
                <th>成员</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.id}>
                  <td>
                    <strong>{r.name}</strong>
                    <span style={{ marginLeft: 8, color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>ID {r.id}</span>
                  </td>
                  <td>
                    <code style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{r.key}</code>
                  </td>
                  <td>
                    <Badge tone="neutral">{r.memberCount ?? 0} 人</Badge>
                  </td>
                  <td>
                    <StatusDot tone={r.enabled ? 'success' : 'neutral'}>{r.enabled ? '启用' : '停用'}</StatusDot>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!loading && filtered.length === 0 ? <EmptyState title="没有匹配的角色" description="调整搜索关键字。" /> : null}
        </div>
      </div>
    </>
  );
}
