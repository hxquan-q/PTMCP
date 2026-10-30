import { request, idempotencyKey } from './http';
import { normalizeOrgResponse } from './portalIds';

function etag(version: string | number): string {
  return `"${version}"`;
}

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
  permissionCodes?: string[];
  managedDepartmentIds?: string[];
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

async function write<T>(
  path: string,
  init: { method: 'POST' | 'PUT' | 'DELETE'; body?: unknown; headers?: Record<string, string> } & (
    | { kind: 'create'; idempotencyKey?: string }
    | { kind: 'update' | 'delete'; version: string | number }
  ),
): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.kind === 'create') headers.set('Idempotency-Key', init.idempotencyKey ?? idempotencyKey());
  else headers.set('If-Match', etag(init.version));
  const { data } = await request<T>(path, { method: init.method, body: init.body, headers });
  return normalizeOrgResponse(path, data) as T;
}

export function listUsers(params: { departmentId?: string; keyword?: string; signal?: AbortSignal } = {}): Promise<UserRow[]> {
  const q = new URLSearchParams({ pageSize: '100' });
  if (params.departmentId) q.set('departmentId', params.departmentId);
  if (params.keyword) q.set('keyword', params.keyword);
  return get<UserRow[]>(`/api/v1/admin/users?${q}`, params.signal);
}

export function listRoles(signal?: AbortSignal): Promise<RoleRow[]> {
  return get<RoleRow[]>('/api/v1/admin/roles?pageSize=100', signal);
}

export interface RoleWrite {
  name: string;
  enabled: boolean;
  permissionCodes: string[];
}

export function createRole(body: RoleWrite & { key: string }, idempotencyKey?: string): Promise<RoleRow> {
  return write<RoleRow>('/api/v1/admin/roles', { method: 'POST', kind: 'create', body, idempotencyKey });
}

export function getRole(id: string, signal?: AbortSignal): Promise<RoleRow> {
  return get<RoleRow>(`/api/v1/admin/roles/${encodeURIComponent(id)}`, signal);
}

export function updateRole(id: string, body: RoleWrite, version: string | number): Promise<RoleRow> {
  return write<RoleRow>(`/api/v1/admin/roles/${encodeURIComponent(id)}`, { method: 'PUT', kind: 'update', version, body });
}

export function deleteRole(id: string, version: string | number): Promise<void> {
  return write<void>(`/api/v1/admin/roles/${encodeURIComponent(id)}`, { method: 'DELETE', kind: 'delete', version });
}

/* ── 部门 ── */
export interface DepartmentWrite {
  parentId: string | null;
  name: string;
  enabled: boolean;
}

export function createDepartment(body: DepartmentWrite): Promise<DepartmentRow> {
  return write<DepartmentRow>('/api/v1/admin/departments', { method: 'POST', kind: 'create', body });
}

export function updateDepartment(id: string, body: DepartmentWrite, version: string | number): Promise<DepartmentRow> {
  return write<DepartmentRow>(`/api/v1/admin/departments/${encodeURIComponent(id)}`, { method: 'PUT', kind: 'update', version, body });
}

export function deleteDepartment(id: string, version: string | number): Promise<void> {
  return write<void>(`/api/v1/admin/departments/${encodeURIComponent(id)}`, { method: 'DELETE', kind: 'delete', version });
}

/* ── 用户 ── */
export interface UserWrite {
  username: string;
  displayName: string;
  deptId: string;
  roleIds: string[];
  enabled: boolean;
}

export function createUser(body: UserWrite): Promise<UserRow> {
  return write<UserRow>('/api/v1/admin/users', { method: 'POST', kind: 'create', body });
}

export function getUser(id: string, signal?: AbortSignal): Promise<UserRow> {
  return get<UserRow>(`/api/v1/admin/users/${encodeURIComponent(id)}`, signal);
}

export function updateUser(id: string, body: Partial<UserWrite>, version: string | number): Promise<UserRow> {
  return write<UserRow>(`/api/v1/admin/users/${encodeURIComponent(id)}`, { method: 'PUT', kind: 'update', version, body });
}

export function setUserStatus(id: string, enabled: boolean, version: string | number): Promise<UserRow> {
  return write<UserRow>(`/api/v1/admin/users/${encodeURIComponent(id)}/status`, {
    method: 'PUT',
    kind: 'update',
    version,
    body: { status: enabled ? 'ACTIVE' : 'DISABLED' },
  });
}

export function setUserRoles(id: string, roleIds: string[], version: string | number): Promise<UserRow> {
  return write<UserRow>(`/api/v1/admin/users/${encodeURIComponent(id)}/roles`, { method: 'PUT', kind: 'update', version, body: { roleIds } });
}

export function setUserDepartment(id: string, departmentId: string, version: string | number): Promise<UserRow> {
  return write<UserRow>(`/api/v1/admin/users/${encodeURIComponent(id)}/department`, {
    method: 'PUT',
    kind: 'update',
    version,
    body: { departmentId },
  });
}

/** 管理员重置用户密码：PUT + If-Match + 幂等键；newPassword/reason 仅入参，响应不回显。 */
export async function resetUserPassword(
  id: string,
  newPassword: string,
  reason: string,
  version: string | number,
  signal?: AbortSignal,
): Promise<UserRow> {
  const { data } = await request<UserRow>(`/api/v1/admin/users/${encodeURIComponent(id)}/password`, {
    method: 'PUT',
    body: { newPassword, reason },
    headers: { 'If-Match': etag(version), 'Idempotency-Key': idempotencyKey() },
    signal,
  });
  return data;
}

export function deleteUser(id: string, version: string | number): Promise<void> {
  return write<void>(`/api/v1/admin/users/${encodeURIComponent(id)}`, { method: 'DELETE', kind: 'delete', version });
}

export function listDepartments(signal?: AbortSignal): Promise<DepartmentRow[]> {
  return get<DepartmentRow[]>('/api/v1/admin/departments?pageSize=100', signal);
}
