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
    | { kind: 'create' }
    | { kind: 'update' | 'delete'; version: string | number }
  ),
): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.kind === 'create') headers.set('Idempotency-Key', idempotencyKey());
  else headers.set('If-Match', etag(init.version));
  const { data } = await request<T>(path, { method: init.method, body: init.body, headers });
  return normalizeOrgResponse(path, data) as T;
}

export function listUsers(signal?: AbortSignal): Promise<UserRow[]> {
  return get<UserRow[]>('/api/v1/admin/users?pageSize=100', signal);
}

export function listRoles(signal?: AbortSignal): Promise<RoleRow[]> {
  return get<RoleRow[]>('/api/v1/admin/roles?pageSize=100', signal);
}

export interface RoleWrite {
  name: string;
  enabled: boolean;
  permissionCodes: string[];
}

export function createRole(body: RoleWrite & { key: string }): Promise<RoleRow> {
  return write<RoleRow>('/api/v1/admin/roles', { method: 'POST', kind: 'create', body });
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

export function listDepartments(signal?: AbortSignal): Promise<DepartmentRow[]> {
  return get<DepartmentRow[]>('/api/v1/admin/departments?pageSize=100', signal);
}
