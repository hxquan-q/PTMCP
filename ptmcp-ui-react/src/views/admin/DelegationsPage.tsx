import { useEffect, useState } from 'react';
import { Alert, Badge, Dialog, EmptyState, Field, PageHeader, Select, StatusDot, TextInput } from '../../components/common';
import { PlusIcon } from '../../components/Icons';
import { createDelegation, listDelegations, revokeDelegation, type DelegationRow } from '../../services/access';
import { listDepartments, listUsers, type DepartmentRow, type UserRow } from '../../services/orgPortalApi';
import { listDatasources } from '../../services/data';
import { validateDelegationForm, type DelegationFormValues } from './delegationForm';
import { useSensitivePermissions } from '../../permissionsContext';
import { showToast } from '../../toast';

function fmt(at: string | null): string {
  return at ? at.slice(0, 16).replace('T', ' ') : '—';
}

export function DelegationsPage() {
  const { can } = useSensitivePermissions();
  const [rows, setRows] = useState<DelegationRow[]>([]);
  const [users, setUsers] = useState<UserRow[]>([]);
  const [depts, setDepts] = useState<DepartmentRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [revokingId, setRevokingId] = useState('');
  const [creating, setCreating] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);
  const [userFilter, setUserFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [resourceTypeFilter, setResourceTypeFilter] = useState('');
  const [nextCursor, setNextCursor] = useState<string | null>(null);
  const [loadingMore, setLoadingMore] = useState(false);
  const filterParams = () => ({
    userId: userFilter || undefined,
    status: statusFilter || undefined,
    resourceType: resourceTypeFilter || undefined,
    pageSize: 20,
  });

  const load = () => {
    setLoading(true);
    Promise.all([listDelegations(filterParams()), listUsers(), listDepartments()])
      .then(([page, us, ds]) => {
        setRows(page.items);
        setNextCursor(page.nextCursor);
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
  }, [reloadTick, userFilter, statusFilter, resourceTypeFilter]);

  const revoke = (id: string) => {
    setRevokingId(id);
    revokeDelegation(id)
      .then(() => {
        setReloadTick((t) => t + 1);
        showToast('委派已撤销');
      })
      .catch(() => undefined)
      .finally(() => setRevokingId(''));
  };

  return (
    <>
      <PageHeader
        title="管理委派"
        description="把资源管理权限临时委派给指定用户，到期自动失效。"
        actions={
          <button className="btn btn--primary" onClick={() => can('delegationCreate') && setCreating(true)} disabled={!can('delegationCreate')}>
            <PlusIcon /> 新建委派
          </button>
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
            value={userFilter}
            onChange={setUserFilter}
            label="管理员"
            width={160}
            options={[
              { value: '', label: '全部管理员' },
              ...users.filter((u) => u.enabled).map((u) => ({ value: u.id, label: u.displayName || u.username })),
            ]}
          />
          <Select
            value={statusFilter}
            onChange={setStatusFilter}
            label="状态"
            width={120}
            options={[
              { value: '', label: '全部状态' },
              { value: 'ACTIVE', label: '生效中' },
              { value: 'REVOKED', label: '已撤销' },
            ]}
          />
          <Select
            value={resourceTypeFilter}
            onChange={setResourceTypeFilter}
            label="资源类型"
            width={140}
            options={[
              { value: '', label: '全部类型' },
              { value: 'DATASOURCE', label: '数据源' },
            ]}
          />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>已加载 {rows.length} 条委派</span>
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
          {!loading && rows.length === 0 ? <EmptyState title="暂无委派" description="调整筛选条件，或点击右上角「新建委派」。" /> : null}
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
                listDelegations({ ...filterParams(), cursor: nextCursor })
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

      {creating ? (
        <DelegationDialog
          users={users}
          depts={depts}
          onClose={() => setCreating(false)}
          onSaved={() => {
            setCreating(false);
            setReloadTick((t) => t + 1);
          }}
        />
      ) : null}
    </>
  );
}

const SELF_USER_ID = '1'; // preview 会话管理员；接管真实后端时从 session 读取
const ACTION_OPTIONS = [
  'ptmcp:user:list',
  'ptmcp:user:read',
  'ptmcp:user:update',
  'ptmcp:department:list',
  'ptmcp:department:read',
  'ptmcp:department:update',
  'ptmcp:role:list',
  'ptmcp:role:read',
  'ptmcp:acl:list',
  'ptmcp:acl:read',
  'ptmcp:acl:update',
];

function DelegationDialog({
  users,
  depts,
  onClose,
  onSaved,
}: {
  users: UserRow[];
  depts: DepartmentRow[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [values, setValues] = useState<DelegationFormValues>(() => ({
    userId: '',
    selfUserId: SELF_USER_ID,
    departmentRootId: '',
    resourceId: '',
    allowedActions: [],
    validFrom: '',
    validTo: '',
  }));
  const [datasources, setDatasources] = useState<Array<{ id: string; name: string; code: string }>>([]);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');

  useEffect(() => {
    listDatasources({ pageSize: 100 })
      .then(({ data }) => setDatasources(data.items.map((d) => ({ id: d.id, name: d.name, code: d.code }))))
      .catch(() => setDatasources([]));
  }, []);

  const submit = async () => {
    setFormError('');
    const err = validateDelegationForm(values);
    if (err) {
      setFormError(err);
      return;
    }
    setSaving(true);
    try {
      await createDelegation({
        userId: values.userId,
        departmentRootId: values.departmentRootId,
        resourceType: 'DATASOURCE',
        resourceId: values.resourceId,
        allowedActions: values.allowedActions,
        validFrom: `${values.validFrom}:00Z`,
        validTo: `${values.validTo}:00Z`,
      });
      onSaved();
      showToast('委派已创建');
    } catch (e) {
      setFormError((e as Error).message || '创建失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title="新建管理委派" onClose={onClose} width={560}>
      <Alert kind="info" title="委派即授权">
        被委派管理员在你的部门子树与所选资源范围内行使勾选的操作；到期自动失效，可随时撤销。
      </Alert>
      <Field label="受委派管理员" hint="不能选择自己">
        <Select
          value={values.userId}
          onChange={(v) => setValues((s) => ({ ...s, userId: v }))}
          width={240}
          options={[
            { value: '', label: '请选择管理员' },
            ...users.filter((u) => u.id !== SELF_USER_ID).map((u) => ({ value: u.id, label: `${u.displayName || u.username} · ID ${u.id}` })),
          ]}
        />
      </Field>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <Field label="部门根">
          <Select
            value={values.departmentRootId}
            onChange={(v) => setValues((s) => ({ ...s, departmentRootId: v }))}
            options={[{ value: '', label: '请选择' }, ...depts.map((d) => ({ value: d.id, label: d.name }))]}
          />
        </Field>
        <Field label="资源（数据源）">
          <Select
            value={values.resourceId}
            onChange={(v) => setValues((s) => ({ ...s, resourceId: v }))}
            options={[{ value: '', label: '请选择' }, ...datasources.map((d) => ({ value: d.id, label: `${d.name}（${d.code}）` }))]}
          />
        </Field>
      </div>
      <Field label={`允许的操作（已选 ${values.allowedActions.length}）`} hint="仅限用户/部门/角色/授权矩阵域">
        <div style={{ maxHeight: 160, overflowY: 'auto', border: '1px solid var(--ptmcp-border)', borderRadius: 8, padding: 6 }}>
          {ACTION_OPTIONS.map((action) => (
            <label
              key={action}
              style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '3px 6px', borderRadius: 6, cursor: 'pointer', fontSize: 12.5 }}
            >
              <input
                type="checkbox"
                checked={values.allowedActions.includes(action)}
                onChange={() =>
                  setValues((s) => ({
                    ...s,
                    allowedActions: s.allowedActions.includes(action) ? s.allowedActions.filter((a) => a !== action) : [...s.allowedActions, action],
                  }))
                }
              />
              <code>{action}</code>
            </label>
          ))}
        </div>
      </Field>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <Field label="生效开始" hint="datetime-local">
          <TextInput value={values.validFrom} onChange={(v) => setValues((s) => ({ ...s, validFrom: v }))} type="datetime-local" />
        </Field>
        <Field label="生效结束">
          <TextInput value={values.validTo} onChange={(v) => setValues((s) => ({ ...s, validTo: v }))} type="datetime-local" />
        </Field>
      </div>
      {formError ? <Alert kind="danger">{formError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
          取消
        </button>
        <button className="btn btn--primary" onClick={submit} disabled={saving}>
          {saving ? '创建中…' : '创建委派'}
        </button>
      </div>
    </Dialog>
  );
}
