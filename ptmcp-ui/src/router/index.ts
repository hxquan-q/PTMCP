import type { Component } from 'vue';
import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router';
import { useSessionStore } from '../stores/session';
import AdminLayout from '../layouts/AdminLayout.vue';
import PersonalLayout from '../layouts/PersonalLayout.vue';
import UnavailableView from '../components/common/UnavailableView.vue';
import StaticErrorPage from '../components/common/StaticErrorPage.vue';

type ViewLoader = () => Promise<{ default: Component }>;
const viewModules = import.meta.glob('../views/**/*.vue') as Record<string, ViewLoader>;
const view = (path: string): ViewLoader => viewModules[path] ?? (() => Promise.resolve({ default: UnavailableView }));

const adminRoutes: RouteRecordRaw[] = [
  { path: '', redirect: '/admin/datasources' },
  {
    path: 'datasources',
    name: 'admin-datasources',
    component: view('../views/admin/data/DatasourcesView.vue'),
    meta: { title: '数据源', permission: 'ptmcp:datasource:list' },
  },
  {
    path: 'datasources/:id',
    name: 'admin-datasource-detail',
    component: view('../views/admin/data/DatasourceDetailView.vue'),
    meta: { title: '数据源详情', permission: 'ptmcp:datasource:read' },
  },
  {
    path: 'jobs/:id',
    name: 'admin-job-detail',
    component: view('../views/admin/data/JobDetailView.vue'),
    meta: { title: '任务详情', permission: 'ptmcp:job:read' },
  },
  {
    path: 'metadata',
    name: 'admin-metadata',
    component: view('../views/admin/metadata/MetadataView.vue'),
    meta: { title: '元数据', permission: 'ptmcp:metadata:list' },
  },
  {
    path: 'metadata/:objectId',
    name: 'admin-metadata-detail',
    component: view('../views/admin/metadata/MetadataDetailView.vue'),
    meta: { title: '元数据详情', permission: 'ptmcp:metadata:read' },
  },
  {
    path: 'departments',
    name: 'admin-departments',
    component: view('../views/admin/org/DepartmentsView.vue'),
    meta: { title: '部门', permission: 'ptmcp:department:list' },
  },
  { path: 'users', name: 'admin-users', component: view('../views/admin/org/UsersView.vue'), meta: { title: '用户', permission: 'ptmcp:user:list' } },
  {
    path: 'users/:id',
    name: 'admin-user-detail',
    component: view('../views/admin/org/UsersView.vue'),
    meta: { title: '用户详情', permission: 'ptmcp:user:read' },
  },
  { path: 'roles', name: 'admin-roles', component: view('../views/admin/org/RolesView.vue'), meta: { title: '角色', permission: 'ptmcp:role:list' } },
  {
    path: 'roles/:id',
    name: 'admin-role-detail',
    component: view('../views/admin/org/RolesView.vue'),
    meta: { title: '角色详情', permission: 'ptmcp:role:read' },
  },
  {
    path: 'permissions',
    name: 'admin-permissions',
    component: view('../views/admin/access/PermissionsView.vue'),
    meta: { title: '权限矩阵', permission: 'ptmcp:acl:list' },
  },
  {
    path: 'permissions/preview',
    name: 'admin-permissions-preview',
    component: view('../views/admin/access/PermissionsView.vue'),
    meta: { title: '有效权限预览', permission: 'ptmcp:acl:preview' },
  },
  {
    path: 'delegations',
    name: 'admin-delegations',
    component: view('../views/admin/integrations/DelegationsView.vue'),
    meta: { title: '管理委派', permission: 'ptmcp:delegation:list' },
  },
  {
    path: 'platforms',
    name: 'admin-platforms',
    component: view('../views/admin/integrations/PlatformsView.vue'),
    meta: { title: '平台与客户端', permission: 'ptmcp:platform:list' },
  },
  {
    path: 'platforms/:id',
    name: 'admin-platform-detail',
    component: view('../views/admin/integrations/PlatformDetailView.vue'),
    meta: { title: '平台详情', permission: 'ptmcp:platform:read' },
  },
  {
    path: 'audit',
    name: 'admin-audit',
    component: view('../views/admin/audit/AuditView.vue'),
    meta: { title: '审计记录', permission: 'ptmcp:audit:list' },
  },
  {
    path: 'audit/:requestId',
    name: 'admin-audit-detail',
    component: view('../views/admin/audit/AuditDetailView.vue'),
    meta: { title: '审计详情', permission: 'ptmcp:audit:read' },
  },
];

const personalRoutes: RouteRecordRaw[] = [
  { path: '', redirect: '/me/dictionary' },
  { path: 'dictionary', name: 'me-dictionary', component: view('../views/me/DictionaryView.vue'), meta: { title: '我的数据字典' } },
  {
    path: 'dictionary/:objectId',
    name: 'me-dictionary-detail',
    component: view('../views/me/DictionaryDetailView.vue'),
    meta: { title: '数据字典详情' },
  },
  { path: 'integrations', name: 'me-integrations', component: view('../views/me/IntegrationsView.vue'), meta: { title: '接入配置' } },
  { path: 'tokens', name: 'me-tokens', component: view('../views/me/TokensView.vue'), meta: { title: '个人令牌' } },
  { path: 'authorizations', name: 'me-authorizations', component: view('../views/me/AuthorizationsView.vue'), meta: { title: '授权管理' } },
  { path: 'history', name: 'me-history', component: view('../views/me/HistoryView.vue'), meta: { title: '查询历史' } },
  { path: 'history/:requestId', name: 'me-history-detail', component: view('../views/me/HistoryDetailView.vue'), meta: { title: '查询详情' } },
  { path: 'security', name: 'me-security', component: view('../views/me/SecurityView.vue'), meta: { title: '账号安全' } },
];

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    ...(import.meta.env.DEV && import.meta.env.MODE === 'frontend-preview'
      ? [
          { path: '/__preview/pages', component: () => import('../preview/PagesPreviewView.vue'), meta: { title: '前端页面工作台' } },
          {
            path: '/__preview/authorization',
            component: () => import('../preview/AuthorizationPreviewView.vue'),
            meta: { title: '授权服务前端预览' },
          },
          { path: '/__preview/login', component: view('../views/auth/LoginView.vue'), meta: { title: '登录前端预览' } },
        ]
      : []),
    { path: '/', name: 'home', component: UnavailableView, meta: { title: '工作台' } },
    { path: '/login', name: 'login', component: view('../views/auth/LoginView.vue'), meta: { title: '登录' } },
    { path: '/403', name: 'forbidden', component: StaticErrorPage, meta: { title: '无权访问' } },
    { path: '/404', name: 'not-found', component: StaticErrorPage, meta: { title: '页面不存在' } },
    { path: '/admin', component: AdminLayout, children: adminRoutes, meta: { requiresAuth: true, layout: 'admin' } },
    { path: '/me', component: PersonalLayout, children: personalRoutes, meta: { requiresAuth: true, layout: 'personal' } },
    { path: '/:pathMatch(.*)*', redirect: '/404' },
  ],
  scrollBehavior: () => ({ top: 0 }),
});

function safeReturnTo(path: string): string {
  return /^\/(?:admin|me)(?:\/|\?|$)/.test(path) && !path.includes('\\') && [...path].every((character) => character.charCodeAt(0) >= 32)
    ? path
    : '/';
}

router.beforeEach(async (to) => {
  if (to.name === 'forbidden' || to.name === 'not-found') return true;
  const session = useSessionStore();
  await session.init();
  if (to.name === 'login') {
    if (session.isAuthenticated) return safeReturnTo(typeof to.query.returnTo === 'string' ? to.query.returnTo : '/');
    return true;
  }
  if (!session.isAuthenticated) return { name: 'login', query: { returnTo: safeReturnTo(to.fullPath) } };
  if (to.name === 'home') {
    const first = adminRoutes.find(
      (route) => !route.path.includes(':') && typeof route.meta?.permission === 'string' && session.can(route.meta.permission),
    );
    return first ? `/admin/${first.path}` : '/me/dictionary';
  }
  const permission = to.meta.permission;
  if (typeof permission === 'string' && !session.can(permission)) return { name: 'forbidden' };
  return true;
});

export default router;
