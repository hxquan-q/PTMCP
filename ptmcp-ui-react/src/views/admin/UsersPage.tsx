import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Dialog,
  EmptyState,
  Field,
  PageHeader,
  SearchInput,
  Select,
  StatusDot,
  TextInput,
  useDebounced,
} from '../../components/common';
import { CloseIcon, EditIcon, PlusIcon } from '../../components/Icons';
import {
  createUser,
  deleteUser,
  listDepartments,
  listRoles,
  listUsers,
  setUserDepartment,
  setUserRoles,
  setUserStatus,
  resetUserPassword,
  updateUser,
  type DepartmentRow,
  type RoleRow,
  type UserRow,
} from '../../services/orgPortalApi';
import { departmentDepth, validateUserForm, type UserFormValues } from './orgForms';
import { passwordError } from '../me/passwordForm';
import { useSensitivePermissions } from '../../permissionsContext';
import { frontendPreview } from '../../services/frontendMode';
import { showToast } from '../../toast';

export function UsersPage() {
  const { can } = useSensitivePermissions();
  /* 后端 UserAdminController 未提供 DELETE /admin/users/{id}；仅合成预览后端支持删除。 */
  const canDeleteUsers = frontendPreview && can('userDelete');
  const [rows, setRows] = useState<UserRow[]>([]);
  const [roles, setRoles] = useState<RoleRow[]>([]);
  const [depts, setDepts] = useState<DepartmentRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<UserRow | null>(null);
  const [deleting, setDeleting] = useState<UserRow | null>(null);
  const [resetting, setResetting] = useState<UserRow | null>(null);
  const [busy, setBusy] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);
  const debounced = useDebounced(keyword);

  const [deptFilter, setDeptFilter] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError('');
    Promise.all([listUsers({ departmentId: deptFilter || undefined, keyword: debounced.trim() || undefined }), listRoles(), listDepartments()])
      .then(([us, rs, ds]) => {
        if (cancelled) return;
        setRows(us);
        setRoles(rs);
        setDepts(ds);
      })
      .catch(() => {
        if (!cancelled) {
          setRows([]);
          setLoadError('用户列表加载失败，请稍后重试。');
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [reloadTick, deptFilter, debounced]);

  const reload = () => setReloadTick((t) => t + 1);
  const deptName = (id: string | null) => depts.find((d) => d.id === id)?.name ?? '—';

  const filtered = useMemo(
    () =>
      rows.filter(
        (r) =>
          !debounced || r.username.toLowerCase().includes(debounced.toLowerCase()) || r.displayName.toLowerCase().includes(debounced.toLowerCase()),
      ),
    [rows, debounced],
  );

  const toggleStatus = async (r: UserRow) => {
    setBusy(true);
    try {
      await setUserStatus(r.id, !r.enabled, r.version);
      reload();
      showToast('用户状态已更新');
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <PageHeader
        title="用户"
        description="账号、角色分配与启停状态。"
        actions={
          <button className="btn btn--primary" onClick={() => can('userCreate') && setCreating(true)} disabled={!can('userCreate')}>
            <PlusIcon /> 新建用户
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
            value={deptFilter}
            onChange={setDeptFilter}
            label="部门"
            width={150}
            options={[{ value: '', label: '全部部门' }, ...depts.map((d) => ({ value: d.id, label: d.name }))]}
          />
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索用户名或显示名…" width={260} />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {filtered.length} 个用户</span>
        </div>
        {loadError ? (
          <div style={{ padding: '10px 14px 0' }}>
            <Alert kind="danger">{loadError}</Alert>
          </div>
        ) : null}
        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>用户</th>
                <th>部门</th>
                <th>角色</th>
                <th>状态</th>
                <th style={{ width: 190 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.id}>
                  <td>
                    <span className="cell-stack">
                      <button className="link" onClick={() => (window.location.hash = `/admin/users/${encodeURIComponent(r.id)}`)}>
                        {r.displayName || r.username}
                      </button>
                      <span>
                        {r.username} · ID {r.id}
                      </span>
                    </span>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)' }}>{deptName(r.deptId)}</td>
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
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button className="link" onClick={() => setEditing(r)}>
                      <EditIcon /> 编辑
                    </button>
                    <button
                      className="link"
                      style={{ marginLeft: 10 }}
                      onClick={() => setResetting(r)}
                      aria-label={`重置 ${r.displayName || r.username} 的密码`}
                    >
                      重置密码
                    </button>
                    <button className="link" style={{ marginLeft: 10 }} disabled={busy} onClick={() => toggleStatus(r)}>
                      {r.enabled ? '停用' : '启用'}
                    </button>
                    <button
                      className="link"
                      style={{ marginLeft: 10, color: canDeleteUsers ? 'var(--ptmcp-red)' : 'var(--ptmcp-text-faint)' }}
                      disabled={!canDeleteUsers}
                      title={canDeleteUsers ? undefined : '当前后端未提供删除用户接口（仅合成预览模式可用）'}
                      onClick={() => setDeleting(r)}
                      aria-label={`删除 ${r.displayName}`}
                    >
                      <CloseIcon /> 删除
                    </button>
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && !loading ? (
                <tr>
                  <td colSpan={5}>
                    <EmptyState
                      title={loadError ? '用户加载失败' : '没有匹配的用户'}
                      description={loadError ? '请刷新重试' : '调整搜索关键字，或点击右上角「新建用户」'}
                    />
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </div>

      {resetting ? <ResetPasswordDialog user={resetting} onClose={() => setResetting(null)} onSaved={reload} /> : null}

      {creating || editing ? (
        <UserDialog
          user={editing}
          roles={roles}
          depts={depts}
          onClose={() => {
            setCreating(false);
            setEditing(null);
          }}
          onSaved={reload}
        />
      ) : null}

      <Dialog
        open={!!deleting}
        title="删除用户"
        onClose={() => setDeleting(null)}
        width={420}
        footer={
          <>
            <button className="btn btn--secondary" onClick={() => setDeleting(null)} disabled={busy}>
              取消
            </button>
            <button
              className="btn btn--primary"
              style={{ background: 'var(--ptmcp-red)' }}
              disabled={busy || !canDeleteUsers}
              onClick={async () => {
                if (!deleting) return;
                setBusy(true);
                try {
                  await deleteUser(deleting.id, deleting.version);
                  showToast('用户已删除');
                  setDeleting(null);
                  reload();
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
        {!canDeleteUsers ? (
          <Alert kind="warning" title="删除不可用">
            当前后端未提供删除用户接口，正常模式禁止提交删除请求；请改用「停用」。
          </Alert>
        ) : null}
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ptmcp-text)' }}>
          确认删除用户「{deleting?.displayName || deleting?.username}」？该账号将立即无法登录，历史审计记录保留。
        </p>
      </Dialog>
    </>
  );
}

function UserDialog({
  user,
  roles,
  depts,
  onClose,
  onSaved,
}: {
  user: UserRow | null;
  roles: RoleRow[];
  depts: DepartmentRow[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [values, setValues] = useState<UserFormValues>(() => ({
    username: user?.username ?? '',
    displayName: user?.displayName ?? '',
    deptId: user?.deptId ?? '',
    roleIds: user?.roleIds ? [...user.roleIds] : [],
    enabled: user?.enabled ?? true,
  }));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [conflict, setConflict] = useState('');

  const submit = async () => {
    setFormError('');
    setConflict('');
    const err = validateUserForm(values, !user);
    if (err) {
      setFormError(err);
      return;
    }
    setSaving(true);
    try {
      if (user) {
        // 与 Vue 相同的分离提交：资料 / 角色 / 部门分别走子资源；每次写后版本递增，链式携带最新 version
        let version = user.version;
        const saved = await updateUser(user.id, { displayName: values.displayName.trim(), enabled: values.enabled }, version);
        version = saved.version ?? version;
        if (user.deptId !== values.deptId) {
          const moved = await setUserDepartment(user.id, values.deptId, version);
          version = moved.version ?? version;
        }
        if (JSON.stringify([...user.roleIds].sort()) !== JSON.stringify([...values.roleIds].sort())) {
          await setUserRoles(user.id, values.roleIds, version);
        }
      } else {
        await createUser({
          username: values.username.trim(),
          displayName: values.displayName.trim(),
          deptId: values.deptId,
          roleIds: values.roleIds,
          enabled: values.enabled,
        });
      }
      onSaved();
      showToast(user ? '用户已更新' : '用户已创建');
      onClose();
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setConflict('该用户已被他人修改，请关闭后重试。');
      else setFormError((e as Error).message || '保存失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  };

  const orderedDepts = [...depts].sort((a, b) => departmentDepth(depts, a.id) - departmentDepth(depts, b.id) || a.name.localeCompare(b.name, 'zh'));

  return (
    <Dialog open title={user ? `编辑用户 · ${user.displayName || user.username}` : '新建用户'} onClose={onClose} width={520}>
      {conflict ? (
        <Alert kind="warning" title="版本冲突（412）">
          {conflict}
        </Alert>
      ) : null}
      <Field label="用户名" hint={user ? '用户名创建后不可修改' : '2–100 个字符'}>
        <TextInput value={values.username} onChange={(v) => setValues((s) => ({ ...s, username: v }))} disabled={!!user} placeholder="zhang.san" />
      </Field>
      <Field label="显示名" hint="2–128 个字符">
        <TextInput value={values.displayName} onChange={(v) => setValues((s) => ({ ...s, displayName: v }))} placeholder="张三" />
      </Field>
      <Field label="所属部门">
        <Select
          value={values.deptId}
          onChange={(v) => setValues((s) => ({ ...s, deptId: v }))}
          width={240}
          options={[
            { value: '', label: '请选择部门' },
            ...orderedDepts.map((d) => ({ value: d.id, label: `${'　'.repeat(departmentDepth(depts, d.id))}${d.name}` })),
          ]}
        />
      </Field>
      <Field label={`角色（已选 ${values.roleIds.length}）`} hint="角色决定可见菜单与操作权限">
        <div style={{ maxHeight: 160, overflowY: 'auto', border: '1px solid var(--ptmcp-border)', borderRadius: 8, padding: 6 }}>
          {roles.length === 0 ? (
            <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>角色加载失败时不可保存，以免覆盖已有角色</span>
          ) : (
            roles.map((role) => (
              <label
                key={role.id}
                style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 6px', borderRadius: 6, cursor: 'pointer', fontSize: 12.5 }}
              >
                <input
                  type="checkbox"
                  checked={values.roleIds.includes(role.id)}
                  onChange={() =>
                    setValues((s) => ({
                      ...s,
                      roleIds: s.roleIds.includes(role.id) ? s.roleIds.filter((id) => id !== role.id) : [...s.roleIds, role.id],
                    }))
                  }
                />
                <span>{role.name}</span>
                <code style={{ fontSize: 11, color: 'var(--ptmcp-text-muted)' }}>{role.key}</code>
              </label>
            ))
          )}
        </div>
      </Field>
      <Field label="状态">
        <Select
          value={values.enabled ? '1' : '0'}
          onChange={(v) => setValues((s) => ({ ...s, enabled: v === '1' }))}
          width={160}
          options={[
            { value: '1', label: '启用' },
            { value: '0', label: '停用' },
          ]}
        />
      </Field>
      {formError ? <Alert kind="danger">{formError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
          取消
        </button>
        <button className="btn btn--primary" onClick={submit} disabled={saving || roles.length === 0}>
          {saving ? '保存中…' : user ? '保存修改' : '创建用户'}
        </button>
      </div>
    </Dialog>
  );
}

/** 管理员重置用户密码：强度校验 + 两次一致 + 理由 10–500；If-Match 版本门禁。 */
function ResetPasswordDialog({ user, onClose, onSaved }: { user: UserRow; onClose: () => void; onSaved: () => void }) {
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');

  const submit = async () => {
    setFormError('');
    const strength = passwordError(newPassword);
    if (strength) {
      setFormError(strength);
      return;
    }
    if (newPassword !== confirm) {
      setFormError('两次密码不一致。');
      return;
    }
    if (reason.trim().length < 10 || reason.length > 500) {
      setFormError('请填写10～500字符的重置理由。');
      return;
    }
    setSaving(true);
    try {
      await resetUserPassword(user.id, newPassword, reason.trim(), user.version);
      onSaved();
      onClose();
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setFormError('该用户已被他人修改，请关闭后重开再试。');
      else setFormError(e instanceof Error ? e.message : '重置失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title={`重置密码 · ${user.displayName || user.username}`} onClose={onClose} width={480}>
      <Alert kind="warning" title="重置会记录审计理由">
        新密码仅本次输入，系统不保留明文；重置动作与理由会进入操作审计。
      </Alert>
      <Field label="新密码" hint="12～64 个字符，UTF-8 不超过 72 字节">
        <TextInput value={newPassword} onChange={setNewPassword} type="password" autoComplete="new-password" />
      </Field>
      <Field label="确认新密码">
        <TextInput value={confirm} onChange={setConfirm} type="password" autoComplete="new-password" />
      </Field>
      <Field label={`重置理由（${reason.length}/500）`} hint="至少 10 个字符，写入审计">
        <textarea
          className="input"
          style={{ width: '100%', minHeight: 64, resize: 'vertical', fontFamily: 'inherit' }}
          value={reason}
          maxLength={500}
          onChange={(e) => setReason(e.target.value)}
        />
      </Field>
      {formError ? <Alert kind="danger">{formError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
          取消
        </button>
        <button className="btn btn--primary" onClick={submit} disabled={saving}>
          {saving ? '重置中…' : '确认重置'}
        </button>
      </div>
    </Dialog>
  );
}
