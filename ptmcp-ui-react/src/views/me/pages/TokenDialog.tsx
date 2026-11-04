import { useEffect, useState } from 'react';
import { Alert, Dialog, Field, Select, TextInput } from '../../../components/common';
import { CopyIcon } from '../../../components/Icons';
import { createToken, type TokenCreated } from '../../../services/access';
import { mePortalApi } from '../../../services/mePortalApi';
import { showToast } from '../../../toast';

/** 个人令牌的作用域与最长有效期：弹窗与列表共用同一份定义。 */
const PAT_SCOPES = ['mcp:catalog', 'mcp:schema', 'mcp:query'];
const MAX_PAT_DAYS = 90;

export function TokenDialog({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [name, setName] = useState('');
  const [platformId, setPlatformId] = useState('');
  const [platforms, setPlatforms] = useState<Array<{ id: string; name: string }>>([]);
  const [scopes, setScopes] = useState<string[]>(['mcp:catalog']);
  const [expiresAt, setExpiresAt] = useState(() => new Date(Date.now() + 30 * 86400_000).toISOString().slice(0, 10));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [created, setCreated] = useState<TokenCreated | null>(null);

  useEffect(() => {
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => {
        setPlatforms(ctx.items.map((p) => ({ id: p.id, name: p.name })));
        setPlatformId((cur) => cur || ctx.items[0]?.id || '');
      })
      .catch(() => setPlatforms([]));
  }, []);

  const submit = async () => {
    setFormError('');
    if (name.trim().length < 1 || name.trim().length > 128) {
      setFormError('令牌名称长度须为 1–128 个字符');
      return;
    }
    if (!platformId) {
      setFormError('请选择所属平台');
      return;
    }
    if (scopes.length === 0) {
      setFormError('请至少选择一个范围');
      return;
    }
    const expiry = new Date(`${expiresAt}T23:59:59`);
    if (Number.isNaN(expiry.getTime()) || expiry.getTime() <= Date.now() || expiry.getTime() > Date.now() + MAX_PAT_DAYS * 86400_000) {
      setFormError(`过期时间须在未来 ${MAX_PAT_DAYS} 天内`);
      return;
    }
    setSaving(true);
    try {
      const result = await createToken({ name: name.trim(), platformId, scopes, expiresAt: expiry.toISOString() });
      setCreated(result);
      showToast('令牌已创建');
    } catch (e) {
      setFormError((e as Error).message || '创建失败，请稍后重试。');
    } finally {
      setSaving(false);
    }
  };

  if (created) {
    return (
      <Dialog
        open
        title="令牌已创建"
        onClose={onClose}
        width={560}
        footer={
          <button className="btn btn--primary" onClick={onSaved}>
            我已保存，关闭
          </button>
        }
      >
        <Alert kind="warning" title="令牌仅此一次展示">
          关闭后无法再次查看，请立即复制并妥善保管；泄露可随时撤销。
        </Alert>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginTop: 10 }}>
          <code
            style={{
              flex: 1,
              padding: '10px 12px',
              background: 'var(--ptmcp-canvas)',
              border: '1px solid var(--ptmcp-border)',
              borderRadius: 'var(--ptmcp-radius-control)',
              fontSize: 12.5,
              wordBreak: 'break-all',
              userSelect: 'all',
            }}
          >
            {created.token}
          </code>
          <button className="btn btn--secondary" onClick={() => navigator.clipboard?.writeText(created.token).catch(() => undefined)}>
            <CopyIcon /> 复制
          </button>
        </div>
      </Dialog>
    );
  }

  return (
    <Dialog open title="新建个人令牌" onClose={onClose} width={500}>
      <Field label="令牌名称" hint="1–128 个字符">
        <TextInput value={name} onChange={setName} placeholder="如：Cursor 接入专用" />
      </Field>
      <Field label="所属平台">
        <Select
          ariaLabel="所属平台"
          value={platformId}
          onChange={setPlatformId}
          width={240}
          options={platforms.map((p) => ({ value: p.id, label: p.name }))}
        />
      </Field>
      <Field label={`范围（已选 ${scopes.length}）`} hint="catalog 目录 / schema 结构 / query 查询">
        <div style={{ display: 'flex', gap: 16 }}>
          {PAT_SCOPES.map((scope) => (
            <label key={scope} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 13, cursor: 'pointer' }}>
              <input
                type="checkbox"
                checked={scopes.includes(scope)}
                onChange={() => setScopes((s) => (s.includes(scope) ? s.filter((x) => x !== scope) : [...s, scope]))}
              />
              <code>{scope}</code>
            </label>
          ))}
        </div>
      </Field>
      <Field label={`过期日期（最长 ${MAX_PAT_DAYS} 天）`}>
        <TextInput value={expiresAt} onChange={setExpiresAt} type="date" />
      </Field>
      {formError ? <Alert kind="danger">{formError}</Alert> : null}
      <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
        <button className="btn btn--secondary" onClick={onClose} disabled={saving}>
          取消
        </button>
        <button className="btn btn--primary" onClick={submit} disabled={saving}>
          {saving ? '创建中…' : '创建令牌'}
        </button>
      </div>
    </Dialog>
  );
}
