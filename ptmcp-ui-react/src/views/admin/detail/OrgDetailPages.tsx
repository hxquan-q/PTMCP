import { useEffect, useState } from 'react';
import { Alert, Badge, DrawerFields, PageHeader, StatusDot } from '../../../components/common';
import { getPlatform, listClients, type ClientRow, type PlatformRow } from '../../../services/access';
import { getRole, getUser, listDepartments, listRoles, type DepartmentRow, type RoleRow, type UserRow } from '../../../services/orgPortalApi';

import { BackBar } from './shared';

export function UserDetailPage({ id }: { id: string }) {
  const [user, setUser] = useState<UserRow | null>(null);
  const [roles, setRoles] = useState<RoleRow[]>([]);
  const [depts, setDepts] = useState<DepartmentRow[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    Promise.all([getUser(id), listRoles(), listDepartments()])
      .then(([u, rs, ds]) => {
        if (cancelled) return;
        setUser(u);
        setRoles(rs);
        setDepts(ds);
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '读取失败'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [id]);

  return (
    <>
      <BackBar to="/admin/users" label="返回用户" />
      <PageHeader title={user ? user.displayName || user.username : '用户详情'} description="账号资料与角色归属；编辑与重置密码在用户列表进行。" />
      {error ? (
        <Alert kind="danger" title="用户详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载用户详情…</Alert> : null}
      {user ? (
        <div className="table-card" style={{ padding: '16px 18px', maxWidth: 720 }}>
          <DrawerFields
            fields={[
              { label: '用户名', value: <code>{user.username}</code> },
              { label: '显示名', value: user.displayName },
              { label: '用户 ID', value: user.id },
              { label: '部门', value: depts.find((d) => d.id === user.deptId)?.name ?? '—' },
              { label: '角色', value: user.roleIds.map((rid) => roles.find((r) => r.id === rid)?.name ?? `角色 ${rid}`).join('、') || '未分配' },
              { label: '状态', value: <StatusDot tone={user.enabled ? 'success' : 'neutral'}>{user.enabled ? '启用' : '停用'}</StatusDot> },
              { label: '版本', value: `v${user.version}` },
            ]}
          />
        </div>
      ) : null}
    </>
  );
}

/* ─── 角色详情（只读；权限码全量展示） ─── */
export function RoleDetailPage({ id }: { id: string }) {
  const [role, setRole] = useState<RoleRow | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    getRole(id)
      .then((r) => !cancelled && setRole(r))
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '读取失败'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [id]);

  return (
    <>
      <BackBar to="/admin/roles" label="返回角色管理" />
      <PageHeader title={role ? role.name : '角色详情'} description="角色定义与权限码；编辑在角色列表进行。" />
      {error ? (
        <Alert kind="danger" title="角色详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载角色详情…</Alert> : null}
      {role ? (
        <>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 720, marginBottom: 14 }}>
            <DrawerFields
              fields={[
                { label: '编码', value: <code>{role.key}</code> },
                { label: '角色 ID', value: role.id },
                { label: '成员数', value: role.memberCount ?? '—' },
                { label: '状态', value: <StatusDot tone={role.enabled ? 'success' : 'neutral'}>{role.enabled ? '启用' : '停用'}</StatusDot> },
                { label: '版本', value: `v${role.version}` },
              ]}
            />
          </div>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 720 }}>
            <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>权限码（{role.permissionCodes?.length ?? 0}）</strong>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 10 }}>
              {(role.permissionCodes ?? []).map((code) => (
                <Badge key={code} tone="neutral">
                  <code style={{ fontSize: 11 }}>{code}</code>
                </Badge>
              ))}
              {(role.permissionCodes ?? []).length === 0 ? <span style={{ fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>无权限码。</span> : null}
            </div>
          </div>
        </>
      ) : null}
    </>
  );
}

/* ─── 平台详情（只读 + 客户端列表 + Scope 入口提示） ─── */
export function PlatformDetailPage({ id }: { id: string }) {
  const [platform, setPlatform] = useState<PlatformRow | null>(null);
  const [clients, setClients] = useState<ClientRow[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError('');
    Promise.all([getPlatform(id), listClients(id)])
      .then(([p, c]) => {
        if (cancelled) return;
        setPlatform(p);
        setClients(c.items);
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '读取失败'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [id]);

  return (
    <>
      <BackBar to="/admin/platforms" label="返回平台与客户端" />
      <PageHeader title={platform ? platform.name : '平台详情'} description="平台层级与关联客户端；编辑与授权范围在平台列表进行。" />
      {error ? (
        <Alert kind="danger" title="平台详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载平台详情…</Alert> : null}
      {platform ? (
        <>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 720, marginBottom: 14 }}>
            <DrawerFields
              fields={[
                { label: '编码', value: <code>{platform.code}</code> },
                { label: '平台 ID', value: platform.id },
                { label: '层级', value: `Tier ${platform.tier}` },
                { label: '目录模式', value: platform.catalogMode },
                { label: '凭证模式', value: platform.proofConfigured ? `${platform.proofMode}（已配置）` : platform.proofMode },
                {
                  label: '状态',
                  value: (
                    <StatusDot tone={platform.status === 'ACTIVE' ? 'success' : 'neutral'}>
                      {platform.status === 'ACTIVE' ? '启用' : '停用'}
                    </StatusDot>
                  ),
                },
                { label: '版本', value: `v${platform.version}` },
              ]}
            />
          </div>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 720 }}>
            <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>关联客户端（{clients.length}）</strong>
            {clients.length ? (
              <table className="data-table" style={{ marginTop: 10 }}>
                <thead>
                  <tr>
                    <th>名称</th>
                    <th>Client ID</th>
                    <th>类型</th>
                    <th>状态</th>
                  </tr>
                </thead>
                <tbody>
                  {clients.map((c) => (
                    <tr key={c.id}>
                      <td>
                        <strong>{c.name}</strong>
                      </td>
                      <td>
                        <code style={{ fontSize: 12 }}>{c.clientId}</code>
                      </td>
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
            ) : (
              <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>暂无关联客户端。</p>
            )}
          </div>
        </>
      ) : null}
    </>
  );
}
