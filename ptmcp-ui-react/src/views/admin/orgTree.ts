/** 部门树构建与路径工具 — 与页面解耦，供测试覆盖。 */
export interface DepartmentTreeNode<T extends { id: string; parentId: string | null; name: string }> {
  item: T;
  depth: number;
  children: DepartmentTreeNode<T>[];
}

const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name, 'zh');

/**
 * 构建部门树：父节点缺失或自引用视为根；环中的剩余节点按名称补为根，保证全部部门可见。
 * 返回顺序按名称排序，depth 从 0 开始。
 */
export function buildDepartmentTree<T extends { id: string; parentId: string | null; name: string }>(items: T[]): DepartmentTreeNode<T>[] {
  const ids = new Set(items.map((item) => item.id));
  const childrenOf = new Map<string, T[]>();
  const roots: T[] = [];
  for (const item of items) {
    const parent = item.parentId;
    if (parent == null || parent === item.id || !ids.has(parent)) roots.push(item);
    else {
      const siblings = childrenOf.get(parent);
      if (siblings) siblings.push(item);
      else childrenOf.set(parent, [item]);
    }
  }
  roots.sort(byName);
  for (const siblings of childrenOf.values()) siblings.sort(byName);

  const visited = new Set<string>();
  const build = (item: T, depth: number): DepartmentTreeNode<T> => {
    visited.add(item.id);
    const children = (childrenOf.get(item.id) ?? []).filter((child) => !visited.has(child.id)).map((child) => build(child, depth + 1));
    return { item, depth, children };
  };
  const tree = roots.map((root) => build(root, 0));
  /* 纯环（A→B→A）没有根节点，按名称补为根；visited 过滤保证不会重复展开。 */
  for (const item of [...items].sort(byName)) {
    if (!visited.has(item.id)) tree.push(build(item, 0));
  }
  return tree;
}

/** 从根到目标部门的名称链；父链缺失或成环时在断点停止。 */
export function departmentPath(items: Array<{ id: string; parentId: string | null; name: string }>, id: string | null): string[] {
  if (!id) return [];
  const byId = new Map(items.map((item) => [item.id, item]));
  const names: string[] = [];
  const seen = new Set<string>();
  let cursor = byId.get(id);
  while (cursor && !seen.has(cursor.id)) {
    seen.add(cursor.id);
    names.unshift(cursor.name);
    cursor = cursor.parentId ? byId.get(cursor.parentId) : undefined;
  }
  return names;
}
