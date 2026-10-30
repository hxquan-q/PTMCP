import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Badge, Dialog, EmptyState, Field, PageHeader, Select, StatusDot, Tabs, TextInput } from '../../components/common';
import { CloseIcon, CopyIcon, EditIcon, PlusIcon } from '../../components/Icons';
import {
  createClient,
  createPlatform,
  getPlatformScopes,
  savePlatformScopes,
  type ScopeRule,
  deleteClient,
  deletePlatform,
  listClients,
  listPlatforms,
  updateClient,
  updatePlatform,
  type ClientCreated,
  type ClientRow,
  type PlatformRow,
} from '../../services/access';
import { scopeChangeText, scopeChanges, scopeKey, scopeRuleText } from './scopeDiff';
import { CLIENT_AUTH_METHODS, CLIENT_SCOPES, parseRedirectUris, toClientWrite, validateClientForm } from './clientForm';
import { useSensitivePermissions } from '../../permissionsContext';
import { showToast } from '../../toast';

const TIER_LABEL: Record<number, string> = { 1: '一级', 2: '二级', 3: '三级' };
const CATALOG_MODES = ['BLACKLIST', 'WHITELIST', 'FULL'];
const PROOF_MODES = ['NONE', 'PKCE_S256'];
const CLIENT_TYPES = ['PUBLIC', 'CONFIDENTIAL'];
const CLIENT_STATUSES = ['ACTIVE', 'DISABLED'];

interface PlatformForm {
  code: string;
  name: string;
  tier: string;
  catalogMode: string;
  proofMode: string;
  status: string;
}

interface ClientForm {
  platformId: string;
  name: string;
  type: string;
  status: string;
  authMethods: string[];
  redirectUrisText: string;
  allowedScopes: string[];
  jwksUri: string;
}

export function PlatformsPage() {
  const { can } = useSensitivePermissions();
  const [platforms, setPlatforms] = useState<PlatformRow[]>([]);
  const [clients, setClients] = useState<ClientRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('platforms');
  const [reloadTick, setReloadTick] = useState(0);
  const [platformDialog, setPlatformDialog] = useState<{ mode: 'create' } | { mode: 'edit'; row: PlatformRow } | null>(null);
  const [clientDialog, setClientDialog] = useState<{ mode: 'create' } | { mode: 'edit'; row: ClientRow } | null>(null);
  const [deleting, setDeleting] = useState<{ kind: 'platform' | 'client'; row: PlatformRow | ClientRow } | null>(null);
  const [busy, setBusy] = useState(false);
  const [secretCreated, setSecretCreated] = useState<ClientCreated | null>(null);
  const [scopePlatform, setScopePlatform] = useState<PlatformRow | null>(null);

  const reload = useCallback(() => {
    let cancelled = false;
    setLoading(true);
    listPlatforms({ pageSize: 100 })
      .then(async (page) => {
        if (cancelled) return;
        setPlatforms(page.items);
        const all = await Promise.all(
          page.items.map((p) =>
            listClients(p.id)
              .then((c) => c.items)
              .catch(() => [] as ClientRow[]),
          ),
        );
        if (!cancelled) setClients(all.flat());
      })
      .catch(() => {
        if (!cancelled) {
          setPlatforms([]);
          setClients([]);
        }
      })
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(reload, [reload, reloadTick]);

  const clientCount = useMemo(() => {
    const m = new Map<string, number>();
    for (const c of clients) m.set(c.platformId, (m.get(c.platformId) ?? 0) + 1);
    return m;
  }, [clients]);

  return (
    <>
      <PageHeader
        title="平台与客户端"
        description="接入平台的层级、目录模式与关联 OAuth 客户端。"
        actions={
          tab === 'platforms' ? (
            <button className="btn btn--primary" onClick={() => setPlatformDialog({ mode: 'create' })}>
              <PlusIcon /> 新建平台
            </button>
          ) : (
            <button className="btn btn--primary" onClick={() => setClientDialog({ mode: 'create' })}>
              <PlusIcon /> 新建客户端
            </button>
          )
        }
      />
      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        <div style={{ padding: '0 14px', flex: 'none' }}>
          <Tabs
            tabs={[
              { key: 'platforms', label: `平台 ${platforms.length}` },
              { key: 'clients', label: `客户端 ${clients.length}` },
            ]}
            active={tab}
            onChange={setTab}
          />
        </div>
        <div className="table-scroll">
          {tab === 'platforms' ? (
            <table className="data-table">
              <thead>
                <tr>
                  <th>平台</th>
                  <th>层级</th>
                  <th>目录模式</th>
                  <th>凭证</th>
                  <th>客户端数</th>
                  <th>状态</th>
                  <th style={{ width: 150 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {platforms.map((p) => (
                  <tr key={p.id}>
                    <td>
                      <span className="cell-stack">
                        <button className="link" onClick={() => (window.location.hash = `/admin/platforms/${encodeURIComponent(p.id)}`)}>
                          {p.name}
                        </button>
                        <span>
                          {p.code} · ID {p.id}
                        </span>
                      </span>
                    </td>
                    <td>
                      <Badge tone="neutral">{TIER_LABEL[p.tier] ?? `T${p.tier}`}</Badge>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{p.catalogMode}</td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>
                      {p.proofMode}
                      {p.proofConfigured ? ' ✓' : ''}
                    </td>
                    <td>
                      <Badge tone="accent">{clientCount.get(p.id) ?? 0}</Badge>
                    </td>
                    <td>
                      <StatusDot tone={p.status === 'ACTIVE' ? 'success' : 'neutral'}>{p.status === 'ACTIVE' ? '启用' : '停用'}</StatusDot>
                    </td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <button
                        className="link"
                        onClick={() => can('platformScope') && setScopePlatform(p)}
                        disabled={!can('platformScope')}
                        aria-label={`管理 ${p.name} 授权范围`}
                      >
                        Scope
                      </button>
                      <button className="link" style={{ marginLeft: 10 }} onClick={() => setPlatformDialog({ mode: 'edit', row: p })}>
                        <EditIcon /> 编辑
                      </button>
                      <button
                        className="link"
                        style={{ marginLeft: 10, color: 'var(--ptmcp-red)' }}
                        onClick={() => setDeleting({ kind: 'platform', row: p })}
                        aria-label={`删除平台 ${p.name}`}
                      >
                        <CloseIcon /> 删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <table className="data-table">
              <thead>
                <tr>
                  <th>客户端</th>
                  <th>Client ID</th>
                  <th>所属平台</th>
                  <th>类型</th>
                  <th>状态</th>
                  <th style={{ width: 150 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {clients.map((c) => (
                  <tr key={c.id}>
                    <td>
                      <strong>{c.name}</strong>
                      <span style={{ marginLeft: 8, color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>ID {c.id}</span>
                    </td>
                    <td>
                      <code style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{c.clientId}</code>
                    </td>
                    <td style={{ color: 'var(--ptmcp-text-muted)' }}>{platforms.find((p) => p.id === c.platformId)?.name ?? c.platformId}</td>
                    <td>
                      <Badge tone="neutral">{c.type}</Badge>
                    </td>
                    <td>
                      <StatusDot tone={c.status === 'ACTIVE' ? 'success' : 'neutral'}>{c.status === 'ACTIVE' ? '启用' : '停用'}</StatusDot>
                    </td>
                    <td style={{ whiteSpace: 'nowrap' }}>
                      <button className="link" onClick={() => setClientDialog({ mode: 'edit', row: c })}>
                        <EditIcon /> 编辑
                      </button>
                      <button
                        className="link"
                        style={{ marginLeft: 10, color: 'var(--ptmcp-red)' }}
                        onClick={() => setDeleting({ kind: 'client', row: c })}
                        aria-label={`删除客户端 ${c.name}`}
                      >
                        <CloseIcon /> 删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {!loading && tab === 'platforms' && platforms.length === 0 ? (
            <EmptyState title="暂无平台" description="点击右上角「新建平台」创建。" />
          ) : null}
          {!loading && tab === 'clients' && clients.length === 0 ? (
            <EmptyState title="暂无客户端" description="切换到客户端页签后点击「新建客户端」。" />
          ) : null}
        </div>
      </div>

      {platformDialog ? (
        <PlatformDialog
          state={platformDialog}
          onClose={() => setPlatformDialog(null)}
          onSaved={() => {
            setPlatformDialog(null);
            setReloadTick((t) => t + 1);
          }}
        />
      ) : null}
      {clientDialog ? (
        <ClientDialog
          state={clientDialog}
          platforms={platforms}
          onClose={() => setClientDialog(null)}
          onSaved={() => {
            setClientDialog(null);
            setReloadTick((t) => t + 1);
          }}
          onSecret={(created) => setSecretCreated(created)}
        />
      ) : null}

      <Dialog
        open={!!deleting}
        title={deleting?.kind === 'platform' ? '删除平台' : '删除客户端'}
        onClose={() => setDeleting(null)}
        width={440}
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
                  if (deleting.kind === 'platform') {
                    await deletePlatform(deleting.row.id, deleting.row.version);
                    showToast('平台已删除');
                  } else {
                    await deleteClient(deleting.row.id, deleting.row.version);
                    showToast('客户端已删除');
                  }
                  setDeleting(null);
                  setReloadTick((t) => t + 1);
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
        <p style={{ margin: 0, fontSize: 13 }}>
          {deleting?.kind === 'platform'
            ? `确认删除平台「${deleting.row.name}」？其下客户端将一并失效，该操作不可撤销。`
            : `确认删除客户端「${deleting?.row.name}」？使用该客户端的会话将立即失效。`}
        </p>
      </Dialog>

      {scopePlatform ? <ScopeDialog platform={scopePlatform} onClose={() => setScopePlatform(null)} onSaved={() => undefined} /> : null}

      {/* 一次性 secret 展示：关闭后不可再取 */}
      <Dialog
        open={!!secretCreated}
        title="客户端密钥已生成"
        onClose={() => setSecretCreated(null)}
        width={520}
        footer={
          <button className="btn btn--primary" onClick={() => setSecretCreated(null)}>
            我已保存，关闭
          </button>
        }
      >
        <Alert kind="warning" title="密钥仅此一次展示">
          关闭后无法再次查看，请立即复制并妥善保管（对照 PAT 的发放模式）。
        </Alert>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 10 }}>
          <code
            style={{
              flex: 1,
              padding: '10px 12px',
              background: 'var(--ptmcp-canvas)',
              border: '1px solid var(--ptmcp-border)',
              borderRadius: 8,
              fontSize: 12.5,
              wordBreak: 'break-all',
              userSelect: 'all',
            }}
          >
            {secretCreated?.clientSecret}
          </code>
          <button
            className="btn btn--secondary"
            onClick={() => navigator.clipboard?.writeText(secretCreated?.clientSecret ?? '').catch(() => undefined)}
          >
            <CopyIcon /> 复制
          </button>
        </div>
      </Dialog>
    </>
  );
}

function PlatformDialog({
  state,
  onClose,
  onSaved,
}: {
  state: { mode: 'create' } | { mode: 'edit'; row: PlatformRow };
  onClose: () => void;
  onSaved: () => void;
}) {
  const editing = state.mode === 'edit' ? state.row : null;
  const [values, setValues] = useState<PlatformForm>(() => ({
    code: editing?.code ?? '',
    name: editing?.name ?? '',
    tier: String(editing?.tier ?? '3'),
    catalogMode: editing?.catalogMode ?? 'BLACKLIST',
    proofMode: editing?.proofMode ?? 'PKCE_S256',
    status: editing?.status ?? 'ACTIVE',
  }));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [conflict, setConflict] = useState('');

  const submit = async () => {
    setFormError('');
    setConflict('');
    const code = values.code.trim();
    const name = values.name.trim();
    if (!editing && !/^[a-z][a-z0-9-]{1,63}$/.test(code)) {
      setFormError('平台编码须以小写字母开头，仅含小写字母、数字与短横线，长度 2–64');
      return;
    }
    if (name.length < 2 || name.length > 128) {
      setFormError('平台名称长度须为 2–128 个字符');
      return;
    }
    const body = {
      code,
      name,
      tier: Number(values.tier),
      catalogMode: values.catalogMode,
      proofMode: values.proofMode,
      status: values.status,
    };
    setSaving(true);
    try {
      if (editing) await updatePlatform(editing.id, body, editing.version);
      else await createPlatform(body);
      onSaved();
      showToast('平台已保存');
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setConflict('该平台已被他人修改，请关闭后重试。');
      else if (status === 409) setConflict('平台编码已存在。');
      else setFormError((e as Error).message || '保存失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title={editing ? `编辑平台 · ${editing.name}` : '新建平台'} onClose={onClose} width={520}>
      {conflict ? (
        <Alert kind="warning" title="版本冲突（412）">
          {conflict}
        </Alert>
      ) : null}
      <Field label="平台编码" hint={editing ? '编码创建后不可修改' : '小写字母开头，可含数字与短横线'}>
        <TextInput value={values.code} onChange={(v) => setValues((s) => ({ ...s, code: v }))} disabled={!!editing} placeholder="prod-desktop" />
      </Field>
      <Field label="平台名称">
        <TextInput value={values.name} onChange={(v) => setValues((s) => ({ ...s, name: v }))} placeholder="如：合成桌面平台" />
      </Field>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <Field label="层级">
          <Select
            value={values.tier}
            onChange={(v) => setValues((s) => ({ ...s, tier: v }))}
            options={[1, 2, 3].map((t) => ({ value: String(t), label: `Tier ${t}（${TIER_LABEL[t]}）` }))}
          />
        </Field>
        <Field label="状态">
          <Select
            value={values.status}
            onChange={(v) => setValues((s) => ({ ...s, status: v }))}
            options={[
              { value: 'ACTIVE', label: '启用' },
              { value: 'DISABLED', label: '停用' },
            ]}
          />
        </Field>
        <Field label="目录模式">
          <Select
            value={values.catalogMode}
            onChange={(v) => setValues((s) => ({ ...s, catalogMode: v }))}
            options={CATALOG_MODES.map((m) => ({ value: m, label: m }))}
          />
        </Field>
        <Field label="凭证模式" hint="留空 NONE 表示不要求 proof">
          <Select
            value={values.proofMode}
            onChange={(v) => setValues((s) => ({ ...s, proofMode: v }))}
            options={PROOF_MODES.map((m) => ({ value: m, label: m }))}
          />
        </Field>
      </div>
      {formError ? <Alert kind="danger">{formError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
          取消
        </button>
        <button className="btn btn--primary" onClick={submit} disabled={saving}>
          {saving ? '保存中…' : editing ? '保存修改' : '创建平台'}
        </button>
      </div>
    </Dialog>
  );
}

function ClientDialog({
  state,
  platforms,
  onClose,
  onSaved,
  onSecret,
}: {
  state: { mode: 'create' } | { mode: 'edit'; row: ClientRow };
  platforms: PlatformRow[];
  onClose: () => void;
  onSaved: () => void;
  onSecret: (created: ClientCreated) => void;
}) {
  const editing = state.mode === 'edit' ? state.row : null;
  const [values, setValues] = useState<ClientForm>(() => ({
    platformId: editing?.platformId ?? platforms[0]?.id ?? '',
    name: editing?.name ?? '',
    type: editing?.type ?? 'CONFIDENTIAL',
    status: editing?.status ?? 'ACTIVE',
    authMethods: editing?.authMethods?.length ? [...editing.authMethods] : ['client_secret_basic'],
    redirectUrisText: (editing?.redirectUris ?? []).join('\n'),
    allowedScopes: editing?.allowedScopes?.length ? [...editing.allowedScopes] : [],
    jwksUri: editing?.jwksUri ?? '',
  }));

  /** 切换类型时收敛认证方式：PUBLIC 仅 none，CONFIDENTIAL 不允许 none。 */
  const changeType = (type: string) =>
    setValues((s) => ({
      ...s,
      type,
      authMethods:
        type === 'PUBLIC' ? ['none'] : s.authMethods.length === 0 || s.authMethods.includes('none') ? ['client_secret_basic'] : s.authMethods,
    }));

  const toggleList = (key: 'authMethods' | 'allowedScopes', value: string) =>
    setValues((s) => ({
      ...s,
      [key]: s[key].includes(value) ? s[key].filter((item) => item !== value) : [...s[key], value],
    }));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [conflict, setConflict] = useState('');

  const submit = async () => {
    setFormError('');
    setConflict('');
    if (!values.platformId) {
      setFormError('请选择所属平台');
      return;
    }
    const formValues = {
      name: values.name,
      type: values.type,
      status: values.status,
      authMethods: values.authMethods,
      redirectUris: parseRedirectUris(values.redirectUrisText),
      allowedScopes: values.allowedScopes,
      jwksUri: values.jwksUri,
    };
    const invalid = validateClientForm(formValues);
    if (invalid) {
      setFormError(invalid);
      return;
    }
    setSaving(true);
    try {
      if (editing) {
        await updateClient(editing.id, toClientWrite(formValues), editing.version);
        onSaved();
      } else {
        const created = await createClient(values.platformId, toClientWrite(formValues));
        onSaved();
        if (created.clientSecret) onSecret(created);
      }
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setConflict('该客户端已被他人修改，请关闭后重试。');
      else setFormError((e as Error).message || '保存失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title={editing ? `编辑客户端 · ${editing.name}` : '新建客户端'} onClose={onClose} width={520}>
      {conflict ? (
        <Alert kind="warning" title="版本冲突（412）">
          {conflict}
        </Alert>
      ) : null}
      <Field label="所属平台" hint={editing ? '所属平台不可修改' : undefined}>
        <Select
          value={values.platformId}
          onChange={(v) => setValues((s) => ({ ...s, platformId: v }))}
          disabled={!!editing}
          options={platforms.map((p) => ({ value: p.id, label: p.name }))}
        />
      </Field>
      <Field label="客户端名称">
        <TextInput value={values.name} onChange={(v) => setValues((s) => ({ ...s, name: v }))} placeholder="如：WorkBuddy 接入端" />
      </Field>
      <Field label="客户端类型" hint={editing ? '服务端不允许修改类型与认证方式' : 'CONFIDENTIAL 类型创建后会一次性发放 client secret'}>
        <Select value={values.type} onChange={changeType} disabled={!!editing} options={CLIENT_TYPES.map((t) => ({ value: t, label: t }))} />
      </Field>
      <Field label="状态">
        <Select
          value={values.status}
          onChange={(v) => setValues((s) => ({ ...s, status: v }))}
          options={CLIENT_STATUSES.map((t) => ({ value: t, label: t === 'ACTIVE' ? '启用' : '停用' }))}
        />
      </Field>
      <Field
        label={`认证方式（已选 ${values.authMethods.length}）`}
        hint={editing ? '服务端不允许修改类型与认证方式' : 'PUBLIC 仅支持 none；private_key_jwt 需要 JWKS 地址'}
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, border: '1px solid var(--ptmcp-border)', borderRadius: 8, padding: 6 }}>
          {CLIENT_AUTH_METHODS.map((method) => (
            <label
              key={method}
              style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 6px', borderRadius: 6, cursor: 'pointer', fontSize: 12.5 }}
            >
              <input
                type="checkbox"
                checked={values.authMethods.includes(method)}
                disabled={!!editing}
                onChange={() => toggleList('authMethods', method)}
              />
              <code>{method}</code>
            </label>
          ))}
        </div>
      </Field>
      <Field label={`授权 Scope（已选 ${values.allowedScopes.length}）`} hint="服务端要求至少一个；决定该客户端可访问的 MCP 能力">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4, border: '1px solid var(--ptmcp-border)', borderRadius: 8, padding: 6 }}>
          {CLIENT_SCOPES.map((scope) => (
            <label
              key={scope}
              style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 6px', borderRadius: 6, cursor: 'pointer', fontSize: 12.5 }}
            >
              <input type="checkbox" checked={values.allowedScopes.includes(scope)} onChange={() => toggleList('allowedScopes', scope)} />
              <code>{scope}</code>
            </label>
          ))}
        </div>
      </Field>
      <Field label="OAuth 回调地址" hint="一行一个，最多 10 个；须为 https，或 http 回环地址（localhost/127.x）">
        <textarea
          className="input"
          style={{ width: '100%', minHeight: 66, resize: 'vertical', fontFamily: 'inherit' }}
          value={values.redirectUrisText}
          onChange={(e) => setValues((s) => ({ ...s, redirectUrisText: e.target.value }))}
          placeholder={'https://client.example.com/callback\nhttp://127.0.0.1:3000/callback'}
        />
      </Field>
      <Field label="JWKS 地址" hint="仅使用 private_key_jwt 时必填，须为 https">
        <TextInput
          value={values.jwksUri}
          onChange={(v) => setValues((s) => ({ ...s, jwksUri: v }))}
          placeholder="https://idp.example.com/jwks.json"
        />
      </Field>
      {formError ? <Alert kind="danger">{formError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
          取消
        </button>
        <button className="btn btn--primary" onClick={submit} disabled={saving}>
          {saving ? '保存中…' : editing ? '保存修改' : '创建客户端'}
        </button>
      </div>
    </Dialog>
  );
}

/** 平台授权范围（Scope）编辑：整组替换 + 保存前差异确认 + If-Match 版本门禁。 */
function ScopeDialog({ platform, onClose, onSaved }: { platform: PlatformRow; onClose: () => void; onSaved: () => void }) {
  const [rules, setRules] = useState<ScopeRule[]>([]);
  const [base, setBase] = useState<ScopeRule[]>([]);
  const [version, setVersion] = useState('');
  const [scopeVersion, setScopeVersion] = useState('');
  const [loading, setLoading] = useState(true);
  const [confirming, setConfirming] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    getPlatformScopes(platform.id)
      .then((read) => {
        if (cancelled) return;
        setRules(read.items);
        setBase(read.items);
        setVersion(read.version);
        setScopeVersion(read.scopeVersion);
      })
      .catch((e) => !cancelled && setError(e instanceof Error ? e.message : '读取授权范围失败。'))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [platform.id]);

  const changes = useMemo(() => scopeChanges(base, rules), [base, rules]);
  const dirty = changes.added.length + changes.removed.length + changes.modified.length > 0;

  const updateRule = (index: number, patch: Partial<ScopeRule>) => setRules((rs) => rs.map((r, i) => (i === index ? { ...r, ...patch } : r)));

  const save = async () => {
    setSaving(true);
    setError('');
    try {
      const read = await savePlatformScopes(platform.id, rules, version);
      setRules(read.items);
      setBase(read.items);
      setVersion(read.version);
      setScopeVersion(read.scopeVersion);
      setConfirming(false);
      showToast('授权范围已保存');
      onSaved();
      // 留在弹窗内展示已保存状态；由用户关闭
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 412) setError('授权范围已被他人修改，请关闭后重开以读取最新版本。');
      else setError(e instanceof Error ? e.message : '保存失败，请稍后重试。');
      setConfirming(false);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Dialog open title={`授权范围 · ${platform.name}`} onClose={onClose} width={720}>
      {loading ? <Alert kind="info">读取授权范围…</Alert> : null}
      {error ? <Alert kind="danger">{error}</Alert> : null}
      {!loading ? (
        <>
          <p style={{ margin: '0 0 10px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
            平台 Scope 与用户有效权限取交集后才生效；整组替换保存（scopeVersion v{scopeVersion} → 保存后递增）。
          </p>
          <div className="table-scroll" style={{ maxHeight: 280, marginBottom: 12 }}>
            <table className="data-table">
              <thead>
                <tr>
                  <th>资源类型</th>
                  <th>资源 ID</th>
                  <th>代次</th>
                  <th>动作</th>
                  <th>子级</th>
                  <th style={{ width: 60 }} />
                </tr>
              </thead>
              <tbody>
                {rules.map((r, i) => (
                  <tr key={`${r.resourceType}:${r.resourceId}:${r.action}`}>
                    <td>
                      <Select
                        value={r.resourceType}
                        onChange={(v) => updateRule(i, { resourceType: v })}
                        options={[
                          { value: 'OBJECT', label: 'OBJECT' },
                          { value: 'DATASOURCE', label: 'DATASOURCE' },
                          { value: 'NAMESPACE', label: 'NAMESPACE' },
                        ]}
                      />
                    </td>
                    <td>
                      <TextInput value={r.resourceId} onChange={(v) => updateRule(i, { resourceId: v })} />
                    </td>
                    <td>
                      <TextInput value={r.resourceGeneration} onChange={(v) => updateRule(i, { resourceGeneration: v })} />
                    </td>
                    <td>
                      <Select
                        value={r.action}
                        onChange={(v) => updateRule(i, { action: v })}
                        options={[
                          { value: 'META', label: 'META' },
                          { value: 'READ', label: 'READ' },
                        ]}
                      />
                    </td>
                    <td>
                      <input
                        type="checkbox"
                        checked={r.includeDescendants}
                        onChange={(e) => updateRule(i, { includeDescendants: e.target.checked })}
                        aria-label="包含子级"
                      />
                    </td>
                    <td>
                      <button
                        className="link"
                        style={{ color: 'var(--ptmcp-red)' }}
                        onClick={() => setRules((rs) => rs.filter((_, idx) => idx !== i))}
                        aria-label="移除规则"
                      >
                        移除
                      </button>
                    </td>
                  </tr>
                ))}
                {rules.length === 0 ? (
                  <tr>
                    <td colSpan={6}>
                      <EmptyState title="无授权范围规则" description="该平台在 Scope 交集后不可见任何资源；添加规则后再保存。" />
                    </td>
                  </tr>
                ) : null}
              </tbody>
            </table>
          </div>
          <button
            className="btn btn--secondary"
            onClick={() =>
              setRules((rs) => [
                ...rs,
                { resourceType: 'OBJECT', resourceId: '', resourceGeneration: '1', action: 'READ', includeDescendants: false },
              ])
            }
          >
            添加规则
          </button>

          {confirming ? (
            <div style={{ marginTop: 12 }}>
              <Alert kind="warning" title="确认整组替换">
                即将保存：新增 {changes.added.length} · 移除 {changes.removed.length} · 修改 {changes.modified.length} 条规则。
              </Alert>
              <div className="table-scroll" style={{ marginTop: 8, maxHeight: 160 }}>
                <table className="data-table">
                  <tbody>
                    {changes.added.map((r) => (
                      <tr key={`a:${scopeKey(r)}`}>
                        <td style={{ color: 'var(--ptmcp-green)' }}>新增</td>
                        <td>{scopeRuleText(r)}</td>
                      </tr>
                    ))}
                    {changes.removed.map((r) => (
                      <tr key={`r:${scopeKey(r)}`}>
                        <td style={{ color: 'var(--ptmcp-red)' }}>移除</td>
                        <td>{scopeRuleText(r)}</td>
                      </tr>
                    ))}
                    {changes.modified.map((m) => (
                      <tr key={`m:${scopeKey(m.after)}`}>
                        <td style={{ color: 'var(--ptmcp-orange)' }}>修改</td>
                        <td>
                          {scopeRuleText(m.after)}（{scopeChangeText(m)}）
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 10 }}>
                <button className="btn btn--secondary" onClick={() => setConfirming(false)} disabled={saving}>
                  再想想
                </button>
                <button className="btn btn--primary" onClick={save} disabled={saving}>
                  {saving ? '保存中…' : '确认保存'}
                </button>
              </div>
            </div>
          ) : (
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 12 }}>
              <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
                关闭
              </button>
              <button className="btn btn--primary" disabled={!dirty || saving} onClick={() => setConfirming(true)}>
                保存（需确认差异）
              </button>
            </div>
          )}
        </>
      ) : null}
    </Dialog>
  );
}
