import { useEffect, useState } from 'react';
import { Button } from 'antd';
import { Alert, Badge, Field, PageHeader, TextInput } from '../../../components/common';
import { KeyIcon, LockIcon, MonitorIcon, ShieldIcon, UserIcon } from '../../../components/Icons';
import { formatTaipeiTime } from '../../../services/dateTime';
import { mePortalApi } from '../../../services/mePortalApi';
import { changePassword, getSession, logout, type SessionInfo } from '../../../services/session';
import { showToast } from '../../../toast';
import { validatePasswordForm } from '../passwordForm';

interface VisiblePlatform {
  id: string;
  name: string;
  tier: number;
}

export function SecurityPage() {
  const [account, setAccount] = useState<SessionInfo | null>(null);
  const [platforms, setPlatforms] = useState<VisiblePlatform[] | null>(null);
  const [pwForm, setPwForm] = useState({ oldPassword: '', newPassword: '', confirmPassword: '' });
  const [pwSaving, setPwSaving] = useState(false);
  const [pwError, setPwError] = useState('');
  const [signingOut, setSigningOut] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getSession()
      .then((info) => !cancelled && setAccount(info))
      .catch(() => undefined);
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => !cancelled && setPlatforms(ctx.items))
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  const submitPassword = async () => {
    if (pwSaving) return;
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
  };

  const signOut = async () => {
    if (signingOut) return;
    setSigningOut(true);
    await logout();
    window.dispatchEvent(new CustomEvent('ptmcp:signed-out'));
  };

  const displayName = account?.displayName?.trim() || account?.username || '正在读取账号…';
  const initial = account ? displayName.slice(0, 1).toUpperCase() : '';

  return (
    <div className="security-page">
      <PageHeader title="账号安全" description="账号资料、登录凭据与会话状态。" />

      <section className="security-account" aria-label="账号概览">
        <div className="security-account-main">
          <span className="security-avatar" aria-hidden="true">
            {initial || <UserIcon />}
          </span>
          <div className="security-identity">
            <div className="security-name-row">
              <h2 className="security-name">{displayName}</h2>
              {account?.platformAdmin ? <Badge tone="accent">平台管理员</Badge> : null}
            </div>
            {account ? (
              <p className="security-meta">
                <span>@{account.username}</span>
                {account.department?.name ? (
                  <>
                    <span className="security-meta-sep" aria-hidden="true">
                      ·
                    </span>
                    <span>{account.department.name}</span>
                  </>
                ) : null}
              </p>
            ) : null}
            {account?.roles?.length ? (
              <div className="security-roles" aria-label="账号角色">
                {account.roles.map((role) => (
                  <Badge key={role.id} tone="neutral">
                    {role.name}
                  </Badge>
                ))}
              </div>
            ) : null}
          </div>
        </div>
        <div className="security-session">
          <span className="security-session-label">会话到期</span>
          {account?.expiresAt ? (
            <time className="security-session-value" dateTime={account.expiresAt}>
              {formatTaipeiTime(account.expiresAt, 'minute')}
            </time>
          ) : (
            <span className="security-session-value">—</span>
          )}
          <span className="security-session-note">到期后需重新登录</span>
          <Button onClick={signOut} disabled={signingOut}>
            {signingOut ? '正在退出…' : '退出当前会话'}
          </Button>
        </div>
      </section>

      <div className="security-grid">
        <form
          className="security-card"
          aria-labelledby="security-password-title"
          onSubmit={(e) => {
            e.preventDefault();
            void submitPassword();
          }}
        >
          <header className="security-card-head">
            <span className="security-card-icon" aria-hidden="true">
              <KeyIcon />
            </span>
            <div className="security-card-title">
              <h2 id="security-password-title">修改密码</h2>
              <p>修改成功后，其他会话、PAT 与客户端授权会按服务端规则失效，需要重新登录。</p>
            </div>
          </header>
          <div className="security-card-body">
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
          </div>
          <footer className="security-card-foot">
            <span className="security-foot-note">
              <LockIcon />
              修改后需使用新密码重新登录
            </span>
            <div className="form-actions">
              <Button onClick={() => setPwForm({ oldPassword: '', newPassword: '', confirmPassword: '' })} disabled={pwSaving}>
                清空
              </Button>
              <Button type="primary" htmlType="submit" disabled={pwSaving}>
                {pwSaving ? '提交中…' : '修改密码'}
              </Button>
            </div>
          </footer>
        </form>

        <aside className="security-aside">
          <section className="security-card" aria-labelledby="security-platform-title">
            <header className="security-card-head">
              <span className="security-card-icon" aria-hidden="true">
                <MonitorIcon />
              </span>
              <div className="security-card-title">
                <h2 id="security-platform-title">当前可见平台</h2>
                <p>接入上下文按当前账号的权限范围过滤。</p>
              </div>
            </header>
            {platforms ? (
              platforms.length ? (
                <ul className="security-platform-list">
                  {platforms.map((p) => (
                    <li key={p.id} className="security-platform">
                      <span className="security-platform-dot" aria-hidden="true" />
                      <span className="security-platform-name">{p.name}</span>
                      <Badge tone="neutral">Tier {p.tier}</Badge>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="security-platform-empty">当前账号暂无可见平台。</p>
              )
            ) : (
              <p className="security-platform-empty">加载接入上下文…</p>
            )}
            <p className="security-card-note">
              <ShieldIcon />
              仅显示与当前账号权限有交集的平台；如需新增范围，请联系平台管理员。
            </p>
          </section>
        </aside>
      </div>
    </div>
  );
}
