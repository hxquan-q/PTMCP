import { useEffect, useMemo, useState, useRef } from 'react';
import { Button } from 'antd';
import { ProTable, type ProColumns } from '@ant-design/pro-components';
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
import { idempotencyKey, readFailure, type Failure } from '../../services/http';
import { createRole, deleteRole, listRoles, updateRole, type RoleRow } from '../../services/orgPortalApi';
import { createIntent } from './createIntent';
import { classifySubmitError, shouldPreserveIntent, submitErrorMessage, uncertainActionLabel, uncertainNotice } from './submitError';
import { validateRoleForm, type RoleFormValues } from './roleForm';
import { useAdminGate } from '../../adminGateContext';
import { showToast } from '../../toast';

export function RolesPage() {
  const isAdmin = useAdminGate();
  const [rows, setRows] = useState<RoleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<Failure | null>(null);
  const [retryTick, setRetryTick] = useState(0);
  const [keyword, setKeyword] = useState('');
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

  const reload = () => setRetryTick((t) => t + 1);

  const roleColumns: ProColumns<RoleRow>[] = [
    {
      title: '角色',
      render: (_, r) => (
        <span className="cell-lead">
          <span className="cell-stack">
            <Button
              type="link"
              size="small"
              style={{ padding: 0, height: 'auto' }}
              onClick={() => (window.location.hash = `/admin/roles/${encodeURIComponent(r.id)}`)}
            >
              {r.name}
            </Button>
            <span>ID {r.id}</span>
          </span>
        </span>
      ),
    },
    { title: '编码', render: (_, r) => <code style={{ fontSize: 12 }}>{r.key}</code> },
    {
      title: '状态',
      render: (_, r) => <StatusDot tone={r.enabled ? 'success' : 'neutral'}>{r.enabled ? '启用' : '停用'}</StatusDot>,
    },
    {
      title: '操作',
      width: 150,
      render: (_, r) => (
        <span className="row-actions">
          <Button type="link" size="small" icon={<EditIcon />} style={{ padding: 0 }} onClick={() => setEditing(r)} aria-label={`编辑角色 ${r.name}`}>
            编辑
          </Button>
          <Button
            type="link"
            size="small"
            danger
            icon={<CloseIcon />}
            style={{ padding: 0 }}
            disabled={!isAdmin}
            title={isAdmin ? undefined : '缺少角色删除权限'}
            onClick={() => {
              setDeleteFailure(null);
              setDeleting(r);
            }}
            aria-label={`删除角色 ${r.name}`}
          >
            删除
          </Button>
        </span>
      ),
    },
  ];

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
        description="角色是挂到用户的数据权限模板；具体资源授权在「授权管理」维护。"
        actions={
          <Button type="primary" icon={<PlusIcon />} onClick={() => isAdmin && setCreating(true)} disabled={!isAdmin}>
            新建角色
          </Button>
        }
      />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 8 }}>
          <SearchInput value={keyword} onChange={setKeyword} label="搜索角色名称或编码" placeholder="搜索角色名称或编码…" width={260} />
        </div>
        <p className="sr-only" aria-live="polite">
          {loading ? '正在加载角色列表' : loadError ? '角色列表加载失败' : `已加载 ${filtered.length} 个角色`}
        </p>
        {loadError ? (
          <div style={{ padding: '10px 14px 0' }}>
            <Alert kind="danger" title="角色列表加载失败">
              {loadError.message}
              <RequestIdText requestId={loadError.requestId} />
              <Button type="link" size="small" style={{ marginLeft: 8 }} onClick={reload}>
                重新读取
              </Button>
            </Alert>
          </div>
        ) : null}
        <ProTable<RoleRow>
          rowKey="id"
          columns={roleColumns}
          dataSource={filtered}
          loading={loading}
          search={false}
          options={false}
          dateFormatter={false}
          cardProps={{ style: { border: 'none', boxShadow: 'none', padding: 0 } }}
          pagination={{ pageSize: 20, showSizeChanger: false, showTotal: (t) => `共 ${t} 条` }}
          locale={{
            emptyText: loadError ? (
              <EmptyState kind="error" title="角色加载失败" description="请按上方的「重新读取」重试；失败时不显示为空列表。" />
            ) : debounced ? (
              <EmptyState
                kind="filtered"
                title="没有匹配的角色"
                description={`没有名称或编码包含「${debounced}」的角色。`}
                action={<Button onClick={() => setKeyword('')}>清空搜索</Button>}
              />
            ) : (
              <EmptyState title="暂无角色" description="点击右上角「新建角色」创建第一个角色。" />
            ),
          }}
        />
      </div>

      {creating || editing ? (
        <RoleDialog
          role={editing}
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
            <Button
              onClick={() => {
                setDeleting(null);
                setDeleteFailure(null);
              }}
              disabled={busy}
            >
              取消
            </Button>
            <Button
              color="danger"
              variant="solid"
              disabled={busy || !isAdmin}
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
            </Button>
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
function RoleDialog({ role, onClose, onSaved }: { role: RoleRow | null; onClose: () => void; onSaved: () => void }) {
  const [values, setValues] = useState<RoleFormValues>(() => ({
    key: role?.key ?? '',
    name: role?.name ?? '',
    enabled: role?.enabled ?? true,
  }));
  const [saving, setSaving] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const idemKeyRef = useRef<string>('');
  const snapshotRef = useRef<ReturnType<typeof createIntent>['body'] | null>(null);
  const [formError, setFormError] = useState<Failure | null>(null);
  const [conflict, setConflict] = useState<string | null>(null);

  const errors = validateRoleForm(values, !role);

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
    setSaving(true);
    try {
      const body = {
        name: values.name.trim(),
        enabled: values.enabled,
      };
      if (role) {
        await updateRole(role.id, body, role.version);
      } else if (uncertain && snapshotRef.current) {
        // OPERATION_UNKNOWN 重试：复用冻结快照+原 key（不改 body）
        await createRole({ ...snapshotRef.current }, idemKeyRef.current);
      } else {
        // 首次提交：生成 key + 冻结 body 快照
        const intent = createIntent({ ...body, key: values.key.trim() }, idempotencyKey());
        idemKeyRef.current = intent.key;
        snapshotRef.current = intent.body;
        await createRole({ ...snapshotRef.current }, idemKeyRef.current);
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
          <Button type="link" size="small" style={{ marginLeft: 8 }} onClick={() => window.location.reload()}>
            刷新页面
          </Button>
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
        {/* antd Select：布尔状态以字符串编码， onChange 解码回布尔。 */}
        <Select
          ariaLabel="状态"
          disabled={uncertain || saving}
          value={values.enabled ? '1' : '0'}
          onChange={(v) => setValues((s) => ({ ...s, enabled: v === '1' }))}
          options={[
            { value: '1', label: '启用' },
            { value: '0', label: '停用' },
          ]}
          width={160}
        />
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
        <Button onClick={onClose} disabled={saving}>
          取消
        </Button>
        <Button
          type="primary"
          onClick={submit}
          disabled={saving || !!errors || (uncertain && !!role)}
          title={uncertain && role ? '编辑不是幂等写：请关闭对话框并刷新列表核对最新版本后再操作' : undefined}
        >
          {saving ? '保存中…' : uncertain ? (role ? '请关闭并刷新核对' : uncertainActionLabel('create')) : role ? '保存修改' : '创建角色'}
        </Button>
      </div>
    </Dialog>
  );
}
