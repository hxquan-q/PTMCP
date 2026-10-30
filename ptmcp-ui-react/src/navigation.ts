import type { NavGroup, NavItem } from './layouts/AppShell';

/** 带权限标记的导航模型：item.permission 缺省表示无需权限（个人门户）。 */
export type PermissionedNavGroup = Omit<NavGroup, 'items'> & { items: (NavItem & { permission?: string })[] };

/** 按会话权限过滤导航：移除无权项，丢弃因此变空的分组。 */
export function filterNavGroups(groups: PermissionedNavGroup[], can: (code: string) => boolean): NavGroup[] {
  return groups
    .map((group) => ({ ...group, items: group.items.filter((item) => !item.permission || can(item.permission)) }))
    .filter((group) => group.items.length > 0);
}

/** 个人视角首页：无任何管理权限时的兜底落地页（docs/frontend/dev-guide.md §4）。 */
export const ME_HOME_PATH = '/me/dictionary';

/** 按给定导航顺序返回首个有权访问且有对应路径的导航项；全部无权时返回 null。 */
export function firstAccessibleNavPath(groups: PermissionedNavGroup[], paths: Record<string, string>, can: (code: string) => boolean): string | null {
  for (const group of groups) {
    for (const item of group.items) {
      const path = paths[item.key];
      if (path && (!item.permission || can(item.permission))) return path;
    }
  }
  return null;
}

export interface ResolvableRoute {
  path: string;
  prefix?: boolean;
  permission?: string;
}

export type RouteResolution<R extends ResolvableRoute> =
  | { match: 'exact'; route: R; id?: undefined }
  | { match: 'prefix'; route: R; id: string }
  | { match: 'none'; route?: undefined; id?: undefined };

/** 纯解析：先精确后前缀；尾部段 decodeURIComponent；都不中返回 none（调用方渲染 404）。 */
export function resolveRoute<R extends ResolvableRoute>(path: string, routes: R[]): RouteResolution<R> {
  const exact = routes.find((r) => !r.prefix && r.path === path);
  if (exact) return { match: 'exact', route: exact };
  const prefix = routes.find((r) => r.prefix && path.startsWith(`${r.path}/`));
  if (prefix) return { match: 'prefix', route: prefix, id: decodeURIComponent(path.slice(prefix.path.length + 1)) };
  return { match: 'none' };
}

/** 会话过期后的安全回跳目标：仅接受站内 /admin /me 开头、无反斜杠、无控制字符的路径；否则回默认。 */
export function safeReturnTo(path: string, fallback = '/'): string {
  return /^\/(?:admin|me)(?:\/|\?|$)/.test(path) && !path.includes('\\') && [...path].every((ch) => ch.charCodeAt(0) >= 32) ? path : fallback;
}

/** 详情/列表路由的读权限（App 路由定义消费同源常量，防漂移）。 */
export const ADMIN_ROUTE_PERMISSIONS = {
  '/admin/datasources': { list: 'ptmcp:datasource:list', read: 'ptmcp:datasource:read' },
  '/admin/metadata': { list: 'ptmcp:metadata:list', read: 'ptmcp:metadata:read' },
  '/admin/jobs': { list: 'ptmcp:audit:list', read: 'ptmcp:job:read' },
  '/admin/users': { list: 'ptmcp:user:list', read: 'ptmcp:user:read' },
  '/admin/roles': { list: 'ptmcp:role:list', read: 'ptmcp:role:read' },
  '/admin/platforms': { list: 'ptmcp:platform:list', read: 'ptmcp:platform:read' },
  '/admin/audit': { list: 'ptmcp:audit:list', read: 'ptmcp:audit:read' },
} as const;
