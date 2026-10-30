import { idempotencyKey, request } from './http';

export type SubjectType = 'USER' | 'ROLE' | 'DEPT';
export type ResourceType = 'DATASOURCE' | 'NAMESPACE' | 'OBJECT';
export interface GrantWrite {
  subjectType: SubjectType;
  subjectId: string;
  resourceType: ResourceType;
  resourceId: string;
  resourceGeneration: string;
  action: 'META' | 'READ';
  effect: 'ALLOW' | 'DENY';
  includeDescendants: boolean;
  validFrom: string;
  validTo: string | null;
  reason: string;
}
export interface Grant extends GrantWrite {
  id: string;
  status: string;
  version: string;
}
/** 与服务端 PreviewResponse 对齐：resourceId 为 ResourceIds 编码值，sourceGrantIds 为授权行 id。 */
export interface Preview {
  decisions: { resourceId: string; meta: boolean; read: boolean; sourceGrantIds: string[] }[];
  evaluatedAt: string;
  nextBoundaryAt: string | null;
}

function grantIds(row: Grant): Grant {
  return {
    ...row,
    id: String(row.id),
    subjectId: String(row.subjectId),
    resourceId: String(row.resourceId),
    resourceGeneration: String(row.resourceGeneration),
    version: String(row.version),
  };
}

export async function listGrants(subjectType: SubjectType, subjectId: string, signal?: AbortSignal): Promise<Grant[]> {
  const params = new URLSearchParams({ subjectType, subjectId });
  const { data } = await request<Grant[]>(`/api/v1/admin/acl/grants?${params}`, { signal });
  return data.map(grantIds);
}

export async function createGrant(grant: GrantWrite, signal?: AbortSignal): Promise<Grant> {
  const { data } = await request<Grant>('/api/v1/admin/acl/grants', { method: 'POST', body: grant, signal });
  return grantIds(data);
}

export async function deleteGrant(id: string, signal?: AbortSignal): Promise<void> {
  const { etag } = await request<Grant>(`/api/v1/admin/acl/grants/${id}`, { signal });
  await request(`/api/v1/admin/acl/grants/${id}`, { method: 'DELETE', signal, headers: etag ? { 'If-Match': etag } : undefined });
}

/** 服务端 PreviewRequest 只接受 subjectUserId；ROLE/DEPT 预览未实现。 */
export interface PreviewAccessParams {
  subjectUserId: string;
  platformId: string;
  /** ResourceIds 编码空间（OBJECT=(3<<48)|tableId），1..100 个。 */
  resourceIds: string[];
  at?: string;
}

export async function previewAccess(params: PreviewAccessParams, signal?: AbortSignal): Promise<Preview> {
  const body: Record<string, unknown> = {
    subjectUserId: params.subjectUserId,
    platformId: params.platformId,
    resourceIds: params.resourceIds,
  };
  if (params.at) body.at = params.at;
  const { data } = await request<Preview>('/api/v1/admin/acl/preview', { method: 'POST', body, signal });
  return {
    ...data,
    decisions: (data.decisions ?? []).map((decision) => ({
      ...decision,
      resourceId: String(decision.resourceId),
      sourceGrantIds: (decision.sourceGrantIds ?? []).map((id) => String(id)),
    })),
  };
}

/* ─── 管理控制台:平台 / 客户端 / 委派 / 审计 / 任务 / 全部规则 ─── */

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface PlatformRow {
  id: string;
  code: string;
  name: string;
  tier: number;
  catalogMode: string;
  proofMode: string;
  proofConfigured?: boolean;
  status: string;
  version: string;
}

export interface ClientRow {
  id: string;
  clientId: string;
  platformId: string;
  name: string;
  type: string;
  authMethods: string[];
  redirectUris: string[];
  allowedScopes: string[];
  jwksUri: string | null;
  status: string;
  version: string;
}

export interface DelegationRow {
  id: string;
  userId: string;
  departmentRootId: string;
  resourceType: string;
  resourceId: string;
  allowedActions: string[];
  validFrom: string;
  validTo: string | null;
  status: string;
  version: string;
}

export interface AuditRow {
  requestId: string;
  userId: string;
  platformId: string;
  datasourceId: string;
  tool: string;
  startedAt: string;
  completedAt: string | null;
  result: string;
  verdict: string | null;
  errorCode: string | null;
  sqlMasked?: string;
  durationMs?: number | null;
  rowsReturned?: number | null;
  truncated?: boolean;
  resourceRefs?: { objectId?: string; datasourceId?: string; [k: string]: unknown }[];
  versions?: Record<string, string>;
  queueMs?: number | null;
  dbMs?: number | null;
  responseBytes?: number | null;
  fingerprint?: string;
}

export interface JobRow {
  id: string;
  datasourceId: string;
  type: string;
  state: string;
  progress?: { processed: number; total: number };
  startedAt?: string;
  finishedAt?: string | null;
  snapshotId?: string | null;
  errorCode?: string | null;
}

export interface TokenRow {
  id: string;
  name: string;
  prefix?: string;
  scopes: string[];
  platformId: string;
  expiresAt: string;
  lastUsedAt: string | null;
  createdAt: string;
  status: string;
}

export interface AuthorizationRow {
  id: string;
  clientName: string;
  platformId: string;
  issuedAt: string;
  lastUsedAt: string | null;
  status: string;
  absoluteExpiresAt: string;
}

async function getPage<T>(path: string, signal?: AbortSignal): Promise<Page<T>> {
  const { data } = await request<Page<T>>(path, { signal });
  return { items: data.items ?? [], nextCursor: data.nextCursor ?? null };
}

export async function getPlatform(id: string, signal?: AbortSignal): Promise<PlatformRow> {
  const { data } = await request<PlatformRow>(`/api/v1/admin/platforms/${encodeURIComponent(id)}`, { signal });
  return data;
}

export function listPlatforms(params?: { pageSize?: number; cursor?: string; signal?: AbortSignal }): Promise<Page<PlatformRow>> {
  const q = new URLSearchParams({ pageSize: String(params?.pageSize ?? 100) });
  if (params?.cursor) q.set('cursor', params.cursor);
  return getPage(`/api/v1/admin/platforms?${q}`, params?.signal);
}

export function listClients(platformId: string, signal?: AbortSignal): Promise<Page<ClientRow>> {
  return getPage(`/api/v1/admin/platforms/${platformId}/clients?pageSize=100`, signal);
}

export interface PlatformWrite {
  code: string;
  name: string;
  tier: number;
  catalogMode: string;
  proofMode: string;
  status: string;
}

/** 客户端写入体：与服务端 ClientWrite 一致，status/allowedScopes 必填。 */
export interface ClientWrite {
  name: string;
  type: string;
  authMethods: string[];
  redirectUris: string[];
  allowedScopes: string[];
  jwksUri: string | null;
  status: string;
}

/** 客户端创建响应：CONFIDENTIAL + client_secret_basic 时一次性返回明文 secret。 */
export interface ClientCreated {
  client: ClientRow;
  clientSecret?: string;
}

async function writeRow<T>(
  path: string,
  init: { method: 'POST' | 'PUT' | 'DELETE'; body?: unknown } & ({ kind: 'create' } | { kind: 'update' | 'delete'; version: string | number }),
): Promise<T> {
  const headers = new Headers();
  if (init.kind === 'create') headers.set('Idempotency-Key', idempotencyKey());
  else headers.set('If-Match', `"${init.version}"`);
  const { data } = await request<T>(path, { method: init.method, body: init.body, headers });
  return data;
}

export function createPlatform(body: PlatformWrite): Promise<PlatformRow> {
  return writeRow<PlatformRow>('/api/v1/admin/platforms', { method: 'POST', kind: 'create', body });
}

export function updatePlatform(id: string, body: PlatformWrite, version: string | number): Promise<PlatformRow> {
  return writeRow<PlatformRow>(`/api/v1/admin/platforms/${encodeURIComponent(id)}`, { method: 'PUT', kind: 'update', version, body });
}

export function deletePlatform(id: string, version: string | number): Promise<void> {
  return writeRow<void>(`/api/v1/admin/platforms/${encodeURIComponent(id)}`, { method: 'DELETE', kind: 'delete', version });
}

export function createClient(platformId: string, body: ClientWrite): Promise<ClientCreated> {
  return writeRow<ClientCreated>(`/api/v1/admin/platforms/${platformId}/clients`, { method: 'POST', kind: 'create', body });
}

export function updateClient(id: string, body: ClientWrite, version: string | number): Promise<ClientRow> {
  return writeRow<ClientRow>(`/api/v1/admin/clients/${encodeURIComponent(id)}`, { method: 'PUT', kind: 'update', version, body });
}

export function deleteClient(id: string, version: string | number): Promise<void> {
  return writeRow<void>(`/api/v1/admin/clients/${encodeURIComponent(id)}`, { method: 'DELETE', kind: 'delete', version });
}

export function listDelegations(
  params: { userId?: string; status?: string; resourceType?: string; cursor?: string; pageSize?: number; signal?: AbortSignal } = {},
): Promise<Page<DelegationRow>> {
  const q = new URLSearchParams({ pageSize: String(params.pageSize ?? 20) });
  if (params.userId) q.set('userId', params.userId);
  if (params.status) q.set('status', params.status);
  if (params.resourceType) q.set('resourceType', params.resourceType);
  if (params.cursor) q.set('cursor', params.cursor);
  return getPage(`/api/v1/admin/delegations?${q}`, params.signal);
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

export async function createDelegation(body: DelegationWrite, signal?: AbortSignal): Promise<DelegationRow> {
  const { data } = await request<DelegationRow>('/api/v1/admin/delegations', {
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': idempotencyKey() },
    signal,
  });
  return data;
}

export async function revokeDelegation(id: string, signal?: AbortSignal): Promise<void> {
  const { etag } = await request(`/api/v1/admin/delegations/${id}`, { signal });
  await request(`/api/v1/admin/delegations/${id}`, { method: 'DELETE', signal, headers: etag ? { 'If-Match': etag } : undefined });
}

export function listAllGrants(params?: { resourceId?: string; pageSize?: number; signal?: AbortSignal }): Promise<Grant[]> {
  const q = new URLSearchParams({ pageSize: String(params?.pageSize ?? 200) });
  if (params?.resourceId) q.set('resourceId', params.resourceId);
  return request<Grant[]>(`/api/v1/admin/acl/grants?${q}`, { signal: params?.signal }).then(({ data }) => data.map(grantIds));
}

export function listJobs(params?: { datasourceId?: string; pageSize?: number; signal?: AbortSignal }): Promise<Page<JobRow>> {
  const q = new URLSearchParams({ pageSize: String(params?.pageSize ?? 100) });
  if (params?.datasourceId) q.set('datasourceId', params.datasourceId);
  return getPage(`/api/v1/admin/jobs?${q}`, params?.signal);
}

export interface AuditQuery {
  userId?: string;
  platformId?: string;
  datasourceId?: string;
  tool?: string;
  result?: string;
  verdict?: string;
  errorCode?: string;
  from?: string;
  to?: string;
  cursor?: string;
  pageSize?: number;
}

export function listAudit(params: AuditQuery = {}, signal?: AbortSignal): Promise<Page<AuditRow>> {
  const q = new URLSearchParams({ pageSize: String(params.pageSize ?? 20) });
  for (const k of ['userId', 'platformId', 'datasourceId', 'tool', 'result', 'verdict', 'errorCode', 'from', 'to', 'cursor'] as const) {
    const v = params[k];
    if (v) q.set(k, v);
  }
  return getPage(`/api/v1/admin/audit?${q}`, signal);
}

export interface SensitiveAccessRow {
  id: string;
  operatorId: string;
  reason: string;
  fields: string[];
  createdAt: string;
}

/** 审计解密的访问留痕：谁、何时、以何理由解密了哪些字段。 */
export async function listSensitiveAccess(requestId: string, signal?: AbortSignal): Promise<SensitiveAccessRow[]> {
  const { data } = await request<SensitiveAccessRow[]>(`/api/v1/admin/audit/${encodeURIComponent(requestId)}/sensitive-access`, { signal });
  return data;
}

/* 触发一次数据源连通性测试,返回落库的 TEST 任务(含真实 state)。可选 draft 让服务端按内存中的表单(而非已落库行)验证。 */
export async function testDatasource(id: string, draft?: unknown, signal?: AbortSignal): Promise<JobRow> {
  const { data } = await request<JobRow>(`/api/v1/admin/datasources/${id}/test`, { method: 'POST', body: draft ? { draft } : {}, signal });
  return data;
}

export async function getJob(id: string, signal?: AbortSignal): Promise<JobRow> {
  const { data } = await request<JobRow>(`/api/v1/admin/jobs/${id}`, { signal });
  return data;
}

export async function getAudit(requestId: string, signal?: AbortSignal): Promise<AuditRow> {
  const { data } = await request<AuditRow>(`/api/v1/admin/audit/${encodeURIComponent(requestId)}`, { signal });
  return data;
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

export async function getPlatformScopes(id: string, signal?: AbortSignal): Promise<ScopeRead> {
  const { data } = await request<ScopeRead>(`/api/v1/admin/platforms/${encodeURIComponent(id)}/scopes`, { signal });
  return data;
}

/** 整组替换：PUT + If-Match；成功后 scopeVersion/version 均递增。 */
export function savePlatformScopes(id: string, items: ScopeRule[], version: string | number, signal?: AbortSignal): Promise<ScopeRead> {
  return writeRow<ScopeRead>(`/api/v1/admin/platforms/${encodeURIComponent(id)}/scopes`, {
    method: 'PUT',
    kind: 'update',
    version,
    body: { items },
    signal,
  } as Parameters<typeof writeRow>[1] & { signal?: AbortSignal });
}

export interface OperationRow {
  id: string;
  actorId: string;
  requestId: string;
  action: string;
  aggregateType: string;
  aggregateId: string;
  beforeMasked: string | null;
  afterMasked: string | null;
  occurredAt: string;
}

/** 管理操作日志（admin_operation_log）：真实后端域，preview 未提供该端点（501）→ 页面展示受控空态。 */
export function listOperations(
  params: { aggregateType?: string; actorId?: string; action?: string; cursor?: string; pageSize?: number } = {},
  signal?: AbortSignal,
): Promise<Page<OperationRow>> {
  const q = new URLSearchParams({ pageSize: String(params.pageSize ?? 20) });
  if (params.aggregateType) q.set('aggregateType', params.aggregateType);
  if (params.actorId) q.set('actorId', params.actorId);
  if (params.action) q.set('action', params.action);
  if (params.cursor) q.set('cursor', params.cursor);
  return getPage(`/api/v1/admin/operations?${q}`, signal);
}

export interface AuditDecryptResult {
  sqlRaw?: string;
  sqlFinal?: string;
  expiresAt: string;
}

/** 审计解密：需理由（10–500 字符）与至少一个字段；明文仅短时可见，到期自动清除。 */
export async function decryptAudit(requestId: string, reason: string, fields: string[], signal?: AbortSignal): Promise<AuditDecryptResult> {
  const { data } = await request<AuditDecryptResult>(`/api/v1/admin/audit/${encodeURIComponent(requestId)}/decrypt`, {
    method: 'POST',
    body: { reason, fields },
    signal,
  });
  return data;
}

/* 个人门户 */
export function listMyTokens(params: { status?: string; cursor?: string; pageSize?: number } = {}, signal?: AbortSignal): Promise<Page<TokenRow>> {
  const q = new URLSearchParams({ pageSize: String(params.pageSize ?? 20) });
  if (params.status) q.set('status', params.status);
  if (params.cursor) q.set('cursor', params.cursor);
  return getPage(`/api/v1/me/tokens?${q}`, signal);
}

export interface TokenWrite {
  name: string;
  platformId: string;
  scopes: string[];
  expiresAt: string;
}

export interface TokenCreated {
  token: string;
  metadata: TokenRow;
}

/** 创建 PAT：响应携带一次性明文 token，metadata 为落库视图（不再含明文）。 */
export async function createToken(body: TokenWrite, signal?: AbortSignal): Promise<TokenCreated> {
  const { data } = await request<TokenCreated>('/api/v1/me/tokens', {
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': idempotencyKey() },
    signal,
  });
  return data;
}

export async function revokeToken(id: string, signal?: AbortSignal): Promise<void> {
  const { etag } = await request(`/api/v1/me/tokens/${id}`, { signal });
  await request(`/api/v1/me/tokens/${id}`, { method: 'DELETE', signal, headers: etag ? { 'If-Match': etag } : undefined });
}

export function listMyAuthorizations(params: { cursor?: string; pageSize?: number } = {}, signal?: AbortSignal): Promise<Page<AuthorizationRow>> {
  const q = new URLSearchParams({ pageSize: String(params.pageSize ?? 20) });
  if (params.cursor) q.set('cursor', params.cursor);
  return getPage(`/api/v1/me/authorizations?${q}`, signal);
}

export async function revokeAuthorization(id: string, signal?: AbortSignal): Promise<void> {
  const { etag } = await request(`/api/v1/me/authorizations/${id}`, { signal });
  await request(`/api/v1/me/authorizations/${id}`, { method: 'DELETE', signal, headers: etag ? { 'If-Match': etag } : undefined });
}

export interface MyHistoryParams {
  from?: string;
  to?: string;
  tool?: string;
  result?: string;
  verdict?: string;
  cursor?: string;
  pageSize?: number;
}

export function listMyHistory(params: MyHistoryParams = {}, signal?: AbortSignal): Promise<Page<AuditRow>> {
  const q = new URLSearchParams({ pageSize: String(params.pageSize ?? 20) });
  if (params.from) q.set('from', params.from);
  if (params.to) q.set('to', params.to);
  if (params.tool) q.set('tool', params.tool);
  if (params.result) q.set('result', params.result);
  if (params.verdict) q.set('verdict', params.verdict);
  if (params.cursor) q.set('cursor', params.cursor);
  return getPage(`/api/v1/me/history?${q}`, signal);
}

export async function getMyHistoryDetail(requestId: string, signal?: AbortSignal): Promise<AuditRow> {
  const { data } = await request<AuditRow>(`/api/v1/me/history/${encodeURIComponent(requestId)}`, { signal });
  return data;
}
