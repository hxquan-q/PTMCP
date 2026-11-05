import { useState } from 'react';
import { Button } from 'antd';
import { Alert, Dialog, Field, Select, TextInput } from '../../../components/common';
import { createPlatform, updatePlatform, type PlatformRow } from '../../../services/access';
import { showToast } from '../../../toast';
import { CATALOG_MODES, PROOF_MODES, platformForm, toPlatformWrite, validatePlatformForm } from '../platformForm';
import { ApiError } from '../../../services/http';

import { TIER_LABEL } from './shared';

export function PlatformDialog({
  state,
  onClose,
  onSaved,
}: {
  state: { mode: 'create' } | { mode: 'edit'; row: PlatformRow };
  onClose: () => void;
  onSaved: () => void;
}) {
  const editing = state.mode === 'edit' ? state.row : null;
  const [values, setValues] = useState(() => platformForm(editing));
  const [replacementRequired, setReplacementRequired] = useState(false);
  const [requiresReload, setRequiresReload] = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [conflict, setConflict] = useState('');

  const submit = async () => {
    setFormError('');
    setConflict('');
    if (saving) return;
    if (requiresReload) return;
    const error = validatePlatformForm(values, editing, replacementRequired);
    if (error) {
      setFormError(error);
      return;
    }
    const body = toPlatformWrite(values);
    setSaving(true);
    try {
      if (editing) await updatePlatform(editing.id, body, editing.version);
      else await createPlatform(body);
      onSaved();
      showToast('平台已保存');
    } catch (e) {
      if (body.proofConfig) setReplacementRequired(true);
      const definiteRejection = e instanceof ApiError && e.status >= 400 && e.status < 500 && e.status !== 408 && e.code !== 'INVALID_RESPONSE';
      if (!definiteRejection) {
        setRequiresReload(true);
        setFormError('保存结果无法确认，请关闭并重新读取平台后核对；不要直接重试。');
      } else if (e.status === 412) {
        setRequiresReload(true);
        setConflict('该平台已被他人修改，请关闭并重新读取后核对。');
      } else if (e.status === 409) setConflict('平台编码已存在。');
      else setFormError(e.message || '保存失败，请重新填写证明材料后重试。');
    } finally {
      setValues((s) => ({ ...s, certificateFingerprint: '', proxyNetwork: '', secret: '' }));
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
      <Field label="平台编码" hint={editing ? '编码创建后不可修改' : '字母开头，可含数字、下划线与短横线，1–64 字符'}>
        <TextInput value={values.code} onChange={(v) => setValues((s) => ({ ...s, code: v }))} disabled={!!editing} placeholder="prod-desktop" />
      </Field>
      <Field label="平台名称">
        <TextInput value={values.name} onChange={(v) => setValues((s) => ({ ...s, name: v }))} placeholder="如：合成桌面平台" />
      </Field>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
        <Field label="层级">
          <Select
            ariaLabel="层级"
            value={values.tier}
            onChange={(v) => setValues((s) => ({ ...s, tier: v }))}
            options={[1, 2, 3].map((t) => ({ value: String(t), label: `Tier ${t}（${TIER_LABEL[t]}）` }))}
          />
        </Field>
        <Field label="状态">
          <Select
            ariaLabel="状态"
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
            ariaLabel="目录模式"
            value={values.catalogMode}
            onChange={(v) => setValues((s) => ({ ...s, catalogMode: v }))}
            options={CATALOG_MODES.map((m) => ({ value: m, label: m }))}
          />
        </Field>
        <Field label="平台证明模式" hint="Tier1 必须配置 MTLS 或代理密钥；OAuth PKCE 不是平台证明">
          <Select
            ariaLabel="平台证明模式"
            value={values.proofMode}
            onChange={(v) => setValues((s) => ({ ...s, proofMode: v, certificateFingerprint: '', proxyNetwork: '', secret: '' }))}
            options={PROOF_MODES.map((m) => ({ value: m, label: m }))}
          />
        </Field>
      </div>
      {values.proofMode !== 'NONE' && editing?.proofConfigured && editing.proofMode === values.proofMode ? (
        <Alert kind="info">证明已配置。全部留空保留当前配置；填写新材料将替换，旧材料不会回显。</Alert>
      ) : null}
      {values.proofMode === 'MTLS' ? (
        <Field label="SHA-256 证书指纹" hint="32 字节十六进制，可用冒号分隔">
          <TextInput
            value={values.certificateFingerprint}
            onChange={(v) => setValues((s) => ({ ...s, certificateFingerprint: v }))}
            autoComplete="off"
          />
        </Field>
      ) : null}
      {values.proofMode === 'PROXY_SECRET' ? (
        <>
          <Field label="代理源网络 CIDR" hint="同时替换源网络和密钥；仅受信代理可发送 X-PTMCP-Proxy-Secret">
            <TextInput
              value={values.proxyNetwork}
              onChange={(v) => setValues((s) => ({ ...s, proxyNetwork: v }))}
              placeholder="192.0.2.0/24"
              maxLength={256}
              autoComplete="off"
            />
          </Field>
          <Field label="代理证明密钥" hint="仅用于本次写入，不回显、不保存到浏览器存储">
            <TextInput type="password" value={values.secret} onChange={(v) => setValues((s) => ({ ...s, secret: v }))} autoComplete="new-password" />
          </Field>
        </>
      ) : null}
      {replacementRequired && !requiresReload ? (
        <Alert kind="warning" title="证明替换尚未完成">
          新材料已清空，请重新填写；留空不会被视为保留旧证明。
          <Button
            disabled={saving}
            onClick={() => {
              setReplacementRequired(false);
              setValues((s) => ({ ...s, proofMode: editing?.proofMode ?? 'NONE', certificateFingerprint: '', proxyNetwork: '', secret: '' }));
              setFormError('');
            }}
          >
            取消本次证明替换
          </Button>
        </Alert>
      ) : null}
      {formError ? <Alert kind="danger">{formError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <Button onClick={onClose} disabled={saving}>
          取消
        </Button>
        <Button type="primary" onClick={submit} disabled={saving || requiresReload}>
          {saving ? '保存中…' : editing ? '保存修改' : '创建平台'}
        </Button>
      </div>
    </Dialog>
  );
}
