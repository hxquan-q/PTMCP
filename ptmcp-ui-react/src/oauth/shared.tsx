import type { ReactNode } from 'react';
import { Alert, RequestIdText } from '../components/common';
import { LogoIcon } from '../components/Icons';
import type { OAuthPageError } from './model';

export function OAuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="login-shell">
      {/* 与 P-01 登录页同一组装饰光斑；不进入可访问性树。 */}
      <div className="login-aurora" aria-hidden="true">
        <span />
        <span />
        <span />
      </div>
      {children}
    </div>
  );
}

export function OAuthBrand() {
  return (
    <div className="login-brand">
      <LogoIcon className="login-brand-mark" />
      <div>
        <p className="login-title">PTMCP</p>
        <p className="login-subtitle">数据访问服务</p>
      </div>
    </div>
  );
}

export function OAuthLoading({ label }: { label: string }) {
  return (
    <OAuthShell>
      <div className="login-card" aria-busy="true">
        <OAuthBrand />
        <p className="oauth-loading" role="status">
          {label}
        </p>
      </div>
    </OAuthShell>
  );
}

export function OAuthErrorCard({ error, heading }: { error: OAuthPageError; heading: string }) {
  return (
    <OAuthShell>
      <div className="login-card oauth-card">
        <OAuthBrand />
        <h1 className="oauth-heading">{heading}</h1>
        <Alert kind="danger" title="授权请求未通过校验">
          {error.message}
          <RequestIdText requestId={error.requestId} />
        </Alert>
        <button
          type="button"
          className="btn btn--secondary login-submit"
          onClick={() => {
            window.location.reload();
          }}
        >
          重新加载
        </button>
        <p className="login-footnote">如果问题持续，请关闭本页并从客户端重新发起授权。</p>
      </div>
    </OAuthShell>
  );
}
