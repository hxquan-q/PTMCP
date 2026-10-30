import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Badge,
  Dialog,
  EmptyState,
  Field,
  PageHeader,
  RequestIdText,
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
import { readFailure, type Failure } from '../../services/http';
import { showToast } from '../../toast';

export function UsersPage() {
  const { can } = useSensitivePermissions();
  /* 后端 UserAdminController 未提供 DELETE /admin/users/{id}；仅合成预览后端支持删除。 */
  const canDeleteUsers = frontendPreview && can('userDelete');
  const [rows, setRows] = useState<UserRow[]>([]);
  const [roles, setRoles] = useState<RoleRow[]>([]);
  const [depts, setDepts] = useState<DepartmentRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [usersFailure, setUsersFailure] = useState<Failure | null>(null);
  const [rolesFailure, setRolesFailure] = useState<Failure | null>(null);
  const [deptsFailure, setDeptsFailure] = useState<Failure | null>(null);
  const [retryTick, setRetryTick] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<UserRow | null>(null);
  const [deleting, setDeleting] = useState<UserRow | null>(null);
  const [resetting, setResetting] = useState<UserRow | null>(null);
  const [busy, setBusy] = useState(false);
  const [actionFailure, setActionFailure] = useState<Failure | null>(null);
  const [deleteFailure, setDeleteFailure] = useState<Failure | null>(null);
  const [reloadTick, setReloadTick] = useState(0);
  const debounced = useDebounced(keyword);

  const [deptFilter, setDeptFilter] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setUsersFailure(null);
    setRolesFailure(null);
    setDeptsFailure(null);
    setRows([]);
    /* 三份目录各自独立失败：合并成一个 catch 会把「角色加载失败」误报成「用户加载失败」。 */
    Promise.allSettled([
      listUsers({ departmentId: deptFilter || undefined, keyword: debounced.trim() || undefined }),
      listRoles(),
      listDepartments(),
    ]).then(([u, r, d]) => {
      if (cancelled) return;
      if (u.status === 'fulfilled') setRows(u.value);
      else {
        setRows([]);
        setUsersFailure(readFailure(u.reason, '用户列表加载失败，请稍后重试。'));
      }
      if (r.status === 'fulfilled') setRoles(r.value);
      else {
        setRoles([]);
        setRolesFailure(readFailure(r.reason, '角色目录加载失败，请稍后重试。'));
      }
      if (d.status === 'fulfilled') setDepts(d.value);
      else {
        setDepts([]);
        setDeptsFailure(readFailure(d.reason, '部门目录加载失败，请稍后重试。'));
      }
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [reloadTick, deptFilter, debounced, retryTick]);

  const reload = () => setReloadTick((t) => t + 1);
  const retryLoad = () => setRetryTick((t) => t + 1);
  const deptName = (id: string | null) => depts.find((d) => d.id === id)?.name ?? '—';

  const filtered = useMemo(
    () =>
      rows.filter(
        (r) =>
          !debounced || r.username.toLowerCase().includes(debounced.toLowerCase()) || r.displayName.toLowerCase().includes(debounced.toLowerCase()),
      ),
    [rows, debounced],
  );

  const directoriesFailed = !!usersFailure || !!rolesFailure || !!deptsFailure;
  const statusText = loading
    ? '正在加载用户列表'
    : usersFailure
      ? '用户列表加载失败'
      : filtered.length === 0
        ? debounced
          ? '没有匹配的用户'
          : '暂无用户'
        : `已加载 ${filtered.length} 个用户`;

  const toggleStatus = async (r: UserRow) => {
    setBusy(true);
    setActionFailure(null);
    try {
      await setUserStatus(r.id, !r.enabled, r.version);
      reload();
      showToast(`用户「${r.displayName || r.username}」已${r.enabled ? '停用' : '启用'}`);
    } catch (e) {
      /* 原先只有 finally：启停失败会变成未处理拒绝，界面仍显示旧状态，管理员以为已生效。 */
      setActionFailure(readFailure(e, `用户「${r.displayName || r.username}」状态未变更`));
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
        <div className="table-toolbar">
          <Select
            value={deptFilter}
            onChange={setDeptFilter}
            label="部门"
            width={150}
            options={[{ value: '', label: '全部部门' }, ...depts.map((d) => ({ value: d.id, label: d.name }))]}
            disabled={loading || !!deptsFailure}
          />
          <SearchInput value={keyword} onChange={setKeyword} label="搜索用户名或显示名" placeholder="搜索用户名或显示名…" width={260} />
          <span className="toolbar-status" aria-hidden="true">
            {loading ? '加载中…' : `已加载 ${filtered.length} 个`}
          </span>
        </div>
        <p className="sr-only" aria-live="polite">
          {statusText}
        </p>
        {usersFailure ? (
          <div style={{ padding: '10px 14px 0' }}>
            <Alert kind="danger" title="用户列表加载失败">
              {usersFailure.message}
              <RequestIdText requestId={usersFailure.requestId} />
              <button className="link" style={{ display: 'inline-flex', marginLeft: 8 }} onClick={retryLoad}>
                重新读取
              </button>
            </Alert>
          </div>
        ) : null}
        {(rolesFailure || deptsFailure) && !usersFailure ? (
          <div style={{ padding: '10px 14px 0' }}>
            <Alert kind="warning" title="部分目录加载失败">
              {[rolesFailure?.message, deptsFailure?.message].filter(Boolean).join('；')}
              {rolesFailure ? <RequestIdText requestId={rolesFailure.requestId} /> : null}
              {deptsFailure ? <RequestIdText requestId={deptsFailure.requestId} /> : null}
              <button className="link" style={{ display: 'inline-flex', marginLeft: 8 }} onClick={retryLoad}>
                重新读取
              </button>
            </Alert>
          </div>
        ) : null}
        {actionFailure ? (
          <div style={{ padding: '10px 14px 0' }}>
            <Alert kind="danger" title="启停未生效">
              {actionFailure.message}
              <RequestIdText requestId={actionFailure.requestId} />
            </Alert>
          </div>
        ) : null}
        <div className="table-scroll" role="region" aria-label="用户列表" tabIndex={0}>
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">用户</th>
                <th scope="col">部门</th>
                <th scope="col">角色</th>
                <th scope="col">状态</th>
                <th scope="col" style={{ width: 210 }}>
                  操作
                </th>
              </tr>
            </thead>
            <tbody>
              {loading && rows.length === 0 ? (
                <tr>
                  <td colSpan={5}>
                    <p className="table-state">正在加载用户…</p>
                  </td>
                </tr>
              ) : null}
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
                  <td style={{ color: 'var(--ptmcp-text-muted)' }}>
                    {deptsFailure && r.deptId ? <span title="部门目录未加载，无法显示名称">部门 ID {r.deptId}</span> : deptName(r.deptId)}
                  </td>
                  <td>
                    <span style={{ display: 'inline-flex', gap: 4, flexWrap: 'wrap' }}>
                      {rolesFailure ? (
                        <span style={{ color: 'var(--ptmcp-text-muted)', fontSize: 12 }}>{`已分配 ${r.roleIds.length} 个（角色目录未加载）`}</span>
                      ) : (
                        <>
                          {r.roleIds.map((rid) => (
                            <Badge key={rid} tone="accent">
                              {roles.find((x) => x.id === rid)?.name ?? `角色 ${rid}`}
                            </Badge>
                          ))}
                          {r.roleIds.length === 0 ? <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>未分配</span> : null}
                        </>
                      )}
                    </span>
                  </td>
                  <td>
                    <StatusDot tone={r.enabled ? 'success' : 'neutral'}>{r.enabled ? '启用' : '停用'}</StatusDot>
                  </td>
                  <td>
                    <span className="row-actions">
                      <button className="link" onClick={() => setEditing(r)} aria-label={`编辑 ${r.displayName || r.username}`}>
                        <EditIcon /> 编辑
                      </button>
                      <button className="link" onClick={() => setResetting(r)} aria-label={`重置 ${r.displayName || r.username} 的密码`}>
                        重置密码
                      </button>
                      <button
                        className="link"
                        disabled={busy || directoriesFailed}
                        title={directoriesFailed ? '目录加载失败时禁止写入，请先重新读取' : undefined}
                        onClick={() => toggleStatus(r)}
                        aria-label={`${r.enabled ? '停用' : '启用'}用户 ${r.displayName || r.username}`}
                      >
                        {r.enabled ? '停用' : '启用'}
                      </button>
                      <button
                        className="link link--danger"
                        disabled={!canDeleteUsers}
                        title={canDeleteUsers ? undefined : '当前后端未提供删除用户接口（仅合成预览模式可用）'}
                        onClick={() => {
                          setDeleteFailure(null);
                          setDeleting(r);
                        }}
                        aria-label={`删除 ${r.displayName || r.username}`}
                      >
                        <CloseIcon /> 删除
                      </button>
                    </span>
                  </td>
                </tr>
              ))}
              {filtered.length === 0 && !loading ? (
                <tr>
                  <td colSpan={5}>
                    {usersFailure ? (
                      <EmptyState title="用户加载失败" description="请按上方的重新读取重试；失败时不显示为空列表。" />
                    ) : debounced ? (
                      <EmptyState
                        title="没有匹配的用户"
                        description={`没有用户名或显示名包含「${debounced}」的记录。`}
                        action={
                          <button className="btn btn--secondary" onClick={() => setKeyword('')}>
                            清空搜索
                          </button>
                        }
                      />
                    ) : (
                      <EmptyState title="暂无用户" description="点击右上角「新建用户」创建第一个用户。" />
                    )}
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
          rolesFailure={rolesFailure}
          depts={depts}
          deptsFailure={deptsFailure}
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
        onClose={() => {
          setDeleting(null);
          setDeleteFailure(null);
        }}
        width={420}
        footer={
          <>
            <button
              className="btn btn--secondary"
              onClick={() => {
                setDeleting(null);
                setDeleteFailure(null);
              }}
              disabled={busy}
            >
              取消
            </button>
            <button
              className="btn btn--primary"
              style={{ background: 'var(--ptmcp-red)' }}
              disabled={busy || !canDeleteUsers}
              onClick={async () => {
                if (!deleting) return;
                setBusy(true);
                setDeleteFailure(null);
                try {
                  await deleteUser(deleting.id, deleting.version);
                  showToast('用户已删除');
                  setDeleting(null);
                  reload();
                } catch (e) {
                  /* 失败必须留在对话框里：原先只有 finally，异常会变成未处理拒绝。 */
                  setDeleteFailure(readFailure(e, '删除请求未完成'));
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
        {deleteFailure ? (
          <Alert kind="danger" title="删除失败">
            {deleteFailure.message}
            <RequestIdText requestId={deleteFailure.requestId} />
          </Alert>
        ) : null}
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

/** 用户写入按子资源分成 3 次请求，不是原子事务：必须告诉管理员哪一步失败、哪些已经生效。 */
const SAVE_STEPS = { profile: '资料（显示名与状态）', department: '所属部门', roles: '角色分配' } as const;
type SaveStep = keyof typeof SAVE_STEPS;

function UserDialog({
  user,
  roles,
  rolesFailure,
  depts,
  deptsFailure,
  onClose,
  onSaved,
}: {
  user: UserRow | null;
  roles: RoleRow[];
  rolesFailure: Failure | null;
  depts: DepartmentRow[];
  deptsFailure: Failure | null;
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
  const [formError, setFormError] = useState<Failure | null>(null);
  const [partial, setPartial] = useState<{ failed: SaveStep; applied: SaveStep[] } | null>(null);
  const [conflict, setConflict] = useState('');

  const knownRoleIds = useMemo(() => new Set(roles.map((r) => r.id)), [roles]);
  /* 目录只返回前 100 条：成员已持有但目录里没有的角色，必须显式保留并展示，不能静默丢掉。 */
  const retainedRoleIds = useMemo(() => (user?.roleIds ?? []).filter((id) => !knownRoleIds.has(id)), [user, knownRoleIds]);
  const rolePlanDirty = JSON.stringify([...(user?.roleIds ?? [])].sort()) !== JSON.stringify([...values.roleIds].sort());
  const deptDirty = !!user && (user.deptId ?? '') !== values.deptId;

  const directoryUnavailable = !!rolesFailure || !!deptsFailure;
  /* 角色目录加载失败时保存会用不完整的清单覆盖真实分配；目录为空但成员已带角色时同理。 */
  const saveBlocked = directoryUnavailable || (roles.length === 0 && !!user && user.roleIds.length > 0);

  const submit = async () => {
    setFormError(null);
    setConflict('');
    setPartial(null);
    const err = validateUserForm(values, !user);
    if (err) {
      setFormError({ message: err, requestId: '' });
      return;
    }
    if (saveBlocked) {
      setFormError({ message: '目录未完整加载，已阻止保存以避免覆盖现有配置。请重新读取后重试。', requestId: '' });
      return;
    }
    setSaving(true);
    const applied: SaveStep[] = [];
    let failed: SaveStep = 'profile';
    try {
      if (user) {
        // 与 Vue 相同的分离提交：资料 / 角色 / 部门分别走子资源；每次写后版本递增，链式携带最新 version
        let version = user.version;
        failed = 'profile';
        const saved = await updateUser(user.id, { displayName: values.displayName.trim(), enabled: values.enabled }, version);
        applied.push('profile');
        version = saved.version ?? version;
        if (deptDirty) {
          failed = 'department';
          const moved = await setUserDepartment(user.id, values.deptId, version);
          applied.push('department');
          version = moved.version ?? version;
        }
        if (rolePlanDirty) {
          failed = 'roles';
          await setUserRoles(user.id, [...new Set([...values.roleIds, ...retainedRoleIds])], version);
          applied.push('roles');
        }
      } else {
        failed = 'profile';
        await createUser({
          username: values.username.trim(),
          displayName: values.displayName.trim(),
          deptId: values.deptId,
          roleIds: [...new Set([...values.roleIds, ...retainedRoleIds])],
          enabled: values.enabled,
        });
        applied.push('profile');
      }
      onSaved();
      showToast(user ? '用户已更新' : '用户已创建');
      onClose();
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setConflict('该用户已被他人修改，请关闭后重试。');
      else {
        setFormError(readFailure(e, `${SAVE_STEPS[failed]}保存失败`));
        setPartial(applied.length ? { failed, applied } : null);
      }
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
      {directoryUnavailable ? (
        <Alert kind="warning" title="目录不完整">
          {[rolesFailure?.message, deptsFailure?.message].filter(Boolean).join('；')} 关闭此对话框后在页面顶部重新读取。
        </Alert>
      ) : null}
      <Field label="用户名" hint={user ? '用户名创建后不可修改' : '2–100 个字符'}>
        <TextInput
          value={values.username}
          onChange={(v) => setValues((s) => ({ ...s, username: v }))}
          disabled={!!user || saving}
          placeholder="zhang.san"
        />
      </Field>
      <Field label="显示名" hint="2–128 个字符">
        <TextInput value={values.displayName} onChange={(v) => setValues((s) => ({ ...s, displayName: v }))} disabled={saving} placeholder="张三" />
      </Field>
      <Field label="所属部门">
        <Select
          value={values.deptId}
          onChange={(v) => setValues((s) => ({ ...s, deptId: v }))}
          width={240}
          disabled={saving}
          options={[
            { value: '', label: '请选择部门' },
            ...orderedDepts.map((d) => ({ value: d.id, label: `${'　'.repeat(departmentDepth(depts, d.id))}${d.name}` })),
          ]}
        />
      </Field>
      <Field label={`角色（已选 ${values.roleIds.length}）`} hint="角色决定可见菜单与操作权限">
        <div className="check-list" role="group" aria-label="可选角色" tabIndex={0}>
          {rolesFailure ? (
            <span className="check-empty">角色目录加载失败，已阻止保存，以免用不完整的清单覆盖现有分配。</span>
          ) : retainedRoleIds.length ? (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginBottom: 6 }}>
              {retainedRoleIds.map((id) => (
                <Badge key={id} tone="neutral">
                  {`角色 ${id}（目录外，保留）`}
                </Badge>
              ))}
            </div>
          ) : null}
          {!rolesFailure && roles.length === 0 ? <span className="check-empty">系统中暂无可分配的角色。</span> : null}
          {roles.map((role) => (
            <label key={role.id} className="check-row">
              <input
                type="checkbox"
                disabled={saving}
                checked={values.roleIds.includes(role.id)}
                onChange={() =>
                  setValues((s) => ({
                    ...s,
                    roleIds: s.roleIds.includes(role.id) ? s.roleIds.filter((id) => id !== role.id) : [...s.roleIds, role.id],
                  }))
                }
              />
              <span>{role.name}</span>
              <code style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{role.key}</code>
            </label>
          ))}
        </div>
      </Field>
      <Field label="状态">
        <Select
          value={values.enabled ? '1' : '0'}
          onChange={(v) => setValues((s) => ({ ...s, enabled: v === '1' }))}
          width={160}
          disabled={saving}
          options={[
            { value: '1', label: '启用' },
            { value: '0', label: '停用' },
          ]}
        />
      </Field>
      {partial ? (
        <Alert kind="warning" title="部分保存">
          {`「${SAVE_STEPS[partial.failed]}」这一步失败。已生效的步骤：${partial.applied.map((s) => SAVE_STEPS[s]).join('、')}。` +
            '用户写入按子资源分次提交、不是原子操作，请关闭对话框并刷新列表核对实际状态后再重试。'}
        </Alert>
      ) : null}
      {formError ? (
        <Alert kind="danger">
          {formError.message}
          <RequestIdText requestId={formError.requestId} />
        </Alert>
      ) : null}
      <div className="form-actions">
        <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
          取消
        </button>
        <button
          className="btn btn--primary"
          onClick={submit}
          disabled={saving || saveBlocked}
          title={saveBlocked ? '角色 / 部门目录未完整加载，禁止保存' : undefined}
        >
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
  const [formError, setFormError] = useState<Failure | null>(null);

  const submit = async () => {
    setFormError(null);
    const strength = passwordError(newPassword);
    if (strength) {
      setFormError({ message: strength, requestId: '' });
      return;
    }
    if (newPassword !== confirm) {
      setFormError({ message: '两次密码不一致。', requestId: '' });
      return;
    }
    if (reason.trim().length < 10 || reason.length > 500) {
      setFormError({ message: '请填写10～500字符的重置理由。', requestId: '' });
      return;
    }
    setSaving(true);
    try {
      await resetUserPassword(user.id, newPassword, reason.trim(), user.version);
      onSaved();
      onClose();
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setFormError({ message: '该用户已被他人修改，请关闭后重开再试。', requestId: '' });
      else setFormError(readFailure(e, '重置密码未完成'));
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
      {formError ? (
        <Alert kind="danger">
          {formError.message}
          <RequestIdText requestId={formError.requestId} />
        </Alert>
      ) : null}
      <div className="form-actions">
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
