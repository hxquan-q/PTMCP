import { request, type RequestOptions } from './http';
import { normalizeOrgResponse } from './portalIds';
import { newUuid } from './uuid';
import {
  auditApiReady,
  clientApiReady,
  controlPlaneAvailable,
  delegationApiReady,
  effectiveApiReady,
  platformApiReady,
  scopeApiReady,
} from './frontendMode';
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
async function controlRequest<T>(path: string, options: RequestOptions = {}, ready: boolean = controlPlaneAvailable): Promise<T> {
  if (!ready) throw new Error('该管理接口尚未接入，请在独立前端预览模式检查交互。');
  return (await request<T>(`/api/v1/admin${path}`, options)).data;
}
const versioned = (version: string): HeadersInit => ({ 'If-Match': `"${version}"`, 'Idempotency-Key': newUuid() });
const create = (body: unknown): RequestOptions => ({ method: 'POST', body, headers: { 'Idempotency-Key': newUuid() } });
/** 委派域后端已实现（DelegationAdminController），预览模式继续走合成数据。 */
const delegationReady = delegationApiReady || controlPlaneAvailable;
/** 当前有效资源端点已实现，预览模式继续走合成判定。 */
const effectiveReady = effectiveApiReady || controlPlaneAvailable;
/** 平台 CRUD 已实现；平台详情页的客户端/Scope 域仍由 controlPlaneAvailable 控制。 */
const platformReady = platformApiReady || controlPlaneAvailable;
/** 平台 Scope 读写已实现，整组替换 + If-Match。 */
const scopeReady = scopeApiReady || controlPlaneAvailable;
/** 客户端管理已实现；secret 只在创建响应出现一次。 */
const clientReady = clientApiReady || controlPlaneAvailable;
/** 审计列表/详情/解密已实现；解密需 reason 并单独落敏感访问审计。 */
const auditReady = auditApiReady || controlPlaneAvailable;
export const controlPlane = {
  effective: (filters: Record<string, string | undefined>, signal?: AbortSignal) =>
    controlRequest<
      Page<{ resourceId: string; resourceType: string; meta: boolean; read: boolean; sourceGrantIds: string[]; reasonCodes?: string[] }>
    >(`/acl/effective${queryString(filters)}`, { signal }, effectiveReady),
  platforms: {
    list: (filters: Record<string, string | undefined>, signal?: AbortSignal) =>
      controlRequest<Page<Platform>>(`/platforms${queryString({ ...filters, pageSize: '20' })}`, { signal }, platformReady).then(
        (page) => normalizeOrgResponse('/api/v1/admin/platforms', page) as Page<Platform>,
      ),
    get: (id: string, signal?: AbortSignal) => controlRequest<Platform>(`/platforms/${encodeURIComponent(id)}`, { signal }, platformReady),
    save: (body: PlatformWrite, row?: Platform) =>
      row
        ? controlRequest<Platform>(
            `/platforms/${encodeURIComponent(row.id)}`,
            { method: 'PUT', body, headers: versioned(row.version) },
            platformReady,
          )
        : controlRequest<Platform>('/platforms', create(body), platformReady),
    remove: (row: Platform) =>
      controlRequest(`/platforms/${encodeURIComponent(row.id)}`, { method: 'DELETE', headers: versioned(row.version) }, platformReady),
  },
  clients: {
    list: (platformId: string, filters: Record<string, string | undefined>, signal?: AbortSignal) =>
      controlRequest<Page<Client>>(`/platforms/${encodeURIComponent(platformId)}/clients${queryString(filters)}`, { signal }, clientReady),
    save: (platformId: string, body: ClientWrite, row?: Client) =>
      row
        ? controlRequest<Client>(
            `/clients/${encodeURIComponent(row.id)}`,
            { method: 'PUT', body, headers: versioned(row.version) },
            clientReady,
          ).then((client) => ({ client }))
        : controlRequest<{ client: Client; clientSecret?: string }>(
            `/platforms/${encodeURIComponent(platformId)}/clients`,
            create(body),
            clientReady,
          ),
    remove: (row: Client) =>
      controlRequest(`/clients/${encodeURIComponent(row.id)}`, { method: 'DELETE', headers: versioned(row.version) }, clientReady),
  },
  scopes: {
    get: (id: string, signal?: AbortSignal) => controlRequest<ScopeRead>(`/platforms/${encodeURIComponent(id)}/scopes`, { signal }, scopeReady),
    save: (id: string, items: ScopeRule[], version: string) =>
      controlRequest<ScopeRead>(
        `/platforms/${encodeURIComponent(id)}/scopes`,
        { method: 'PUT', body: { items }, headers: versioned(version) },
        scopeReady,
      ),
  },
  delegations: {
    list: (filters: Record<string, string | undefined>, signal?: AbortSignal) =>
      controlRequest<Page<Delegation>>(`/delegations${queryString(filters)}`, { signal }, delegationReady).then(
        (page) => normalizeOrgResponse('/api/v1/admin/delegations', page) as Page<Delegation>,
      ),
    create: (body: DelegationWrite) =>
      controlRequest<Delegation>('/delegations', create(body), delegationReady).then(
        (row) => normalizeOrgResponse('/api/v1/admin/delegations', row) as Delegation,
      ),
    revoke: (row: Delegation) =>
      controlRequest(`/delegations/${encodeURIComponent(row.id)}`, { method: 'DELETE', headers: versioned(row.version) }, delegationReady),
  },
  audit: {
    list: (filters: Record<string, string | undefined>, signal?: AbortSignal) =>
      controlRequest<Page<AdminAuditSummary>>(`/audit${queryString(filters)}`, { signal }, auditReady),
    get: (id: string, signal?: AbortSignal) => controlRequest<AdminAuditDetail>(`/audit/${encodeURIComponent(id)}`, { signal }, auditReady),
    decrypt: (id: string, reason: string, fields: string[], signal?: AbortSignal) =>
      controlRequest<DecryptedAudit>(`/audit/${encodeURIComponent(id)}/decrypt`, { ...create({ reason, fields }), signal }, auditReady),
    sensitiveAccess: (id: string, signal?: AbortSignal) =>
      controlRequest<{ id: string; operatorId: string; reason: string; fields: string[]; createdAt: string }[]>(
        `/audit/${encodeURIComponent(id)}/sensitive-access`,
        { signal },
        auditReady,
      ),
    operations: {
      list: (filters: Record<string, string | undefined>, signal?: AbortSignal) =>
        controlRequest<
          Page<{
            id: string;
            actorId: string;
            requestId: string;
            action: string;
            aggregateType: string;
            aggregateId: string;
            beforeMasked: string | null;
            afterMasked: string | null;
            occurredAt: string;
          }>
        >(`/operations${queryString(filters)}`, { signal }, auditReady),
    },
  },
};
