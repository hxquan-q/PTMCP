import { request, type RequestOptions } from './http';
import { newUuid } from './uuid';
import { controlPlaneAvailable } from './frontendMode';
import type { AuditDetail, AuditSummary } from './mePortalApi';

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}
export interface PlatformWrite {
  code: string;
  name: string;
  tier: number;
  catalogMode: string;
  proofMode: string;
  status: string;
  proofConfig?: Record<string, string>;
}
export interface Platform extends PlatformWrite {
  id: string;
  version: string;
  scopeVersion: string;
  securityVersion: string;
  proofConfigured: boolean;
}
export interface ClientWrite {
  name: string;
  type: string;
  authMethods: string[];
  redirectUris: string[];
  allowedScopes: string[];
  jwksUri: string;
  status: string;
}
export interface Client extends ClientWrite {
  id: string;
  clientId: string;
  platformId: string;
  version: string;
  secretConfigured: boolean;
}
export interface ScopeRule {
  resourceType: string;
  resourceId: string;
  resourceGeneration: string;
  action: string;
  includeDescendants: boolean;
}
export interface ScopeRead {
  items: ScopeRule[];
  scopeVersion: string;
  version: string;
}
export interface DelegationWrite {
  userId: string;
  departmentRootId: string;
  resourceType: string;
  resourceId: string;
  allowedActions: string[];
  validFrom: string;
  validTo: string;
}
export interface Delegation extends DelegationWrite {
  id: string;
  status: string;
  version: string;
}
export interface AdminAuditSummary extends AuditSummary {
  userId: string;
  fingerprint?: string;
}
export interface AdminAuditDetail extends AuditDetail {
  userId: string;
  fingerprint?: string;
}
export interface DecryptedAudit {
  sqlRaw?: string;
  sqlFinal?: string;
  expiresAt: string;
}

export function queryString(values: Record<string, string | undefined>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) if (value) params.set(key, value);
  return params.size ? `?${params}` : '';
}
async function controlRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  if (!controlPlaneAvailable) throw new Error('该管理接口尚未接入，请在独立前端预览模式检查交互。');
  return (await request<T>(`/api/v1/admin${path}`, options)).data;
}
const versioned = (version: string): HeadersInit => ({ 'If-Match': `"${version}"`, 'Idempotency-Key': newUuid() });
const create = (body: unknown): RequestOptions => ({ method: 'POST', body, headers: { 'Idempotency-Key': newUuid() } });
export const controlPlane = {
  effective: (filters: Record<string, string | undefined>, signal?: AbortSignal) =>
    controlRequest<
      Page<{ resourceId: string; resourceType: string; meta: boolean; read: boolean; sourceGrantIds: string[]; reasonCodes?: string[] }>
    >(`/acl/effective${queryString(filters)}`, { signal }),
  platforms: {
    list: (filters: Record<string, string | undefined>, signal?: AbortSignal) =>
      controlRequest<Page<Platform>>(`/platforms${queryString({ ...filters, pageSize: '20' })}`, { signal }),
    get: (id: string, signal?: AbortSignal) => controlRequest<Platform>(`/platforms/${encodeURIComponent(id)}`, { signal }),
    save: (body: PlatformWrite, row?: Platform) =>
      row
        ? controlRequest<Platform>(`/platforms/${encodeURIComponent(row.id)}`, { method: 'PUT', body, headers: versioned(row.version) })
        : controlRequest<Platform>('/platforms', create(body)),
    remove: (row: Platform) => controlRequest(`/platforms/${encodeURIComponent(row.id)}`, { method: 'DELETE', headers: versioned(row.version) }),
  },
  clients: {
    list: (platformId: string, filters: Record<string, string | undefined>, signal?: AbortSignal) =>
      controlRequest<Page<Client>>(`/platforms/${encodeURIComponent(platformId)}/clients${queryString(filters)}`, { signal }),
    save: (platformId: string, body: ClientWrite, row?: Client) =>
      row
        ? controlRequest<Client>(`/clients/${encodeURIComponent(row.id)}`, { method: 'PUT', body, headers: versioned(row.version) }).then(
            (client) => ({ client }),
          )
        : controlRequest<{ client: Client; clientSecret?: string }>(`/platforms/${encodeURIComponent(platformId)}/clients`, create(body)),
    remove: (row: Client) => controlRequest(`/clients/${encodeURIComponent(row.id)}`, { method: 'DELETE', headers: versioned(row.version) }),
  },
  scopes: {
    get: (id: string, signal?: AbortSignal) => controlRequest<ScopeRead>(`/platforms/${encodeURIComponent(id)}/scopes`, { signal }),
    save: (id: string, items: ScopeRule[], version: string) =>
      controlRequest<ScopeRead>(`/platforms/${encodeURIComponent(id)}/scopes`, { method: 'PUT', body: { items }, headers: versioned(version) }),
  },
  delegations: {
    list: (filters: Record<string, string | undefined>, signal?: AbortSignal) =>
      controlRequest<Page<Delegation>>(`/delegations${queryString(filters)}`, { signal }),
    create: (body: DelegationWrite) => controlRequest<Delegation>('/delegations', create(body)),
    revoke: (row: Delegation) => controlRequest(`/delegations/${encodeURIComponent(row.id)}`, { method: 'DELETE', headers: versioned(row.version) }),
  },
  audit: {
    list: (filters: Record<string, string | undefined>, signal?: AbortSignal) =>
      controlRequest<Page<AdminAuditSummary>>(`/audit${queryString(filters)}`, { signal }),
    get: (id: string, signal?: AbortSignal) => controlRequest<AdminAuditDetail>(`/audit/${encodeURIComponent(id)}`, { signal }),
    decrypt: (id: string, reason: string, fields: string[], signal?: AbortSignal) =>
      controlRequest<DecryptedAudit>(`/audit/${encodeURIComponent(id)}/decrypt`, { ...create({ reason, fields }), signal }),
  },
};
