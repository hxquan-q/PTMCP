import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Alert, Badge, Dialog, DrawerFields, EmptyState, Field, PageHeader, Select, StatusDot, TextInput } from '../../components/common';
import { getAudit, getJob, decryptAudit, testDatasource, type AuditDecryptResult, type AuditRow, type JobRow } from '../../services/access';
import {
  DataRequestError,
  deleteDatasource,
  getDatasource,
  getMetadataObject,
  listDatasources,
  syncDatasource,
  updateDatasource,
  type DatasourceRead,
  type DatasourceWrite,
  type MetadataObject,
} from '../../services/data';
import {
  datasourceDifferences,
  dictionaryDifferences,
  dictionaryDraft,
  isVersionConflict,
  rebaseDictionaryDraft,
} from '../../adapters/edit-conflict';
import type { ConflictDifference } from '../../adapters/edit-conflict';
import { updateDictionary, type DictionaryWrite } from '../../services/data';
import { dsForm, dsValidate, type DsForm } from './dsForm';
import { EditIcon, LockIcon } from '../../components/Icons';
import { ApiError } from '../../services/http';
import { newUuid } from '../../services/uuid';

function fmt(at: string | null | undefined): string {
  return at ? at.slice(0, 19).replace('T', ' ') : '—';
}

function useDatasources(): [DatasourceRead[], (id: string | null | undefined) => string] {
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  useEffect(() => {
    let cancelled = false;
    listDatasources({ pageSize: 100 })
      .then(({ data }) => !cancelled && setDatasources(data.items))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);
  const name = useCallback(
    (id: string | null | undefined) => (id ? (datasources.find((d) => d.id === id)?.name ?? `数据源 ${id}`) : '—'),
    [datasources],
  );
  return [datasources, name];
}

export function BackBar({ to, label }: { to: string; label: string }) {
  return (
    <div style={{ marginBottom: 10 }}>
      <button className="btn btn--link" style={{ padding: 0 }} onClick={() => (window.location.hash = to)}>
        ‹ {label}
      </button>
    </div>
  );
}

function errorText(e: unknown): string {
  if (e instanceof ApiError) return `${e.message}(${e.code})`;
  return e instanceof Error ? e.message : '读取失败';
}

/* ─── 任务详情:/admin/jobs/{id},非终态每 2s 轮询 ─── */
const TERMINAL = new Set(['SUCCEEDED', 'FAILED', 'CANCELLED']);
function stateTone(state: string): 'success' | 'warning' | 'danger' | 'neutral' {
  return state === 'SUCCEEDED' ? 'success' : state === 'FAILED' ? 'danger' : 'warning';
}
function stateLabel(state: string): string {
  return (
    ({ QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消' } as Record<string, string>)[state] ?? state
  );
}

export function JobDetailPage({ id }: { id: string }) {
  const [job, setJob] = useState<JobRow | null>(null);
  const [error, setError] = useState('');
  const [, dsName] = useDatasources();
  const seq = useRef(0);

  useEffect(() => {
    const own = ++seq.current;
    let timer: number | undefined;
    let cancelled = false;
    setJob(null);
    setError('');
    const load = () => {
      getJob(id)
        .then((j) => {
          if (cancelled || own !== seq.current) return;
          setJob(j);
          if (!TERMINAL.has(j.state) && !document.hidden) timer = window.setTimeout(load, 2000);
        })
        .catch((e) => !cancelled && own === seq.current && setError(errorText(e)));
    };
    load();
    return () => {
      cancelled = true;
      if (timer) window.clearTimeout(timer);
    };
  }, [id]);

  return (
    <>
      <BackBar to="/admin/jobs" label="返回操作日志" />
      <PageHeader
        title={job ? `任务 #${job.id}` : '任务详情'}
        description={job ? `${job.type} · ${dsName(job.datasourceId)}` : '任务执行状态与进度。'}
      />
      {error ? (
        <Alert kind="danger" title="任务状态读取失败">
          {error}
        </Alert>
      ) : null}
      {!job && !error ? <Alert kind="info">加载任务详情…</Alert> : null}
      {job ? (
        <div className="table-card" style={{ padding: '16px 18px', maxWidth: 760 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
            <StatusDot tone={stateTone(job.state)}>{stateLabel(job.state)}</StatusDot>
            <Badge tone="neutral">{job.type}</Badge>
            {!TERMINAL.has(job.state) ? <span style={{ fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>每 2 秒自动刷新</span> : null}
          </div>
          {job.progress ? (
            <div style={{ marginBottom: 14 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12, color: 'var(--ptmcp-text-muted)', marginBottom: 5 }}>
                <span>处理进度</span>
                <span>
                  {job.progress.processed}/{job.progress.total}
                </span>
              </div>
              <div style={{ height: 6, borderRadius: 3, background: 'var(--ptmcp-chrome)', overflow: 'hidden' }}>
                <div
                  style={{
                    height: '100%',
                    width: `${job.progress.total ? Math.min(100, Math.round((job.progress.processed / job.progress.total) * 100)) : 0}%`,
                    background: job.state === 'FAILED' ? 'var(--ptmcp-red)' : 'var(--ptmcp-blue)',
                    transition: 'width .3s ease',
                  }}
                />
              </div>
            </div>
          ) : null}
          <DrawerFields
            fields={[
              { label: '数据源', value: dsName(job.datasourceId) },
              { label: '开始时间', value: fmt(job.startedAt) },
              { label: '完成时间', value: fmt(job.finishedAt) },
              { label: '关联快照', value: job.snapshotId ? `快照 #${job.snapshotId}` : '—' },
              { label: '错误码', value: job.errorCode ?? '—' },
            ]}
          />
          {job.errorCode ? (
            <Alert kind="danger" title="任务失败">
              错误码 {job.errorCode}。请检查数据源连接与配置后重试。
            </Alert>
          ) : null}
        </div>
      ) : null}
    </>
  );
}

/* ─── 审计详情:/admin/audit/{requestId} ─── */
export function AuditDetailPage({ id }: { id: string }) {
  const [decryptOpen, setDecryptOpen] = useState(false);
  const [row, setRow] = useState<AuditRow | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [, dsName] = useDatasources();

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setRow(null);
    setError('');
    getAudit(id)
      .then((r) => !cancelled && setRow(r))
      .catch((e) => !cancelled && setError(errorText(e)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [id]);

  const uncertain = row && (row.result === 'UNKNOWN' || row.result === 'START');

  return (
    <>
      <BackBar to="/admin/audit" label="返回审计记录" />
      <PageHeader title={row ? `请求 ${row.requestId}` : '审计详情'} description="单次 MCP 请求的判定证据与执行指标。" />
      {error ? (
        <Alert kind="danger" title="审计详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载审计详情…</Alert> : null}
      {row ? (
        <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 }}>
            <StatusDot tone={row.result === 'SUCCESS' ? 'success' : row.result === 'ERROR' ? 'danger' : 'warning'}>
              {row.result === 'SUCCESS' ? '成功' : row.result === 'ERROR' ? '失败' : '进行中'}
            </StatusDot>
            {row.verdict ? <Badge tone={row.verdict === 'ALLOW' ? 'success' : 'danger'}>{row.verdict}</Badge> : null}
            {row.errorCode ? <Badge tone="danger">{row.errorCode}</Badge> : null}
          </div>
          {uncertain ? (
            <Alert kind="warning" title="执行结局不确定">
              该请求未完成或结局未知。按 requestId 排查,不能认定未执行或自动重试。
            </Alert>
          ) : null}
          <DrawerFields
            fields={[
              { label: '用户', value: `用户 ${row.userId}` },
              { label: '平台', value: `平台 ${row.platformId}` },
              { label: '数据源', value: dsName(row.datasourceId) },
              { label: '工具', value: row.tool },
              { label: '开始时间', value: fmt(row.startedAt) },
              { label: '完成时间', value: fmt(row.completedAt) },
            ]}
          />
          {row.sqlMasked ? (
            <div style={{ marginTop: 14 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
                <strong style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>SQL（脱敏）</strong>
                <button className="btn btn--secondary" onClick={() => setDecryptOpen(true)}>
                  <LockIcon /> 解密查看
                </button>
              </div>
              <pre
                style={{
                  margin: 0,
                  padding: '12px 14px',
                  background: 'var(--ptmcp-chrome)',
                  border: '1px solid var(--ptmcp-border)',
                  borderRadius: 'var(--ptmcp-radius-panel)',
                  fontSize: 12.5,
                  lineHeight: 1.6,
                  overflow: 'auto',
                  whiteSpace: 'pre-wrap',
                  fontFamily: "ui-monospace, 'SF Mono', Consolas, monospace",
                  color: 'var(--ptmcp-text-strong)',
                }}
              >
                {row.sqlMasked}
              </pre>
              <AuditDecryptDialog open={decryptOpen} requestId={row.requestId} onClose={() => setDecryptOpen(false)} />
            </div>
          ) : null}
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))', gap: 10, marginTop: 14 }}>
            {[
              { label: '总耗时', value: row.durationMs != null ? `${row.durationMs} ms` : '—' },
              { label: '排队', value: row.queueMs != null ? `${row.queueMs} ms` : '—' },
              { label: '数据库', value: row.dbMs != null ? `${row.dbMs} ms` : '—' },
              { label: '返回行数', value: row.rowsReturned ?? '—' },
              { label: '响应字节', value: row.responseBytes ?? '—' },
            ].map((m) => (
              <div key={m.label} className="table-card" style={{ padding: '10px 12px' }}>
                <div style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{m.label}</div>
                <div style={{ fontSize: 16, fontWeight: 600, color: 'var(--ptmcp-heading)', marginTop: 3, fontVariantNumeric: 'tabular-nums' }}>
                  {m.value}
                </div>
              </div>
            ))}
          </div>
          {row.fingerprint ? (
            <div style={{ marginTop: 12, fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>
              指纹 <code>{row.fingerprint}</code>
              {row.truncated ? ' · 结果已截断' : ''}
            </div>
          ) : null}
        </div>
      ) : null}
    </>
  );
}

/* ─── 元数据详情:/admin/metadata/{id} ─── */
export function MetadataDetailPage({ id }: { id: string }) {
  const [obj, setObj] = useState<MetadataObject | null>(null);
  const [etag, setEtag] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [, dsName] = useDatasources();

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setObj(null);
    setError('');
    getMetadataObject(id)
      .then(({ data, etag }) => {
        if (cancelled) return;
        setObj(data);
        setEtag(etag);
      })
      .catch((e) => !cancelled && setError(errorText(e)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [id]);

  const dict = obj?.dictionary;

  return (
    <>
      <BackBar to="/admin/metadata" label="返回元数据与字典" />
      <PageHeader
        title={obj ? (dict?.title ?? obj.objectName) : '元数据详情'}
        description={obj ? `${obj.databaseName}${obj.schemaName ? ` / ${obj.schemaName}` : ''} · ${obj.objectType}` : '元数据对象结构与业务字典。'}
      />
      {error ? (
        <Alert kind="danger" title="元数据详情读取失败">
          {error}
        </Alert>
      ) : null}
      {loading ? <Alert kind="info">加载元数据详情…</Alert> : null}
      {obj ? (
        <>
          <div className="table-card" style={{ padding: '16px 18px', maxWidth: 860, marginBottom: 14 }}>
            {dict?.description ? <p style={{ margin: '0 0 12px', fontSize: 13, color: 'var(--ptmcp-text)' }}>{dict.description}</p> : null}
            <DrawerFields
              fields={[
                { label: '对象名', value: <code>{obj.objectName}</code> },
                { label: '表名', value: <code>{obj.tableName}</code> },
                { label: '数据源', value: dsName(obj.datasourceId) },
                { label: '类型', value: <Badge tone={obj.objectType === 'TABLE' ? 'accent' : 'neutral'}>{obj.objectType}</Badge> },
                { label: '代次', value: `v${obj.generation}` },
                { label: '快照', value: obj.snapshotId ? `#${obj.snapshotId}` : '—' },
                { label: '授权', value: <StatusDot tone={obj.authorized ? 'success' : 'neutral'}>{obj.authorized ? '已授权' : '未授权'}</StatusDot> },
                { label: '字典可见', value: obj.metadataVisible ? '可见' : '隐藏' },
                { label: '表注释', value: obj.tableComment ?? '—' },
              ]}
            />
          </div>
          <DictionaryEditor
            obj={obj}
            etag={etag}
            onSaved={(next, nextEtag) => {
              setObj(next);
              setEtag(nextEtag ?? etag);
            }}
          />
          <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
            <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--ptmcp-border)', fontSize: 13, fontWeight: 600 }}>
              列结构{' '}
              <span style={{ color: 'var(--ptmcp-text-faint)', fontWeight: 400 }}>{obj.columns?.length ? `${obj.columns.length} 列` : ''}</span>
            </div>
            <div className="table-scroll">
              {obj.columns?.length ? (
                <table className="data-table">
                  <thead>
                    <tr>
                      <th style={{ width: 40 }}>#</th>
                      <th>列名</th>
                      <th>类型</th>
                      <th>可空</th>
                      <th>键</th>
                      <th>注释</th>
                    </tr>
                  </thead>
                  <tbody>
                    {obj.columns.map((c) => {
                      const dictCol = dict?.columns?.find((d) => d.name === c.name);
                      return (
                        <tr key={c.ordinal}>
                          <td style={{ color: 'var(--ptmcp-text-faint)' }}>{c.ordinal}</td>
                          <td>
                            <code style={{ fontSize: 12 }}>{c.name}</code>
                            {dictCol?.displayName ? (
                              <span style={{ marginLeft: 6, fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{dictCol.displayName}</span>
                            ) : null}
                          </td>
                          <td>
                            <Badge tone="neutral">
                              {c.databaseType}
                              {c.precision != null ? `(${c.precision}${c.scale != null ? `,${c.scale}` : ''})` : ''}
                            </Badge>
                          </td>
                          <td style={{ color: 'var(--ptmcp-text-muted)' }}>{c.nullable ? '是' : '否'}</td>
                          <td>
                            {c.primaryKey ? <Badge tone="accent">PK</Badge> : null}
                            {c.foreignKey ? <Badge tone="neutral">FK</Badge> : null}
                          </td>
                          <td style={{ color: 'var(--ptmcp-text-muted)' }}>
                            {dictCol?.description ?? c.comment ?? '—'}
                            {dictCol?.sensitiveTags?.length ? (
                              <span style={{ marginLeft: 6 }}>
                                {dictCol.sensitiveTags.map((t) => (
                                  <Badge key={t} tone="warning">
                                    {t}
                                  </Badge>
                                ))}
                              </span>
                            ) : null}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              ) : (
                <EmptyState title="该对象没有列信息" />
              )}
            </div>
          </div>
        </>
      ) : null}
    </>
  );
}

/* ─── 数据源详情:/admin/datasources/{id} — 编辑表单 + 乐观并发 ─── */
function dsStatusTone(status: string): 'success' | 'warning' | 'danger' | 'neutral' {
  const s = status.toUpperCase();
  return s === 'ACTIVE' || s === 'ENABLED' ? 'success' : s === 'DISABLED' ? 'danger' : 'neutral';
}
function dsStatusLabel(status: string): string {
  return ({ ACTIVE: '运行中', ENABLED: '运行中', DRAFT: '草稿', DISABLED: '已停用' } as Record<string, string>)[status.toUpperCase()] ?? status;
}
function dsHealthLabel(health: string): string {
  const h = health.toUpperCase();
  return h === 'HEALTHY' || h === 'UP' || h === 'OK'
    ? '连接正常'
    : h === 'DEGRADED' || h === 'WARN'
      ? '连接异常'
      : h === 'DOWN' || h === 'FAIL'
        ? '连接失败'
        : '连接未知';
}

export function DatasourceDetailPage({ id }: { id: string }) {
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

  return (
    <>
      <BackBar to="/admin/datasources" label="返回数据源" />
      <PageHeader
        title={datasource ? datasource.name : '数据源详情'}
        description={datasource ? datasource.code : '加载中…'}
        actions={
          datasource ? (
            <div style={{ display: 'flex', gap: 10 }}>
              <button className="btn btn--secondary" disabled={saving || actionLoading || conflictRequired} onClick={() => void runTest()}>
                {actionLoading ? '处理中…' : '测试连接'}
              </button>
              <button
                className="btn btn--primary"
                disabled={datasource.status !== 'ACTIVE' || saving || actionLoading || conflictRequired}
                onClick={() => void runSync()}
              >
                同步元数据
              </button>
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
            <button className="btn btn--secondary" style={{ marginTop: 8 }} onClick={() => void load()}>
              重新读取
            </button>
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
              <button className="btn btn--secondary" style={{ marginLeft: 8 }} disabled={conflictLoading} onClick={() => void readConflict()}>
                读取最新
              </button>
            </Alert>
          ) : null}
          {conflict ? (
            <>
              {conflictRows.length ? (
                <div className="table-scroll" style={{ marginBottom: 12 }}>
                  <table className="data-table">
                    <thead>
                      <tr>
                        <th>字段</th>
                        <th>我编辑前</th>
                        <th>服务端最新</th>
                        <th>我的草稿</th>
                      </tr>
                    </thead>
                    <tbody>
                      {conflictRows.map((r) => (
                        <tr key={r.key}>
                          <td>
                            <strong>{r.label}</strong>
                          </td>
                          <td style={{ color: 'var(--ptmcp-text-muted)' }}>{r.base}</td>
                          <td
                            style={{
                              color: r.serverChanged ? 'var(--ptmcp-orange)' : 'var(--ptmcp-text-muted)',
                              fontWeight: r.serverChanged ? 600 : 400,
                            }}
                          >
                            {r.latest}
                          </td>
                          <td
                            style={{
                              color: r.draftChanged ? 'var(--ptmcp-blue)' : 'var(--ptmcp-text-muted)',
                              fontWeight: r.draftChanged ? 600 : 400,
                            }}
                          >
                            {r.draft}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : (
                <Alert kind="info">字段一致，仅版本号推进。</Alert>
              )}
              <Alert kind="warning">凭据已清空。选择处理方式后请重新输入并再次保存。</Alert>
              <div style={{ display: 'flex', gap: 10 }}>
                <button className="btn btn--secondary" disabled={conflictLoading || saving || actionLoading} onClick={() => resolveConflict(false)}>
                  用最新版本覆盖草稿
                </button>
                <button className="btn btn--primary" disabled={conflictLoading || saving || actionLoading} onClick={() => resolveConflict(true)}>
                  保留我的草稿(重新保存)
                </button>
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
              <button className="btn btn--link" onClick={() => (window.location.hash = `/admin/metadata`)}>
                查看元数据
              </button>
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
                <input className="input" style={{ width: '100%' }} value={form.code} disabled />
              </Field>
              <Field label="名称" hint={fieldErrors.name}>
                <TextInput value={form.name} onChange={(v) => !formDisabled && update({ name: v })} />
              </Field>
              <Field label="引擎">
                <input className="input" style={{ width: '100%' }} value={form.engine} disabled />
              </Field>
              <Field label="状态" hint={fieldErrors.status}>
                <Select
                  value={form.status}
                  onChange={(v) => !formDisabled && update({ status: v })}
                  options={[
                    { value: 'DRAFT', label: '草稿' },
                    { value: 'ACTIVE', label: '运行中' },
                    { value: 'DISABLED', label: '已停用' },
                  ]}
                />
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
              <button className="btn btn--primary" disabled={formDisabled} onClick={() => void save()}>
                {saving ? '保存中…' : '保存更改'}
              </button>
              <button className="btn btn--secondary" style={{ color: 'var(--ptmcp-red)' }} disabled={formDisabled} onClick={remove}>
                删除数据源
              </button>
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
      {!loading && !datasource && !error ? <EmptyState title="数据源不存在或你已失去访问权限" /> : null}
      {confirm ? (
        <Dialog
          open
          title="确认操作"
          onClose={() => setConfirm(null)}
          footer={
            <>
              <button className="btn btn--secondary" onClick={() => setConfirm(null)}>
                取消
              </button>
              <button
                className="btn btn--primary"
                style={confirm.danger ? { background: 'var(--ptmcp-red)', borderColor: 'var(--ptmcp-red)' } : undefined}
                onClick={() => {
                  const run = confirm.run;
                  setConfirm(null);
                  void run();
                }}
              >
                确认
              </button>
            </>
          }
        >
          <p style={{ margin: 0, fontSize: 13, color: 'var(--ptmcp-text)' }}>{confirm.text}</p>
        </Dialog>
      ) : null}
    </>
  );
}

/** 审计解密弹窗：理由（10–500）+ 字段选择；明文 30s 倒计时后自动清除（aria-live 播报）。 */
export function AuditDecryptDialog({ open, requestId, onClose }: { open: boolean; requestId: string; onClose: () => void }) {
  const [reason, setReason] = useState('');
  const [fields, setFields] = useState<string[]>(['sqlRaw']);
  const [result, setResult] = useState<AuditDecryptResult | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const valid = reason.trim().length >= 10 && reason.length <= 500 && fields.length > 0;

  // 到期倒计时：到 0 自动清除明文（组件卸载/关闭时同样丢弃）
  useEffect(() => {
    if (!result) return;
    const tick = () => {
      const left = Math.max(0, Math.ceil((new Date(result.expiresAt).getTime() - Date.now()) / 1000));
      setRemaining(left);
      if (left === 0) setResult(null);
    };
    tick();
    const timer = window.setInterval(tick, 1000);
    return () => window.clearInterval(timer);
  }, [result]);

  const reset = () => {
    setReason('');
    setFields(['sqlRaw']);
    setResult(null);
    setError('');
  };

  const submit = async () => {
    setError('');
    setLoading(true);
    try {
      const data = await decryptAudit(requestId, reason.trim(), fields);
      setResult(data);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '解密失败，请稍后重试。');
    } finally {
      setLoading(false);
    }
  };

  return (
    <Dialog
      open={open}
      title="审计记录解密"
      onClose={() => {
        reset();
        onClose();
      }}
      width={640}
      footer={
        result ? (
          <button
            className="btn btn--secondary"
            onClick={() => {
              reset();
              onClose();
            }}
          >
            关闭并清除明文
          </button>
        ) : (
          <>
            <button className="btn btn--secondary" onClick={reset} disabled={loading}>
              重置
            </button>
            <button className="btn btn--primary" onClick={submit} disabled={loading || !valid}>
              {loading ? '解密中…' : '确认解密'}
            </button>
          </>
        )
      }
    >
      <Alert kind="warning" title="操作将被审计">
        解密属于敏感访问，需要填写排查理由（10～500 字符），系统记录访问人、理由与字段；明文仅短时可见，到期自动清除。
      </Alert>
      <Field label={`排查理由（${reason.length}/500）`} hint="至少 10 个字符">
        <textarea
          className="input"
          style={{ width: '100%', minHeight: 72, resize: 'vertical', fontFamily: 'inherit' }}
          value={reason}
          maxLength={500}
          disabled={loading || !!result}
          onChange={(e) => setReason(e.target.value)}
          placeholder="例如：用户反馈工单 #123 查询被拒，需核对实际执行的 SQL 与 guard 重写差异。"
        />
      </Field>
      <Field label="解密字段">
        <div style={{ display: 'flex', gap: 16 }}>
          {['sqlRaw', 'sqlFinal'].map((field) => (
            <label key={field} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={fields.includes(field)}
                disabled={loading || !!result}
                onChange={() => setFields((s) => (s.includes(field) ? s.filter((f) => f !== field) : [...s, field]))}
              />
              <code>{field}</code>
              <span style={{ color: 'var(--ptmcp-text-muted)' }}>{field === 'sqlRaw' ? '原始请求' : '执行语句'}</span>
            </label>
          ))}
        </div>
      </Field>
      {error ? <Alert kind="danger">{error}</Alert> : null}
      {result ? (
        <div role="status" aria-live="polite">
          <p style={{ margin: '0 0 8px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
            明文已解锁。剩余 {remaining} 秒，到期自动清除（expiresAt：{result.expiresAt}）。
          </p>
          {(['sqlRaw', 'sqlFinal'] as const).map((field) =>
            result[field] ? (
              <div key={field} style={{ marginTop: 8 }}>
                <strong style={{ display: 'block', fontSize: 12, color: 'var(--ptmcp-text-muted)', marginBottom: 4 }}>{field}</strong>
                <pre
                  style={{
                    margin: 0,
                    padding: '12px 14px',
                    background: 'var(--ptmcp-chrome)',
                    border: '1px solid var(--ptmcp-border)',
                    borderRadius: 8,
                    fontSize: 12.5,
                    lineHeight: 1.6,
                    whiteSpace: 'pre-wrap',
                    overflow: 'auto',
                    fontFamily: "ui-monospace, 'SF Mono', Consolas, monospace",
                  }}
                >
                  {result[field]}
                </pre>
              </div>
            ) : null,
          )}
        </div>
      ) : null}
    </Dialog>
  );
}

/** 元数据字典编辑：对象级标题/说明/负责人 + 按列注释；If-Match 提交，412 走三方 diff + 变基。 */
function DictionaryEditor({
  obj,
  etag,
  onSaved,
}: {
  obj: MetadataObject;
  etag: string | null;
  onSaved: (next: MetadataObject, etag: string | null) => void;
}) {
  const [draft, setDraft] = useState<DictionaryWrite | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');
  const [conflict, setConflict] = useState<{ latest: MetadataObject; etag: string | null } | null>(null);
  const [removedNotes, setRemovedNotes] = useState<DictionaryWrite['columns']>([]);

  const conflictRows: ConflictDifference[] = conflict && draft ? dictionaryDifferences(obj, conflict.latest, draft) : [];

  const setColumn = (columnName: string, key: 'title' | 'description', value: string) => {
    setDraft((d) => (d ? { ...d, columns: d.columns.map((c) => (c.columnName === columnName ? { ...c, [key]: value } : c)) } : d));
  };

  const save = async () => {
    if (!draft) return;
    if (!etag) {
      setSaveError('缺少版本信息，请刷新页面后重试。');
      return;
    }
    setSaving(true);
    setSaveError('');
    try {
      const { data, etag: nextEtag } = await updateDictionary(obj.id, draft, etag);
      setDraft(null);
      setConflict(null);
      onSaved(data, nextEtag);
    } catch (err) {
      if (err instanceof DataRequestError && err.status === 412) {
        try {
          const { data: latest, etag: latestEtag } = await getMetadataObject(obj.id);
          setConflict({ latest, etag: latestEtag });
        } catch {
          setSaveError('版本冲突且读取最新版本失败，请刷新页面。');
        }
      } else {
        setSaveError(err instanceof Error ? err.message : '保存失败，请稍后重试。');
      }
    } finally {
      setSaving(false);
    }
  };

  const adoptServerAndRebase = () => {
    if (!conflict || !draft) return;
    const { draft: rebased, removed } = rebaseDictionaryDraft(draft, conflict.latest);
    setDraft(rebased);
    setRemovedNotes(removed);
    onSaved(conflict.latest, conflict.etag);
    setConflict(null);
  };

  const discardDraft = () => {
    setDraft(null);
    setConflict(null);
    setRemovedNotes([]);
    setSaveError('');
  };

  return (
    <div className="table-card" style={{ padding: '16px 18px', marginBottom: 14, maxWidth: 860 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 10 }}>
        <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>业务字典</strong>
        {draft ? (
          <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>编辑中 · 字典版本 {obj.dictionary?.version ?? '—'}</span>
        ) : (
          <button className="btn btn--secondary" onClick={() => setDraft(dictionaryDraft(obj))}>
            <EditIcon /> 编辑字典
          </button>
        )}
      </div>

      {conflict ? (
        <div style={{ marginBottom: 12 }}>
          <Alert kind="warning" title="版本冲突（412）">
            该对象的字典在你编辑期间已被他人修改（结构或注释可能已变化）。可采纳服务端最新结构并保留你的注释（变基），或放弃本地修改。
          </Alert>
          {conflictRows.length ? (
            <div className="table-scroll" style={{ marginTop: 10 }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th>字段</th>
                    <th>我编辑前</th>
                    <th>服务端最新</th>
                    <th>我的草稿</th>
                  </tr>
                </thead>
                <tbody>
                  {conflictRows.map((r) => (
                    <tr key={r.key}>
                      <td>
                        <strong>{r.label}</strong>
                        {r.serverChanged ? <span style={{ marginLeft: 6, fontSize: 11, color: 'var(--ptmcp-orange)' }}>服务端已变</span> : null}
                      </td>
                      <td style={{ color: 'var(--ptmcp-text-muted)' }}>{r.base}</td>
                      <td style={{ color: 'var(--ptmcp-text-strong)' }}>{r.latest}</td>
                      <td style={{ color: 'var(--ptmcp-blue)' }}>{r.draft}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : null}
          <div style={{ display: 'flex', gap: 10, marginTop: 10 }}>
            <button className="btn btn--secondary" onClick={discardDraft}>
              放弃本地修改
            </button>
            <button className="btn btn--primary" onClick={adoptServerAndRebase}>
              采纳服务端结构并保留我的注释
            </button>
          </div>
        </div>
      ) : null}

      {removedNotes.length ? (
        <Alert kind="warning" title={`${removedNotes.length} 列已从最新结构中移除`}>
          这些列的注释不会被丢弃，如需保留请合并到相邻列说明：{removedNotes.map((c) => c.columnName).join('、')}
        </Alert>
      ) : null}

      {draft ? (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: '2fr 3fr 1fr', gap: 12, marginBottom: 12 }}>
            <Field label="业务标题" hint="最长 128">
              <TextInput value={draft.title} onChange={(v) => setDraft((d) => (d ? { ...d, title: v } : d))} maxLength={128} />
            </Field>
            <Field label="业务说明" hint="最长 8000">
              <TextInput value={draft.description} onChange={(v) => setDraft((d) => (d ? { ...d, description: v } : d))} maxLength={8000} />
            </Field>
            <Field label="负责人 ID" hint="可空">
              <TextInput value={draft.ownerUserId ?? ''} onChange={(v) => setDraft((d) => (d ? { ...d, ownerUserId: v || null } : d))} />
            </Field>
          </div>
          {draft.columns.length ? (
            <div className="table-scroll" style={{ marginBottom: 12, maxHeight: 320 }}>
              <table className="data-table">
                <thead>
                  <tr>
                    <th style={{ width: 160 }}>列</th>
                    <th>业务标题</th>
                    <th>业务说明</th>
                  </tr>
                </thead>
                <tbody>
                  {draft.columns.map((c) => (
                    <tr key={c.columnName}>
                      <td>
                        <code style={{ fontSize: 12 }}>{c.columnName}</code>
                      </td>
                      <td>
                        <TextInput value={c.title} onChange={(v) => setColumn(c.columnName, 'title', v)} maxLength={128} />
                      </td>
                      <td>
                        <TextInput value={c.description} onChange={(v) => setColumn(c.columnName, 'description', v)} maxLength={8000} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p style={{ margin: '0 0 12px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>该对象没有列结构信息，仅可编辑对象级字典字段。</p>
          )}
          {saveError ? <Alert kind="danger">{saveError}</Alert> : null}
          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10 }}>
            <button className="btn btn--secondary" onClick={discardDraft} disabled={saving}>
              取消
            </button>
            <button className="btn btn--primary" onClick={save} disabled={saving}>
              {saving ? '保存中…' : '保存字典'}
            </button>
          </div>
        </>
      ) : (
        <p style={{ margin: 0, fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
          {obj.dictionary?.title || obj.dictionary?.description
            ? `${obj.dictionary.title ?? ''}${obj.dictionary.description ? ` — ${obj.dictionary.description}` : ''}`
            : '尚未填写业务字典。点击「编辑字典」为该对象与各列补充业务含义。'}
        </p>
      )}
    </div>
  );
}
