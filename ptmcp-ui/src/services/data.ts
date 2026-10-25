import { request, ApiError } from './http';

export type DataRequestOptions = {
  method?: string;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
  etag?: string;
  idempotencyKey?: string;
};

export class DataRequestError extends Error {
  readonly status: number;
  readonly code?: string;
  readonly requestId?: string;
  readonly retryable?: boolean;
  readonly details?: unknown;

  constructor(message: string, status: number, options: { code?: string; requestId?: string; retryable?: boolean; details?: unknown } = {}) {
    super(message);
    this.name = 'DataRequestError';
    this.status = status;
    this.code = options.code;
    this.requestId = options.requestId;
    this.retryable = options.retryable;
    this.details = options.details;
  }
}

export interface ApiResult<T> {
  data: T;
  etag: string | null;
}

export function setDataCsrfToken(token: string | null): void {
  void token;
  // CSRF is injected by the shared HTTP transport. Kept as a no-op for compatibility with callers.
}

function idempotencyKey(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

export async function dataRequest<T>(path: string, options: DataRequestOptions = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (options.etag) headers['If-Match'] = options.etag;
  const method = (options.method ?? 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'HEAD') headers['Idempotency-Key'] ??= options.idempotencyKey ?? idempotencyKey();
  try {
    const result = await request<T>(path, { method, body: options.body, headers, signal: options.signal });
    return { data: result.data, etag: result.etag };
  } catch (cause) {
    if (cause instanceof DataRequestError) throw cause;
    if (cause instanceof ApiError) {
      throw new DataRequestError(cause.message, cause.status, {
        code: cause.code,
        requestId: cause.requestId ?? undefined,
        retryable: cause.retryable,
        details: cause.details
      });
    }
    throw cause;
  }
}

export interface DatasourceRead {
  id: string;
  version?: string;
  code: string;
  name: string;
  engine: 'MYSQL' | 'POSTGRESQL' | string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  tlsMode: string;
  poolMax: number;
  status: 'DRAFT' | 'ACTIVE' | 'DISABLED' | string;
  configVersion: string;
  resourceVersion: string;
  activeSnapshotId: string | null;
  lastSyncAt: string | null;
  credentialConfigured: boolean;
  health: string;
  createdAt?: string;
  updatedAt?: string;
}

export interface DatasourceWrite {
  code: string;
  name: string;
  engine: string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  credential: string;
  tlsMode: 'VERIFY_FULL';
  poolMax: number;
  status: string;
}

export interface DatasourcePage {
  items: DatasourceRead[];
  nextCursor: string | null;
}

export interface JobView {
  id: string;
  type: 'TEST' | 'SYNC' | string;
  datasourceId: string;
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | string;
  progress?: { processed: number; total: number | null };
  startedAt: string | null;
  finishedAt: string | null;
  errorCode: string | null;
  snapshotId: string | null;
}

export interface MetadataItem {
  id: string;
  namespaceId: string;
  databaseName: string;
  schemaName: string | null;
  objectName: string;
  objectType: 'TABLE' | 'VIEW' | string;
  tableComment: string | null;
  generation: string;
  snapshotId: string;
  snapshotVersion: string;
}

export interface MetadataPage {
  items: MetadataItem[];
  nextCursor: string | null;
}

export interface MetadataColumn {
  ordinal: number;
  name: string;
  databaseType: string;
  jdbcType: number;
  nullable: boolean;
  precision: number | null;
  scale: number | null;
  maxLength: number | null;
  comment: string | null;
  primaryKey: boolean;
  foreignKey: unknown;
}

export interface DictionaryColumn {
  columnName: string;
  title: string | null;
  description: string | null;
}

export interface MetadataDictionary {
  title: string | null;
  description: string | null;
  ownerUserId: string | null;
  version: string;
  columns: DictionaryColumn[];
}

export interface MetadataDetail {
  id: string;
  namespaceId: string;
  databaseName: string;
  schemaName: string | null;
  objectName: string;
  objectType: string;
  tableComment: string | null;
  physicalIdentity: string;
  generation: string;
  snapshotId: string;
  snapshotVersion: string;
  columns: MetadataColumn[];
  dictionary: MetadataDictionary;
}

export interface DictionaryWrite {
  title: string;
  description: string;
  ownerUserId: string | null;
  columns: Array<{ columnName: string; title: string; description: string }>;
}

export async function listDatasources(
  params: {
    keyword?: string;
    engine?: string;
    status?: string;
    cursor?: string | null;
    pageSize?: number;
    signal?: AbortSignal;
  } = {}
): Promise<ApiResult<DatasourcePage>> {
  const query = new URLSearchParams();
  if (params.keyword) query.set('keyword', params.keyword);
  if (params.engine) query.set('engine', params.engine);
  if (params.status) query.set('status', params.status);
  if (params.cursor) query.set('cursor', params.cursor);
  query.set('pageSize', String(params.pageSize ?? 20));
  return dataRequest<DatasourcePage>(`/api/v1/admin/datasources?${query}`, { signal: params.signal });
}

export function getDatasource(id: string, signal?: AbortSignal): Promise<ApiResult<DatasourceRead>> {
  return dataRequest<DatasourceRead>(`/api/v1/admin/datasources/${encodeURIComponent(id)}`, { signal });
}

export function createDatasource(body: DatasourceWrite): Promise<ApiResult<DatasourceRead>> {
  return dataRequest<DatasourceRead>('/api/v1/admin/datasources', { method: 'POST', body });
}

export function updateDatasource(id: string, body: DatasourceWrite, etag: string): Promise<ApiResult<DatasourceRead>> {
  return dataRequest<DatasourceRead>(`/api/v1/admin/datasources/${encodeURIComponent(id)}`, { method: 'PUT', body, etag });
}

export function deleteDatasource(id: string, etag: string): Promise<ApiResult<{ deleted: boolean; id: string; version: string }>> {
  return dataRequest(`/api/v1/admin/datasources/${encodeURIComponent(id)}`, { method: 'DELETE', etag });
}

export function testDatasource(id: string, draft?: DatasourceWrite): Promise<ApiResult<JobView>> {
  return dataRequest<JobView>(`/api/v1/admin/datasources/${encodeURIComponent(id)}/test`, {
    method: 'POST',
    body: draft ? { draft } : undefined
  });
}

export function syncDatasource(id: string): Promise<ApiResult<JobView>> {
  return dataRequest<JobView>(`/api/v1/admin/datasources/${encodeURIComponent(id)}/sync`, { method: 'POST', body: { mode: 'FULL' } });
}

export function getJob(id: string, signal?: AbortSignal): Promise<ApiResult<JobView>> {
  return dataRequest<JobView>(`/api/v1/admin/jobs/${encodeURIComponent(id)}`, { signal });
}

export async function listMetadata(params: {
  datasourceId: string;
  namespaceId?: string;
  keyword?: string;
  kind?: string;
  cursor?: string | null;
  pageSize?: number;
  signal?: AbortSignal;
}): Promise<ApiResult<MetadataPage>> {
  const query = new URLSearchParams({ datasourceId: params.datasourceId, pageSize: String(params.pageSize ?? 20) });
  if (params.namespaceId) query.set('namespaceId', params.namespaceId);
  if (params.keyword) query.set('keyword', params.keyword);
  if (params.kind) query.set('kind', params.kind);
  if (params.cursor) query.set('cursor', params.cursor);
  return dataRequest<MetadataPage>(`/api/v1/admin/metadata/objects?${query}`, { signal: params.signal });
}

export function getMetadata(id: string, signal?: AbortSignal): Promise<ApiResult<MetadataDetail>> {
  return dataRequest<MetadataDetail>(`/api/v1/admin/metadata/objects/${encodeURIComponent(id)}`, { signal });
}

export function updateDictionary(id: string, body: DictionaryWrite, etag: string): Promise<ApiResult<MetadataDetail>> {
  return dataRequest<MetadataDetail>(`/api/v1/admin/metadata/objects/${encodeURIComponent(id)}/dictionary`, {
    method: 'PUT',
    body,
    etag
  });
}
