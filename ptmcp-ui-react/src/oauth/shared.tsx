import type { ReactNode } from 'react';
import { Button } from 'antd';
import { Alert, CharReveal, LoginBackdrop, RequestIdText } from '../components/common';
import { LogoIcon } from '../components/Icons';
import type { OAuthPageError } from './model';

export function OAuthShell({ children }: { children: ReactNode }) {
  return (
    <div className="login-shell">
      {/* 与 P-01 登录页同一组背景 CG；不进入可访问性树。 */}
      <LoginBackdrop />
      {children}
    </div>
  );
}

export function OAuthBrand() {
  return (
    <div className="login-brand">
      <LogoIcon className="login-brand-mark" />
      <div>
        <p className="login-title">
          <CharReveal text="PTMCP" delay={220} />
        </p>
        <p className="login-subtitle">
          <CharReveal text="数据访问服务" delay={300} />
        </p>
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
        <Button
          onClick={() => {
            window.location.reload();
          }}
        >
          重新加载
        </Button>
        <p className="login-footnote">如果问题持续，请关闭本页并从客户端重新发起授权。</p>
      </div>
    </OAuthShell>
  );
}
