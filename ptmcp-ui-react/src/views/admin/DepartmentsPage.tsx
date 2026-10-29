import { useEffect, useMemo, useState } from 'react';
import { Alert, Dialog, EmptyState, Field, PageHeader, SearchInput, Select, StatusDot, TextInput, useDebounced } from '../../components/common';
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
import { departmentDepth, departmentDescendants, validateDeptForm, type DeptFormValues } from './orgForms';
import { showToast } from '../../toast';

export function DepartmentsPage() {
  const [rows, setRows] = useState<DepartmentRow[]>([]);
  const [users, setUsers] = useState<UserRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState('');
  const [keyword, setKeyword] = useState('');
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<DepartmentRow | null>(null);
  const [deleting, setDeleting] = useState<DepartmentRow | null>(null);
  const [busy, setBusy] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);
  const debounced = useDebounced(keyword);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setLoadError('');
    Promise.all([listDepartments(), listUsers()])
      .then(([depts, us]) => {
        if (cancelled) return;
        setRows(depts);
        setUsers(us);
      })
      .catch(() => {
        if (!cancelled) setLoadError('部门列表加载失败，请稍后重试。');
      })
      .finally(() => !cancelled && setLoading(false));
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
  const memberCount = (id: string) => users.filter((u) => u.deptId === id).length;
  const deptName = (id: string | null) => rows.find((d) => d.id === id)?.name ?? '—';

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
          <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索部门名称…" width={240} />
          <span style={{ marginLeft: 'auto', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>共 {filtered.length} 个部门</span>
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
                <th>部门</th>
                <th>上级部门</th>
                <th>成员</th>
                <th>状态</th>
                <th style={{ width: 150 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((d) => (
                <tr key={d.id}>
                  <td>
                    <span style={{ paddingLeft: departmentDepth(rows, d.id) * 18, fontWeight: 600, color: 'var(--ptmcp-heading)' }}>{d.name}</span>
                    <span style={{ marginLeft: 8, fontSize: 11, color: 'var(--ptmcp-text-faint)' }}>ID {d.id}</span>
                  </td>
                  <td style={{ color: 'var(--ptmcp-text-muted)' }}>{d.parentId ? deptName(d.parentId) : '—（根部门）'}</td>
                  <td>{memberCount(d.id)}</td>
                  <td>
                    <StatusDot tone={d.enabled ? 'success' : 'neutral'}>{d.enabled ? '启用' : '停用'}</StatusDot>
                  </td>
                  <td style={{ whiteSpace: 'nowrap' }}>
                    <button className="link" onClick={() => setEditing(d)}>
                      <EditIcon /> 编辑
                    </button>
                    <button
                      className="link"
                      style={{ marginLeft: 10, color: 'var(--ptmcp-red)' }}
                      onClick={() => setDeleting(d)}
                      aria-label={`删除 ${d.name}`}
                      disabled={memberCount(d.id) > 0}
                      title={memberCount(d.id) > 0 ? '部门下仍有成员，不能删除' : undefined}
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
                      title={loadError ? '部门加载失败' : '暂无部门'}
                      description={loadError ? '请刷新重试' : '点击右上角「新建部门」创建第一个部门'}
                    />
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
              disabled={busy}
              onClick={async () => {
                if (!deleting) return;
                setBusy(true);
                try {
                  await deleteDepartment(deleting.id, deleting.version);
                  showToast('部门已删除');
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
          确认删除部门「{deleting?.name}」？仅可删除没有成员且没有子部门的节点，该操作不可撤销。
        </p>
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
  const [formError, setFormError] = useState('');
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
    setFormError('');
    setConflict('');
    const err = validateDeptForm(values);
    if (err) {
      setFormError(err);
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
      else setFormError((e as Error).message || '保存失败，请稍后重试。');
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
        <TextInput value={values.name} onChange={(v) => setValues((s) => ({ ...s, name: v }))} placeholder="如：数据分析组" />
      </Field>
      <Field label="上级部门" hint="不能选择自己或自己的下级，避免成环">
        <Select
          value={values.parentId ?? ''}
          onChange={(v) => setValues((s) => ({ ...s, parentId: v || null }))}
          width={240}
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
        <button className="btn btn--primary" onClick={submit} disabled={saving}>
          {saving ? '保存中…' : dept ? '保存修改' : '创建部门'}
        </button>
      </div>
    </Dialog>
  );
}
