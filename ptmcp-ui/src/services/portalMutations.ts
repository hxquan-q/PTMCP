export class PortalWriteIntent {
  private epoch = 0;
  private active = true;
  private pending = false;

  begin(): number | null {
    if (!this.active || this.pending) return null;
    this.pending = true;
    return this.epoch;
  }

  accepts(epoch: number): boolean {
    return this.active && this.pending && epoch === this.epoch;
  }

  finish(epoch: number): void {
    if (epoch === this.epoch) this.pending = false;
  }

  dispose(): void {
    this.epoch += 1;
    this.active = false;
    this.pending = false;
  }
}

interface UserFields {
  displayName: string;
  deptId: string;
  roleIds: string[];
  enabled: boolean;
}

export type UserChange = 'profile' | 'department' | 'roles' | 'status';

/** Validate all requested capabilities before the first write in a multi-endpoint edit. */
export function userChangePlan(current: UserFields, draft: UserFields, can: (permission: string) => boolean, rolesLoaded: boolean): UserChange[] {
  const plan: UserChange[] = [];
  const require = (changed: boolean, permission: string, operation: UserChange, message: string): void => {
    if (!changed) return;
    if (!can(permission)) throw new Error(message);
    plan.push(operation);
  };
  require(current.displayName !== draft.displayName.trim(), 'ptmcp:user:update', 'profile', '没有修改用户基本信息的权限');
  require(current.deptId !== draft.deptId, 'ptmcp:user:move', 'department', '没有移动用户部门的权限');
  const rolesChanged = [...current.roleIds].sort().join(',') !== [...draft.roleIds].sort().join(',');
  if (rolesChanged && !rolesLoaded) throw new Error('角色选项加载失败，不能覆盖已有角色');
  require(rolesChanged, 'ptmcp:user:assign-role', 'roles', '没有分配用户角色的权限');
  require(current.enabled !== draft.enabled, 'ptmcp:user:status', 'status', '没有修改用户状态的权限');
  return plan;
}

export function departmentDescendants(items: Array<{ id: string; parentId: string | null }>, id: string): Set<string> {
  const result = new Set([id]);
  let changed = true;
  while (changed) {
    changed = false;
    items.forEach((item) => {
      if (item.parentId && result.has(item.parentId) && !result.has(item.id)) {
        result.add(item.id);
        changed = true;
      }
    });
  }
  return result;
}
