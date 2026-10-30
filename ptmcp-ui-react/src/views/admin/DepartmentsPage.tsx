import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
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
  createDepartment,
  deleteDepartment,
  listDepartments,
  listUsers,
  updateDepartment,
  type DepartmentRow,
  type UserRow,
} from '../../services/orgPortalApi';
import { readFailure, type Failure } from '../../services/http';
import { canDeleteDepartment, departmentDepth, departmentDescendants, validateDeptForm, type DeptFormValues } from './orgForms';
import { showToast } from '../../toast';

export function DepartmentsPage() {
  const [rows, setRows] = useState<DepartmentRow[]>([]);
  const [users, setUsers] = useState<UserRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<Failure | null>(null);
  /* 成员列表与部门列表分别读取：成员读不到时删除条件不可判定，不能按 0 个成员放行删除。 */
  const [usersFailure, setUsersFailure] = useState<Failure | null>(null);
  const [keyword, setKeyword] = useState('');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<DepartmentRow | null>(null);
  const [deleting, setDeleting] = useState<DepartmentRow | null>(null);
  const [busy, setBusy] = useState(false);
  const [deleteFailure, setDeleteFailure] = useState<Failure | null>(null);
  const [reloadTick, setReloadTick] = useState(0);
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError(null);
    setUsersFailure(null);
    Promise.allSettled([listDepartments(), listUsers()]).then(([d, u]) => {
      if (cancelled) return;
      if (d.status === 'fulfilled') setRows(d.value);
      else {
        setRows([]);
        setLoadError(readFailure(d.reason, '部门列表加载失败'));
      }
      if (u.status === 'fulfilled') setUsers(u.value);
      else {
        setUsers([]);
        setUsersFailure(readFailure(u.reason, '成员列表加载失败'));
      }
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [reloadTick]);

  const reload = () => setReloadTick((t) => t + 1);

  const sorted = useMemo(
    () => [...rows].sort((a, b) => departmentDepth(rows, a.id) - departmentDepth(rows, b.id) || a.name.localeCompare(b.name, 'zh')),
    [rows],
  );
  const filtered = useMemo(() => sorted.filter((d) => !debounced || d.name.toLowerCase().includes(debounced.toLowerCase())), [sorted, debounced]);
  const membersUnknown = usersFailure !== null;
  const memberCount = (id: string) => users.filter((u) => u.deptId === id).length;
  const deptName = (id: string | null) => rows.find((d) => d.id === id)?.name ?? '—';

  /** 返回禁止删除的原因；null 表示允许删除。 */
  const deletionBlock = (d: DepartmentRow): string | null => {
    if (loadError) return '部门目录未加载，无法确认删除条件';
    if (usersFailure) return `${usersFailure.message}，无法确认该部门没有成员`;
    const check = canDeleteDepartment(d, rows, memberCount(d.id));
    return check.allowed ? null : (check.reason ?? '该部门不满足删除条件');
  };

  return (
    <>
      <PageHeader
        title="部门"
        description="组织层级与成员归属。"
        actions={
          <button className="btn btn--primary" onClick={() => setCreating(true)}>
            <PlusIcon /> 新建部门
          </button>
        }
      />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div className="table-toolbar">
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索部门名称…" label="搜索部门名称" width={240} />
          <span className="toolbar-status">{loading ? '加载中…' : `已加载 ${filtered.length} 个`}</span>
        </div>
        <p className="sr-only" aria-live="polite">
          {loading ? '正在读取部门列表' : loadError ? '部门列表读取失败' : `已加载 ${filtered.length} 个部门`}
        </p>
        {loadError ? (
          <Alert kind="danger" title="部门列表读取失败">
            列表不完整时请勿据此判断删除条件。{loadError.message}
            <RequestIdText requestId={loadError.requestId} />
            <button className="btn btn--secondary" style={{ marginTop: 8 }} onClick={reload}>
              重新读取
            </button>
          </Alert>
        ) : null}
        {usersFailure ? (
          <Alert kind="warning" title="成员数据不可用">
            {usersFailure.message}；成员数显示为「—」，删除按钮暂时禁用。
            <RequestIdText requestId={usersFailure.requestId} />
            <button className="btn btn--secondary" style={{ marginTop: 8 }} onClick={reload}>
              重新读取
            </button>
          </Alert>
        ) : null}
        <div className="table-scroll" role="region" aria-label="部门列表" tabIndex={0}>
          {loading ? <p className="table-state">正在读取部门与成员数据…</p> : null}
          <table className="data-table">
            <thead>
              <tr>
                <th scope="col">部门</th>
                <th scope="col">上级部门</th>
                <th scope="col">成员</th>
                <th scope="col">状态</th>
                <th scope="col" style={{ width: 150 }}>
                  操作
                </th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((d) => {
                const block = deletionBlock(d);
                return (
                  <tr key={d.id}>
                    <td>
                      <span style={{ paddingLeft: departmentDepth(rows, d.id) * 18, fontWeight: 600, color: 'var(--ptmcp-heading)' }}>{d.name}</span>
                      <span style={{ marginLeft: 8, fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>ID {d.id}</span>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{d.parentId ? deptName(d.parentId) : '—（根部门）'}</td>
                    <td title={membersUnknown ? '成员数据未加载，无法给出成员数' : undefined}>{membersUnknown ? '—' : memberCount(d.id)}</td>
                    <td>
                      <StatusDot tone={d.enabled ? 'success' : 'neutral'}>{d.enabled ? '启用' : '停用'}</StatusDot>
                    </td>
                    <td>
                      <span className="row-actions">
                        <button className="link" onClick={() => setEditing(d)} aria-label={`编辑部门 ${d.name}`}>
                          <EditIcon /> 编辑
                        </button>
                        <button
                          className="link link--danger"
                          onClick={() => {
                            setDeleteFailure(null);
                            setDeleting(d);
                          }}
                          aria-label={`删除 ${d.name}`}
                          disabled={!!block}
                          title={block ?? undefined}
                        >
                          <CloseIcon /> 删除
                        </button>
                      </span>
                    </td>
                  </tr>
                );
              })}
              {filtered.length === 0 && !loading ? (
                <tr>
                  <td colSpan={5}>
                    {loadError ? (
                      <EmptyState title="部门加载失败" description="上方已给出失败原因与重新读取入口；读取失败不等于没有部门。" />
                    ) : (
                      <EmptyState
                        title={debounced.trim() ? `没有匹配「${debounced.trim()}」的部门` : '暂无部门'}
                        description={debounced.trim() ? '搜索在当前列表内筛选；清空关键字可看到全部部门。' : '点击右上角「新建部门」创建第一个部门'}
                        action={
                          debounced.trim() ? (
                            <button className="btn btn--secondary" onClick={() => setKeyword('')}>
                              清空搜索
                            </button>
                          ) : undefined
                        }
                      />
                    )}
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
      </div>

      {creating || editing ? (
        <DeptDialog
          dept={editing}
          all={rows}
          onClose={() => {
            setCreating(false);
            setEditing(null);
          }}
          onSaved={reload}
        />
      ) : null}

      <Dialog
        open={!!deleting}
        title="删除部门"
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
              disabled={busy || (!!deleting && !!deletionBlock(deleting))}
              onClick={async () => {
                if (!deleting) return;
                const block = deletionBlock(deleting);
                if (block) {
                  setDeleteFailure({ message: block, requestId: '' });
                  return;
                }
                setBusy(true);
                setDeleteFailure(null);
                try {
                  await deleteDepartment(deleting.id, deleting.version);
                  showToast('部门已删除');
                  setDeleting(null);
                  reload();
                } catch (e) {
                  /* 失败必须留在对话框里：原先只有 finally，异常会变成未处理拒绝且页面无提示。 */
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
        <p style={{ margin: 0, fontSize: 13, color: 'var(--ptmcp-text)' }}>
          确认删除部门「{deleting?.name}」？仅可删除没有成员且没有子部门的节点，该操作不可撤销。
        </p>
        {deleteFailure ? (
          <Alert kind="danger" title="删除失败">
            部门未删除，可修正原因后重试。{deleteFailure.message}
            <RequestIdText requestId={deleteFailure.requestId} />
          </Alert>
        ) : null}
      </Dialog>
    </>
  );
}

function DeptDialog({ dept, all, onClose, onSaved }: { dept: DepartmentRow | null; all: DepartmentRow[]; onClose: () => void; onSaved: () => void }) {
  const [values, setValues] = useState<DeptFormValues>(() => ({
    name: dept?.name ?? '',
    parentId: dept?.parentId ?? null,
    enabled: dept?.enabled ?? true,
  }));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<Failure | null>(null);
  const [conflict, setConflict] = useState('');

  const forbidden = useMemo(() => (dept ? departmentDescendants(all, dept.id) : new Set<string>()), [all, dept]);
  const candidates = useMemo(
    () =>
      [...all]
        .filter((d) => !forbidden.has(d.id))
        .sort((a, b) => departmentDepth(all, a.id) - departmentDepth(all, b.id) || a.name.localeCompare(b.name, 'zh')),
    [all, forbidden],
  );

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
      if (dept) await updateDepartment(dept.id, { name: values.name.trim(), parentId: values.parentId, enabled: values.enabled }, dept.version);
      else await createDepartment({ name: values.name.trim(), parentId: values.parentId, enabled: values.enabled });
      onSaved();
      showToast('部门已保存');
      onClose();
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setConflict('该部门已被他人修改，请关闭后重试。');
      else setFormError(readFailure(e, '部门未保存'));
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
        <TextInput value={values.name} onChange={(v) => setValues((s) => ({ ...s, name: v }))} disabled={saving} placeholder="如：数据分析组" />
      </Field>
      <Field label="上级部门" hint="不能选择自己或自己的下级，避免成环">
        <Select
          value={values.parentId ?? ''}
          onChange={(v) => setValues((s) => ({ ...s, parentId: v || null }))}
          width={240}
          disabled={saving}
          options={[
            { value: '', label: '—（根部门）' },
            ...candidates.map((d) => ({ value: d.id, label: `${'　'.repeat(departmentDepth(all, d.id))}${d.name}` })),
          ]}
        />
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
          {saving ? '保存中…' : dept ? '保存修改' : '创建部门'}
        </button>
      </div>
    </Dialog>
  );
}
