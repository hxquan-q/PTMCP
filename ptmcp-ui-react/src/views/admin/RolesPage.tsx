import { useEffect, useMemo, useState } from 'react';
import { Alert, Badge, Dialog, EmptyState, Field, PageHeader, SearchInput, StatusDot, TextInput, useDebounced } from '../../components/common';
import { CloseIcon, EditIcon, PlusIcon } from '../../components/Icons';
import { request } from '../../services/http';
import { createRole, deleteRole, listRoles, updateRole, type RoleRow } from '../../services/orgPortalApi';
import { validateRoleForm, type RoleFormValues } from './roleForm';
import { useSensitivePermissions } from '../../permissionsContext';
import { showToast } from '../../toast';

interface SessionInfo {
  userId: string;
  displayName: string;
  permissions: string[];
  platformAdmin: boolean;
}

async function getSession(signal?: AbortSignal): Promise<SessionInfo> {
  const { data } = await request<SessionInfo>('/api/v1/session', { signal });
  return data;
}

export function RolesPage() {
  const { can } = useSensitivePermissions();
  const [rows, setRows] = useState<RoleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [permissionOptions, setPermissionOptions] = useState<string[]>([]);
  const [editing, setEditing] = useState<RoleRow | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<RoleRow | null>(null);
  const [busy, setBusy] = useState(false);
  const debounced = useDebounced(keyword);

  const reload = () => {
    let cancelled = false;
    setLoading(true);
    setLoadError('');
    listRoles()
      .then((rs) => !cancelled && setRows(rs))
      .catch(() => {
        if (!cancelled) {
          setRows([]);
          setLoadError('角色列表加载失败，请稍后重试。');
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  };

  useEffect(reload, []);
  useEffect(() => {
    const controller = new AbortController();
    getSession(controller.signal)
      .then((s) => setPermissionOptions([...new Set(s.permissions ?? [])].sort()))
      .catch(() => setPermissionOptions([]));
    return () => controller.abort();
  }, []);

  const filtered = useMemo(
    () =>
      rows.filter(
        (r) => !debounced || r.name.toLowerCase().includes(debounced.toLowerCase()) || r.key.toLowerCase().includes(debounced.toLowerCase()),
      ),
    [rows, debounced],
  );

  const onSaved = () => {
    setEditing(null);
    setCreating(false);
    reload();
  };

  return (
    <>
      <PageHeader
        title="角色管理"
        description="角色定义、成员数与权限码。"
        actions={
          <button className="btn btn--primary" onClick={() => can('roleCreate') && setCreating(true)} disabled={!can('roleCreate')}>
            <PlusIcon /> 新建角色
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
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索角色名称或编码…" width={260} />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {filtered.length} 个角色</span>
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
                <th>角色</th>
                <th>编码</th>
                <th>成员</th>
                <th>状态</th>
                <th style={{ width: 150 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => (
                <tr key={r.id}>
                  <td>
                    <span className="cell-lead">
                      <span className="cell-stack">
                        <button className="link" onClick={() => (window.location.hash = `/admin/roles/${encodeURIComponent(r.id)}`)}>
                          {r.name}
                        </button>
                        <span>ID {r.id}</span>
                      </span>
                    </span>
                  </td>
                  <td>
                    <code style={{ fontSize: 12 }}>{r.key}</code>
                  </td>
                  <td>{r.memberCount ?? '—'}</td>
                  <td>
                    <StatusDot tone={r.enabled ? 'success' : 'neutral'}>{r.enabled ? '启用' : '停用'}</StatusDot>
                  </td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button className="link" onClick={() => setEditing(r)}>
                      <EditIcon /> 编辑
                    </button>
                    <button
                      className="link"
                      style={{ marginLeft: 10, color: 'var(--ptmcp-red)' }}
                      onClick={() => setDeleting(r)}
                      aria-label={`删除 ${r.name}`}
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
                      title={loadError ? '角色加载失败' : '暂无角色'}
                      description={loadError ? '请刷新重试' : '点击右上角「新建角色」创建第一个角色'}
                    />
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </div>

      {creating || editing ? (
        <RoleDialog
          role={editing}
          permissionOptions={permissionOptions}
          onClose={() => {
            setCreating(false);
            setEditing(null);
          }}
          onSaved={onSaved}
        />
      ) : null}

      <Dialog
        open={!!deleting}
        title="删除角色"
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
              disabled={busy || !can('roleDelete')}
              onClick={async () => {
                if (!deleting) return;
                setBusy(true);
                try {
                  await deleteRole(deleting.id, deleting.version);
                  showToast('角色已删除');
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
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ptmcp-text)' }}>
          确认删除角色「{deleting?.name}」？该操作不可撤销，成员将立即失去该角色的权限。
        </p>
      </Dialog>
    </>
  );
}
function RoleDialog({
  role,
  permissionOptions,
  onClose,
  onSaved,
}: {
  role: RoleRow | null;
  permissionOptions: string[];
  onClose: () => void;
  onSaved: () => void;
}) {
  const [values, setValues] = useState<RoleFormValues>(() => ({
    key: role?.key ?? '',
    name: role?.name ?? '',
    enabled: role?.enabled ?? true,
    permissionCodes: role?.permissionCodes ? [...role.permissionCodes] : [],
  }));
  const [codeFilter, setCodeFilter] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [conflict, setConflict] = useState<string | null>(null);

  const retainedCodes = useMemo(() => (role?.permissionCodes ?? []).filter((code) => !permissionOptions.includes(code)), [role, permissionOptions]);
  const visibleOptions = useMemo(() => {
    const kw = codeFilter.trim().toLowerCase();
    return kw ? permissionOptions.filter((c) => c.toLowerCase().includes(kw)) : permissionOptions;
  }, [permissionOptions, codeFilter]);
  const selected = new Set(values.permissionCodes);
  const errors = validateRoleForm(values, !!role);

  const toggleCode = (code: string) => {
    setValues((v) => ({
      ...v,
      permissionCodes: v.permissionCodes.includes(code) ? v.permissionCodes.filter((c) => c !== code) : [...v.permissionCodes, code],
    }));
  };

  const submit = async () => {
    setFormError('');
    setConflict(null);
    const err = validateRoleForm(values, !!role);
    if (err) {
      setFormError(err);
      return;
    }
    setSaving(true);
    try {
      const body = {
        name: values.name.trim(),
        enabled: values.enabled,
        permissionCodes: [...new Set([...values.permissionCodes, ...retainedCodes])].sort(),
      };
      if (role) await updateRole(role.id, body, role.version);
      else await createRole({ ...body, key: values.key.trim() });
      onSaved();
      showToast(role ? '角色已更新' : '角色已创建');
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setConflict('该角色已被他人修改。请复制本地修改后刷新，再重新提交。');
      else if (status === 409) setConflict('编码已存在，请更换角色编码。');
      else setFormError((e as Error).message || '保存失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title={role ? `编辑角色 · ${role.name}` : '新建角色'} onClose={onClose} width={560}>
      {conflict ? (
        <Alert kind="warning" title="版本冲突（412）">
          {conflict}
          <button className="link" style={{ marginLeft: 8 }} onClick={() => window.location.reload()}>
            刷新页面
          </button>
        </Alert>
      ) : null}
      <Field label="角色编码" hint={role ? '编码创建后不可修改' : '大写字母开头，可含数字与下划线'}>
        <TextInput value={values.key} onChange={(v) => setValues((s) => ({ ...s, key: v }))} disabled={!!role} placeholder="ROLE_KEY" />
      </Field>
      <Field label="角色名称" hint="2–128 字符">
        <TextInput value={values.name} onChange={(v) => setValues((s) => ({ ...s, name: v }))} placeholder="如：数据专员" />
      </Field>
      <Field label="状态">
        <select
          value={values.enabled ? '1' : '0'}
          onChange={(e) => setValues((s) => ({ ...s, enabled: e.target.value === '1' }))}
          style={{ width: 160 }}
        >
          <option value="1">启用</option>
          <option value="0">停用</option>
        </select>
      </Field>
      <Field label={`权限码（已选 ${values.permissionCodes.length + retainedCodes.length}）`} hint="保留角色中已有但当前会话不可见的权限码">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {retainedCodes.length ? (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {retainedCodes.map((code) => (
                <Badge key={code} tone="neutral">
                  {code}（保留）
                </Badge>
              ))}
            </div>
          ) : null}
          <TextInput value={codeFilter} onChange={setCodeFilter} placeholder="筛选权限码，如 datasource" />
          <div style={{ maxHeight: 220, overflowY: 'auto', border: '1px solid var(--ptmcp-border)', borderRadius: 8, padding: 6 }}>
            {visibleOptions.length === 0 ? (
              <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>无匹配权限码</span>
            ) : (
              visibleOptions.map((code) => (
                <label
                  key={code}
                  style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 6px', borderRadius: 6, cursor: 'pointer', fontSize: 12.5 }}
                >
                  <input type="checkbox" checked={selected.has(code)} onChange={() => toggleCode(code)} />
                  <code>{code}</code>
                </label>
              ))
            )}
          </div>
        </div>
      </Field>
      {formError ? <Alert kind="danger">{formError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
          取消
        </button>
        <button className="btn btn--primary" onClick={submit} disabled={saving || !!errors}>
          {saving ? '保存中…' : role ? '保存修改' : '创建角色'}
        </button>
      </div>
    </Dialog>
  );
}
