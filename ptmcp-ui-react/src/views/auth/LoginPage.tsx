import { useRef, useState } from 'react';
import { Alert, Field, RequestIdText, TextInput } from '../../components/common';
import { LogoIcon } from '../../components/Icons';
import { readFailure, type Failure } from '../../services/http';
import { getSession, login } from '../../services/session';

const ERROR_ID = 'login-error';

type MissingField = 'username' | 'password';

/** 会话是否已建立：服务端是唯一可信来源，用于区分“凭据被拒”与“登录后续步骤失败”。 */
async function sessionEstablished(): Promise<boolean> {
  try {
    await getSession();
    return true;
  } catch {
    return false;
  }
}

export function LoginPage({ onLoggedIn, reason }: { onLoggedIn: () => void; reason?: string }) {
  const [username, setUsername] = useState('');
  const [password, setSetPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [invalidField, setInvalidField] = useState<MissingField | null>(null);
  const [failure, setFailure] = useState<Failure | null>(null);
  const usernameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    const missing: MissingField | null = !username.trim() ? 'username' : !password ? 'password' : null;
    if (missing) {
      setInvalidField(missing);
      setFailure({ message: missing === 'username' ? '请输入用户名。' : '请输入密码。', requestId: '' });
      (missing === 'username' ? usernameRef : passwordRef).current?.focus();
      return;
    }
    setInvalidField(null);
    setFailure(null);
    setBusy(true);
    let loginError: unknown = null;
    try {
      await login(username.trim(), password);
    } catch (err) {
      loginError = err;
    }
    // login() 在 POST 之后还要刷新 CSRF；若失败发生在刷新阶段，服务端会话其实已建立，
    // 此时按会话状态进入控制台，不能把已登录的账号报成“登录失败”。
    const signedIn = loginError === null || (await sessionEstablished());
    setBusy(false);
    if (signedIn) {
      setSetPassword('');
      onLoggedIn();
      return;
    }
    setFailure(readFailure(loginError, '登录未完成，请稍后重试。'));
    passwordRef.current?.focus();
  };

  return (
    <div className="login-shell">
      <form onSubmit={submit} className="login-card" aria-label="登录 PTMCP" aria-busy={busy}>
        <div className="login-brand">
          <LogoIcon className="login-brand-mark" />
          <div>
            <h1 className="login-title">PTMCP</h1>
            <p className="login-subtitle">数据访问管理平台</p>
          </div>
        </div>
        {reason ? (
          <Alert kind="warning" title="需要重新登录">
            {reason}
          </Alert>
        ) : null}
        <Field label="用户名">
          <TextInput
            value={username}
            onChange={(v) => {
              setUsername(v);
              if (invalidField === 'username') {
                setInvalidField(null);
                setFailure(null);
              }
            }}
            autoComplete="username"
            invalid={invalidField === 'username'}
            describedBy={invalidField === 'username' ? ERROR_ID : undefined}
            inputRef={usernameRef}
          />
        </Field>
        <Field label="密码">
          <TextInput
            value={password}
            onChange={(v) => {
              setSetPassword(v);
              if (invalidField === 'password') {
                setInvalidField(null);
                setFailure(null);
              }
            }}
            type="password"
            autoComplete="current-password"
            invalid={invalidField === 'password'}
            describedBy={invalidField === 'password' ? ERROR_ID : undefined}
            inputRef={passwordRef}
          />
        </Field>
        {failure ? (
          <Alert id={ERROR_ID} kind="danger" title="无法登录">
            {failure.message}
            <RequestIdText requestId={failure.requestId} />
          </Alert>
        ) : null}
        <button className="btn btn--primary login-submit" type="submit" disabled={busy}>
          {busy ? '登录中…' : '登录'}
        </button>
        <p className="login-footnote">权限管控型 MCP 网关 · 连续多次失败会临时锁定登录，锁定时长以服务端提示为准</p>
      </form>
    </div>
  );
}
