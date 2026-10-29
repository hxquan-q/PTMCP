import { useCallback, useEffect, useMemo, useState, useRef } from 'react';
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
import {
  AuthorizationsPage,
  DictionaryDetailPage,
  DictionaryPage,
  HistoryDetailPage,
  HistoryPage,
  SecurityPage,
  TokensPage,
} from './views/me/MePages';
import { AuditDetailPage, DatasourceDetailPage, JobDetailPage, MetadataDetailPage } from './views/admin/DetailPages';
import { LoginPage } from './views/auth/LoginPage';
import { getSession, logout, type SessionInfo } from './services/session';
import { onSessionExpired } from './services/http';
import { filterNavGroups, resolveRoute, safeReturnTo, type PermissionedNavGroup } from './navigation';

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
  permission?: string;
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
    path: '/me/dictionary',
    navKey: 'me-dictionary',
    prefix: true,
    breadcrumb: <>个人门户 &nbsp;›&nbsp; 数据字典 &nbsp;›&nbsp; 字典详情</>,
    realm: 'me',
    element: (id?: string) => <DictionaryDetailPage objectId={decodeURIComponent(id ?? '')} />,
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
  {
    path: '/me/history',
    navKey: 'me-history',
    prefix: true,
    breadcrumb: <>个人门户 &nbsp;›&nbsp; 查询历史 &nbsp;›&nbsp; 查询详情</>,
    realm: 'me',
    element: (id?: string) => <HistoryDetailPage requestId={decodeURIComponent(id ?? '')} />,
  },
  { path: '/me/security', navKey: 'me-security', breadcrumb: <>个人门户 &nbsp;›&nbsp; 账号安全</>, realm: 'me', element: () => <SecurityPage /> },
  {
    path: '/admin/datasources',
    permission: 'ptmcp:datasource:list',
    navKey: 'admin-datasources',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 数据源</>,
    realm: 'admin',
    element: () => <DatasourcesPage />,
  },
  {
    path: '/admin/datasources',
    permission: 'ptmcp:datasource:read',
    navKey: 'admin-datasources',
    prefix: true,
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 数据源 &nbsp;›&nbsp; 详情</>,
    realm: 'admin',
    element: (id) => <DatasourceDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/metadata',
    permission: 'ptmcp:metadata:list',
    navKey: 'admin-metadata',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 元数据与字典</>,
    realm: 'admin',
    element: () => <MetadataPage />,
  },
  {
    path: '/admin/permissions',
    permission: 'ptmcp:acl:list',
    navKey: 'admin-permissions',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 权限矩阵</>,
    realm: 'admin',
    element: () => <PermissionsPage />,
  },
  {
    path: '/admin/delegations',
    permission: 'ptmcp:delegation:list',
    navKey: 'admin-delegations',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 管理委派</>,
    realm: 'admin',
    element: () => <DelegationsPage />,
  },
  {
    path: '/admin/departments',
    permission: 'ptmcp:department:list',
    navKey: 'admin-departments',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 部门</>,
    realm: 'admin',
    element: () => <DepartmentsPage />,
  },
  {
    path: '/admin/users',
    permission: 'ptmcp:user:list',
    navKey: 'admin-users',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 用户</>,
    realm: 'admin',
    element: () => <UsersPage />,
  },
  {
    path: '/admin/roles',
    permission: 'ptmcp:role:list',
    navKey: 'admin-roles',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 角色管理</>,
    realm: 'admin',
    element: () => <RolesPage />,
  },
  {
    path: '/admin/platforms',
    permission: 'ptmcp:platform:list',
    navKey: 'admin-platforms',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 平台与客户端</>,
    realm: 'admin',
    element: () => <PlatformsPage />,
  },
  {
    path: '/admin/audit',
    permission: 'ptmcp:audit:list',
    navKey: 'admin-audit',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 审计记录</>,
    realm: 'admin',
    element: () => <AuditPage />,
  },
  {
    path: '/admin/audit',
    permission: 'ptmcp:audit:read',
    navKey: 'admin-audit',
    prefix: true,
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 审计记录 &nbsp;›&nbsp; 详情</>,
    realm: 'admin',
    element: (id) => <AuditDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/jobs',
    permission: 'ptmcp:audit:list',
    navKey: 'admin-jobs',
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 操作日志</>,
    realm: 'admin',
    element: () => <AuditPage initialTab="jobs" title="操作日志" description="数据源同步、测试等系统任务记录。" />,
  },
  {
    path: '/admin/jobs',
    permission: 'ptmcp:job:read',
    navKey: 'admin-jobs',
    prefix: true,
    breadcrumb: <>管理控制台 &nbsp;›&nbsp; 操作日志 &nbsp;›&nbsp; 任务详情</>,
    realm: 'admin',
    element: (id) => <JobDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/metadata',
    permission: 'ptmcp:metadata:read',
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

const ADMIN_GROUPS: PermissionedNavGroup[] = [
  {
    title: '数据管理',
    items: [
      { key: 'admin-datasources', permission: 'ptmcp:datasource:list', label: '数据源', icon: <DatabaseIcon /> },
      { key: 'admin-metadata', permission: 'ptmcp:metadata:list', label: '元数据与字典', icon: <DocumentIcon /> },
    ],
  },
  {
    title: '访问控制',
    items: [
      { key: 'admin-permissions', permission: 'ptmcp:acl:list', label: '授权矩阵', icon: <CheckboxIcon /> },
      { key: 'admin-delegations', permission: 'ptmcp:delegation:list', label: '管理委派', icon: <ListIcon /> },
    ],
  },
  {
    title: '组织与接入',
    items: [
      { key: 'admin-departments', permission: 'ptmcp:department:list', label: '部门', icon: <UsersIcon /> },
      { key: 'admin-users', permission: 'ptmcp:user:list', label: '用户', icon: <UserIcon /> },
      { key: 'admin-roles', permission: 'ptmcp:role:list', label: '角色管理', icon: <TagIcon /> },
      { key: 'admin-platforms', permission: 'ptmcp:platform:list', label: '平台与客户端', icon: <MonitorIcon /> },
    ],
  },
  {
    title: '安全与追踪',
    items: [
      { key: 'admin-audit', permission: 'ptmcp:audit:list', label: '审计记录', icon: <BarChartIcon /> },
      { key: 'admin-jobs', permission: 'ptmcp:audit:list', label: '操作日志', icon: <BoltIcon /> },
    ],
  },
];

const NAV_TO_PATH: Record<string, string> = Object.fromEntries(ROUTES.map((r) => [r.navKey, r.path]));

export function App() {
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [sessionState, setSessionState] = useState<'probing' | 'anonymous' | 'signed-in'>('probing');
  const [expiredNote, setExpiredNote] = useState('');
  const [returnTo, setReturnTo] = useState('/me/integrations');
  const pendingReturnRef = useRef<string | null>(null);

  const probe = useCallback(() => {
    setSessionState('probing');
    getSession()
      .then((info) => {
        setSession(info);
        setSessionState('signed-in');
      })
      .catch(() => {
        setSession(null);
        setSessionState('anonymous');
      });
  }, []);

  useEffect(probe, [probe]);

  const [path, navigate] = useHashRoute();

  /* 登录确认后再回跳：避免在登录请求与 hash 变更之间竞态。 */
  useEffect(() => {
    if (sessionState === 'signed-in' && pendingReturnRef.current) {
      const target = pendingReturnRef.current;
      pendingReturnRef.current = null;
      navigate(target);
    }
  }, [sessionState, navigate]);

  /* 改密成功即视为全会话失效：立即回登录页（不等下一次 401）。 */
  useEffect(() => {
    const onPasswordChanged = () => {
      setReturnTo(safeReturnTo(path));
      setSession(null);
      setSessionState('anonymous');
      setExpiredNote('密码已修改，全部会话已失效，请用新密码重新登录。');
    };
    window.addEventListener('ptmcp:password-changed', onPasswordChanged);
    return () => window.removeEventListener('ptmcp:password-changed', onPasswordChanged);
  }, [path]);

  useEffect(
    () =>
      onSessionExpired(() => {
        setReturnTo(safeReturnTo(path));
        setSession(null);
        setSessionState('anonymous');
        setExpiredNote('会话已过期或未登录，请重新登录。');
      }),
    [path],
  );

  const can = useCallback((code: string) => !!session?.permissions.includes(code), [session]);
  const resolved = useMemo(() => resolveRoute(path, ROUTES), [path]);
  const { route } = resolved;
  const denied = !!route?.permission && !can(route.permission);
  const baseGroups = route?.realm === 'me' ? ME_GROUPS : ADMIN_GROUPS;
  const groups = useMemo(() => filterNavGroups(baseGroups, can), [baseGroups, can]);
  const onNavigate = useCallback(
    (key: string) => {
      const target = NAV_TO_PATH[key];
      if (target) navigate(target);
    },
    [navigate],
  );
  if (sessionState === 'signed-in' && resolved.match === 'none') {
    return (
      <StaticErrorCard code="404" title="页面不存在" description={`没有找到 ${path} 对应的页面。`} onBack={() => navigate('/me/integrations')} />
    );
  }
  if (sessionState === 'signed-in' && denied && route) {
    return (
      <StaticErrorCard
        code="403"
        title="无权访问"
        description={`当前账号缺少 ${route.permission} 权限，无法访问该页面。如需开通请联系平台管理员。`}
        onBack={() => navigate('/me/integrations')}
      />
    );
  }

  if (sessionState !== 'signed-in') {
    return (
      <LoginPage
        reason={sessionState === 'anonymous' ? expiredNote || undefined : undefined}
        onLoggedIn={() => {
          setExpiredNote('');
          pendingReturnRef.current = returnTo;
          probe();
        }}
      />
    );
  }

  return (
    <AppShell
      groups={groups}
      active={route?.navKey ?? ''}
      onNavigate={onNavigate}
      breadcrumb={route?.breadcrumb}
      onLogout={() => {
        setExpiredNote('');
        setReturnTo('/me/integrations');
        void logout().finally(() => {
          setSession(null);
          setSessionState('anonymous');
        });
      }}
      user={session ? { displayName: session.displayName, department: session.department?.name ?? '' } : undefined}
    >
      {route ? route.element(resolved.match === 'prefix' ? resolved.id : undefined) : null}
    </AppShell>
  );
}

function StaticErrorCard({ code, title, description, onBack }: { code: string; title: string; description: string; onBack: () => void }) {
  return (
    <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--ptmcp-canvas)' }}>
      <div className="table-card" style={{ width: 420, padding: '30px 28px', textAlign: 'center' }}>
        <div style={{ fontSize: 40, fontWeight: 800, color: 'var(--ptmcp-blue)' }}>{code}</div>
        <h1 style={{ margin: '8px 0 6px', fontSize: 17, color: 'var(--ptmcp-heading)' }}>{title}</h1>
        <p style={{ margin: '0 0 18px', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>{description}</p>
        <button className="btn btn--primary" onClick={onBack}>
          返回首页
        </button>
      </div>
    </div>
  );
}
