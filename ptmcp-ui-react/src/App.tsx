import { useCallback, useEffect, useMemo, useState } from 'react';
import { AppShell, type NavGroup } from './layouts/AppShell';
import {
  BarChartIcon,
  CheckboxIcon,
  ClockIcon,
  DatabaseIcon,
  DocumentIcon,
  KeyIcon,
  ListIcon,
  MonitorIcon,
  ShieldIcon,
  TagIcon,
  UserIcon,
  UsersIcon,
  BoltIcon,
  LinkIcon,
} from './components/Icons';
import { IntegrationsPage } from './views/me/IntegrationsPage';
import { DatasourcesPage } from './views/admin/DatasourcesPage';
import { PermissionsPage } from './views/admin/PermissionsPage';
import { DepartmentsPage } from './views/admin/DepartmentsPage';
import { UsersPage } from './views/admin/UsersPage';
import { RolesPage } from './views/admin/RolesPage';
import { PlatformsPage } from './views/admin/PlatformsPage';
import { DelegationsPage } from './views/admin/DelegationsPage';
import { AuditPage } from './views/admin/AuditPage';
import { MetadataPage } from './views/admin/MetadataPage';
import { AuthorizationsPage, DictionaryPage, HistoryPage, SecurityPage, TokensPage } from './views/me/MePages';
import { AuditDetailPage, DatasourceDetailPage, JobDetailPage, MetadataDetailPage } from './views/admin/DetailPages';

/* 轻量 hash 路由:#/me/integrations、#/admin/datasources … */
function useHashRoute(): [string, (path: string) => void] {
  const [hash, setHash] = useState(() => window.location.hash.replace(/^#/, '') || '/me/integrations');
  useEffect(() => {
    const onChange = () => setHash(window.location.hash.replace(/^#/, '') || '/me/integrations');
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  const navigate = useCallback((path: string) => {
    window.location.hash = path;
  }, []);
  return [hash, navigate];
}

interface RouteDef {
  path: string;
  navKey: string;
  breadcrumb: React.ReactNode;
  realm: 'me' | 'admin';
  /* element(id?) — detail routes receive the trailing path segment */
  element: (id?: string) => React.ReactElement;
  prefix?: boolean;
}

const ROUTES: RouteDef[] = [
  {
    path: '/me/dictionary',
    navKey: 'me-dictionary',
    breadcrumb: <>个人门户 &nbsp;›&nbsp; 数据字典</>,
    realm: 'me',
    element: () => <DictionaryPage />,
  },
  {
    path: '/me/integrations',
    navKey: 'me-integrations',
    breadcrumb: <>个人门户 &nbsp;›&nbsp; 接入配置</>,
    realm: 'me',
    element: () => <IntegrationsPage />,
  },
  { path: '/me/tokens', navKey: 'me-tokens', breadcrumb: <>个人门户 &nbsp;›&nbsp; 个人令牌</>, realm: 'me', element: () => <TokensPage /> },
  {
    path: '/me/authorizations',
    navKey: 'me-authorizations',
    breadcrumb: <>个人门户 &nbsp;›&nbsp; 授权管理</>,
    realm: 'me',
    element: () => <AuthorizationsPage />,
  },
  { path: '/me/history', navKey: 'me-history', breadcrumb: <>个人门户 &nbsp;›&nbsp; 查询历史</>, realm: 'me', element: () => <HistoryPage /> },
  { path: '/me/security', navKey: 'me-security', breadcrumb: <>个人门户 &nbsp;›&nbsp; 账号安全</>, realm: 'me', element: () => <SecurityPage /> },
  {
    path: '/admin/datasources',
    navKey: 'admin-datasources',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 数据源</>,
    realm: 'admin',
    element: () => <DatasourcesPage />,
  },
  {
    path: '/admin/datasources',
    navKey: 'admin-datasources',
    prefix: true,
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 数据源 &nbsp;›&nbsp; 详情</>,
    realm: 'admin',
    element: (id) => <DatasourceDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/metadata',
    navKey: 'admin-metadata',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 元数据与字典</>,
    realm: 'admin',
    element: () => <MetadataPage />,
  },
  {
    path: '/admin/permissions',
    navKey: 'admin-permissions',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 权限矩阵</>,
    realm: 'admin',
    element: () => <PermissionsPage />,
  },
  {
    path: '/admin/delegations',
    navKey: 'admin-delegations',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 管理委派</>,
    realm: 'admin',
    element: () => <DelegationsPage />,
  },
  {
    path: '/admin/departments',
    navKey: 'admin-departments',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 部门</>,
    realm: 'admin',
    element: () => <DepartmentsPage />,
  },
  { path: '/admin/users', navKey: 'admin-users', breadcrumb: <>管理控制台 &nbsp;›&nbsp; 用户</>, realm: 'admin', element: () => <UsersPage /> },
  { path: '/admin/roles', navKey: 'admin-roles', breadcrumb: <>管理控制台 &nbsp;›&nbsp; 角色管理</>, realm: 'admin', element: () => <RolesPage /> },
  {
    path: '/admin/platforms',
    navKey: 'admin-platforms',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 平台与客户端</>,
    realm: 'admin',
    element: () => <PlatformsPage />,
  },
  { path: '/admin/audit', navKey: 'admin-audit', breadcrumb: <>管理控制台 &nbsp;›&nbsp; 审计记录</>, realm: 'admin', element: () => <AuditPage /> },
  {
    path: '/admin/audit',
    navKey: 'admin-audit',
    prefix: true,
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 审计记录 &nbsp;›&nbsp; 详情</>,
    realm: 'admin',
    element: (id) => <AuditDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/jobs',
    navKey: 'admin-jobs',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 操作日志</>,
    realm: 'admin',
    element: () => <AuditPage initialTab="jobs" title="操作日志" description="数据源同步、测试等系统任务记录。" />,
  },
  {
    path: '/admin/jobs',
    navKey: 'admin-jobs',
    prefix: true,
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 操作日志 &nbsp;›&nbsp; 任务详情</>,
    realm: 'admin',
    element: (id) => <JobDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/metadata',
    navKey: 'admin-metadata',
    prefix: true,
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 元数据与字典 &nbsp;›&nbsp; 详情</>,
    realm: 'admin',
    element: (id) => <MetadataDetailPage id={id ?? ''} />,
  },
];

const ME_GROUPS: NavGroup[] = [
  {
    title: '个人门户',
    items: [
      { key: 'me-dictionary', label: '我的数据字典', icon: <DocumentIcon /> },
      { key: 'me-integrations', label: '接入配置', icon: <LinkIcon /> },
      { key: 'me-tokens', label: '个人令牌', icon: <KeyIcon /> },
      { key: 'me-authorizations', label: '授权管理', icon: <ShieldIcon /> },
      { key: 'me-history', label: '查询历史', icon: <ClockIcon /> },
      { key: 'me-security', label: '账号安全', icon: <CheckboxIcon /> },
    ],
  },
];

const ADMIN_GROUPS: NavGroup[] = [
  {
    title: '数据管理',
    items: [
      { key: 'admin-datasources', label: '数据源', icon: <DatabaseIcon /> },
      { key: 'admin-metadata', label: '元数据与字典', icon: <DocumentIcon /> },
    ],
  },
  {
    title: '访问控制',
    items: [
      { key: 'admin-permissions', label: '授权矩阵', icon: <CheckboxIcon /> },
      { key: 'admin-delegations', label: '管理委派', icon: <ListIcon /> },
    ],
  },
  {
    title: '组织与接入',
    items: [
      { key: 'admin-departments', label: '部门', icon: <UsersIcon /> },
      { key: 'admin-users', label: '用户', icon: <UserIcon /> },
      { key: 'admin-roles', label: '角色管理', icon: <TagIcon /> },
      { key: 'admin-platforms', label: '平台与客户端', icon: <MonitorIcon /> },
    ],
  },
  {
    title: '安全与追踪',
    items: [
      { key: 'admin-audit', label: '审计记录', icon: <BarChartIcon /> },
      { key: 'admin-jobs', label: '操作日志', icon: <BoltIcon /> },
    ],
  },
];

const NAV_TO_PATH: Record<string, string> = Object.fromEntries(ROUTES.map((r) => [r.navKey, r.path]));

export function App() {
  const [path, navigate] = useHashRoute();
  const resolved = useMemo(() => {
    const exact = ROUTES.find((r) => !r.prefix && r.path === path);
    if (exact) return { route: exact, id: undefined as string | undefined };
    const prefix = ROUTES.find((r) => r.prefix && path.startsWith(`${r.path}/`));
    if (prefix) return { route: prefix, id: decodeURIComponent(path.slice(prefix.path.length + 1)) };
    return { route: ROUTES[0], id: undefined };
  }, [path]);
  const { route, id } = resolved;
  const groups = route.realm === 'me' ? ME_GROUPS : ADMIN_GROUPS;
  const onNavigate = useCallback(
    (key: string) => {
      const target = NAV_TO_PATH[key];
      if (target) navigate(target);
    },
    [navigate],
  );
  return (
    <AppShell groups={groups} active={route.navKey} onNavigate={onNavigate} breadcrumb={route.breadcrumb}>
      {route.element(id)}
    </AppShell>
  );
}
