import { normalizeMeResponse } from './portalIds';
import { request as transportRequest, setCsrfToken } from './http';

export interface PlatformItem {
  type: 'PLATFORM';
  id: string;
  name: string;
  tier: number;
  catalogMode: string;
}
export interface DatasourceItem {
  type: 'DATASOURCE';
  id: string;
  name: string;
  engine: string;
}
export interface NamespaceItem {
  type: 'NAMESPACE';
  id: string;
  databaseName: string;
  schemaName: string | null;
  displayName: string;
}
export interface AccessContext<T> {
  items: T[];
  nextCursor: string | null;
  evaluatedAt: string;
  versions: Record<string, string>;
}
export interface ObjectSummary {
  objectId: string;
  databaseName: string;
  schemaName: string | null;
  tableName: string;
  objectType: string;
  tableComment: string | null;
  authorized: boolean;
  metadataVisible: boolean;
}
export interface ObjectDetail extends ObjectSummary {
  dictionaryTitle: string | null;
  dictionaryDescription: string | null;
  snapshotVersion: number;
  generation: number;
  columns: Array<{
    ordinal: number;
    name: string;
    databaseType: string;
    jdbcType: number;
    nullable: boolean;
    precision: number | null;
    scale: number | null;
    comment: string | null;
    primaryKey: boolean;
    title: string | null;
    description: string | null;
  }>;
  foreignKeys: unknown[];
  foreignKeysRestricted: boolean;
}
export interface TokenView {
  id: string;
  name: string;
  prefix: string;
  scopes: string[];
  platformId: string | null;
  expiresAt: string;
  lastUsedAt: string | null;
  createdAt: string;
  status: string;
}
export interface AuditSummary {
  requestId: string;
  tool: string;
  datasourceId: string | null;
  platformId: string | null;
  startedAt: string;
  completedAt: string | null;
  result: string;
  verdict: string;
  errorCode: string | null;
  sqlMasked: string | null;
  durationMs: number | null;
  rowsReturned: number | null;
  truncated: boolean;
}
export interface AuditDetail extends AuditSummary {
  resourceRefs: unknown;
  versions: unknown;
  queueMs: number | null;
  dbMs: number | null;
  responseBytes: number | null;
}
export interface McpConfig {
  transport: string;
  url: string | null;
  authentication: string[];
  configTemplate: string;
  compatibilityStatus: string;
  validatedVersion: string | null;
}
export interface AuthorizationView {
  id: string;
  clientName: string;
  platformId: string | null;
  issuedAt: string;
  lastUsedAt: string | null;
  status: string;
  absoluteExpiresAt: string | null;
}

export function setMePortalCsrfToken(value: string | null): void {
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
  return normalizeMeResponse(path, result.data) as T;
}

function query(values: Record<string, string | undefined>): string {
  const params = new URLSearchParams();
  Object.entries(values).forEach(([key, value]) => {
    if (value) params.set(key, value);
  });
  return params.size ? `?${params.toString()}` : '';
}

export const mePortalApi = {
  accessContext: {
    platforms: (cursor?: string, signal?: AbortSignal) =>
      request<AccessContext<PlatformItem>>(`/api/v1/me/access-context${query({ level: 'PLATFORM', cursor, pageSize: '100' })}`, { signal }),
    datasources: (platformId: string, cursor?: string, signal?: AbortSignal) =>
      request<AccessContext<DatasourceItem>>(`/api/v1/me/access-context${query({ level: 'DATASOURCE', platformId, cursor, pageSize: '100' })}`, {
        signal,
      }),
    namespaces: (platformId: string, datasourceId: string, cursor?: string, signal?: AbortSignal) =>
      request<AccessContext<NamespaceItem>>(
        `/api/v1/me/access-context${query({ level: 'NAMESPACE', platformId, datasourceId, cursor, pageSize: '100' })}`,
        { signal },
      ),
  },
  dictionary: {
    list: (
      params: { platformId: string; datasourceId: string; databaseName?: string; schemaName?: string; keyword?: string; cursor?: string },
      signal?: AbortSignal,
    ) => request<{ items: ObjectSummary[]; nextCursor: string | null }>(`/api/v1/me/dictionary${query({ ...params, pageSize: '20' })}`, { signal }),
    detail: (platformId: string, objectId: string, signal?: AbortSignal) =>
      request<ObjectDetail>(`/api/v1/me/dictionary/${encodeURIComponent(objectId)}${query({ platformId })}`, { signal }),
  },
  tokens: {
    list: (status?: string, cursor?: string, signal?: AbortSignal) =>
      request<{ items: TokenView[]; nextCursor: string | null }>(`/api/v1/me/tokens${query({ status, cursor, pageSize: '20' })}`, { signal }),
    create: (body: { name: string; platformId: string; scopes: string[]; expiresAt?: string }) =>
      request<{ token: string; metadata: TokenView }>('/api/v1/me/tokens', { method: 'POST', body: JSON.stringify(body) }),
    revoke: (id: string) => request<TokenView>(`/api/v1/me/tokens/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  },
  history: {
    list: (params: { from?: string; to?: string; tool?: string; result?: string; verdict?: string; cursor?: string } = {}, signal?: AbortSignal) =>
      request<{ items: AuditSummary[]; nextCursor: string | null }>(`/api/v1/me/history${query({ ...params, pageSize: '20' })}`, { signal }),
    detail: (requestId: string, signal?: AbortSignal) => request<AuditDetail>(`/api/v1/me/history/${encodeURIComponent(requestId)}`, { signal }),
  },
  mcpConfig: (platformId: string, clientType: string, signal?: AbortSignal) =>
    request<McpConfig>(`/api/v1/me/mcp-config${query({ platformId, clientType })}`, { signal }),
  authorizations: {
    list: (cursor?: string, signal?: AbortSignal) =>
      request<{ items: AuthorizationView[]; nextCursor: string | null }>(`/api/v1/me/authorizations${query({ cursor, pageSize: '20' })}`, { signal }),
    revoke: (id: string) =>
      request<{ id: string; status: string; revoked: boolean }>(`/api/v1/me/authorizations/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  },
  changePassword: (oldPassword: string, newPassword: string) =>
    request<unknown>('/api/v1/session/password', { method: 'PUT', body: JSON.stringify({ oldPassword, newPassword }) }),
};
