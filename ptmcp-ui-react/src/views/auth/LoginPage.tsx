import { useState } from 'react';
import { Alert, Field, TextInput } from '../../components/common';
import { LogoIcon } from '../../components/Icons';
import { login } from '../../services/session';

export function LoginPage({ onLoggedIn, reason }: { onLoggedIn: () => void; reason?: string }) {
  const [username, setUsername] = useState('');
  const [password, setSetPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    if (!username.trim() || !password) {
      setError('请输入用户名和密码。');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await login(username.trim(), password);
      onLoggedIn();
    } catch (err) {
      setError(err instanceof Error ? err.message : '登录失败，请稍后重试。');
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      style={{
        height: '100vh',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'linear-gradient(180deg, var(--ptmcp-canvas) 0%, var(--ptmcp-blue-bg, #eef4fb) 100%)',
      }}
    >
      <form onSubmit={submit} className="table-card" style={{ width: 360, padding: '32px 30px', gap: 4 }} aria-label="登录 PTMCP">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 6 }}>
          <LogoIcon style={{ fontSize: 30, color: 'var(--ptmcp-blue)' }} />
          <div>
            <div style={{ fontSize: 19, fontWeight: 800, color: 'var(--ptmcp-heading)' }}>PTMCP</div>
            <div style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>数据访问管理平台</div>
          </div>
        </div>
        {reason ? (
          <Alert kind="warning" title="需要重新登录">
            {reason}
          </Alert>
        ) : null}
        <Field label="用户名">
          <TextInput value={username} onChange={setUsername} autoComplete="username" />
        </Field>
        <Field label="密码">
          <TextInput value={password} onChange={setSetPassword} type="password" autoComplete="current-password" />
        </Field>
        {error ? <Alert kind="danger">{error}</Alert> : null}
        <button className="btn btn--primary" type="submit" disabled={busy} style={{ width: '100%', marginTop: 6 }}>
          {busy ? '登录中…' : '登录'}
        </button>
        <p style={{ margin: '10px 0 0', fontSize: 12, color: 'var(--ptmcp-text-faint)', textAlign: 'center' }}>
          权限管控型 MCP 网关 · 登录行为将被审计
        </p>
      </form>
    </div>
  );
}
