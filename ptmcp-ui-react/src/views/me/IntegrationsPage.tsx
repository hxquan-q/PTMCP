import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  CellIcon,
  Drawer,
  DrawerFields,
  EmptyState,
  OptionCard,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatCard,
  StatusDot,
  Stepper,
  useDebounced,
} from '../../components/common';
import {
  ArrowLeftIcon,
  ArrowRightIcon,
  BoltIcon,
  CheckIcon,
  CodeIcon,
  CopyIcon,
  DesktopIcon,
  DocumentIcon,
  GearIcon,
  InfoIcon,
  MonitorIcon,
  PlusIcon,
  PuzzleIcon,
  RefreshIcon,
  ShieldIcon,
  StreamIcon,
  WarningIcon,
  type IconComponent,
} from '../../components/Icons';
import { mePortalApi, type McpConfig, type PlatformItem } from '../../services/mePortalApi';
import { frontendPreview } from '../../services/frontendMode';
import { authAvailability, templatesFromConfig } from './mcpTemplate';
import { testDatasource } from '../../services/access';

/* ─── 视图模型 ─── */
interface IntegrationRow {
  id: string;
  name: string;
  code: string;
  platform: string;
  client: string;
  clientIcon: IconComponent;
  transport: string;
  auth: string;
  status: '已生效' | '配置中' | '未验证' | '草稿' | '异常';
  tone: 'success' | 'warning' | 'danger' | 'neutral';
  createdAt: string;
  updatedAt: string;
}

/** 仅合成预览模式展示的样例接入记录；正常模式没有对应服务端列表接口，必须留空。 */
const PREVIEW_INTEGRATIONS: IntegrationRow[] = [
  {
    id: '1',
    name: '个人开发环境',
    code: 'dev-mcp-01',
    platform: '合成桌面平台 · Tier 3',
    client: 'Cursor',
    clientIcon: CodeIcon,
    transport: 'streamable-http',
    auth: 'OAuth / PAT',
    status: '已生效',
    tone: 'success',
    createdAt: '2026-10-29 16:08',
    updatedAt: '2026-10-29 16:28',
  },
  {
    id: '2',
    name: '数据分析助手',
    code: 'analytics-mcp',
    platform: '合成桌面平台 · Tier 3',
    client: 'Claude Code',
    clientIcon: GearIcon,
    transport: 'streamable-http',
    auth: 'OAuth / PAT',
    status: '未验证',
    tone: 'warning',
    createdAt: '2026-10-28 14:22',
    updatedAt: '2026-10-28 14:22',
  },
  {
    id: '3',
    name: '本地测试',
    code: 'local-mcp',
    platform: '合成桌面平台 · Tier 3',
    client: '通用客户端',
    clientIcon: PuzzleIcon,
    transport: 'streamable-http',
    auth: 'OAuth / PAT',
    status: '已生效',
    tone: 'success',
    createdAt: '2026-10-25 11:09',
    updatedAt: '2026-10-25 11:09',
  },
  {
    id: '4',
    name: '研发客户端',
    code: 'dev-mcp-02',
    platform: '合成桌面平台 · Tier 3',
    client: 'VS Code',
    clientIcon: MonitorIcon,
    transport: 'streamable-http',
    auth: 'OAuth',
    status: '配置中',
    tone: 'warning',
    createdAt: '2026-10-27 11:04',
    updatedAt: '2026-10-27 11:04',
  },
  {
    id: '5',
    name: '示例配置',
    code: 'example-mcp',
    platform: '通用客户端',
    client: '通用客户端',
    clientIcon: PuzzleIcon,
    transport: 'streamable-http',
    auth: 'PAT',
    status: '草稿',
    tone: 'neutral',
    createdAt: '2026-10-24 15:33',
    updatedAt: '2026-10-24 15:33',
  },
  {
    id: '6',
    name: '生产环境',
    code: 'prod-mcp',
    platform: '合成桌面平台 · Tier 3',
    client: 'WorkBuddy',
    clientIcon: BoltIcon,
    transport: 'streamable-http',
    auth: 'OAuth',
    status: '已生效',
    tone: 'success',
    createdAt: '2026-10-20 10:45',
    updatedAt: '2026-10-20 10:45',
  },
];

const CLIENT_TYPES: { value: string; label: string; desc: string; icon: IconComponent }[] = [
  { value: 'GENERIC', label: '通用客户端', desc: '适用于任何支持 MCP 的客户端', icon: PuzzleIcon },
  { value: 'WORKBUDDY', label: 'WorkBuddy', desc: '企业级 AI 助手', icon: BoltIcon },
  { value: 'CURSOR', label: 'Cursor', desc: 'AI 编程助手', icon: CodeIcon },
  { value: 'CLAUDE_CODE', label: 'Claude Code', desc: 'Anthropic 开发工具', icon: GearIcon },
  { value: 'VSCODE', label: 'VS Code', desc: 'Visual Studio Code', icon: MonitorIcon },
];

/** 服务端 me/mcp-config 只返回 streamable-http 与 oauth/pat，UI 不再展示其他选项。 */
const TRANSPORTS = [{ value: 'streamable-http', label: 'streamable-http', desc: '通过 HTTP 流式连接 MCP 服务端', icon: StreamIcon }];
const AUTHS = [{ value: 'oauth-pat', label: 'OAuth / PAT', desc: '推荐使用，支持个人访问令牌（PAT）', icon: ShieldIcon }];

const PLATFORM_FILTER_OPTIONS = [
  { value: '', label: '全部平台' },
  { value: 'T3', label: '合成桌面平台 · Tier 3' },
  { value: 'OTHER', label: '其他平台' },
];
const STATUS_FILTER_OPTIONS = [
  { value: '', label: '全部状态' },
  { value: '已生效', label: '已生效' },
  { value: '未验证', label: '未验证' },
  { value: '配置中', label: '配置中' },
  { value: '草稿', label: '草稿' },
  { value: '异常', label: '异常' },
];

const STEPS = ['选择平台', '选择客户端', '连接方式与认证', '获取配置模板'];

export function IntegrationsPage() {
  const [mode, setMode] = useState<'list' | 'wizard'>('list');
  return mode === 'list' ? <IntegrationList onCreate={() => setMode('wizard')} /> : <Wizard onExit={() => setMode('list')} />;
}

/* ═══════════ 列表视图 ═══════════ */
function IntegrationList({ onCreate }: { onCreate: () => void }) {
  /* 正常模式没有服务端「我的接入」列表接口：不得展示样例数据，只保留空态与新建入口。 */
  const integrations = frontendPreview ? PREVIEW_INTEGRATIONS : [];
  const [tab, setTab] = useState('all');
  const [keyword, setKeyword] = useState('');
  const [platformFilter, setPlatformFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [page, setPage] = useState(1);
  const [detail, setDetail] = useState<IntegrationRow | null>(null);
  const debounced = useDebounced(keyword);

  const stats = useMemo(
    () => [
      { icon: DocumentIcon, value: integrations.length, label: '全部接入', hint: '配置总量', tone: undefined },
      {
        icon: CheckIcon,
        value: integrations.filter((r) => r.status === '已生效').length,
        label: '已启用',
        hint: '可正常使用',
        tone: 'success' as const,
      },
      {
        icon: WarningIcon,
        value: integrations.filter((r) => r.status === '未验证' || r.status === '配置中').length,
        label: '待验证',
        hint: '需要完成验证',
        tone: 'warning' as const,
      },
      {
        icon: InfoIcon,
        value: integrations.filter((r) => r.status === '异常').length,
        label: '异常',
        hint: '连接或认证失败',
        tone: 'danger' as const,
      },
    ],
    [],
  );
  const tabs = [
    { key: 'all', label: `全部 (${integrations.length})` },
    { key: 'enabled', label: `已启用 (${integrations.filter((r) => r.status === '已生效').length})` },
    { key: 'pending', label: `待验证 (${integrations.filter((r) => r.status === '未验证' || r.status === '配置中').length})` },
    { key: 'error', label: `异常 (${integrations.filter((r) => r.status === '异常').length})` },
  ];
  const rows = useMemo(() => {
    let list = integrations;
    if (tab === 'enabled') list = list.filter((r) => r.status === '已生效');
    // 待验证口径与统计卡一致：未验证 + 配置中（已配置但尚未生效）
    if (tab === 'pending') list = list.filter((r) => r.status === '未验证' || r.status === '配置中');
    if (tab === 'error') list = list.filter((r) => r.status === '异常');
    if (platformFilter) list = list.filter((r) => (platformFilter === 'T3' ? r.platform.includes('Tier 3') : !r.platform.includes('Tier')));
    if (statusFilter) list = list.filter((r) => r.status === statusFilter);
    const kw = debounced.trim();
    if (kw) list = list.filter((r) => `${r.name}${r.code}${r.client}${r.platform}`.toLowerCase().includes(kw.toLowerCase()));
    return list;
  }, [tab, debounced, platformFilter, statusFilter]);
  const pageSize = 20;
  const pageRows = rows.slice((page - 1) * pageSize, page * pageSize);

  return (
    <>
      <PageHeader
        title="MCP 接入配置"
        description="配置并管理本人的 MCP 客户端接入，快速获取配置模板并完成接入。"
        actions={
          <button className="btn btn--primary" onClick={onCreate}>
            <PlusIcon /> 新建接入
          </button>
        }
      />
      {/* 统计卡 */}
      <div className="stat-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14, marginBottom: 14 }}>
        {stats.map((s) => (
          <StatCard key={s.label} icon={s.icon} value={s.value} label={s.label} hint={s.hint} tone={s.tone} />
        ))}
      </div>

      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        {/* pill tabs */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '12px 14px',
            borderBottom: '1px solid var(--ptmcp-border)',
            flexWrap: 'wrap',
          }}
        >
          <div
            role="tablist"
            className="pill-tabs"
            style={{
              display: 'inline-flex',
              gap: 6,
              padding: 3,
              borderRadius: 999,
              background: 'var(--ptmcp-canvas)',
              border: '1px solid var(--ptmcp-border)',
            }}
          >
            {tabs.map((t) => {
              const active = tab === t.key;
              return (
                <button
                  key={t.key}
                  role="tab"
                  aria-selected={active}
                  onClick={() => {
                    setTab(t.key);
                    setPage(1);
                  }}
                  style={{
                    appearance: 'none',
                    border: 'none',
                    padding: '6px 14px',
                    borderRadius: 999,
                    fontSize: 13,
                    fontWeight: active ? 700 : 500,
                    color: active ? '#fff' : 'var(--ptmcp-text-muted)',
                    background: active ? 'var(--ptmcp-blue)' : 'transparent',
                    cursor: 'pointer',
                  }}
                >
                  {t.label}
                </button>
              );
            })}
          </div>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 10 }}>
            <SearchInput
              value={keyword}
              onChange={(v) => {
                setKeyword(v);
                setPage(1);
              }}
              placeholder="搜索接入名称或客户端类型…"
              width={220}
            />
            <Select
              value={platformFilter}
              onChange={(v) => {
                setPlatformFilter(v);
                setPage(1);
              }}
              width={130}
              options={PLATFORM_FILTER_OPTIONS}
            />
            <Select
              value={statusFilter}
              onChange={(v) => {
                setStatusFilter(v);
                setPage(1);
              }}
              width={120}
              options={STATUS_FILTER_OPTIONS}
            />
            <button
              className="btn btn--secondary"
              onClick={() => {
                setKeyword('');
                setPlatformFilter('');
                setStatusFilter('');
              }}
            >
              重置
            </button>
          </div>
        </div>

        <div className="table-scroll">
          <table className="data-table">
            <thead>
              <tr>
                <th>接入名称</th>
                <th>平台</th>
                <th>客户端类型</th>
                <th>传输方式</th>
                <th>认证方式</th>
                <th>状态</th>
                <th>创建时间</th>
                <th style={{ width: 90 }}>操作</th>
              </tr>
            </thead>
            <tbody>
              {pageRows.map((r) => (
                <tr key={r.id}>
                  <td>
                    <span className="cell-lead">
                      <CellIcon icon={r.clientIcon} />
                      <button className="link" onClick={() => setDetail(r)}>
                        {r.name}
                      </button>
                    </span>
                  </td>
                  <td>{r.platform}</td>
                  <td>{r.client}</td>
                  <td>
                    <code style={{ fontSize: 12 }}>{r.transport}</code>
                  </td>
                  <td>{r.auth}</td>
                  <td>
                    <StatusDot tone={r.tone}>{r.status}</StatusDot>
                  </td>
                  <td>{r.createdAt}</td>
                  <td>
                    <button className="link" onClick={() => setDetail(r)}>
                      {r.status === '配置中' || r.status === '未验证' ? '继续配置' : '查看'}
                    </button>
                  </td>
                </tr>
              ))}
              {pageRows.length === 0 ? (
                <tr>
                  <td colSpan={8}>
                    <EmptyState
                      title="暂无接入配置"
                      description={
                        frontendPreview
                          ? '点击右上角「新建接入」开始配置'
                          : '当前没有服务端登记的接入记录。点击「新建接入」，按平台目录生成真实客户端配置模板。'
                      }
                    />
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
        <div className="table-footer">
          <span style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>共 {rows.length} 条</span>
          <Pagination total={rows.length} page={page} pageSize={pageSize} onPage={setPage} />
        </div>
      </div>

      <Drawer open={!!detail} title={detail?.name ?? ''} onClose={() => setDetail(null)} width={420}>
        {detail ? (
          <DrawerFields
            fields={[
              { label: '接入名称', value: detail.name },
              { label: '平台', value: detail.platform },
              { label: '客户端类型', value: detail.client },
              { label: '传输方式', value: detail.transport },
              { label: '认证方式', value: detail.auth },
              { label: '状态', value: <StatusDot tone={detail.tone}>{detail.status}</StatusDot> },
              { label: '创建时间', value: detail.createdAt },
              { label: '更新时间', value: detail.updatedAt },
            ]}
          />
        ) : null}
      </Drawer>
    </>
  );
}

/* ═══════════ 向导视图（4 步 + 完成页） ═══════════ */
function Wizard({ onExit }: { onExit: () => void }) {
  const [step, setStep] = useState(0);
  const [platform, setPlatform] = useState('');
  const [client, setClient] = useState('GENERIC');
  const [transport, setTransport] = useState('streamable-http');
  const [auth, setAuth] = useState('oauth-pat');
  const [name, setName] = useState('');
  const [format, setFormat] = useState<'json' | 'yaml'>('json');
  const [copied, setCopied] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<'demo-ok' | 'fail' | null>(null);
  const [done, setDone] = useState(false);
  const [platformItems, setPlatformItems] = useState<PlatformItem[]>([]);
  const [platformCursor, setPlatformCursor] = useState<string | null>(null);
  const [platformLoading, setPlatformLoading] = useState(true);
  const [platformError, setPlatformError] = useState('');
  const [mcpCfg, setMcpCfg] = useState<McpConfig | null>(null);
  const [cfgLoading, setCfgLoading] = useState(false);
  const [cfgError, setCfgError] = useState('');
  const [cfgRetry, setCfgRetry] = useState(0);

  const platformMeta = platformItems.find((p) => p.id === platform) ?? null;
  const clientMeta = CLIENT_TYPES.find((c) => c.value === client)!;
  const authMeta = AUTHS.find((a) => a.value === auth)!;
  const inferredName = name || `${clientMeta.label}接入`;

  /* 平台目录来自 me/access-context?level=PLATFORM：只用真实 platformId，不按 tier 猜或回退。 */
  useEffect(() => {
    let cancelled = false;
    setPlatformLoading(true);
    setPlatformError('');
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => {
        if (cancelled) return;
        setPlatformItems(ctx.items);
        setPlatformCursor(ctx.nextCursor);
      })
      .catch((error: unknown) => !cancelled && setPlatformError((error as Error).message || '平台目录加载失败'))
      .finally(() => !cancelled && setPlatformLoading(false));
    return () => {
      cancelled = true;
    };
  }, []);

  const loadMorePlatforms = () => {
    if (!platformCursor || platformLoading) return;
    setPlatformLoading(true);
    setPlatformError('');
    mePortalApi.accessContext
      .platforms(platformCursor)
      .then((ctx) => {
        setPlatformItems((prev) => [...prev, ...ctx.items]);
        setPlatformCursor(ctx.nextCursor);
      })
      .catch((error: unknown) => setPlatformError((error as Error).message || '平台目录加载失败'))
      .finally(() => setPlatformLoading(false));
  };

  /* 进入连接方式/模板步骤后，用所选真实 platformId + clientType 拉 mcp-config；无平台不发请求。 */
  useEffect(() => {
    if (step < 2 || !platform || mcpCfg) return;
    let cancelled = false;
    setCfgLoading(true);
    setCfgError('');
    mePortalApi
      .mcpConfig(platform, client)
      .then((cfg) => !cancelled && setMcpCfg(cfg))
      .catch((error: unknown) => !cancelled && setCfgError((error as Error).message || '服务端模板获取失败'))
      .finally(() => !cancelled && setCfgLoading(false));
    return () => {
      cancelled = true;
    };
  }, [step, platform, client, mcpCfg, cfgRetry]);

  /* 换平台/客户端后旧配置作废，重新拉取 */
  const choosePlatform = (id: string) => {
    setPlatform(id);
    setMcpCfg(null);
    setCfgError('');
  };
  const chooseClient = (id: string) => {
    setClient(id);
    setMcpCfg(null);
    setCfgError('');
  };

  /* 只用服务端模板；解析失败视为错误，不回退示例模板。 */
  const templates = mcpCfg ? templatesFromConfig(mcpCfg.configTemplate) : null;
  const template = templates ? templates[format] : '';
  const templateReady = !!mcpCfg && !!templates && !cfgError;
  const authAllowed = authAvailability(mcpCfg?.authentication);
  // 只有服务端能力已成功返回时才展示“不支持”；加载/失败期间保持不可选，避免把未知当成不支持或支持。
  const authKnown = !!mcpCfg && !cfgError;

  const canNext =
    (step === 0 && !!platform) ||
    (step === 1 && !!client) ||
    // 认证方式必须由服务端 mcp-config.authentication 实际支持，元数据缺失时 fail-closed。
    (step === 2 && transport === 'streamable-http' && auth === 'oauth-pat' && authAllowed['oauth-pat']) ||
    (step === 3 && templateReady);

  const isPreview = frontendPreview;

  const runTest = async () => {
    if (!isPreview) return;
    setTesting(true);
    setTestResult(null);
    try {
      await testDatasource('preview');
      setTestResult('demo-ok');
    } catch {
      setTestResult('fail');
    } finally {
      setTesting(false);
    }
  };

  const copy = () => {
    if (!template) return;
    navigator.clipboard?.writeText(template).catch(() => undefined);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  if (done) {
    return (
      <>
        <PageHeader title="新建 MCP 接入" description="已按所选平台与客户端生成配置模板；实际接入由客户端握手验证。" />
        <div className="table-card" style={{ maxWidth: 860, margin: '0 auto', padding: '32px 36px' }}>
          <div style={{ textAlign: 'center', marginBottom: 22 }}>
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 56,
                height: 56,
                borderRadius: '50%',
                background: 'var(--ptmcp-green-bg, #e7f6ec)',
                color: 'var(--ptmcp-green)',
                fontSize: 30,
                marginBottom: 12,
              }}
            >
              <CheckIcon />
            </span>
            <h2 style={{ margin: 0, fontSize: 20, color: 'var(--ptmcp-heading)' }}>配置模板已生成</h2>
            <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>
              配置来自服务端按平台与客户端生成的模板；是否真正连通需由客户端完成 MCP 握手后验证。
            </p>
          </div>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'center', marginBottom: 22 }}>
            <button
              className="btn btn--primary"
              onClick={runTest}
              disabled={testing || !isPreview}
              title={isPreview ? undefined : 'MCP 连接由客户端完成握手，此处不可代替客户端验证'}
            >
              <RefreshIcon /> {testing ? '测试中…' : '测试连接'}
            </button>
            <button className="btn btn--secondary" onClick={copy} disabled={!template}>
              <CopyIcon /> 复制配置
            </button>
          </div>
          {testResult === 'demo-ok' ? (
            <Alert kind="info" title="演示：连接测试（仅 preview 模式）">
              当前为合成后端演示，此测试仅演示 UI 流程，不代表真实 MCP 连接。实际 MCP 连接由客户端完成握手。
            </Alert>
          ) : null}
          {testResult === 'fail' ? (
            <Alert kind="danger" title="测试失败">
              连接测试失败，请检查配置后重试。
            </Alert>
          ) : null}

          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(4, 1fr)',
              gap: 14,
              marginTop: 18,
              padding: '16px 0',
              borderTop: '1px solid var(--ptmcp-border)',
              borderBottom: '1px solid var(--ptmcp-border)',
            }}
          >
            {[
              ['接入名称', inferredName],
              ['平台', platformMeta ? `${platformMeta.name} · Tier ${platformMeta.tier}` : '—'],
              ['平台 ID', platform || '—'],
              ['客户端', clientMeta.label],
              ['传输方式', transport],
              ['认证方式', authMeta.label],
              ['状态', '模板已生成（待客户端验证）'],
              ['兼容性', mcpCfg?.compatibilityStatus === 'UNVERIFIED' ? '尚未验证' : (mcpCfg?.validatedVersion ?? '—')],
            ].map(([k, v]) => (
              <div key={k}>
                <div style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{k}</div>
                <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--ptmcp-heading)', marginTop: 4 }}>{v}</div>
              </div>
            ))}
          </div>

          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14, marginTop: 18, fontSize: 13 }}>
            {[
              ['1', '在客户端中使用配置模板', '已提供 JSON 格式的客户端配置模板'],
              ['2', '测试连接是否成功', '重启客户端后即生效，必要时再配置 PAT'],
              ['3', '查看查询历史验证访问', '必要时再查询'],
              ['4', '根据需要调整权限配置', '后续可在授权矩阵中调整'],
            ].map(([n, t, d]) => (
              <div key={n} style={{ display: 'flex', gap: 8 }}>
                <span
                  style={{
                    flex: 'none',
                    display: 'inline-flex',
                    width: 20,
                    height: 20,
                    borderRadius: '50%',
                    background: 'var(--ptmcp-blue-bg)',
                    color: 'var(--ptmcp-blue)',
                    fontSize: 11,
                    fontWeight: 700,
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  {n}
                </span>
                <div>
                  <div style={{ fontWeight: 600, color: 'var(--ptmcp-heading)' }}>{t}</div>
                  <div style={{ color: 'var(--ptmcp-text-muted)', fontSize: 12, marginTop: 3 }}>{d}</div>
                </div>
              </div>
            ))}
          </div>

          <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 24 }}>
            <button className="btn btn--secondary" onClick={onExit}>
              查看我的接入
            </button>
            <button
              className="btn btn--primary"
              onClick={() => {
                setDone(false);
                setStep(0);
                setName('');
              }}
            >
              配置另一个接入
            </button>
          </div>
        </div>
      </>
    );
  }

  return (
    <>
      <PageHeader title="新建 MCP 接入" description="通过以下步骤完成配置，预计 3–5 分钟即可接入。" />
      <Stepper steps={STEPS} current={step} />

      <div className="table-card" style={{ padding: '22px 24px' }}>
        {step === 0 && (
          <>
            <StepHeading n={1} title="选择平台" desc="平台目录来自当前账号可见的授权范围（me/access-context）" />
            {platformLoading && platformItems.length === 0 ? (
              <p style={{ margin: '0 0 8px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>正在加载平台目录…</p>
            ) : null}
            {platformError ? (
              <Alert kind="danger" title="平台目录加载失败">
                {platformError}
              </Alert>
            ) : null}
            {!platformLoading && !platformError && platformItems.length === 0 ? (
              <Alert kind="warning" title="没有可用平台">
                当前账号没有可见的平台，无法生成 MCP 接入配置。请联系管理员开通平台授权后重试。
              </Alert>
            ) : null}
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 14 }}>
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
              <button className="btn btn--secondary" style={{ marginTop: 10 }} onClick={loadMorePlatforms} disabled={platformLoading}>
                {platformLoading ? '加载中…' : '加载更多平台'}
              </button>
            ) : null}
            <Alert kind="info" title="平台说明">
              不同平台的资源隔离、配额和访问策略不同，请根据实际使用场景选择合适的平台环境。
            </Alert>
          </>
        )}

        {step === 1 && (
          <>
            <StepHeading n={2} title="选择客户端" desc="选择要使用的 MCP 客户端工具，按场景分类展示" />
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 14 }}>
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
            <StepHeading n={3} title="连接方式与认证" desc="配置 MCP 服务的传输方式和认证方式" />
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
              <div>
                <h4 style={{ margin: '0 0 10px', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>传输方式</h4>
                <div style={{ display: 'grid', gap: 12 }}>
                  {TRANSPORTS.map((t) => (
                    <OptionCard
                      key={t.value}
                      icon={t.icon}
                      title={t.label}
                      description={t.desc}
                      selected={transport === t.value}
                      onSelect={() => setTransport(t.value)}
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
                      tag={authKnown && !authAllowed[a.value as keyof typeof authAllowed] ? '该平台不支持' : undefined}
                      selected={auth === a.value}
                      onSelect={() => authKnown && authAllowed[a.value as keyof typeof authAllowed] && setAuth(a.value)}
                    />
                  ))}
                </div>
              </div>
            </div>
            {cfgLoading ? <Alert kind="info">正在读取该平台的 MCP 能力与配置模板…</Alert> : null}
            {cfgError ? (
              <Alert kind="danger" title="MCP 能力获取失败">
                {cfgError}。未取得服务端能力时不能继续（fail-closed）。
                <button className="link" style={{ marginLeft: 8 }} onClick={() => setCfgRetry((n) => n + 1)}>
                  重试
                </button>
              </Alert>
            ) : null}
            {!cfgLoading && !cfgError && mcpCfg && !authAllowed['oauth-pat'] ? (
              <Alert kind="warning" title="认证能力未确认">
                服务端未返回 OAuth/PAT 认证能力（或该平台不支持），无法生成 MCP 配置。请返回上一步确认平台与客户端，或联系管理员补全认证元数据。
              </Alert>
            ) : null}
          </>
        )}

        {step === 3 && (
          <>
            <StepHeading n={4} title="获取配置模板" desc="复制下方配置模板到您的客户端配置文件中，并替换相应的访问令牌" />
            {cfgLoading ? (
              <p style={{ margin: '0 0 8px', fontSize: 12.5, color: 'var(--ptmcp-text-muted)' }}>正在从服务端获取当前平台/客户端的配置模板…</p>
            ) : null}
            {cfgError ? (
              <Alert kind="danger" title="模板获取失败">
                {cfgError}。未取得服务端模板时不能复制或完成配置，请返回连接方式步骤点击重试。
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
            <Alert kind="warning" title="模板包含凭证占位符">
              使用 PAT 时，先创建 PAT，再在客户端配置中替换凭证占位符；使用 OAuth 时由客户端完成授权。
              <button className="link" style={{ marginLeft: 6 }}>
                前往创建 PAT →
              </button>
            </Alert>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', margin: '14px 0 8px' }}>
              <div
                role="tablist"
                style={{
                  display: 'inline-flex',
                  gap: 6,
                  padding: 3,
                  borderRadius: 8,
                  background: 'var(--ptmcp-canvas)',
                  border: '1px solid var(--ptmcp-border)',
                }}
              >
                {(['json', 'yaml'] as const).map((f) => (
                  <button
                    key={f}
                    role="tab"
                    aria-selected={format === f}
                    onClick={() => setFormat(f)}
                    style={{
                      appearance: 'none',
                      border: 'none',
                      padding: '5px 14px',
                      borderRadius: 6,
                      fontSize: 12.5,
                      fontWeight: format === f ? 700 : 500,
                      color: format === f ? 'var(--ptmcp-heading)' : 'var(--ptmcp-text-muted)',
                      background: format === f ? 'var(--ptmcp-surface)' : 'transparent',
                      cursor: 'pointer',
                      boxShadow: format === f ? '0 1px 2px rgba(15,23,42,.08)' : 'none',
                    }}
                  >
                    {f.toUpperCase()}
                    {f === 'json' ? '（推荐）' : ''}
                  </button>
                ))}
              </div>
              <button className="btn btn--secondary" onClick={copy} disabled={!template}>
                <CopyIcon /> {copied ? '已复制' : '复制模板'}
              </button>
            </div>
            {template ? (
              <pre
                style={{
                  margin: 0,
                  padding: '16px 18px',
                  background: '#0d1526',
                  color: '#c8dcf5',
                  borderRadius: 10,
                  fontSize: 12.5,
                  lineHeight: 1.65,
                  overflowX: 'auto',
                  fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
                }}
              >
                {template.split('\n').map((line, i) => (
                  <div key={i} style={{ display: 'flex' }}>
                    <span style={{ width: 28, flex: 'none', color: '#4c6184', userSelect: 'none' }}>{i + 1}</span>
                    <span>{line}</span>
                  </div>
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
        <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 22, paddingTop: 16, borderTop: '1px solid var(--ptmcp-border)' }}>
          <button className="btn btn--secondary" onClick={() => (step === 0 ? onExit() : setStep(step - 1))}>
            <ArrowLeftIcon /> 上一步
          </button>
          {step < STEPS.length - 1 ? (
            <button className="btn btn--primary" disabled={!canNext} onClick={() => setStep(step + 1)}>
              下一步 <ArrowRightIcon />
            </button>
          ) : (
            <button
              className="btn btn--primary"
              disabled={!templateReady}
              title={templateReady ? undefined : '未取得服务端模板，不能完成配置'}
              onClick={() => setDone(true)}
            >
              完成并验证 <ArrowRightIcon />
            </button>
          )}
        </div>
      </div>
    </>
  );
}

function StepHeading({ n, title, desc }: { n: number; title: string; desc: string }) {
  return (
    <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', marginBottom: 16 }}>
      <span
        style={{
          flex: 'none',
          display: 'inline-flex',
          width: 24,
          height: 24,
          borderRadius: '50%',
          background: 'var(--ptmcp-blue)',
          color: '#fff',
          fontSize: 12.5,
          fontWeight: 700,
          alignItems: 'center',
          justifyContent: 'center',
          marginTop: 1,
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
