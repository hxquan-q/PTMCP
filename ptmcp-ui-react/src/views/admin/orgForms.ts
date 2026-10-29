/** 用户/部门表单纯校验与树工具 — 与页面解耦，供测试覆盖。 */
export interface UserFormValues {
  username: string;
  displayName: string;
  deptId: string;
  roleIds: string[];
  enabled: boolean;
}

export function validateUserForm(values: UserFormValues, isCreate: boolean): string | null {
  if (isCreate) {
    const username = values.username.trim();
    if (!username) return '请输入用户名';
    if (username.length < 2 || username.length > 100) return '用户名长度须为 2–100 个字符';
  }
  const displayName = values.displayName.trim();
  if (!displayName) return '请输入显示名';
  if (displayName.length < 2 || displayName.length > 128) return '显示名长度须为 2–128 个字符';
  if (!values.deptId) return '请选择所属部门';
  return null;
}

export interface DeptFormValues {
  name: string;
  parentId: string | null;
  enabled: boolean;
}

export function validateDeptForm(values: DeptFormValues): string | null {
  const name = values.name.trim();
  if (!name) return '请输入部门名称';
  if (name.length < 2 || name.length > 128) return '部门名称长度须为 2–128 个字符';
  return null;
}

/** 计算以 id 为根的子树（含自身）；用于阻止把部门挂到自己后代下成环。 */
export function departmentDescendants(items: Array<{ id: string; parentId: string | null }>, id: string): Set<string> {
  const childrenOf = new Map<string, string[]>();
  for (const item of items) {
    if (item.parentId == null) continue;
    if (!childrenOf.has(item.parentId)) childrenOf.set(item.parentId, []);
    childrenOf.get(item.parentId)!.push(item.id);
  }
  const seen = new Set<string>([id]);
  const queue = [id];
  while (queue.length) {
    const current = queue.shift()!;
    for (const child of childrenOf.get(current) ?? []) {
      if (!seen.has(child)) {
        seen.add(child);
        queue.push(child);
      }
    }
  }
  return seen;
}

/** 部门下拉的缩进展示：根为 0 层。 */
export function departmentDepth(items: Array<{ id: string; parentId: string | null }>, id: string): number {
  const byId = new Map(items.map((item) => [item.id, item]));
  let depth = 0;
  let cursor = byId.get(id);
  while (cursor?.parentId != null) {
    cursor = byId.get(cursor.parentId);
    depth += 1;
    if (depth > 50) break;
  }
  return depth;
}
