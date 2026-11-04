import type { NavGroup, NavItem } from './layouts/AppShell';

/** S5：管理控制台整体由 platformAdmin 布尔把关，导航模型不再逐项携带权限码。 */
export type AdminNavItem = NavItem;
export type AdminNavGroup = NavGroup;

/** 个人视角首页：非管理员的兜底落地页（docs/frontend/dev-guide.md §4）。 */
export const ME_HOME_PATH = '/me/dictionary';

/** 管理员视角按给定导航顺序返回首个有对应路径的导航项；列表为空时回退个人首页。 */
export function firstAccessibleNavPath(groups: AdminNavGroup[], paths: Record<string, string>, isAdmin: boolean): string | null {
  if (!isAdmin) return ME_HOME_PATH;
  for (const group of groups) {
    for (const item of group.items) {
      const path = paths[item.key];
      if (path) return path;
    }
  }
  return ME_HOME_PATH;
}

export interface ResolvableRoute {
  path: string;
  prefix?: boolean;
}

/** 路由是否放行：管理控制台路由整体要求平台管理员。 */
export function routeAllowed(_route: ResolvableRoute, isAdmin: boolean): boolean {
  return isAdmin;
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
