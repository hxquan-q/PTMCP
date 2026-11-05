import { Fragment, useId, useMemo, useState } from 'react';
import { Button } from 'antd';
import { Alert } from '../components/common';
import { loadConsentContext } from './context';
import {
  accessPolicyText,
  allowFields,
  clientMeta,
  consentTitle,
  defaultSelected,
  denyFields,
  refreshPolicyText,
  scopeSummary,
  type ConsentContext,
  type FormField,
} from './model';
import { OAuthBrand, OAuthErrorCard, OAuthLoading, OAuthShell } from './shared';
import { useAsyncContext } from './useAsyncContext';

export function ConsentApp() {
  const state = useAsyncContext(loadConsentContext);
  if (state.kind === 'loading') return <OAuthLoading label="正在加载授权信息…" />;
  if (state.kind === 'error') return <OAuthErrorCard error={state.error} heading="无法继续授权" />;
  return <ConsentCard context={state.value} />;
}

function HiddenFields({ fields }: { fields: FormField[] }) {
  return (
    <>
      {fields.map((field) => (
        <input key={`${field.name}:${field.value}`} type="hidden" name={field.name} value={field.value} />
      ))}
    </>
  );
}

function ConsentCard({ context }: { context: ConsentContext }) {
  const groupId = useId();
  const [selected, setSelected] = useState<string[]>(() => defaultSelected(context.scopes));
  const [submitting, setSubmitting] = useState<'allow' | 'deny' | null>(null);
  const selectedSet = useMemo(() => new Set(selected), [selected]);
  const grantedSet = useMemo(() => new Set(context.grantedScopes), [context.grantedScopes]);

  // 空选择不提供「允许」；拒绝必须走服务端端点，空 scope 提交在已有同意时会签发授权码。
  const allow = selected.length > 0 ? allowFields(context, selected) : [];
  const deny = denyFields(context);
  const canAllow = allow.length > 0;

  const toggle = (value: string) => {
    setSelected((current) => (current.includes(value) ? current.filter((item) => item !== value) : [...current, value]));
  };

  return (
    <OAuthShell>
      <div className="login-card oauth-card">
        <OAuthBrand />
        <h1 className="oauth-heading">{consentTitle(context)}</h1>
        <dl className="oauth-meta">
          {clientMeta(context).map((item) => (
            <Fragment key={item.label}>
              <dt>{item.label}</dt>
              <dd>{item.value}</dd>
            </Fragment>
          ))}
        </dl>

        <fieldset className="oauth-fieldset">
          <legend className="oauth-section-title">请求的权限</legend>
          {context.scopes.length === 0 ? (
            <Alert kind="warning" title="没有可授予的权限">
              本次请求未包含任何权限范围，只能拒绝。
            </Alert>
          ) : (
            <>
              <p className="oauth-section-hint">申请范围：{scopeSummary(context.scopes)}</p>
              <ul className="oauth-scope-list">
                {context.scopes.map((scope, index) => (
                  <li key={scope.value}>
                    <label className="oauth-scope" htmlFor={`${groupId}-${index}`}>
                      <input
                        id={`${groupId}-${index}`}
                        type="checkbox"
                        checked={selectedSet.has(scope.value)}
                        disabled={grantedSet.has(scope.value)}
                        onChange={() => toggle(scope.value)}
                      />
                      <span className="oauth-scope-body">
                        <span className="oauth-scope-label">
                          {scope.label}
                          {grantedSet.has(scope.value) ? <span className="oauth-scope-badge">已授权</span> : null}
                        </span>
                        <span className="oauth-scope-desc">{scope.description}</span>
                        <code className="oauth-scope-value">{scope.value}</code>
                      </span>
                    </label>
                  </li>
                ))}
              </ul>
            </>
          )}
        </fieldset>

        <section className="oauth-section">
          <h2 className="oauth-section-title">回调目标</h2>
          <code className="oauth-callback">{context.redirectUri}</code>
          <p className="oauth-section-hint">授权结果只会发送到这个经核验的地址。</p>
        </section>

        <section className="oauth-section">
          <h2 className="oauth-section-title">有效期</h2>
          <p className="oauth-policy">{accessPolicyText(context)}</p>
          <p className="oauth-policy">{refreshPolicyText(context)}</p>
        </section>

        <div className="oauth-actions">
          <form method="post" action={context.denyAction} onSubmit={() => setSubmitting('deny')} aria-label="拒绝授权">
            <HiddenFields fields={deny} />
            <Button htmlType="submit" disabled={submitting !== null}>
              {submitting === 'deny' ? '正在拒绝…' : '拒绝'}
            </Button>
          </form>
          <form method="post" action={context.action} onSubmit={() => setSubmitting('allow')} aria-label="允许授权">
            <HiddenFields fields={allow} />
            <Button type="primary" htmlType="submit" disabled={!canAllow || submitting !== null}>
              {submitting === 'allow' ? '正在授权…' : '允许'}
            </Button>
          </form>
        </div>
        <p className="login-footnote">已授权的权限会保留且不可在此取消，本次只调整新增权限；授权后可在平台侧撤销。</p>
      </div>
    </OAuthShell>
  );
}
