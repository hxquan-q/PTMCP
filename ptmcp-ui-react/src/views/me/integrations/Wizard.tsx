import { useEffect, useRef, useState } from 'react';
import { Button } from 'antd';
import { Alert, OptionCard, PageHeader, RequestIdText, Stepper } from '../../../components/common';
import { ArrowLeftIcon, ArrowRightIcon, CheckIcon, CopyIcon, DesktopIcon } from '../../../components/Icons';
import { mePortalApi, type McpConfig, type PlatformItem } from '../../../services/mePortalApi';
import { readFailure } from '../../../services/http';
import { authAvailability, preferredChoice, templatesFromConfig, transportAvailability, wizardCanAdvance } from '../mcpTemplate';
import { AUTHS, CLIENT_TYPES, DONE_STEPS, STEPS, TRANSPORTS } from './shared';

export function Wizard({ onExit }: { onExit: () => void }) {
  const [step, setStep] = useState(0);
  const [platform, setPlatform] = useState('');
  const [client, setClient] = useState('GENERIC');
  const [transport, setTransport] = useState('streamable-http');
  const [auth, setAuth] = useState('oauth-pat');
  const [format, setFormat] = useState<'json' | 'yaml'>('json');
  const [copied, setCopied] = useState<'ok' | 'fail' | null>(null);
  const [done, setDone] = useState(false);
  const [platformItems, setPlatformItems] = useState<PlatformItem[]>([]);
  const [platformCursor, setPlatformCursor] = useState<string | null>(null);
  const [platformLoading, setPlatformLoading] = useState(true);
  const [platformError, setPlatformError] = useState<{ message: string; requestId: string } | null>(null);
  const [mcpCfg, setMcpCfg] = useState<McpConfig | null>(null);
  const [cfgLoading, setCfgLoading] = useState(false);
  const [cfgError, setCfgError] = useState<{ message: string; requestId: string } | null>(null);
  const [cfgRetry, setCfgRetry] = useState(0);
  const stepRef = useRef<HTMLDivElement>(null);

  const platformMeta = platformItems.find((p) => p.id === platform) ?? null;
  const clientMeta = CLIENT_TYPES.find((c) => c.value === client)!;
  const authMeta = AUTHS.find((a) => a.value === auth);

  /* 平台目录来自 me/access-context?level=PLATFORM：只用真实 platformId，不按 tier 猜或回退。 */
  useEffect(() => {
    let cancelled = false;
    setPlatformLoading(true);
    setPlatformError(null);
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => {
        if (cancelled) return;
        setPlatformItems(ctx.items);
        setPlatformCursor(ctx.nextCursor);
      })
      .catch((error: unknown) => !cancelled && setPlatformError(readFailure(error, '平台目录加载失败')))
      .finally(() => !cancelled && setPlatformLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const loadMorePlatforms = () => {
    if (!platformCursor || platformLoading) return;
    setPlatformLoading(true);
    setPlatformError(null);
    mePortalApi.accessContext
      .platforms(platformCursor)
      .then((ctx) => {
        setPlatformItems((prev) => [...prev, ...ctx.items]);
        setPlatformCursor(ctx.nextCursor);
      })
      .catch((error: unknown) => setPlatformError(readFailure(error, '平台目录加载失败')))
      .finally(() => setPlatformLoading(false));
  };

  /* 进入连接方式/模板步骤后，用所选真实 platformId + clientType 拉 mcp-config；无平台不发请求。 */
  useEffect(() => {
    if (step < 2 || !platform || mcpCfg) return;
    let cancelled = false;
    setCfgLoading(true);
    setCfgError(null);
    mePortalApi
      .mcpConfig(platform, client)
      .then((cfg) => !cancelled && setMcpCfg(cfg))
      .catch((error: unknown) => !cancelled && setCfgError(readFailure(error, '服务端模板获取失败')))
      .finally(() => !cancelled && setCfgLoading(false));
    return () => {
      cancelled = true;
    };
  }, [step, platform, client, mcpCfg, cfgRetry]);

  /* 换平台/客户端后旧配置作废并重新拉取；已选平台与客户端本身保留，便于按 requestId 排查后原地重试。 */
  const choosePlatform = (id: string) => {
    setPlatform(id);
    setMcpCfg(null);
    setCfgError(null);
  };
  const chooseClient = (id: string) => {
    setClient(id);
    setMcpCfg(null);
    setCfgError(null);
  };

  /* 只用服务端模板；解析失败视为错误，不回退示例模板。 */
  const templates = mcpCfg ? templatesFromConfig(mcpCfg.configTemplate) : null;
  const template = templates ? templates[format] : '';
  const templateReady = !!mcpCfg && !!templates && !cfgError;
  const authAllowed = authAvailability(mcpCfg?.authentication);
  const transportAllowed = transportAvailability(mcpCfg?.transport);
  // 只有服务端能力已成功返回时才展示“不支持”；加载/失败期间保持不可选，避免把未知当成不支持或支持。
  const authKnown = !!mcpCfg && !cfgError;
  /* 服务端未返回部署地址时按“待配置”呈现，不伪造可连通的 URL。 */
  const serverUrl = mcpCfg?.url?.trim() ?? '';

  /* 能力返回后，把仍停在未声明选项上的选择拨到服务端允许的第一项；没有允许项则清空并挡住下一步。 */
  useEffect(() => {
    if (!authKnown) return;
    setTransport((current) =>
      transportAllowed[current as 'streamable-http' | 'stdio'] ? current : preferredChoice(transportAllowed, ['streamable-http', 'stdio']),
    );
    setAuth((current) =>
      authAllowed[current as 'oauth-pat' | 'apikey' | 'none'] ? current : preferredChoice(authAllowed, ['oauth-pat', 'apikey', 'none']),
    );
  }, [authKnown, mcpCfg]);

  const canNext = wizardCanAdvance(step, {
    platform,
    client,
    transport,
    auth,
    transports: transportAllowed,
    auths: authAllowed,
    authKnown,
    templateReady,
  });

  /* 步骤切换后把焦点交给该步标题，键盘与读屏用户才知道内容已换。 */
  useEffect(() => {
    stepRef.current?.focus();
  }, [step, done]);

  const copy = () => {
    if (!template) return;
    const write = navigator.clipboard?.writeText(template);
    if (!write) {
      setCopied('fail');
      return;
    }
    write.then(
      () => setCopied('ok'),
      () => setCopied('fail'),
    );
    setTimeout(() => setCopied(null), 2000);
  };

  if (done) {
    return (
      <>
        <PageHeader title="新建 MCP 接入配置" description="已按所选平台与客户端生成配置模板；实际接入由客户端握手验证。" />
        <div className="table-card" style={{ maxWidth: 900, margin: '0 auto', padding: '32px 36px' }}>
          <div style={{ textAlign: 'center', marginBottom: 22 }}>
            <span className="done-badge">
              <CheckIcon />
            </span>
            <h2 style={{ margin: 0, fontSize: 20, color: 'var(--ptmcp-heading)' }}>配置模板已生成</h2>
            <p style={{ margin: '8px auto 0', fontSize: 13, color: 'var(--ptmcp-text-muted)', maxWidth: 520 }}>
              模板来自服务端按平台与客户端生成的配置；本页面不代表已接入，是否连通需由客户端完成 MCP 握手后验证。
            </p>
          </div>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'center', marginBottom: 22, flexWrap: 'wrap' }}>
            <Button type="primary" onClick={copy} disabled={!template}>
              <CopyIcon /> {copied === 'ok' ? '已复制到剪贴板' : '复制配置'}
            </Button>
            <Button onClick={() => (window.location.hash = '/me/tokens')}>
              <ArrowRightIcon /> 前往创建个人令牌
            </Button>
          </div>
          {copied === 'fail' ? (
            <Alert kind="warning" title="复制失败">
              当前浏览器不允许访问剪贴板。请选中下方模板文本后手动复制（Ctrl/⌘ + C）。
            </Alert>
          ) : null}
          {!serverUrl ? (
            <Alert kind="warning" title="服务端地址待配置">
              该平台的 mcp-config 未返回部署地址（url 为空）。模板中的连接地址可能不完整，请先与管理员确认网关地址后再在客户端使用。
            </Alert>
          ) : null}

          <div
            className="summary-grid"
            style={{ padding: '16px 0', borderTop: '1px solid var(--ptmcp-border)', borderBottom: '1px solid var(--ptmcp-border)' }}
          >
            {[
              ['平台', platformMeta ? `${platformMeta.name} · Tier ${platformMeta.tier}` : '—'],
              ['平台 ID', platform || '—'],
              ['客户端', clientMeta.label],
              ['传输方式', transport],
              ['服务端地址', serverUrl || '待配置'],
              ['认证方式', authMeta?.label ?? '—'],
              ['状态', '待验证（模板已生成）'],
              [
                '兼容性',
                mcpCfg?.compatibilityStatus === 'UNVERIFIED'
                  ? '尚未验证'
                  : mcpCfg?.validatedVersion
                    ? `已验证 ${mcpCfg.validatedVersion}`
                    : '未知版本',
              ],
            ].map(([k, v]) => (
              <div className="summary-item" key={k}>
                <div className="k">{k}</div>
                <div className="v">{v}</div>
              </div>
            ))}
          </div>

          <div className="next-steps" style={{ marginTop: 18 }}>
            {DONE_STEPS.map((s, i) => (
              <div key={s.title} style={{ display: 'flex', gap: 8 }}>
                <span className="step-dot">{i + 1}</span>
                <div style={{ minWidth: 0 }}>
                  <div style={{ fontWeight: 600, color: 'var(--ptmcp-heading)' }}>{s.title}</div>
                  <div style={{ color: 'var(--ptmcp-text-muted)', fontSize: 12, marginTop: 4 }}>
                    {s.desc}
                    {s.linkLabel ? (
                      <>
                        {'：'}
                        <a className="link" href={`#${s.linkHash}`}>
                          {s.linkLabel}
                        </a>
                      </>
                    ) : null}
                  </div>
                </div>
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 24, flexWrap: 'wrap' }}>
            <Button onClick={onExit}>查看我的接入</Button>
            <Button
              type="primary"
              onClick={() => {
                setDone(false);
                setStep(0);
              }}
            >
              配置另一个接入
            </Button>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader title="新建 MCP 接入配置" description="通过以下步骤完成配置。模板生成后仍需在客户端完成握手，本页不代表已经接入。" />
      <Stepper steps={STEPS} current={step} />
      <p className="sr-only" aria-live="polite">
        第 {step + 1} 步，共 {STEPS.length} 步：{STEPS[step]}
      </p>

      <div className="table-card wizard-card" ref={stepRef} tabIndex={-1}>
        {step === 0 && (
          <>
            <StepHeading n={1} title="选择平台" desc="选择要接入的平台环境；列表只包含当前账号可见的授权范围" />
            {platformLoading && platformItems.length === 0 ? (
              <p style={{ margin: '0 0 8px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>正在加载平台目录…</p>
            ) : null}
            {platformError ? (
              <Alert kind="danger" title="平台目录加载失败">
                {platformError.message}
                {platformError.requestId ? <RequestIdText requestId={platformError.requestId} /> : null}
              </Alert>
            ) : null}
            {!platformLoading && !platformError && platformItems.length === 0 ? (
              <Alert kind="warning" title="没有可用平台">
                当前账号没有可见的平台，无法生成 MCP 接入配置。请联系管理员开通平台授权后重试。
              </Alert>
            ) : null}
            <div className="option-grid option-grid--platforms">
              {' '}
              {platformItems.map((p) => (
                <OptionCard
                  key={p.id}
                  icon={DesktopIcon}
                  title={`${p.name} · Tier ${p.tier}`}
                  description={`目录模式 ${p.catalogMode} · 平台 ID ${p.id}`}
                  selected={platform === p.id}
                  onSelect={() => choosePlatform(p.id)}
                />
              ))}
            </div>
            {platformCursor ? (
              <Button style={{ marginTop: 10 }} onClick={loadMorePlatforms} disabled={platformLoading}>
                {platformLoading ? '加载中…' : '加载更多平台'}
              </Button>
            ) : null}
            <Alert kind="info" title="平台说明">
              不同平台的资源隔离、配额和访问策略不同，请根据实际使用场景选择合适的平台环境。
            </Alert>
          </>
        )}

        {step === 1 && (
          <>
            <StepHeading n={2} title="选择客户端" desc="选择要使用的 MCP 客户端工具，模板按客户端类型生成" />
            <div className="option-grid option-grid--clients">
              {CLIENT_TYPES.map((c) => (
                <OptionCard
                  key={c.value}
                  icon={c.icon}
                  title={c.label}
                  description={c.desc}
                  selected={client === c.value}
                  onSelect={() => chooseClient(c.value)}
                />
              ))}
            </div>
          </>
        )}

        {step === 2 && (
          <>
            <StepHeading n={3} title="连接方式与认证" desc="选择传输与认证方式；只有平台声明支持的组合才能继续" />
            <div className="option-grid option-grid--wide" style={{ gap: 24 }}>
              <div>
                <h4 style={{ margin: '0 0 10px', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>传输方式</h4>
                <div style={{ display: 'grid', gap: 12 }}>
                  {TRANSPORTS.map((t) => (
                    <OptionCard
                      key={t.value}
                      icon={t.icon}
                      title={t.label}
                      description={t.desc}
                      tag={authKnown && !transportAllowed[t.value] ? '该平台不支持' : undefined}
                      selected={transport === t.value}
                      onSelect={() => authKnown && transportAllowed[t.value] && setTransport(t.value)}
                    />
                  ))}
                </div>
              </div>
              <div>
                <h4 style={{ margin: '0 0 10px', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>认证方式</h4>
                <div style={{ display: 'grid', gap: 12 }}>
                  {AUTHS.map((a) => (
                    <OptionCard
                      key={a.value}
                      icon={a.icon}
                      title={a.label}
                      description={a.desc}
                      tag={authKnown && !authAllowed[a.value] ? '该平台不支持' : undefined}
                      selected={auth === a.value}
                      onSelect={() => authKnown && authAllowed[a.value] && setAuth(a.value)}
                    />
                  ))}
                </div>
              </div>
            </div>
            {mcpCfg ? (
              <div className="server-capabilities">
                <div className="summary-item">
                  <div className="k">服务端 transport</div>
                  <div className="v">
                    <code>{mcpCfg.transport || '未返回'}</code>
                  </div>
                </div>
                <div className="summary-item">
                  <div className="k">服务端地址</div>
                  <div className="v">{serverUrl ? <code>{serverUrl}</code> : '待配置（未返回部署地址）'}</div>
                </div>
                <div className="summary-item">
                  <div className="k">服务端 authentication</div>
                  <div className="v">{mcpCfg.authentication?.length ? mcpCfg.authentication.join(' / ') : '未返回（按不支持处理）'}</div>
                </div>
                <div className="summary-item">
                  <div className="k">兼容性</div>
                  <div className="v">
                    {mcpCfg.compatibilityStatus === 'UNVERIFIED'
                      ? '尚未验证'
                      : mcpCfg.validatedVersion
                        ? `已验证 ${mcpCfg.validatedVersion}`
                        : '未知版本'}
                  </div>
                </div>
              </div>
            ) : null}
            {cfgLoading ? <Alert kind="info">正在读取该平台的 MCP 能力与配置模板…</Alert> : null}
            {cfgError ? (
              <Alert kind="danger" title="MCP 能力获取失败">
                {cfgError.message}。未取得服务端能力时不能继续（fail-closed）；已选平台与客户端保持不变。
                <button className="link" style={{ marginLeft: 8 }} onClick={() => setCfgRetry((n) => n + 1)}>
                  重试
                </button>
                {cfgError.requestId ? <RequestIdText requestId={cfgError.requestId} /> : null}
              </Alert>
            ) : null}
            {!cfgLoading && !cfgError && mcpCfg && !Object.values(authAllowed).some(Boolean) ? (
              <Alert kind="warning" title="认证能力未确认">
                服务端未返回可用的认证方式，无法生成 MCP 配置。请返回上一步确认平台与客户端，或联系管理员补全认证元数据。
              </Alert>
            ) : null}
            {!cfgLoading && !cfgError && mcpCfg && !Object.values(transportAllowed).some(Boolean) ? (
              <Alert kind="warning" title="传输方式未确认">
                服务端返回的 transport 不是 streamable-http 或 stdio，无法继续。请联系管理员核对网关传输声明。
              </Alert>
            ) : null}
          </>
        )}

        {step === 3 && (
          <>
            <StepHeading n={4} title="获取配置模板" desc="复制下方模板到客户端配置文件，并替换访问令牌占位符" />
            {cfgLoading ? (
              <p style={{ margin: '0 0 8px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>正在从服务端获取当前平台/客户端的配置模板…</p>
            ) : null}
            {cfgError ? (
              <Alert kind="danger" title="模板获取失败">
                {cfgError.message}。未取得服务端模板时不能复制或完成配置，请返回连接方式步骤点击重试。
                {cfgError.requestId ? <RequestIdText requestId={cfgError.requestId} /> : null}
              </Alert>
            ) : null}
            {!cfgLoading && !cfgError && mcpCfg && !templates ? (
              <Alert kind="danger" title="模板解析失败">
                服务端返回的 configTemplate 不是合法 JSON，无法生成客户端配置；请返回上一步重试或联系管理员。
              </Alert>
            ) : null}
            {!cfgLoading && mcpCfg ? (
              <p style={{ margin: '0 0 8px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
                模板由服务端按所选平台与客户端生成；验证版本：
                {mcpCfg.compatibilityStatus === 'UNVERIFIED' ? '尚未验证（PAT 仅作为占位符使用）' : (mcpCfg.validatedVersion ?? '未知')}。
              </p>
            ) : null}
            {!serverUrl && mcpCfg ? (
              <Alert kind="warning" title="服务端地址待配置">
                该平台的 mcp-config 未返回部署地址，模板中的连接地址可能不完整，请先与管理员确认网关地址。
              </Alert>
            ) : null}
            <Alert kind="warning" title="模板包含凭证占位符">
              使用 PAT 时，先创建 PAT，再在客户端配置中替换凭证占位符；使用 OAuth 时由客户端完成授权。完整 PAT 只在创建响应中出现一次，不会写入模板。
              <button className="link" style={{ marginLeft: 6 }} onClick={() => (window.location.hash = '/me/tokens')}>
                前往创建 PAT →
              </button>
            </Alert>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap', margin: '14px 0 8px' }}>
              <div className="seg-group" role="radiogroup" aria-label="模板格式">
                {(['json', 'yaml'] as const).map((f) => (
                  <label key={f} className={`seg-item${format === f ? ' is-active' : ''}`}>
                    <input type="radio" name="mcp-template-format" value={f} checked={format === f} onChange={() => setFormat(f)} />
                    {f.toUpperCase()}
                    {f === 'json' ? '（推荐）' : ''}
                  </label>
                ))}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span aria-live="polite" style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
                  {copied === 'ok' ? '已复制到剪贴板' : copied === 'fail' ? '复制失败，请手动选中复制' : ''}
                </span>
                <Button onClick={copy} disabled={!template}>
                  <CopyIcon /> 复制模板
                </Button>
              </div>
            </div>
            {template ? (
              <pre className="code-block">
                {template.split('\n').map((line, i) => (
                  <span className="code-line" key={i}>
                    <span className="code-gutter">{i + 1}</span>
                    <span className="code-text">{line}</span>
                  </span>
                ))}
              </pre>
            ) : (
              <p style={{ margin: 0, fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>
                尚未取得服务端模板；取得前不展示任何示例配置，复制与完成均不可用。
              </p>
            )}
          </>
        )}

        {/* footer buttons */}
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            gap: 10,
            marginTop: 22,
            paddingTop: 16,
            borderTop: '1px solid var(--ptmcp-border)',
          }}
        >
          <Button onClick={() => (step === 0 ? onExit() : setStep(step - 1))}>
            <ArrowLeftIcon /> 上一步
          </Button>
          {step < STEPS.length - 1 ? (
            <Button type="primary" disabled={!canNext} onClick={() => setStep(step + 1)}>
              下一步 <ArrowRightIcon />
            </Button>
          ) : (
            <Button
              type="primary"
              disabled={!templateReady}
              title={templateReady ? undefined : '未取得服务端模板，不能完成配置'}
              onClick={() => setDone(true)}
            >
              完成并查看后续步骤 <ArrowRightIcon />
            </Button>
          )}
        </div>
      </div>
    </>
  );
}

/** 失败时给出可排查的服务端 requestId（P-13），只读展示，不复制凭证。 */
function StepHeading({ n, title, desc }: { n: number; title: string; desc: string }) {
  return (
    <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', marginBottom: 16 }}>
      <span
        style={{
          flex: 'none',
          display: 'inline-flex',
          width: 24,
          height: 24,
          borderRadius: 'var(--ptmcp-radius-circle)',
          background: 'var(--ptmcp-blue)',
          color: '#fff',
          fontSize: 12.5,
          fontWeight: 700,
          alignItems: 'center',
          justifyContent: 'center',
          marginTop: 2,
        }}
      >
        {n}
      </span>
      <div>
        <h3 style={{ margin: 0, fontSize: 15, color: 'var(--ptmcp-heading)' }}>{title}</h3>
        <p style={{ margin: '3px 0 0', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>{desc}</p>
      </div>
    </div>
  );
}
