import { lazy, Suspense, useCallback, useEffect, useMemo, useState, useRef } from 'react';
import { Button } from 'antd';
import { AppShell, type NavGroup } from './layouts/AppShell';
import {
  BarChartIcon,
  CheckboxIcon,
  ClockIcon,
  DatabaseIcon,
  DocumentIcon,
  KeyIcon,
  MonitorIcon,
  ShieldIcon,
  TagIcon,
  UsersIcon,
  BoltIcon,
  LinkIcon,
} from './components/Icons';
import { LoginPage } from './views/auth/LoginPage';
import { getSession, logout, type SessionInfo } from './services/session';
import { onSessionExpired } from './services/http';
import { firstAccessibleNavPath, ME_HOME_PATH, resolveRoute, routeAllowed, safeReturnTo } from './navigation';
import { AdminGateContext, createAdminGateValue } from './adminGateContext';
import { ToastContainer } from './components/Toast';
/* 页面按路由懒加载：首屏只下载导航骨架与命中页，升级业务视图不再让用户重下整包。 */
const IntegrationsPage = lazy(() => import('./views/me/IntegrationsPage').then((m) => ({ default: m.IntegrationsPage })));
const DatasourcesPage = lazy(() => import('./views/admin/DatasourcesPage').then((m) => ({ default: m.DatasourcesPage })));
const PermissionsPage = lazy(() => import('./views/admin/PermissionsPage').then((m) => ({ default: m.PermissionsPage })));
const OrgPage = lazy(() => import('./views/admin/OrgPage').then((m) => ({ default: m.OrgPage })));
const RolesPage = lazy(() => import('./views/admin/RolesPage').then((m) => ({ default: m.RolesPage })));
const PlatformsPage = lazy(() => import('./views/admin/PlatformsPage').then((m) => ({ default: m.PlatformsPage })));
const AuditPage = lazy(() => import('./views/admin/AuditPage').then((m) => ({ default: m.AuditPage })));
const MetadataPage = lazy(() => import('./views/admin/MetadataPage').then((m) => ({ default: m.MetadataPage })));
const mePages = () => import('./views/me/MePages');
const AuthorizationsPage = lazy(() => mePages().then((m) => ({ default: m.AuthorizationsPage })));
const DictionaryDetailPage = lazy(() => mePages().then((m) => ({ default: m.DictionaryDetailPage })));
const DictionaryPage = lazy(() => mePages().then((m) => ({ default: m.DictionaryPage })));
const HistoryDetailPage = lazy(() => mePages().then((m) => ({ default: m.HistoryDetailPage })));
const HistoryPage = lazy(() => mePages().then((m) => ({ default: m.HistoryPage })));
const SecurityPage = lazy(() => mePages().then((m) => ({ default: m.SecurityPage })));
const TokensPage = lazy(() => mePages().then((m) => ({ default: m.TokensPage })));
const detailPages = () => import('./views/admin/DetailPages');
const AuditDetailPage = lazy(() => detailPages().then((m) => ({ default: m.AuditDetailPage })));
const DatasourceDetailPage = lazy(() => detailPages().then((m) => ({ default: m.DatasourceDetailPage })));
const JobDetailPage = lazy(() => detailPages().then((m) => ({ default: m.JobDetailPage })));
const MetadataDetailPage = lazy(() => detailPages().then((m) => ({ default: m.MetadataDetailPage })));
const PlatformDetailPage = lazy(() => detailPages().then((m) => ({ default: m.PlatformDetailPage })));
const RoleDetailPage = lazy(() => detailPages().then((m) => ({ default: m.RoleDetailPage })));
const UserDetailPage = lazy(() => detailPages().then((m) => ({ default: m.UserDetailPage })));

/* 轻量 hash 路由:#/me/integrations、#/admin/datasources …；空 hash 视为 `/`，由会话权限决定落地页。 */
function useHashRoute(): [string, (path: string) => void] {
  const [hash, setHash] = useState(() => window.location.hash.replace(/^#/, '') || '/');
  useEffect(() => {
    const onChange = () => setHash(window.location.hash.replace(/^#/, '') || '/');
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  const navigate = useCallback((path: string) => {
    window.location.hash = path;
  }, []);
  return [hash, navigate];
}

/** 面包屑一项；带 navKey 的中间层渲染成可跳转的返回入口，末项始终是当前页。 */
interface Crumb {
  label: string;
  navKey?: string;
}

interface RouteDef {
  path: string;
  navKey: string;
  breadcrumb: Crumb[];
  realm: 'me' | 'admin';
  /* element(id?) — detail routes receive the trailing path segment */
  element: (id?: string) => React.ReactElement;
  prefix?: boolean;
}

const ROUTES: RouteDef[] = [
  {
    path: '/me/dictionary',
    navKey: 'me-dictionary',
    breadcrumb: [{ label: '个人门户' }, { label: '数据字典' }],
    realm: 'me',
    element: () => <DictionaryPage />,
  },
  {
    path: '/me/dictionary',
    navKey: 'me-dictionary',
    prefix: true,
    breadcrumb: [{ label: '个人门户' }, { label: '数据字典', navKey: 'me-dictionary' }, { label: '字典详情' }],
    realm: 'me',
    element: (id?: string) => <DictionaryDetailPage objectId={decodeURIComponent(id ?? '')} />,
  },
  {
    path: '/me/integrations',
    navKey: 'me-integrations',
    breadcrumb: [{ label: '个人门户' }, { label: '接入配置' }],
    realm: 'me',
    element: () => <IntegrationsPage />,
  },
  {
    path: '/me/tokens',
    navKey: 'me-tokens',
    breadcrumb: [{ label: '个人门户' }, { label: '个人令牌' }],
    realm: 'me',
    element: () => <TokensPage />,
  },
  {
    path: '/me/authorizations',
    navKey: 'me-authorizations',
    breadcrumb: [{ label: '个人门户' }, { label: '授权管理' }],
    realm: 'me',
    element: () => <AuthorizationsPage />,
  },
  {
    path: '/me/history',
    navKey: 'me-history',
    breadcrumb: [{ label: '个人门户' }, { label: '查询历史' }],
    realm: 'me',
    element: () => <HistoryPage />,
  },
  {
    path: '/me/history',
    navKey: 'me-history',
    prefix: true,
    breadcrumb: [{ label: '个人门户' }, { label: '查询历史', navKey: 'me-history' }, { label: '查询详情' }],
    realm: 'me',
    element: (id?: string) => <HistoryDetailPage requestId={decodeURIComponent(id ?? '')} />,
  },
  {
    path: '/me/security',
    navKey: 'me-security',
    breadcrumb: [{ label: '个人门户' }, { label: '账号安全' }],
    realm: 'me',
    element: () => <SecurityPage />,
  },
  {
    path: '/admin/datasources',
    navKey: 'admin-datasources',
    breadcrumb: [{ label: '管理控制台' }, { label: '数据源' }],
    realm: 'admin',
    element: () => <DatasourcesPage />,
  },
  {
    path: '/admin/datasources',
    navKey: 'admin-datasources',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '数据源', navKey: 'admin-datasources' }, { label: '详情' }],
    realm: 'admin',
    element: (id) => <DatasourceDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/metadata',
    navKey: 'admin-metadata',
    breadcrumb: [{ label: '管理控制台' }, { label: '元数据与字典' }],
    realm: 'admin',
    element: () => <MetadataPage />,
  },
  {
    path: '/admin/permissions',
    navKey: 'admin-permissions',
    breadcrumb: [{ label: '管理控制台' }, { label: '权限矩阵' }],
    realm: 'admin',
    element: () => <PermissionsPage />,
  },
  {
    path: '/admin/org',
    navKey: 'admin-org',
    breadcrumb: [{ label: '管理控制台' }, { label: '人员组织' }],
    realm: 'admin',
    element: () => <OrgPage />,
  },
  /* 旧地址保留为别名：书签和外部链接仍能落到合并后的页面。 */
  {
    path: '/admin/departments',
    navKey: 'admin-org',
    breadcrumb: [{ label: '管理控制台' }, { label: '人员组织', navKey: 'admin-org' }],
    realm: 'admin',
    element: () => <OrgPage />,
  },
  {
    path: '/admin/users',
    navKey: 'admin-org',
    breadcrumb: [{ label: '管理控制台' }, { label: '人员组织', navKey: 'admin-org' }],
    realm: 'admin',
    element: () => <OrgPage />,
  },
  {
    path: '/admin/roles',
    navKey: 'admin-roles',
    breadcrumb: [{ label: '管理控制台' }, { label: '角色管理' }],
    realm: 'admin',
    element: () => <RolesPage />,
  },
  {
    path: '/admin/platforms',
    navKey: 'admin-platforms',
    breadcrumb: [{ label: '管理控制台' }, { label: '平台与客户端' }],
    realm: 'admin',
    element: () => <PlatformsPage />,
  },
  {
    path: '/admin/users',
    navKey: 'admin-org',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '人员组织', navKey: 'admin-org' }, { label: '用户详情' }],
    realm: 'admin',
    element: (id?: string) => <UserDetailPage id={decodeURIComponent(id ?? '')} />,
  },
  {
    path: '/admin/roles',
    navKey: 'admin-roles',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '角色管理', navKey: 'admin-roles' }, { label: '角色详情' }],
    realm: 'admin',
    element: (id?: string) => <RoleDetailPage id={decodeURIComponent(id ?? '')} />,
  },
  {
    path: '/admin/platforms',
    navKey: 'admin-platforms',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '平台与客户端', navKey: 'admin-platforms' }, { label: '平台详情' }],
    realm: 'admin',
    element: (id?: string) => <PlatformDetailPage id={decodeURIComponent(id ?? '')} />,
  },
  {
    path: '/admin/audit',
    navKey: 'admin-audit',
    breadcrumb: [{ label: '管理控制台' }, { label: '审计记录' }],
    realm: 'admin',
    element: () => <AuditPage />,
  },
  {
    path: '/admin/audit',
    navKey: 'admin-audit',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '审计记录', navKey: 'admin-audit' }, { label: '详情' }],
    realm: 'admin',
    element: (id) => <AuditDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/jobs',
    navKey: 'admin-jobs',
    breadcrumb: [{ label: '管理控制台' }, { label: '操作日志' }],
    realm: 'admin',
    element: () => <AuditPage initialTab="jobs" title="操作日志" description="管理操作审计：委派、平台、客户端、用户、部门与角色的变更记录。" />,
  },
  {
    path: '/admin/jobs',
    navKey: 'admin-jobs',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '操作日志', navKey: 'admin-jobs' }, { label: '任务详情' }],
    realm: 'admin',
    element: (id) => <JobDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/metadata',
    navKey: 'admin-metadata',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '元数据与字典', navKey: 'admin-metadata' }, { label: '详情' }],
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
    ],
  },
  {
    title: '个人设置',
    items: [{ key: 'me-security', label: '账号安全', icon: <CheckboxIcon /> }],
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
    items: [{ key: 'admin-permissions', label: '授权矩阵', icon: <CheckboxIcon /> }],
  },
  {
    title: '组织与接入',
    items: [
      { key: 'admin-org', label: '人员组织', icon: <UsersIcon /> },
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

/* 同一 navKey 可挂多个地址（规范地址 + 旧别名 + 详情前缀）：只取第一个精确路由作为导航目标。 */
const NAV_TO_PATH: Record<string, string> = {};
for (const route of ROUTES) {
  if (!route.prefix && !NAV_TO_PATH[route.navKey]) NAV_TO_PATH[route.navKey] = route.path;
}

export function App() {
  const [session, setSession] = useState<SessionInfo | null>(null);
  const [sessionState, setSessionState] = useState<'probing' | 'anonymous' | 'signed-in'>('probing');
  const [expiredNote, setExpiredNote] = useState('');
  const [returnTo, setReturnTo] = useState('/');
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

  /* S5：管理控制台整体由 platformAdmin 把关，不再逐项判权限码。 */
  const isAdmin = session?.platformAdmin === true;
  const adminHomePath = useMemo(() => firstAccessibleNavPath(ADMIN_GROUPS, NAV_TO_PATH, isAdmin), [isAdmin]);
  const homePath = adminHomePath ?? ME_HOME_PATH;

  /* 登录确认后再回跳：避免在登录请求与 hash 变更之间竞态。 */
  useEffect(() => {
    if (sessionState === 'signed-in' && pendingReturnRef.current) {
      const target = pendingReturnRef.current;
      pendingReturnRef.current = null;
      navigate(target === '/' ? homePath : target);
    }
  }, [sessionState, navigate, homePath]);

  /* `/` 与空 hash：落到首个有权访问的管理页；无管理权限则回个人字典。 */
  useEffect(() => {
    if (sessionState === 'signed-in' && path === '/') navigate(homePath);
  }, [sessionState, path, homePath, navigate]);

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

  /* 账号安全页主动退出：与顶栏退出同效，安静回到登录页。 */
  useEffect(() => {
    const onSignedOut = () => {
      setReturnTo(safeReturnTo(path));
      setSession(null);
      setSessionState('anonymous');
      setExpiredNote('');
    };
    window.addEventListener('ptmcp:signed-out', onSignedOut);
    return () => window.removeEventListener('ptmcp:signed-out', onSignedOut);
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

  const resolved = useMemo(() => resolveRoute(path, ROUTES), [path]);
  const { route } = resolved;
  const denied = !!route && !routeAllowed(route, isAdmin);
  const groups = route?.realm === 'me' ? ME_GROUPS : ADMIN_GROUPS;
  const onNavigate = useCallback(
    (key: string) => {
      const target = NAV_TO_PATH[key];
      if (target) navigate(target);
    },
    [navigate],
  );
  /* 首次进入或刷新：先核对会话，避免把已登录用户闪回登录页、中途又被替换掉。 */
  if (sessionState === 'probing') return <BootstrapScreen label="正在核对登录状态…" />;
  /* 未登录（含会话过期）一律先回到登录页，避免把鉴权前的地址渲染成 403/404。 */
  if (sessionState !== 'signed-in') {
    return (
      <LoginPage
        reason={expiredNote || undefined}
        onLoggedIn={() => {
          setExpiredNote('');
          pendingReturnRef.current = returnTo;
          probe();
        }}
      />
    );
  }

  /* `/` 正在按权限重定向：给出明确的等待帧，不留无说明的空白。 */
  if (path === '/') return <BootstrapScreen label="正在打开首页…" />;
  if (resolved.match === 'none') {
    return <StaticErrorCard code="404" title="页面不存在" description={`没有找到 ${path} 对应的页面。`} onBack={() => navigate(homePath)} />;
  }
  if (denied && route) {
    return (
      <StaticErrorCard
        code="403"
        title="无权访问"
        description="管理控制台仅对平台管理员开放。如需访问请联系平台管理员。"
        onBack={() => navigate(homePath)}
      />
    );
  }

  return (
    <>
      <AdminGateContext.Provider value={createAdminGateValue(isAdmin)}>
        <AppShell
          groups={groups}
          active={route?.navKey ?? ''}
          onNavigate={onNavigate}
          realm={route?.realm ?? 'me'}
          adminHomePath={adminHomePath}
          onSwitchRealm={(next) => navigate(next === 'admin' ? (adminHomePath ?? ME_HOME_PATH) : ME_HOME_PATH)}
          breadcrumb={route?.breadcrumb}
          onLogout={() => {
            setExpiredNote('');
            setReturnTo('/');
            void logout().finally(() => {
              setSession(null);
              setSessionState('anonymous');
            });
          }}
          user={session ? { displayName: session.displayName, department: session.department?.name ?? '' } : undefined}
        >
          <Suspense
            fallback={
              <div className="route-loading" role="status">
                <span className="boot-mark" aria-hidden="true" />
                <span>正在加载页面…</span>
              </div>
            }
          >
            {route ? route.element(resolved.match === 'prefix' ? resolved.id : undefined) : null}
          </Suspense>
        </AppShell>
      </AdminGateContext.Provider>
      <ToastContainer />
    </>
  );
}

/** 鉴权与重定向期间的等待帧：不显示可操作内容，避免用户在状态未定时误点。 */
function BootstrapScreen({ label }: { label: string }) {
  return (
    <div className="boot-shell" role="status">
      <span className="boot-mark" aria-hidden="true" />
      <span>{label}</span>
    </div>
  );
}

function StaticErrorCard({ code, title, description, onBack }: { code: string; title: string; description: string; onBack: () => void }) {
  const titleRef = useRef<HTMLHeadingElement>(null);
  /* 整页替换时焦点仍停留在原位置，读屏不会念出原因；挂载后把焦点移到标题。 */
  useEffect(() => {
    titleRef.current?.focus();
  }, []);
  return (
    <main className="error-shell">
      <div className="error-card">
        <p className="error-code">{code}</p>
        <h1 ref={titleRef} className="error-title" tabIndex={-1}>
          {title}
        </h1>
        <p className="error-desc">{description}</p>
        <Button type="primary" onClick={onBack}>
          返回首页
        </Button>
      </div>
    </main>
  );
}
