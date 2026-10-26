import { normalizeOrgResponse } from './portalIds';
import { ApiError, request as transportRequest, setCsrfToken } from './http';

export { ApiError };

export interface Department {
  id: string;
  parentId: string | null;
  name: string;
  enabled: boolean;
  version: number;
  ancestors: string;
  orderNum?: number;
}

export interface User {
  id: string;
  username: string;
  displayName: string;
  deptId: string;
  enabled: boolean;
  roleIds: string[];
  version: number;
  email?: string;
  phoneNumber?: string;
  remark?: string;
}

export interface Role {
  id: string;
  key: string;
  name: string;
  enabled: boolean;
  permissionCodes: string[];
  version: number;
  memberCount?: number;
  managedDepartmentIds?: string[];
}

export interface Grant {
  id: string;
  subjectType: string;
  subjectId: string;
  resourceType: string;
  resourceId: string;
  resourceGeneration: string;
  action: string;
  effect: string;
  includeDescendants: boolean;
  validFrom: string;
  validTo: string | null;
  status: string;
  reason: string;
  version: number;
}

export function setPortalCsrfToken(value: string | null): void {
  setCsrfToken(value);
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let body: unknown = null;
  if (typeof init.body === 'string') {
    try {
      body = JSON.parse(init.body) as unknown;
    } catch {
      body = init.body;
    }
  } else {
    body = init.body;
  }
  const result = await transportRequest<T>(path, {
    method: init.method,
    body,
    headers: init.headers,
    signal: init.signal ?? undefined,
  });
  return normalizeOrgResponse(path, result.data) as T;
}

function etag(version: number): string {
  return `"${version}"`;
}

export const orgPortalApi = {
  departments: {
    list: (signal?: AbortSignal) => request<Department[]>('/api/v1/admin/departments', { signal }),
    get: (id: string, signal?: AbortSignal) => request<Department>(`/api/v1/admin/departments/${encodeURIComponent(id)}`, { signal }),
    create: (body: { parentId: string | null; name: string; enabled: boolean }) =>
      request<Department>('/api/v1/admin/departments', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: { parentId: string | null; name: string; enabled: boolean }, version: number) =>
      request<Department>(`/api/v1/admin/departments/${encodeURIComponent(id)}`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify(body),
      }),
    remove: (id: string, version: number) =>
      request<void>(`/api/v1/admin/departments/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers: { 'If-Match': etag(version) },
      }),
  },
  users: {
    list: (params: { departmentId?: string; keyword?: string } = {}, signal?: AbortSignal) => {
      const query = new URLSearchParams();
      if (params.departmentId) query.set('departmentId', params.departmentId);
      if (params.keyword) query.set('keyword', params.keyword);
      return request<User[]>(`/api/v1/admin/users${query.size ? `?${query}` : ''}`, { signal });
    },
    get: (id: string, signal?: AbortSignal) => request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}`, { signal }),
    create: (body: { username: string; displayName: string; deptId: string; roleIds: string[]; enabled: boolean }) =>
      request<User>('/api/v1/admin/users', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: { username?: string; displayName: string; deptId: string; roleIds?: string[]; enabled: boolean }, version: number) =>
      request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify(body),
      }),
    setStatus: (id: string, status: 'ACTIVE' | 'DISABLED', version: number) =>
      request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}/status`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify({ status }),
      }),
    setRoles: (id: string, roleIds: string[], version: number) =>
      request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}/roles`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify({ roleIds }),
      }),
    setDepartment: (id: string, departmentId: string, version: number) =>
      request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}/department`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify({ departmentId }),
      }),
  },
  roles: {
    list: (signal?: AbortSignal) => request<Role[]>('/api/v1/admin/roles', { signal }),
    get: (id: string, signal?: AbortSignal) => request<Role>(`/api/v1/admin/roles/${encodeURIComponent(id)}`, { signal }),
    create: (body: { key: string; name: string; enabled: boolean; permissionCodes: string[] }) =>
      request<Role>('/api/v1/admin/roles', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: { name: string; enabled: boolean; permissionCodes: string[] }, version: number) =>
      request<Role>(`/api/v1/admin/roles/${encodeURIComponent(id)}`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify(body),
      }),
    remove: (id: string, version: number) =>
      request<void>(`/api/v1/admin/roles/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers: { 'If-Match': etag(version) },
      }),
  },
  grants: {
    list: (params: { subjectType?: string; subjectId?: string } = {}) => {
      const query = new URLSearchParams();
      if (params.subjectType) query.set('subjectType', params.subjectType);
      if (params.subjectId) query.set('subjectId', params.subjectId);
      return request<Grant[]>(`/api/v1/admin/acl/grants${query.size ? `?${query}` : ''}`);
    },
    revoke: (id: string, version: number) =>
      request<Grant>(`/api/v1/admin/acl/grants/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers: { 'If-Match': etag(version) },
      }),
    preview: (body: { subjectUserId: string; platformId: string; resourceIds: string[]; at?: string }) =>
      request<{
        decisions: Array<{ resourceId: string; meta: boolean; read: boolean; sourceGrantIds: string[] }>;
        evaluatedAt: string;
        nextBoundaryAt: string | null;
      }>('/api/v1/admin/acl/preview', { method: 'POST', body: JSON.stringify(body) }),
  },
};
