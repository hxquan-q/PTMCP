import { useEffect, useMemo, useState, useRef } from 'react';
import {
  Alert,
  Badge,
  Dialog,
  EmptyState,
  Field,
  PageHeader,
  RequestIdText,
  SearchInput,
  StatusDot,
  TextInput,
  useDebounced,
} from '../../components/common';
import { CloseIcon, EditIcon, PlusIcon } from '../../components/Icons';
import { readFailure, request, idempotencyKey, type Failure } from '../../services/http';
import { createRole, deleteRole, listRoles, updateRole, type RoleRow } from '../../services/orgPortalApi';
import { createIntent } from './createIntent';
import { classifySubmitError, shouldPreserveIntent, submitErrorMessage, uncertainActionLabel, uncertainNotice } from './submitError';
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
  const { data } = await request<SessionInfo>('/api/v1/session/me', { signal });
  return data;
}

export function RolesPage() {
  const { can } = useSensitivePermissions();
  const [rows, setRows] = useState<RoleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<Failure | null>(null);
  const [retryTick, setRetryTick] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [permissionOptions, setPermissionOptions] = useState<string[]>([]);
  const [permsFailure, setPermsFailure] = useState<Failure | null>(null);
  const [editing, setEditing] = useState<RoleRow | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<RoleRow | null>(null);
  const [busy, setBusy] = useState(false);
  const [deleteFailure, setDeleteFailure] = useState<Failure | null>(null);
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    setRows([]);
    listRoles()
      .then((rs) => !cancelled && setRows(rs))
      .catch((e) => {
        if (!cancelled) setLoadError(readFailure(e, '角色列表加载失败，请稍后重试。'));
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [retryTick]);

  useEffect(() => {
    const controller = new AbortController();
    getSession(controller.signal)
      .then((s) => {
        setPermissionOptions([...new Set(s.permissions ?? [])].sort());
        setPermsFailure(null);
      })
      .catch((e) => {
        if (controller.signal.aborted) return;
        /* 原先静默置空：对话框只显示「无匹配权限码」，管理员无从知道是会话读取失败。 */
        setPermissionOptions([]);
        setPermsFailure(readFailure(e, '权限码目录读取失败'));
      });
    return () => controller.abort();
  }, [retryTick]);

  const reload = () => setRetryTick((t) => t + 1);

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
        <div className="table-toolbar">
          <SearchInput value={keyword} onChange={setKeyword} label="搜索角色名称或编码" placeholder="搜索角色名称或编码…" width={260} />
          <span className="toolbar-status" aria-hidden="true">
            {loading ? '加载中…' : `已加载 ${filtered.length} 个`}
          </span>
        </div>
        <p className="sr-only" aria-live="polite">
          {loading ? '正在加载角色列表' : loadError ? '角色列表加载失败' : `已加载 ${filtered.length} 个角色`}
        </p>
        {loadError ? (
          <div style={{ padding: '10px 14px 0' }}>
            <Alert kind="danger" title="角色列表加载失败">
              {loadError.message}
              <RequestIdText requestId={loadError.requestId} />
              <button className="link" style={{ display: 'inline-flex', marginLeft: 8 }} onClick={reload}>
                重新读取
              </button>
            </Alert>
          </div>
        ) : null}
        {permsFailure && !loadError ? (
          <div style={{ padding: '10px 14px 0' }}>
            <Alert kind="warning" title="权限码目录读取失败">
              {permsFailure.message}：新建或编辑角色时无法勾选权限码。
              <RequestIdText requestId={permsFailure.requestId} />
              <button className="link" style={{ display: 'inline-flex', marginLeft: 8 }} onClick={reload}>
                重新读取
              </button>
            </Alert>
          </div>
        ) : null}
        <div className="table-scroll" role="region" aria-label="角色列表" tabIndex={0}>
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">角色</th>
                <th scope="col">编码</th>
                <th scope="col">成员</th>
                <th scope="col">状态</th>
                <th scope="col" style={{ width: 150 }}>
                  操作
                </th>
              </tr>
            </thead>
            <tbody>
              {loading && rows.length === 0 ? (
                <tr>
                  <td colSpan={5}>
                    <p className="table-state">正在加载角色…</p>
                  </td>
                </tr>
              ) : null}
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
                  <td>
                    {r.memberCount ?? (
                      <span title="契约未返回成员数" style={{ color: 'var(--ptmcp-text-muted)' }}>
                        未知
                      </span>
                    )}
                  </td>
                  <td>
                    <StatusDot tone={r.enabled ? 'success' : 'neutral'}>{r.enabled ? '启用' : '停用'}</StatusDot>
                  </td>
                  <td>
                    <span className="row-actions">
                      <button className="link" onClick={() => setEditing(r)} aria-label={`编辑角色 ${r.name}`}>
                        <EditIcon /> 编辑
                      </button>
                      <button
                        className="link link--danger"
                        disabled={!can('roleDelete')}
                        title={can('roleDelete') ? undefined : '缺少角色删除权限'}
                        onClick={() => {
                          setDeleteFailure(null);
                          setDeleting(r);
                        }}
                        aria-label={`删除角色 ${r.name}`}
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
                    {loadError ? (
                      <EmptyState title="角色加载失败" description="请按上方的「重新读取」重试；失败时不显示为空列表。" />
                    ) : debounced ? (
                      <EmptyState
                        title="没有匹配的角色"
                        description={`没有名称或编码包含「${debounced}」的角色。`}
                        action={
                          <button className="btn btn--secondary" onClick={() => setKeyword('')}>
                            清空搜索
                          </button>
                        }
                      />
                    ) : (
                      <EmptyState title="暂无角色" description="点击右上角「新建角色」创建第一个角色。" />
                    )}
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
          permsFailure={permsFailure}
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
              disabled={busy || !can('roleDelete')}
              onClick={async () => {
                if (!deleting) return;
                setBusy(true);
                setDeleteFailure(null);
                try {
                  await deleteRole(deleting.id, deleting.version);
                  showToast('角色已删除');
                  setDeleting(null);
                  reload();
                } catch (e) {
                  /* 失败留在对话框：原先只有 finally，异常会变成未处理拒绝且页面无提示。 */
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
  permsFailure,
  onClose,
  onSaved,
}: {
  role: RoleRow | null;
  permissionOptions: string[];
  permsFailure: Failure | null;
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
  const [uncertain, setUncertain] = useState(false);
  const idemKeyRef = useRef<string>('');
  const snapshotRef = useRef<ReturnType<typeof createIntent>['body'] | null>(null);
  const [formError, setFormError] = useState<Failure | null>(null);
  const [conflict, setConflict] = useState<string | null>(null);

  const retainedCodes = useMemo(() => (role?.permissionCodes ?? []).filter((code) => !permissionOptions.includes(code)), [role, permissionOptions]);
  const visibleOptions = useMemo(() => {
    const kw = codeFilter.trim().toLowerCase();
    return kw ? permissionOptions.filter((c) => c.toLowerCase().includes(kw)) : permissionOptions;
  }, [permissionOptions, codeFilter]);
  const selected = new Set(values.permissionCodes);
  const errors = validateRoleForm(values, !role);
  /* 目录为空多半是会话读取失败，而非系统真的没有权限码；此时保存会产出无权限角色。 */
  const catalogUnavailable = !!permsFailure || (permissionOptions.length === 0 && retainedCodes.length > 0);

  const toggleCode = (code: string) => {
    setValues((v) => ({
      ...v,
      permissionCodes: v.permissionCodes.includes(code) ? v.permissionCodes.filter((c) => c !== code) : [...v.permissionCodes, code],
    }));
  };

  const submit = async () => {
    setFormError(null);
    setConflict(null);
    // 编辑不是幂等写：结果未知后禁止原地重提，必须关闭并刷新核对最新版本。
    if (uncertain && role) return;
    const err = validateRoleForm(values, !role);
    if (err) {
      setFormError({ message: err, requestId: '' });
      return;
    }
    if (catalogUnavailable) {
      setFormError({ message: '权限码目录不可用，已阻止保存，避免创建或覆盖出一个没有任何权限的角色。', requestId: permsFailure?.requestId ?? '' });
      return;
    }
    setSaving(true);
    try {
      const body = {
        name: values.name.trim(),
        enabled: values.enabled,
        permissionCodes: [...new Set([...values.permissionCodes, ...retainedCodes])].sort(),
      };
      if (role) {
        await updateRole(role.id, body, role.version);
      } else if (uncertain && snapshotRef.current) {
        // OPERATION_UNKNOWN 重试：复用冻结快照+原 key（不改 body）
        await createRole({ ...snapshotRef.current, permissionCodes: [...snapshotRef.current.permissionCodes] }, idemKeyRef.current);
      } else {
        // 首次提交：生成 key + 冻结 body 快照
        const intent = createIntent({ ...body, key: values.key.trim() }, idempotencyKey());
        idemKeyRef.current = intent.key;
        snapshotRef.current = intent.body;
        await createRole({ ...snapshotRef.current, permissionCodes: [...snapshotRef.current.permissionCodes] }, idemKeyRef.current);
      }
      onSaved();
      showToast(role ? '角色已更新' : '角色已创建');
    } catch (e) {
      const err = e as { status?: number; code?: string; message?: string; requestId?: string | null };
      const outcome = classifySubmitError(err);
      if (shouldPreserveIntent(outcome)) {
        setUncertain(true);
        setFormError(null);
      } else {
        setUncertain(false);
        const msg = submitErrorMessage(outcome, err.message || '保存失败，请稍后重试。', role ? 'edit' : 'create');
        if (outcome === 'conflict' || outcome === 'duplicate') setConflict(msg);
        else setFormError({ message: msg, requestId: err.requestId ?? '' });
      }
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title={role ? `编辑角色 · ${role.name}` : '新建角色'} onClose={onClose} width={560}>
      {conflict ? (
        <Alert kind="warning" title="版本冲突">
          {conflict}
          <button className="link" style={{ marginLeft: 8 }} onClick={() => window.location.reload()}>
            刷新页面
          </button>
        </Alert>
      ) : null}
      <Field label="角色编码" hint={role ? '编码创建后不可修改' : '小写字母开头，1–100字符'}>
        <TextInput
          value={values.key}
          onChange={(v) => setValues((s) => ({ ...s, key: v }))}
          disabled={!!role || uncertain || saving}
          placeholder="ROLE_KEY"
        />
      </Field>
      <Field label="角色名称" hint="2–30 字符">
        <TextInput
          value={values.name}
          onChange={(v) => setValues((s) => ({ ...s, name: v }))}
          disabled={uncertain || saving}
          placeholder="如：数据专员"
        />
      </Field>
      <Field label="状态">
        <select
          disabled={uncertain || saving}
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
          <TextInput value={codeFilter} onChange={setCodeFilter} label="筛选权限码" placeholder="筛选权限码，如 datasource" disabled={saving} />
          <div className="check-list" role="group" aria-label="可选权限码" tabIndex={0}>
            {visibleOptions.length === 0 ? (
              <span className="check-empty">
                {permsFailure
                  ? `权限码目录读取失败：${permsFailure.message}。已阻止保存，请先重新读取。`
                  : permissionOptions.length === 0
                    ? '当前会话没有任何可分配的权限码。'
                    : `没有权限码包含「${codeFilter.trim()}」。`}
              </span>
            ) : (
              visibleOptions.map((code) => (
                <label key={code} className="check-row">
                  <input type="checkbox" checked={selected.has(code)} disabled={uncertain || saving} onChange={() => toggleCode(code)} />
                  <code style={{ fontSize: 12 }}>{code}</code>
                </label>
              ))
            )}
          </div>
        </div>
      </Field>
      {uncertain ? (
        <Alert kind="warning" title="操作结果未知">
          {uncertainNotice(role ? 'edit' : 'create')}
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
          disabled={saving || !!errors || (uncertain && !!role) || catalogUnavailable}
          title={
            uncertain && role
              ? '编辑不是幂等写：请关闭对话框并刷新列表核对最新版本后再操作'
              : catalogUnavailable
                ? '权限码目录不可用，禁止保存'
                : undefined
          }
        >
          {saving ? '保存中…' : uncertain ? (role ? '请关闭并刷新核对' : uncertainActionLabel('create')) : role ? '保存修改' : '创建角色'}
        </button>
      </div>
    </Dialog>
  );
}
