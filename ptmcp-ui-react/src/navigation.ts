import type { NavGroup, NavItem } from './layouts/AppShell';

/** 带权限标记的导航模型：item.permission 缺省表示无需权限（个人门户）。 */
export type PermissionedNavGroup = Omit<NavGroup, 'items'> & { items: (NavItem & { permission?: string })[] };

/** 按会话权限过滤导航：移除无权项，丢弃因此变空的分组。 */
export function filterNavGroups(groups: PermissionedNavGroup[], can: (code: string) => boolean): NavGroup[] {
  return groups
    .map((group) => ({ ...group, items: group.items.filter((item) => !item.permission || can(item.permission)) }))
    .filter((group) => group.items.length > 0);
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
