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
import {
  AuditDetailPage,
  DatasourceDetailPage,
  JobDetailPage,
  MetadataDetailPage,
  PlatformDetailPage,
  RoleDetailPage,
  UserDetailPage,
} from './views/admin/DetailPages';
import { LoginPage } from './views/auth/LoginPage';
import { getSession, logout, type SessionInfo } from './services/session';
import { onSessionExpired } from './services/http';
import {
  ADMIN_ROUTE_PERMISSIONS,
  ME_HOME_PATH,
  filterNavGroups,
  firstAccessibleNavPath,
  resolveRoute,
  safeReturnTo,
  type PermissionedNavGroup,
} from './navigation';
import { PermissionsContext, createPermissionsValue } from './permissionsContext';
import { ToastContainer } from './components/Toast';

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
  permission?: string;
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
    permission: 'ptmcp:datasource:list',
    navKey: 'admin-datasources',
    breadcrumb: [{ label: '管理控制台' }, { label: '数据源' }],
    realm: 'admin',
    element: () => <DatasourcesPage />,
  },
  {
    path: '/admin/datasources',
    permission: ADMIN_ROUTE_PERMISSIONS['/admin/datasources'].read,
    navKey: 'admin-datasources',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '数据源', navKey: 'admin-datasources' }, { label: '详情' }],
    realm: 'admin',
    element: (id) => <DatasourceDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/metadata',
    permission: 'ptmcp:metadata:list',
    navKey: 'admin-metadata',
    breadcrumb: [{ label: '管理控制台' }, { label: '元数据与字典' }],
    realm: 'admin',
    element: () => <MetadataPage />,
  },
  {
    path: '/admin/permissions',
    permission: 'ptmcp:acl:list',
    navKey: 'admin-permissions',
    breadcrumb: [{ label: '管理控制台' }, { label: '权限矩阵' }],
    realm: 'admin',
    element: () => <PermissionsPage />,
  },
  {
    path: '/admin/delegations',
    permission: 'ptmcp:delegation:list',
    navKey: 'admin-delegations',
    breadcrumb: [{ label: '管理控制台' }, { label: '管理委派' }],
    realm: 'admin',
    element: () => <DelegationsPage />,
  },
  {
    path: '/admin/departments',
    permission: 'ptmcp:department:list',
    navKey: 'admin-departments',
    breadcrumb: [{ label: '管理控制台' }, { label: '部门' }],
    realm: 'admin',
    element: () => <DepartmentsPage />,
  },
  {
    path: '/admin/users',
    permission: 'ptmcp:user:list',
    navKey: 'admin-users',
    breadcrumb: [{ label: '管理控制台' }, { label: '用户' }],
    realm: 'admin',
    element: () => <UsersPage />,
  },
  {
    path: '/admin/roles',
    permission: 'ptmcp:role:list',
    navKey: 'admin-roles',
    breadcrumb: [{ label: '管理控制台' }, { label: '角色管理' }],
    realm: 'admin',
    element: () => <RolesPage />,
  },
  {
    path: '/admin/platforms',
    permission: 'ptmcp:platform:list',
    navKey: 'admin-platforms',
    breadcrumb: [{ label: '管理控制台' }, { label: '平台与客户端' }],
    realm: 'admin',
    element: () => <PlatformsPage />,
  },
  {
    path: '/admin/users',
    navKey: 'admin-users',
    prefix: true,
    permission: ADMIN_ROUTE_PERMISSIONS['/admin/users'].read,
    breadcrumb: [{ label: '管理控制台' }, { label: '用户', navKey: 'admin-users' }, { label: '用户详情' }],
    realm: 'admin',
    element: (id?: string) => <UserDetailPage id={decodeURIComponent(id ?? '')} />,
  },
  {
    path: '/admin/roles',
    navKey: 'admin-roles',
    prefix: true,
    permission: ADMIN_ROUTE_PERMISSIONS['/admin/roles'].read,
    breadcrumb: [{ label: '管理控制台' }, { label: '角色管理', navKey: 'admin-roles' }, { label: '角色详情' }],
    realm: 'admin',
    element: (id?: string) => <RoleDetailPage id={decodeURIComponent(id ?? '')} />,
  },
  {
    path: '/admin/platforms',
    navKey: 'admin-platforms',
    prefix: true,
    permission: ADMIN_ROUTE_PERMISSIONS['/admin/platforms'].read,
    breadcrumb: [{ label: '管理控制台' }, { label: '平台与客户端', navKey: 'admin-platforms' }, { label: '平台详情' }],
    realm: 'admin',
    element: (id?: string) => <PlatformDetailPage id={decodeURIComponent(id ?? '')} />,
  },
  {
    path: '/admin/audit',
    permission: 'ptmcp:audit:list',
    navKey: 'admin-audit',
    breadcrumb: [{ label: '管理控制台' }, { label: '审计记录' }],
    realm: 'admin',
    element: () => <AuditPage />,
  },
  {
    path: '/admin/audit',
    permission: ADMIN_ROUTE_PERMISSIONS['/admin/audit'].read,
    navKey: 'admin-audit',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '审计记录', navKey: 'admin-audit' }, { label: '详情' }],
    realm: 'admin',
    element: (id) => <AuditDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/jobs',
    permission: 'ptmcp:audit:list',
    navKey: 'admin-jobs',
    breadcrumb: [{ label: '管理控制台' }, { label: '操作日志' }],
    realm: 'admin',
    element: () => <AuditPage initialTab="jobs" title="操作日志" description="管理操作审计：委派、平台、客户端、用户、部门与角色的变更记录。" />,
  },
  {
    path: '/admin/jobs',
    permission: ADMIN_ROUTE_PERMISSIONS['/admin/jobs'].read,
    navKey: 'admin-jobs',
    prefix: true,
    breadcrumb: [{ label: '管理控制台' }, { label: '操作日志', navKey: 'admin-jobs' }, { label: '任务详情' }],
    realm: 'admin',
    element: (id) => <JobDetailPage id={id ?? ''} />,
  },
  {
    path: '/admin/metadata',
    permission: ADMIN_ROUTE_PERMISSIONS['/admin/metadata'].read,
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
  const [returnTo, setReturnTo] = useState('/');
  const permissionsValue = useMemo(() => createPermissionsValue(session?.permissions), [session]);
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

  const can = useCallback((code: string) => !!session?.permissions.includes(code), [session]);
  /* 管理视角入口：首个有权访问的管理页；没有则为 null（个人门户用户不显示管理视角）。 */
  const adminHomePath = useMemo(() => firstAccessibleNavPath(ADMIN_GROUPS, NAV_TO_PATH, can), [can]);
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
        description={`当前账号缺少 ${route.permission} 权限，无法访问该页面。如需开通请联系平台管理员。`}
        onBack={() => navigate(homePath)}
      />
    );
  }

  return (
    <>
      <PermissionsContext.Provider value={permissionsValue}>
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
          {route ? route.element(resolved.match === 'prefix' ? resolved.id : undefined) : null}
        </AppShell>
      </PermissionsContext.Provider>
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
        <button type="button" className="btn btn--primary" onClick={onBack}>
          返回首页
        </button>
      </div>
    </main>
  );
}
