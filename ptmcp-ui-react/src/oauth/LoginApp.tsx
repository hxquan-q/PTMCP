import { useState } from 'react';
import { Alert, Field } from '../components/common';
import { loadLoginContext } from './context';
import { loginNotice } from './model';
import { OAuthBrand, OAuthErrorCard, OAuthLoading, OAuthShell } from './shared';
import { useAsyncContext } from './useAsyncContext';

export function LoginApp() {
  const state = useAsyncContext(loadLoginContext);
  const [submitting, setSubmitting] = useState(false);

  if (state.kind === 'loading') return <OAuthLoading label="正在加载登录页…" />;
  if (state.kind === 'error') return <OAuthErrorCard error={state.error} heading="无法加载登录页" />;

  const notice = loginNotice(state.value);
  return (
    <OAuthShell>
      <form method="post" action="/login" className="login-card" aria-label="登录 PTMCP" aria-busy={submitting} onSubmit={() => setSubmitting(true)}>
        <h1 className="sr-only">登录 PTMCP</h1>
        <input type="hidden" name="_csrf" value={state.value.csrfToken} />
        <OAuthBrand />
        {notice ? (
          <Alert kind={notice.kind} title={notice.title}>
            {notice.message}
          </Alert>
        ) : null}
        <Field label="用户名">
          <input
            id="oauth-username"
            className="input oauth-input"
            type="text"
            name="username"
            autoComplete="username"
            autoCapitalize="none"
            spellCheck={false}
            required
            autoFocus
          />
        </Field>
        <Field label="密码">
          <input id="oauth-password" className="input oauth-input" type="password" name="password" autoComplete="current-password" required />
        </Field>
        <button className="btn btn--primary login-submit" type="submit" disabled={submitting}>
          {submitting ? '登录中…' : '登录'}
        </button>
        <p className="login-footnote">凭证由管理员签发，不开放自助注册 · 连续多次失败会临时锁定登录，锁定时长以服务端提示为准</p>
      </form>
    </OAuthShell>
  );
}
