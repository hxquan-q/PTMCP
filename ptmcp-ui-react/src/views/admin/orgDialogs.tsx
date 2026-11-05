import { useId, useMemo, useState } from 'react';
import { Button } from 'antd';
import { Alert, Badge, Dialog, Drawer, Field, RequestIdText, Select, StatusDot, TextInput } from '../../components/common';
import {
  createDepartment,
  createUser,
  resetUserPassword,
  setUserDepartment,
  setUserRoles,
  updateDepartment,
  updateUser,
  type DepartmentRow,
  type RoleRow,
  type UserRow,
} from '../../services/orgPortalApi';
import { readFailure, type Failure } from '../../services/http';
import { showToast } from '../../toast';
import { passwordError } from '../me/passwordForm';
import { departmentDepth, departmentDescendants, validateDeptForm, validateUserForm, type DeptFormValues, type UserFormValues } from './orgForms';

/** 用户写入按子资源分成 3 次请求，不是原子事务：必须告诉管理员哪一步失败、哪些已经生效。 */
const SAVE_STEPS = { profile: '资料（显示名与状态）', department: '所属部门', roles: '角色分配' } as const;
type SaveStep = keyof typeof SAVE_STEPS;

function orderedDepartments(depts: DepartmentRow[]): DepartmentRow[] {
  return [...depts].sort((a, b) => departmentDepth(depts, a.id) - departmentDepth(depts, b.id) || a.name.localeCompare(b.name, 'zh'));
}

function departmentOptions(depts: DepartmentRow[]): { value: string; label: string }[] {
  return orderedDepartments(depts).map((d) => ({
    value: d.id,
    label: `${'　'.repeat(departmentDepth(depts, d.id))}${d.name}${d.enabled ? '' : '（停用）'}`,
  }));
}

interface MemberEditorProps {
  user: UserRow | null;
  defaultDeptId: string;
  roles: RoleRow[];
  rolesFailure: Failure | null;
  depts: DepartmentRow[];
  deptsFailure: Failure | null;
  showReset: boolean;
  onClose: () => void;
  onSaved: () => void;
}

/** 成员资料、部门与角色编辑主体：新建对话框和成员详情抽屉共用同一套提交语义。 */
function MemberEditor({ user, defaultDeptId, roles, rolesFailure, depts, deptsFailure, showReset, onClose, onSaved }: MemberEditorProps) {
  const [values, setValues] = useState<UserFormValues>(() => ({
    username: user?.username ?? '',
    displayName: user?.displayName ?? '',
    deptId: user?.deptId ?? defaultDeptId,
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
        /* 与 Vue 相同的分离提交：资料 / 角色 / 部门分别走子资源；每次写后版本递增，链式携带最新 version。 */
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
      showToast(user ? '成员已更新' : '成员已创建');
      onClose();
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setConflict('该成员已被他人修改，请关闭后重试。');
      else {
        setFormError(readFailure(e, `${SAVE_STEPS[failed]}保存失败`));
        setPartial(applied.length ? { failed, applied } : null);
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
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
          ariaLabel="所属部门"
          value={values.deptId}
          onChange={(v) => setValues((s) => ({ ...s, deptId: v }))}
          width={260}
          disabled={saving || !!deptsFailure}
          options={[{ value: '', label: '请选择部门' }, ...departmentOptions(depts)]}
        />
      </Field>
      <Field label={`角色（已选 ${values.roleIds.length}）`} hint="角色决定可见菜单与操作权限">
        <div className="check-list" role="group" aria-label="可选角色" tabIndex={0}>
          {rolesFailure ? (
            <span className="check-empty">角色目录加载失败，已阻止保存，以免用不完整的清单覆盖现有分配。</span>
          ) : retainedRoleIds.length ? (
            <div className="org-retained-roles">
              {retainedRoleIds.map((id) => (
                <Badge key={id} tone="neutral">
                  {`角色 ${id}（目录外，保留）`}
                </Badge>
              ))}
            </div>
          ) : null}
          {roles.map((role) => (
            <label key={role.id} className="check-row">
              <input
                type="checkbox"
                /* Field 用 <label> 包住整组：不显式命名时每个勾选框都会念出分组标签和提示。 */
                aria-label={`${role.name}（${role.key}）`}
                checked={values.roleIds.includes(role.id)}
                disabled={saving}
                onChange={() =>
                  setValues((s) => ({
                    ...s,
                    roleIds: s.roleIds.includes(role.id) ? s.roleIds.filter((id) => id !== role.id) : [...s.roleIds, role.id],
                  }))
                }
              />
              <span>{role.name}</span>
              <code className="org-role-key">{role.key}</code>
            </label>
          ))}
          {roles.length === 0 && !rolesFailure ? <span className="check-empty">暂无可分配角色。</span> : null}
        </div>
      </Field>
      <Field label="状态">
        <Select
          ariaLabel="状态"
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
        <Button onClick={onClose} disabled={saving}>
          取消
        </Button>
        <Button
          type="primary"
          onClick={submit}
          disabled={saving || saveBlocked}
          title={saveBlocked ? '角色 / 部门目录未完整加载，禁止保存' : undefined}
        >
          {saving ? '保存中…' : user ? '保存修改' : '创建成员'}
        </Button>
      </div>
      {showReset && user ? (
        <ResetPasswordSection
          user={user}
          onDone={() => {
            onSaved();
            onClose();
          }}
        />
      ) : null}
    </>
  );
}

/** 管理员重置成员密码：强度校验 + 两次一致 + 理由 10–500；If-Match 版本门禁。 */
function ResetPasswordSection({ user, onDone }: { user: UserRow; onDone: () => void }) {
  const titleId = useId();
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [reason, setReason] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<Failure | null>(null);

  const clear = () => {
    setNewPassword('');
    setConfirm('');
    setReason('');
    setFormError(null);
  };

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
      setFormError({ message: '请填写 10～500 字符的重置理由。', requestId: '' });
      return;
    }
    setSaving(true);
    try {
      await resetUserPassword(user.id, newPassword, reason.trim(), user.version);
      showToast('密码已重置');
      onDone();
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setFormError({ message: '该成员已被他人修改，请关闭后重开再试。', requestId: '' });
      else setFormError(readFailure(e, '重置密码未完成'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <section className="org-reset" aria-labelledby={titleId}>
      <header className="org-reset-head">
        <h3 id={titleId}>重置登录密码</h3>
        <p>账密仅由管理员创建和管理；新密码仅本次输入，系统不保留明文，操作与理由进入审计。</p>
      </header>
      <Field label="新密码" hint="12～64 个字符，UTF-8 不超过 72 字节">
        <TextInput value={newPassword} onChange={setNewPassword} type="password" autoComplete="new-password" />
      </Field>
      <Field label="确认新密码">
        <TextInput value={confirm} onChange={setConfirm} type="password" autoComplete="new-password" />
      </Field>
      <Field label="重置理由" hint="10～500 个字符，写入审计记录">
        <TextInput value={reason} onChange={setReason} maxLength={500} placeholder="例如：员工忘记密码，经本人与主管确认" />
      </Field>
      {formError ? (
        <Alert kind="danger">
          {formError.message}
          <RequestIdText requestId={formError.requestId} />
        </Alert>
      ) : null}
      <div className="form-actions">
        <Button onClick={clear} disabled={saving}>
          清空
        </Button>
        <Button type="primary" onClick={submit} disabled={saving}>
          {saving ? '重置中…' : '重置密码'}
        </Button>
      </div>
    </section>
  );
}

/** 新建成员：与详情抽屉共用编辑主体，创建时默认落在当前选中的部门。 */
export function MemberFormDialog(props: Omit<MemberEditorProps, 'user' | 'showReset'>) {
  return (
    <Dialog open title="新建成员" onClose={props.onClose} width={560}>
      <MemberEditor {...props} user={null} showReset={false} />
    </Dialog>
  );
}

/** 成员详情抽屉：资料、部门、角色与密码重置在同一处完成。 */
export function MemberDrawer({
  user,
  departmentLabel,
  onClose,
  onSaved,
  ...editorProps
}: Omit<MemberEditorProps, 'user' | 'showReset' | 'onClose' | 'onSaved'> & {
  user: UserRow;
  departmentLabel: string;
  onClose: () => void;
  onSaved: () => void;
}) {
  const initial = (user.displayName || user.username).slice(0, 1).toUpperCase();
  return (
    <Drawer
      open
      onClose={onClose}
      title={user.displayName || user.username}
      subtitle={`@${user.username} · ${departmentLabel}`}
      icon={
        <span className="org-avatar org-avatar--lg" aria-hidden="true">
          {initial}
        </span>
      }
      badge={<StatusDot tone={user.enabled ? 'success' : 'neutral'}>{user.enabled ? '启用' : '停用'}</StatusDot>}
      width={520}
    >
      <MemberEditor {...editorProps} user={user} showReset onClose={onClose} onSaved={onSaved} />
    </Drawer>
  );
}

/** 新建 / 编辑部门：上级部门下拉排除自身子树，避免提交即成的环。 */
export function DeptDialog({
  dept,
  defaultParentId,
  all,
  onClose,
  onSaved,
}: {
  dept: DepartmentRow | null;
  defaultParentId: string | null;
  all: DepartmentRow[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [values, setValues] = useState<DeptFormValues>(() => ({
    name: dept?.name ?? '',
    parentId: dept ? dept.parentId : defaultParentId,
    enabled: dept?.enabled ?? true,
  }));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<Failure | null>(null);
  const [conflict, setConflict] = useState('');

  const blocked = useMemo(() => (dept ? departmentDescendants(all, dept.id) : new Set<string>()), [dept, all]);
  const parentOptions = useMemo(() => departmentOptions(all.filter((d) => !blocked.has(d.id))), [all, blocked]);

  const submit = async () => {
    setFormError(null);
    setConflict('');
    const err = validateDeptForm(values);
    if (err) {
      setFormError({ message: err, requestId: '' });
      return;
    }
    setSaving(true);
    try {
      if (dept) {
        await updateDepartment(dept.id, { parentId: values.parentId, name: values.name.trim(), enabled: values.enabled }, dept.version);
      } else {
        await createDepartment({ parentId: values.parentId, name: values.name.trim(), enabled: values.enabled });
      }
      onSaved();
      showToast(dept ? '部门已更新' : '部门已创建');
      onClose();
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setConflict('该部门已被他人修改，请关闭后重试。');
      else setFormError(readFailure(e, dept ? '部门更新未完成' : '部门创建未完成'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title={dept ? `编辑部门 · ${dept.name}` : '新建部门'} onClose={onClose} width={460}>
      {conflict ? (
        <Alert kind="warning" title="版本冲突（412）">
          {conflict}
        </Alert>
      ) : null}
      <Field label="部门名称" hint="2–128 个字符">
        <TextInput value={values.name} onChange={(v) => setValues((s) => ({ ...s, name: v }))} disabled={saving} placeholder="平台工程组" />
      </Field>
      <Field label="上级部门" hint="留空表示根级部门">
        <Select
          ariaLabel="上级部门"
          value={values.parentId ?? ''}
          onChange={(v) => setValues((s) => ({ ...s, parentId: v || null }))}
          width={260}
          disabled={saving}
          options={[{ value: '', label: '根级部门' }, ...parentOptions]}
        />
      </Field>
      <Field label="状态">
        <Select
          ariaLabel="部门状态"
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
      {formError ? (
        <Alert kind="danger">
          {formError.message}
          <RequestIdText requestId={formError.requestId} />
        </Alert>
      ) : null}
      <div className="form-actions">
        <Button onClick={onClose} disabled={saving}>
          取消
        </Button>
        <Button type="primary" onClick={submit} disabled={saving}>
          {saving ? '保存中…' : dept ? '保存修改' : '创建部门'}
        </Button>
      </div>
    </Dialog>
  );
}
