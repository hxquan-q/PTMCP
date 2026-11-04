import { useState } from 'react';
import { Alert, Field, useMagnetic } from '../components/common';
import { loadLoginContext } from './context';
import { loginNotice } from './model';
import { OAuthBrand, OAuthErrorCard, OAuthLoading, OAuthShell } from './shared';
import { useAsyncContext } from './useAsyncContext';

export function LoginApp() {
  const state = useAsyncContext(loadLoginContext);
  const [submitting, setSubmitting] = useState(false);
  const magnetRef = useMagnetic<HTMLDivElement>();

  if (state.kind === 'loading') return <OAuthLoading label="正在加载登录页…" />;
  if (state.kind === 'error') return <OAuthErrorCard error={state.error} heading="无法加载登录页" />;

  const notice = loginNotice(state.value);
  return (
    <OAuthShell>
      <form
        method="post"
        action="/login"
        className="login-card glow-follow"
        aria-label="登录 PTMCP"
        aria-busy={submitting}
        onSubmit={() => setSubmitting(true)}
        onPointerMove={(e) => {
          /* 柔光跟随指针：与统计卡同一习语，只写两个自定义属性，不触发布局重排。 */
          const rect = e.currentTarget.getBoundingClientRect();
          e.currentTarget.style.setProperty('--ptmcp-mx', `${e.clientX - rect.left}px`);
          e.currentTarget.style.setProperty('--ptmcp-my', `${e.clientY - rect.top}px`);
        }}
      >
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
        {/* 磁吸挂在包裹层：按钮自身的 hover/active/入场动画不被内联 transform 干扰。 */}
        <div className="login-submit-wrap" ref={magnetRef}>
          <button className="btn btn--primary login-submit" type="submit" disabled={submitting}>
            {submitting ? '登录中…' : '登录'}
          </button>
        </div>
        <p className="login-footnote">凭证由管理员签发，不开放自助注册 · 连续多次失败会临时锁定登录，锁定时长以服务端提示为准</p>
      </form>
    </OAuthShell>
  );
}
