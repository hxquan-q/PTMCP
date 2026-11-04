import { useState } from 'react';
import { Alert, Dialog, Field, Select, TextInput } from '../../../components/common';
import { createClient, updateClient, type ClientCreated, type ClientRow, type PlatformRow } from '../../../services/access';
import { CLIENT_AUTH_METHODS, CLIENT_SCOPES, parseRedirectUris, toClientWrite, validateClientForm } from '../clientForm';
import { CLIENT_STATUSES, CLIENT_TYPES, type ClientForm } from './shared';

export function ClientDialog({
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
          ariaLabel="所属平台"
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
        <Select
          ariaLabel="客户端类型"
          value={values.type}
          onChange={changeType}
          disabled={!!editing}
          options={CLIENT_TYPES.map((t) => ({ value: t, label: t }))}
        />
      </Field>
      <Field label="状态">
        <Select
          ariaLabel="状态"
          value={values.status}
          onChange={(v) => setValues((s) => ({ ...s, status: v }))}
          options={CLIENT_STATUSES.map((t) => ({ value: t, label: t === 'ACTIVE' ? '启用' : '停用' }))}
        />
      </Field>
      <Field
        label={`认证方式（已选 ${values.authMethods.length}）`}
        hint={editing ? '服务端不允许修改类型与认证方式' : 'PUBLIC 仅支持 none；private_key_jwt 需要 JWKS 地址'}
      >
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 4,
            border: '1px solid var(--ptmcp-border)',
            borderRadius: 'var(--ptmcp-radius-control)',
            padding: 6,
          }}
        >
          {CLIENT_AUTH_METHODS.map((method) => (
            <label
              key={method}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '4px 6px',
                borderRadius: 'var(--ptmcp-radius-sm)',
                cursor: 'pointer',
                fontSize: 12.5,
              }}
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
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 4,
            border: '1px solid var(--ptmcp-border)',
            borderRadius: 'var(--ptmcp-radius-control)',
            padding: 6,
          }}
        >
          {CLIENT_SCOPES.map((scope) => (
            <label
              key={scope}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 8,
                padding: '4px 6px',
                borderRadius: 'var(--ptmcp-radius-sm)',
                cursor: 'pointer',
                fontSize: 12.5,
              }}
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
