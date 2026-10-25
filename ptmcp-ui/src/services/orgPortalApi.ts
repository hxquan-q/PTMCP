export type ApiError = Error & { status?: number; code?: string; requestId?: string };

export interface Department {
  id: string;
  parentId: string | null;
  name: string;
  enabled: boolean;
  version: number;
  ancestors: string;
}

export interface User {
  id: string;
  username: string;
  displayName: string;
  deptId: string;
  enabled: boolean;
  roleIds: string[];
  version: number;
}

export interface Role {
  id: string;
  key: string;
  name: string;
  enabled: boolean;
  permissionCodes: string[];
  version: number;
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

let csrfToken: string | null = null;

/** CSRF lives only in this module's memory; the session cookie remains HttpOnly. */
export function setPortalCsrfToken(value: string | null): void {
  csrfToken = value;
}

function apiError(response: Response, body: unknown): ApiError {
  const payload = isRecord(body) ? body : {};
  const error = new Error(typeof payload.code === 'string' ? payload.code : `请求失败（${response.status}）`) as ApiError;
  error.status = response.status;
  error.code = typeof payload.code === 'string' ? payload.code : undefined;
  const data = isRecord(payload.data) ? payload.data : undefined;
  error.requestId = (data && typeof data.requestId === 'string' ? data.requestId : undefined) ?? response.headers.get('X-Request-Id') ?? undefined;
  return error;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null;
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body !== undefined && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  if (init.method && !['GET', 'HEAD', 'OPTIONS'].includes(init.method.toUpperCase())) {
    if (csrfToken) headers.set('X-CSRF-Token', csrfToken);
  }
  const response = await fetch(`/backend/admin${path}`, {
    ...init,
    headers,
    credentials: 'same-origin',
    cache: 'no-store'
  });
  const text = await response.text();
  let body: unknown = null;
  if (text) {
    try {
      body = JSON.parse(text) as unknown;
    } catch {
      body = text;
    }
  }
  if (!response.ok) throw apiError(response, body);
  return body as T;
}

function etag(version: number): string {
  return `"${version}"`;
}

export const orgPortalApi = {
  departments: {
    list: () => request<Department[]>('/api/v1/admin/departments'),
    get: (id: string) => request<Department>(`/api/v1/admin/departments/${encodeURIComponent(id)}`),
    create: (body: { parentId: string | null; name: string; enabled: boolean }) =>
      request<Department>('/api/v1/admin/departments', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: { parentId: string | null; name: string; enabled: boolean }, version: number) =>
      request<Department>(`/api/v1/admin/departments/${encodeURIComponent(id)}`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify(body)
      }),
    remove: (id: string, version: number) =>
      request<void>(`/api/v1/admin/departments/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers: { 'If-Match': etag(version) }
      })
  },
  users: {
    list: (params: { departmentId?: string; keyword?: string } = {}) => {
      const query = new URLSearchParams();
      if (params.departmentId) query.set('departmentId', params.departmentId);
      if (params.keyword) query.set('keyword', params.keyword);
      return request<User[]>(`/api/v1/admin/users${query.size ? `?${query}` : ''}`);
    },
    get: (id: string) => request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}`),
    create: (body: { username: string; displayName: string; deptId: string; roleIds: string[]; enabled: boolean }) =>
      request<User>('/api/v1/admin/users', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: { username?: string; displayName: string; deptId: string; roleIds: string[]; enabled: boolean }, version: number) =>
      request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify(body)
      }),
    setStatus: (id: string, status: 'ACTIVE' | 'DISABLED', version: number) =>
      request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}/status`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify({ status })
      }),
    setRoles: (id: string, roleIds: string[], version: number) =>
      request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}/roles`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify({ roleIds })
      }),
    setDepartment: (id: string, departmentId: string, version: number) =>
      request<User>(`/api/v1/admin/users/${encodeURIComponent(id)}/department`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify({ departmentId })
      })
  },
  roles: {
    list: () => request<Role[]>('/api/v1/admin/roles'),
    get: (id: string) => request<Role>(`/api/v1/admin/roles/${encodeURIComponent(id)}`),
    create: (body: { key: string; name: string; enabled: boolean; permissionCodes: string[] }) =>
      request<Role>('/api/v1/admin/roles', { method: 'POST', body: JSON.stringify(body) }),
    update: (id: string, body: { name: string; enabled: boolean; permissionCodes: string[] }, version: number) =>
      request<Role>(`/api/v1/admin/roles/${encodeURIComponent(id)}`, {
        method: 'PUT',
        headers: { 'If-Match': etag(version) },
        body: JSON.stringify(body)
      }),
    remove: (id: string, version: number) =>
      request<void>(`/api/v1/admin/roles/${encodeURIComponent(id)}`, {
        method: 'DELETE',
        headers: { 'If-Match': etag(version) }
      })
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
        headers: { 'If-Match': etag(version) }
      }),
    preview: (body: { subjectUserId: string; platformId: string; resourceIds: string[]; at?: string }) =>
      request<{
        decisions: Array<{ resourceId: string; meta: boolean; read: boolean; sourceGrantIds: string[] }>;
        evaluatedAt: string;
        nextBoundaryAt: string | null;
      }>('/api/v1/admin/acl/preview', { method: 'POST', body: JSON.stringify(body) })
  }
};
