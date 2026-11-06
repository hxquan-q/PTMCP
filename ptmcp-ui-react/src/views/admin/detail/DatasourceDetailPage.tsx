import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Alert, Dialog, DrawerFields, EmptyState, Field, PageHeader, Select, StatusDot, TextInput } from '../../../components/common';
import { testDatasource } from '../../../services/access';
import {
  DataRequestError,
  deleteDatasource,
  getDatasource,
  syncDatasource,
  updateDatasource,
  type DatasourceRead,
  type DatasourceWrite,
} from '../../../services/data';
import { datasourceDifferences, isVersionConflict, type ConflictDifference } from '../../../adapters/edit-conflict';
import { DATASOURCE_STATUSES, dsForm, dsValidate, type DsForm } from '../dsForm';
import { useAdminGate } from '../../../adminGateContext';
import { newUuid } from '../../../services/uuid';

import { BackBar, dsHealthLabel, dsStatusLabel, dsStatusTone } from './shared';
import { formatTaipeiTime as fmt } from '../../../services/dateTime';

export function DatasourceDetailPage({ id }: { id: string }) {
  const isAdmin = useAdminGate();
  const [datasource, setDatasource] = useState<DatasourceRead | null>(null);
  const [etag, setEtag] = useState('');
  const [form, setForm] = useState<DsForm | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState('');
  const [requestId, setRequestId] = useState<string | undefined>();
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [toast, setToast] = useState('');
  const [confirm, setConfirm] = useState<{ text: string; danger: boolean; run: () => void } | null>(null);
  const [conflict, setConflict] = useState<{ base: DatasourceRead; latest: DatasourceRead; etag: string; resolved: boolean } | null>(null);
  const [conflictRequired, setConflictRequired] = useState(false);
  const [conflictLoading, setConflictLoading] = useState(false);
  const seq = useRef(0);
  const saveKey = useRef(newUuid());
  const testKey = useRef(newUuid());
  const syncKey = useRef(newUuid());
  const deleteKey = useRef(newUuid());
  const abortRef = useRef<AbortController | null>(null);

  const load = useCallback(async () => {
    const own = ++seq.current;
    abortRef.current?.abort();
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    setLoading(true);
    setError('');
    setConflict(null);
    setConflictRequired(false);
    try {
      const { data, etag: e } = await getDatasource(id, ctrl.signal);
      if (own !== seq.current) return;
      setDatasource(data);
      setEtag(e ?? `"${data.configVersion ?? data.resourceVersion ?? ''}"`);
      setForm(dsForm(data));
      setFieldErrors({});
      setToast('');
    } catch (e) {
      if (own === seq.current && !(e instanceof DOMException && e.name === 'AbortError')) {
        if (e instanceof DataRequestError && (e.status === 401 || e.status === 404 || (e.status === 403 && e.code !== 'CSRF_INVALID'))) {
          setDatasource(null);
          setForm(null);
        }
        setError(e instanceof DataRequestError ? `${e.message}${e.code ? `(${e.code})` : ''}` : e instanceof Error ? e.message : '读取失败');
        if (e instanceof DataRequestError) setRequestId(e.requestId);
      }
    } finally {
      if (own === seq.current) setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    void load();
    return () => abortRef.current?.abort();
  }, [load]);

  const update = useCallback(
    (patch: Partial<DsForm>) => {
      setForm((f) => (f ? { ...f, ...patch } : f));
      if (!saving && !actionLoading) {
        saveKey.current = newUuid();
        testKey.current = newUuid();
      }
    },
    [saving, actionLoading],
  );

  const readConflict = useCallback(async () => {
    if (conflictLoading || !datasource) return;
    const own = seq.current;
    const base = conflict?.base ?? datasource;
    setConflictLoading(true);
    setError('');
    try {
      const { data, etag: e } = await getDatasource(id, abortRef.current?.signal);
      if (own !== seq.current) return;
      setConflict({ base, latest: data, etag: e ?? `"${data.configVersion ?? data.resourceVersion ?? ''}"`, resolved: false });
    } catch (err) {
      if (own === seq.current) setError(err instanceof Error ? err.message : '读取失败');
    } finally {
      if (own === seq.current) setConflictLoading(false);
    }
  }, [conflictLoading, datasource, conflict, id]);

  const resolveConflict = useCallback(
    (keepDraft: boolean) => {
      if (!conflict || conflictLoading || saving || actionLoading) return;
      const latest = conflict.latest;
      setDatasource(latest);
      setEtag(conflict.etag);
      setFieldErrors({});
      if (keepDraft) {
        setConflict({ ...conflict, resolved: true });
        setForm((f) => (f ? { ...f, credential: '' } : f));
      } else {
        setForm(dsForm(latest));
        setConflict(null);
      }
      setConflictRequired(false);
      setError('');
      saveKey.current = newUuid();
      testKey.current = newUuid();
      syncKey.current = newUuid();
      deleteKey.current = newUuid();
    },
    [conflict, conflictLoading, saving, actionLoading],
  );

  const save = useCallback(async () => {
    if (!etag || !form || saving || actionLoading || conflictRequired) return;
    const errs = dsValidate(form);
    setFieldErrors(errs);
    if (Object.keys(errs).length) return;
    const doPut = async () => {
      const own = ++seq.current;
      setSaving(true);
      setError('');
      try {
        const { data, etag: e } = await updateDatasource(id, form as DatasourceWrite, etag, saveKey.current);
        if (own !== seq.current) return;
        setDatasource(data);
        setEtag(e ?? `"${data.configVersion ?? data.resourceVersion ?? ''}"`);
        setForm(dsForm(data));
        setConflict(null);
        saveKey.current = newUuid();
        setToast('数据源配置已保存');
      } catch (err) {
        if (own !== seq.current) return;
        if (err instanceof DataRequestError && isVersionConflict(err)) {
          setForm((f) => (f ? { ...f, credential: '' } : f));
          setConflict(null);
          setConflictRequired(true);
          await readConflict();
        } else {
          setError(
            err instanceof DataRequestError ? `${err.message}${err.code ? `(${err.code})` : ''}` : err instanceof Error ? err.message : '保存失败',
          );
          if (err instanceof DataRequestError) setRequestId(err.requestId);
        }
      } finally {
        if (own === seq.current) {
          setForm((f) => (f ? { ...f, credential: '' } : f));
          setSaving(false);
        }
      }
    };
    if (form.status === 'DISABLED' && datasource?.status !== 'DISABLED') {
      setConfirm({ text: '停用后此数据源将不能继续用于查询或同步，相关授权的实际访问也会受影响。', danger: true, run: () => void doPut() });
    } else {
      await doPut();
    }
  }, [etag, form, saving, actionLoading, conflictRequired, id, datasource, readConflict]);

  const runTest = useCallback(async () => {
    if (!form || saving || actionLoading || conflictRequired) return;
    const errs = dsValidate(form);
    setFieldErrors(errs);
    if (Object.keys(errs).length) return;
    const own = ++seq.current;
    setActionLoading(true);
    setError('');
    try {
      const job = await testDatasource(id, form as DatasourceWrite, abortRef.current?.signal);
      if (own !== seq.current) return;
      setForm((f) => (f ? { ...f, credential: '' } : f));
      testKey.current = newUuid();
      setToast('连接测试已提交');
      window.location.hash = `/admin/jobs/${job.id}`;
    } catch (err) {
      if (own === seq.current) {
        setError(
          err instanceof DataRequestError ? `${err.message}${err.code ? `(${err.code})` : ''}` : err instanceof Error ? err.message : '测试失败',
        );
        if (err instanceof DataRequestError) setRequestId(err.requestId);
      }
    } finally {
      if (own === seq.current) {
        setForm((f) => (f ? { ...f, credential: '' } : f));
        setActionLoading(false);
      }
    }
  }, [form, saving, actionLoading, conflictRequired, id]);

  const runSync = useCallback(async () => {
    if (!datasource || datasource.status !== 'ACTIVE' || saving || actionLoading || conflictRequired) return;
    const own = ++seq.current;
    setActionLoading(true);
    setError('');
    try {
      const { data: job } = await syncDatasource(id, syncKey.current);
      if (own !== seq.current) return;
      syncKey.current = newUuid();
      setToast('元数据同步已提交');
      window.location.hash = `/admin/jobs/${job.id}`;
    } catch (err) {
      if (own === seq.current) {
        setError(
          err instanceof DataRequestError ? `${err.message}${err.code ? `(${err.code})` : ''}` : err instanceof Error ? err.message : '同步失败',
        );
        if (err instanceof DataRequestError) setRequestId(err.requestId);
      }
    } finally {
      if (own === seq.current) setActionLoading(false);
    }
  }, [datasource, saving, actionLoading, conflictRequired, id]);

  const remove = useCallback(() => {
    if (!etag || saving || actionLoading || conflictRequired) return;
    setConfirm({
      text: '删除后将无法继续使用此数据源。若仍有活动授权，服务端会拒绝删除。',
      danger: true,
      run: async () => {
        const own = ++seq.current;
        setActionLoading(true);
        setError('');
        try {
          await deleteDatasource(id, etag, deleteKey.current);
          if (own !== seq.current) return;
          setToast('数据源已删除');
          window.location.hash = '/admin/datasources';
        } catch (err) {
          if (own !== seq.current) return;
          if (err instanceof DataRequestError && isVersionConflict(err)) {
            setForm((f) => (f ? { ...f, credential: '' } : f));
            setConflict(null);
            setConflictRequired(true);
            await readConflict();
          } else {
            const msg =
              err instanceof DataRequestError && err.code === 'REFERENCES_EXIST'
                ? '数据源仍有活动依赖。请先停用并处理相关授权，再删除。'
                : err instanceof DataRequestError
                  ? `${err.message}${err.code ? `(${err.code})` : ''}`
                  : err instanceof Error
                    ? err.message
                    : '删除失败';
            setError(msg);
            if (err instanceof DataRequestError) setRequestId(err.requestId);
          }
        } finally {
          if (own === seq.current) setActionLoading(false);
        }
      },
    });
  }, [etag, saving, actionLoading, conflictRequired, id, readConflict]);

  const conflictRows = useMemo(
    () => (conflict && form ? datasourceDifferences(conflict.base, conflict.latest, form as DatasourceWrite) : []),
    [conflict, form],
  );
  const formDisabled = saving || actionLoading || conflictRequired;

  const conflictColumns: ColumnsType<ConflictDifference> = [
    {
      title: '字段',
      dataIndex: 'label',
      render: (v: string) => <strong>{v}</strong>,
    },
    {
      title: '我编辑前',
      dataIndex: 'base',
      render: (v: string) => <span style={{ color: 'var(--ptmcp-text-muted)' }}>{v}</span>,
    },
    {
      title: '服务端最新',
      dataIndex: 'latest',
      render: (v: string, r) => (
        <span
          style={{
            color: r.serverChanged ? 'var(--ptmcp-orange)' : 'var(--ptmcp-text-muted)',
            fontWeight: r.serverChanged ? 600 : 400,
          }}
        >
          {v}
        </span>
      ),
    },
    {
      title: '我的草稿',
      dataIndex: 'draft',
      render: (v: string, r) => (
        <span
          style={{
            color: r.draftChanged ? 'var(--ptmcp-blue)' : 'var(--ptmcp-text-muted)',
            fontWeight: r.draftChanged ? 600 : 400,
          }}
        >
          {v}
        </span>
      ),
    },
  ];

  return (
    /* 详情页阅读列：860px 在宽屏居中，标题/返回/卡片同列对齐，不再顶左留大片空白。 */
    <div style={{ maxWidth: 860, marginInline: 'auto' }}>
      <BackBar to="/admin/datasources" label="返回数据源" />
      <PageHeader
        title={datasource ? datasource.name : '数据源详情'}
        description={datasource ? datasource.code : '加载中…'}
        actions={
          datasource ? (
            <div style={{ display: 'flex', gap: 10 }}>
              <Button disabled={saving || actionLoading || conflictRequired || !isAdmin} onClick={() => isAdmin && void runTest()}>
                {actionLoading ? '处理中…' : '测试连接'}
              </Button>
              <Button
                type="primary"
                disabled={datasource.status !== 'ACTIVE' || saving || actionLoading || conflictRequired || !isAdmin}
                onClick={() => void runSync()}
              >
                同步元数据
              </Button>
            </div>
          ) : undefined
        }
      />
      {toast ? (
        <Alert kind="info" title="操作完成">
          {toast}
        </Alert>
      ) : null}
      {error ? (
        <Alert kind="danger" title="数据源操作失败">
          {error}
          {requestId ? <div style={{ marginTop: 6, fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>请求编号:{requestId}</div> : null}
          {!datasource ? (
            <Button style={{ marginTop: 8 }} onClick={() => void load()}>
              重新读取
            </Button>
          ) : null}
        </Alert>
      ) : null}
      {conflictRequired || conflict ? (
        <div className="table-card" style={{ padding: '14px 16px', marginBottom: 14, maxWidth: 860 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
            <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>版本冲突</strong>
            {conflict ? (
              <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
                配置版本 {conflict.base.configVersion} → {conflict.latest.configVersion}
              </span>
            ) : null}
          </div>
          {conflictLoading ? <Alert kind="info">正在读取最新配置…</Alert> : null}
          {!conflict && !conflictLoading ? (
            <Alert kind="warning" title="保存被服务端拒绝">
              此数据源在你编辑期间已被他人修改。请读取最新版本后再提交。
              <Button style={{ marginLeft: 8 }} disabled={conflictLoading} onClick={() => void readConflict()}>
                读取最新
              </Button>
            </Alert>
          ) : null}
          {conflict ? (
            <>
              {conflictRows.length ? (
                <div className="table-scroll" role="region" aria-label="字段冲突对比" tabIndex={0} style={{ marginBottom: 12 }}>
                  <Table<ConflictDifference>
                    rowKey="key"
                    size="middle"
                    dataSource={conflictRows}
                    columns={conflictColumns}
                    pagination={false}
                    locale={{ emptyText: <span /> }}
                  />
                </div>
              ) : (
                <Alert kind="info">字段一致，仅版本号推进。</Alert>
              )}
              <Alert kind="warning">凭据已清空。选择处理方式后请重新输入并再次保存。</Alert>
              <div style={{ display: 'flex', gap: 10 }}>
                <Button disabled={conflictLoading || saving || actionLoading} onClick={() => resolveConflict(false)}>
                  用最新版本覆盖草稿
                </Button>
                <Button type="primary" disabled={conflictLoading || saving || actionLoading} onClick={() => resolveConflict(true)}>
                  保留我的草稿(重新保存)
                </Button>
              </div>
            </>
          ) : null}
        </div>
      ) : null}
      {loading ? <Alert kind="info">加载数据源详情…</Alert> : null}
      {!loading && datasource && form ? (
        <>
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              flexWrap: 'wrap',
              gap: 14,
              margin: '4px 0 14px',
              padding: '10px 14px',
              border: '1px solid var(--ptmcp-border)',
              borderRadius: 'var(--ptmcp-radius-panel)',
              background: 'var(--ptmcp-chrome)',
              fontSize: 13,
              color: 'var(--ptmcp-text-muted)',
            }}
          >
            <span style={{ fontWeight: 700, color: 'var(--ptmcp-text)' }}>当前状态</span>
            <StatusDot tone={dsStatusTone(datasource.status)}>{dsStatusLabel(datasource.status)}</StatusDot>
            <StatusDot
              tone={
                datasource.health.toUpperCase() === 'HEALTHY' || datasource.health.toUpperCase() === 'UP'
                  ? 'success'
                  : datasource.health.toUpperCase() === 'DOWN' || datasource.health.toUpperCase() === 'FAIL'
                    ? 'danger'
                    : 'warning'
              }
            >
              {dsHealthLabel(datasource.health)}
            </StatusDot>
            <span>凭据:{datasource.credentialConfigured ? '已配置' : '未配置'}</span>
            {datasource.activeSnapshotId ? (
              <Button type="link" onClick={() => (window.location.hash = `/admin/metadata`)}>
                查看元数据
              </Button>
            ) : null}
          </div>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860, marginBottom: 14 }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 14 }}>
              <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>连接配置</strong>
              <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
                版本 {datasource.configVersion ?? datasource.resourceVersion ?? '—'}
              </span>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: '12px 16px' }}>
              <Field label="编码">
                <TextInput value={form.code} onChange={() => undefined} disabled />
              </Field>
              <Field label="名称" hint={fieldErrors.name}>
                <TextInput value={form.name} onChange={(v) => !formDisabled && update({ name: v })} />
              </Field>
              <Field label="引擎">
                <TextInput value={form.engine} onChange={() => undefined} disabled />
              </Field>
              <Field label="状态" hint={fieldErrors.status}>
                <Select ariaLabel="状态" value={form.status} onChange={(v) => !formDisabled && update({ status: v })} options={DATASOURCE_STATUSES} />
              </Field>
              <Field label="主机" hint={fieldErrors.host}>
                <TextInput value={form.host} onChange={(v) => !formDisabled && update({ host: v })} />
              </Field>
              <Field label="端口" hint={fieldErrors.port}>
                <TextInput value={String(form.port)} onChange={(v) => !formDisabled && update({ port: Number(v) })} />
              </Field>
              <Field label="数据库名" hint={fieldErrors.databaseName}>
                <TextInput value={form.databaseName} onChange={(v) => !formDisabled && update({ databaseName: v })} />
              </Field>
              <Field label="用户名" hint={fieldErrors.username}>
                <TextInput value={form.username} onChange={(v) => !formDisabled && update({ username: v })} />
              </Field>
              <Field label="替换凭据" hint={fieldErrors.credential ?? '凭据不回显,留空表示保持现有值。'}>
                <TextInput
                  value={form.credential}
                  type="password"
                  placeholder="留空保持已配置凭据"
                  onChange={(v) => !formDisabled && update({ credential: v })}
                />
              </Field>
              <Field label="连接池上限" hint={fieldErrors.poolMax}>
                <TextInput value={String(form.poolMax)} onChange={(v) => !formDisabled && update({ poolMax: Number(v) })} />
              </Field>
            </div>
            <Alert kind="info">TLS 固定为 VERIFY_FULL。凭据不回显,留空表示保持现有值。</Alert>
            <div style={{ display: 'flex', gap: 10, marginTop: 14 }}>
              <Button type="primary" disabled={formDisabled} onClick={() => void save()}>
                {saving ? '保存中…' : '保存更改'}
              </Button>
              <Button style={{ color: 'var(--ptmcp-red)' }} disabled={formDisabled || !isAdmin} onClick={isAdmin ? remove : undefined}>
                删除数据源
              </Button>
            </div>
          </div>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860 }}>
            <strong style={{ display: 'block', fontSize: 14, color: 'var(--ptmcp-heading)', marginBottom: 12 }}>同步信息</strong>
            <DrawerFields
              fields={[
                { label: '最近同步', value: fmt(datasource.lastSyncAt) },
                { label: '活动快照', value: datasource.activeSnapshotId ? `快照 #${datasource.activeSnapshotId}` : '待同步' },
                { label: '资源版本', value: datasource.resourceVersion ?? '—' },
                { label: '最近同步结果', value: datasource.lastSyncResult ?? '—' },
              ]}
            />
          </div>
        </>
      ) : null}
      {!loading && !datasource && !error ? (
        <EmptyState kind="error" title="数据源不存在或你已失去访问权限" description="链接可能已过期，或该数据源已被删除／收窄授权。" />
      ) : null}
      {confirm ? (
        <Dialog
          open
          title="确认操作"
          onClose={() => setConfirm(null)}
          footer={
            <>
              <Button onClick={() => setConfirm(null)}>取消</Button>
              <Button
                type="primary"
                style={confirm.danger ? { background: 'var(--ptmcp-red)', borderColor: 'var(--ptmcp-red)' } : undefined}
                onClick={() => {
                  const run = confirm.run;
                  setConfirm(null);
                  void run();
                }}
              >
                确认
              </Button>
            </>
          }
        >
          <p style={{ margin: 0, fontSize: 13, color: 'var(--ptmcp-text)' }}>{confirm.text}</p>
        </Dialog>
      ) : null}
    </div>
  );
}

/** 审计解密弹窗：理由（10–500）+ 字段选择；明文 30s 倒计时后自动清除（aria-live 播报）。 */
