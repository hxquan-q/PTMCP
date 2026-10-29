import { request } from './http';
import { normalizeOrgResponse } from './portalIds';

export interface UserRow {
  id: string;
  username: string;
  displayName: string;
  deptId: string | null;
  enabled: boolean;
  roleIds: string[];
  version: string;
}

export interface RoleRow {
  id: string;
  key: string;
  name: string;
  enabled: boolean;
  memberCount?: number;
  version: string;
}

export interface DepartmentRow {
  id: string;
  name: string;
  parentId: string | null;
  enabled: boolean;
  version: string;
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
  const { data } = await request<T>(path, { signal });
  return normalizeOrgResponse(path, data) as T;
}

export function listUsers(signal?: AbortSignal): Promise<UserRow[]> {
  return get<UserRow[]>('/api/v1/admin/users?pageSize=100', signal);
}

export function listRoles(signal?: AbortSignal): Promise<RoleRow[]> {
  return get<RoleRow[]>('/api/v1/admin/roles?pageSize=100', signal);
}

export function listDepartments(signal?: AbortSignal): Promise<DepartmentRow[]> {
  return get<DepartmentRow[]>('/api/v1/admin/departments?pageSize=100', signal);
}
