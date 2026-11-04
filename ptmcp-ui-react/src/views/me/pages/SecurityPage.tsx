import { useEffect, useState } from 'react';
import { Alert, Badge, Field, PageHeader, StatusDot, TextInput } from '../../../components/common';
import { mePortalApi } from '../../../services/mePortalApi';
import { changePassword } from '../../../services/session';
import { validatePasswordForm } from '../passwordForm';
import { showToast } from '../../../toast';

export function SecurityPage() {
  const [platforms, setPlatforms] = useState<{ items: { id: string; name: string; tier: number }[] } | null>(null);
  const [pwForm, setPwForm] = useState({ oldPassword: '', newPassword: '', confirmPassword: '' });
  const [pwSaving, setPwSaving] = useState(false);
  const [pwError, setPwError] = useState('');

  useEffect(() => {
    let cancelled = false;
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => !cancelled && setPlatforms({ items: ctx.items }))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <>
      <PageHeader title="账号安全" description="会话状态与当前可见接入上下文。" />
      <div className="table-card" style={{ padding: '16px 18px', maxWidth: 640 }}>
        <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>当前可见平台</strong>
        {platforms ? (
          <ul style={{ margin: '10px 0 0', padding: 0, listStyle: 'none', display: 'grid', gap: 8 }}>
            {platforms.items.map((p) => (
              <li key={p.id} style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}>
                <StatusDot tone="success">·</StatusDot>
                <strong>{p.name}</strong>
                <Badge tone="neutral">Tier {p.tier}</Badge>
              </li>
            ))}
          </ul>
        ) : (
          <p style={{ margin: '10px 0 0', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>加载接入上下文…</p>
        )}
      </div>
      <div className="table-card" style={{ padding: '16px 18px', maxWidth: 640, marginTop: 14 }}>
        <strong style={{ fontSize: 14, color: 'var(--ptmcp-heading)' }}>修改密码</strong>
        <p style={{ margin: '6px 0 12px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
          修改成功后，当前账号的其他会话、PAT 与客户端授权会按服务端规则失效，需要重新登录。
        </p>
        <Field label="当前密码">
          <TextInput
            value={pwForm.oldPassword}
            onChange={(v) => setPwForm((f) => ({ ...f, oldPassword: v }))}
            type="password"
            autoComplete="current-password"
          />
        </Field>
        <Field label="新密码" hint="12～64 个字符，UTF-8 不超过 72 字节">
          <TextInput
            value={pwForm.newPassword}
            onChange={(v) => setPwForm((f) => ({ ...f, newPassword: v }))}
            type="password"
            autoComplete="new-password"
          />
        </Field>
        <Field label="确认新密码">
          <TextInput
            value={pwForm.confirmPassword}
            onChange={(v) => setPwForm((f) => ({ ...f, confirmPassword: v }))}
            type="password"
            autoComplete="new-password"
          />
        </Field>
        {pwError ? <Alert kind="danger">{pwError}</Alert> : null}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 4 }}>
          <button
            className="btn btn--secondary"
            onClick={() => setPwForm({ oldPassword: '', newPassword: '', confirmPassword: '' })}
            disabled={pwSaving}
          >
            清空
          </button>
          <button
            className="btn btn--primary"
            disabled={pwSaving}
            onClick={async () => {
              setPwError('');
              const err = validatePasswordForm(pwForm);
              if (err) {
                setPwError(err);
                return;
              }
              setPwSaving(true);
              try {
                await changePassword(pwForm.oldPassword, pwForm.newPassword);
                setPwForm({ oldPassword: '', newPassword: '', confirmPassword: '' });
                showToast('密码已修改，全部会话已失效');
                window.dispatchEvent(new CustomEvent('ptmcp:password-changed'));
              } catch (e) {
                setPwError(e instanceof Error ? e.message : '修改失败，请稍后重试。');
              } finally {
                setPwSaving(false);
              }
            }}
          >
            {pwSaving ? '提交中…' : '修改密码'}
          </button>
        </div>
      </div>
    </>
  );
}

/* ─── 查询历史详情（个人视角，脱敏 SQL + 耗时分解 + 排障指引） ─── */
