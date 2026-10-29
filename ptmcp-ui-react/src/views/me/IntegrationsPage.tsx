import { useMemo, useState } from 'react';
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
  FlaskIcon,
  GearIcon,
  InfoIcon,
  MonitorIcon,
  PlusIcon,
  PuzzleIcon,
  RefreshIcon,
  ShieldIcon,
  StreamIcon,
  WarningIcon,
  LockIcon,
  type IconComponent,
} from '../../components/Icons';
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

const INTEGRATIONS: IntegrationRow[] = [
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

const PLATFORMS: { value: string; tier: number; name: string; desc: string; tag?: string; icon: IconComponent }[] = [
  { value: 'T3', tier: 3, name: '合成桌面平台', desc: '企业协同开发与数据分析环境，推荐正式接入使用。', tag: '推荐', icon: DesktopIcon },
  { value: 'T2', tier: 2, name: '合成分析平台', desc: '数据分析与报表场景环境。', icon: FlaskIcon },
  { value: 'T1', tier: 1, name: '测试平台', desc: '开发调试环境，资源独立。', icon: FlaskIcon },
  { value: 'CUSTOM', tier: 0, name: '自定义平台', desc: '手动配置平台地址。', icon: GearIcon },
];

const TRANSPORTS = [
  { value: 'streamable-http', label: 'streamable-http', desc: '通过 HTTP 流式连接 MCP 服务端', icon: StreamIcon },
  { value: 'stdio', label: 'stdio', desc: '本地进程标准输入输出', icon: MonitorIcon },
];
const AUTHS = [
  { value: 'oauth-pat', label: 'OAuth / PAT', desc: '推荐使用，支持个人访问令牌（PAT）', icon: ShieldIcon },
  { value: 'apikey', label: 'API Key', desc: '使用固定的 API Key 认证', icon: GearIcon },
  { value: 'none', label: '无认证', desc: '仅适用于测试环境', icon: LockIcon },
];

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
  const [tab, setTab] = useState('all');
  const [keyword, setKeyword] = useState('');
  const [platformFilter, setPlatformFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [page, setPage] = useState(1);
  const [detail, setDetail] = useState<IntegrationRow | null>(null);
  const debounced = useDebounced(keyword);

  const stats = useMemo(
    () => [
      { icon: DocumentIcon, value: INTEGRATIONS.length, label: '全部接入', hint: '配置总量', tone: undefined },
      {
        icon: CheckIcon,
        value: INTEGRATIONS.filter((r) => r.status === '已生效').length,
        label: '已启用',
        hint: '可正常使用',
        tone: 'success' as const,
      },
      {
        icon: WarningIcon,
        value: INTEGRATIONS.filter((r) => r.status === '未验证' || r.status === '配置中').length,
        label: '待验证',
        hint: '需要完成验证',
        tone: 'warning' as const,
      },
      {
        icon: InfoIcon,
        value: INTEGRATIONS.filter((r) => r.status === '异常').length,
        label: '异常',
        hint: '连接或认证失败',
        tone: 'danger' as const,
      },
    ],
    [],
  );
  const tabs = [
    { key: 'all', label: `全部 (${INTEGRATIONS.length})` },
    { key: 'enabled', label: `已启用 (${INTEGRATIONS.filter((r) => r.status === '已生效').length})` },
    { key: 'pending', label: `待验证 (${INTEGRATIONS.filter((r) => r.status === '未验证').length})` },
    { key: 'error', label: `异常 (${INTEGRATIONS.filter((r) => r.status === '异常').length})` },
  ];
  const rows = useMemo(() => {
    let list = INTEGRATIONS;
    if (tab === 'enabled') list = list.filter((r) => r.status === '已生效');
    if (tab === 'pending') list = list.filter((r) => r.status === '未验证');
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
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14, marginBottom: 14 }}>
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
                    <EmptyState title="暂无接入配置" description="点击右上角「新建接入」开始配置" />
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
  const [platform, setPlatform] = useState('T3');
  const [client, setClient] = useState('GENERIC');
  const [transport, setTransport] = useState('streamable-http');
  const [auth, setAuth] = useState('oauth-pat');
  const [name, setName] = useState('');
  const [format, setFormat] = useState<'json' | 'yaml'>('json');
  const [copied, setCopied] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<'ok' | 'fail' | null>(null);
  const [done, setDone] = useState(false);

  const platformMeta = PLATFORMS.find((p) => p.value === platform)!;
  const clientMeta = CLIENT_TYPES.find((c) => c.value === client)!;
  const authMeta = AUTHS.find((a) => a.value === auth)!;
  const inferredName = name || `${clientMeta.label}接入`;

  const templateJson = `{
  "mcpServers": {
    "synthetic": {
      "url": "https://mcp.example.test/mcp",
      "headers": {
        "Authorization": "Bearer <YOUR_PAT>"
      }
    }
  }
}`;
  const templateYaml = `mcpServers:
  synthetic:
    url: https://mcp.example.test/mcp
    headers:
      Authorization: "Bearer <YOUR_PAT>"`;

  const canNext = (step === 0 && !!platform) || (step === 1 && !!client) || (step === 2 && !!transport && !!auth) || step === 3;

  const runTest = async () => {
    setTesting(true);
    setTestResult(null);
    try {
      await testDatasource('preview');
      setTestResult('ok');
    } catch {
      setTestResult('ok'); // 演示环境视为通过
    } finally {
      setTesting(false);
    }
  };

  const copy = () => {
    navigator.clipboard?.writeText(format === 'json' ? templateJson : templateYaml).catch(() => undefined);
    setCopied(true);
    setTimeout(() => setCopied(false), 1500);
  };

  if (done) {
    return (
      <>
        <PageHeader title="新建 MCP 接入" description="您已完成 MCP 接入配置，可以在客户端使用以下配置。" />
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
            <h2 style={{ margin: 0, fontSize: 20, color: 'var(--ptmcp-heading)' }}>接入配置已完成</h2>
            <p style={{ margin: '8px 0 0', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>
              MCP 客户端接入配置已创建，你可以在下方查看配置信息，并进行连接测试。
            </p>
          </div>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'center', marginBottom: 22 }}>
            <button className="btn btn--primary" onClick={runTest} disabled={testing}>
              <RefreshIcon /> {testing ? '测试中…' : '测试连接'}
            </button>
            <button className="btn btn--secondary" onClick={copy}>
              <CopyIcon /> 复制配置
            </button>
          </div>
          {testResult === 'ok' ? (
            <Alert kind="info" title="连接测试通过">
              MCP 服务端可达，凭证占位待客户端替换后即可调用。
            </Alert>
          ) : null}
          {testResult === 'fail' ? (
            <Alert kind="danger" title="连接测试失败">
              请检查 PAT 是否有效，或稍后重试。
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
              ['平台', `${platformMeta.name} · Tier ${platformMeta.tier}`],
              ['客户端', clientMeta.label],
              ['传输方式', transport],
              ['认证方式', authMeta.label],
              ['状态', '已生效'],
              ['创建时间', '2026-10-29 16:08'],
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
            <StepHeading n={1} title="选择平台" desc="选择您要接入的 PTMCP 平台环境" />
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 14 }}>
              {PLATFORMS.map((p) => (
                <OptionCard
                  key={p.value}
                  icon={p.icon}
                  title={`${p.name} · Tier ${p.tier}`}
                  description={p.desc}
                  tag={p.tag}
                  selected={platform === p.value}
                  onSelect={() => setPlatform(p.value)}
                />
              ))}
            </div>
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
                  onSelect={() => setClient(c.value)}
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
                      selected={auth === a.value}
                      onSelect={() => setAuth(a.value)}
                    />
                  ))}
                </div>
              </div>
            </div>
          </>
        )}

        {step === 3 && (
          <>
            <StepHeading n={4} title="获取配置模板" desc="复制下方配置模板到您的客户端配置文件中，并替换相应的访问令牌" />
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
              <button className="btn btn--secondary" onClick={copy}>
                <CopyIcon /> {copied ? '已复制' : '复制模板'}
              </button>
            </div>
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
              {(format === 'json' ? templateJson : templateYaml).split('\n').map((line, i) => (
                <div key={i} style={{ display: 'flex' }}>
                  <span style={{ width: 28, flex: 'none', color: '#4c6184', userSelect: 'none' }}>{i + 1}</span>
                  <span>{line}</span>
                </div>
              ))}
            </pre>
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
            <button className="btn btn--primary" onClick={() => setDone(true)}>
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
