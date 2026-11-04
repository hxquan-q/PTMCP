import { useMemo, useState } from 'react';
import {
  CellIcon,
  Drawer,
  DrawerFields,
  EmptyState,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatCard,
  StatusDot,
  Tabs,
  useDebounced,
} from '../../components/common';
import {
  BoltIcon,
  CheckIcon,
  CodeIcon,
  DocumentIcon,
  GearIcon,
  InfoIcon,
  MonitorIcon,
  PlusIcon,
  PuzzleIcon,
  WarningIcon,
  type IconComponent,
} from '../../components/Icons';
import { frontendPreview } from '../../services/frontendMode';
import { Wizard } from './integrations/Wizard';

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
  tone: 'success' | 'warning' | 'danger' | 'neutral' | 'accent';
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
    tone: 'accent',
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

const EMPTY_INTEGRATIONS: IntegrationRow[] = [];

export function IntegrationsPage() {
  const [mode, setMode] = useState<'list' | 'wizard'>('list');
  return mode === 'list' ? <IntegrationList onCreate={() => setMode('wizard')} /> : <Wizard onExit={() => setMode('list')} />;
}

/* ═══════════ 列表视图 ═══════════ */
function IntegrationList({ onCreate }: { onCreate: () => void }) {
  /* 正常模式没有服务端「我的接入」列表接口：不展示样例数据，也不给出只能作用于合成数据的统计与筛选。 */
  const integrations = frontendPreview ? PREVIEW_INTEGRATIONS : EMPTY_INTEGRATIONS;
  const [tab, setTab] = useState('all');
  const [keyword, setKeyword] = useState('');
  const [platformFilter, setPlatformFilter] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [page, setPage] = useState(1);
  const [detail, setDetail] = useState<IntegrationRow | null>(null);
  const debounced = useDebounced(keyword);

  const stats = useMemo(
    () => [
      { icon: DocumentIcon, value: integrations.length, label: '我的接入', hint: '已创建的接入配置', tone: undefined },
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
    [integrations],
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
  }, [integrations, tab, debounced, platformFilter, statusFilter]);
  const pageSize = 20;
  const pageRows = rows.slice((page - 1) * pageSize, page * pageSize);

  return (
    <>
      <PageHeader
        title="MCP 接入配置"
        description="配置并管理本人的 MCP 客户端接入，快速获取配置模板并完成接入。是否连通仍由客户端握手验证。"
        actions={
          <button className="btn btn--primary" onClick={onCreate}>
            <PlusIcon /> 新建接入配置
          </button>
        }
      />
      {frontendPreview ? (
        /* 统计卡 */
        <div className="stat-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 14, marginBottom: 14 }}>
          {stats.map((s) => (
            <StatCard key={s.label} icon={s.icon} value={s.value} label={s.label} hint={s.hint} tone={s.tone} />
          ))}
        </div>
      ) : null}

      <div className="table-card" style={{ flex: 1, minHeight: 0 }}>
        {frontendPreview ? (
          <>
            <div className="card-tabs">
              <Tabs
                tabs={tabs}
                active={tab}
                onChange={(key) => {
                  setTab(key);
                  setPage(1);
                }}
              />
            </div>
            <div className="card-toolbar">
              <SearchInput
                value={keyword}
                onChange={(v) => {
                  setKeyword(v);
                  setPage(1);
                }}
                placeholder="搜索接入名称或客户端类型…"
                width={240}
              />
              <Select
                ariaLabel="平台筛选"
                value={platformFilter}
                onChange={(v) => {
                  setPlatformFilter(v);
                  setPage(1);
                }}
                width={130}
                options={PLATFORM_FILTER_OPTIONS}
              />
              <Select
                ariaLabel="状态筛选"
                value={statusFilter}
                onChange={(v) => {
                  setStatusFilter(v);
                  setPage(1);
                }}
                width={120}
                options={STATUS_FILTER_OPTIONS}
              />
              <button
                className="btn btn--ghost"
                onClick={() => {
                  setTab('all');
                  setKeyword('');
                  setPlatformFilter('');
                  setStatusFilter('');
                  setPage(1);
                }}
              >
                重置
              </button>
            </div>
          </>
        ) : null}

        <div className="table-scroll" role="region" aria-label="接入列表" tabIndex={0}>
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
                      <span className="cell-stack">
                        <button className="link" onClick={() => setDetail(r)}>
                          {r.name}
                        </button>
                        <span>{r.code}</span>
                      </span>
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
                          ? '当前筛选条件下没有样例接入记录。点击右上角「新建接入」开始配置。'
                          : '控制台不登记客户端接入状态：服务端只提供按平台与客户端生成的配置模板，是否接入成功由客户端 MCP 握手结果决定。点击「新建接入」生成模板，并在「个人令牌」创建 PAT 后替换占位符。'
                      }
                      action={
                        frontendPreview ? undefined : (
                          <button className="btn btn--secondary" onClick={() => (window.location.hash = '/me/tokens')}>
                            前往创建个人令牌
                          </button>
                        )
                      }
                    />
                  </td>
                </tr>
              ) : null}
            </tbody>
          </table>
        </div>
        <div className="table-footer">
          <span>
            共 {rows.length} 条
            {frontendPreview ? (
              <span className="footer-note" style={{ marginLeft: 10 }}>
                演示数据：合成预览模式下的样例接入，不代表真实登记
              </span>
            ) : null}
          </span>
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
