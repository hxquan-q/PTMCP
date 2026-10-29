import { request, ApiError } from './http';
import { newUuid } from './uuid';

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

export async function dataRequest<T>(path: string, options: DataRequestOptions = {}): Promise<ApiResult<T>> {
  const headers: Record<string, string> = { Accept: 'application/json', ...options.headers };
  if (options.etag) headers['If-Match'] = options.etag;
  const method = (options.method ?? 'GET').toUpperCase();
  if (method !== 'GET' && method !== 'HEAD') headers['Idempotency-Key'] ??= options.idempotencyKey ?? newUuid();
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
        details: cause.details,
      });
    }
    throw cause;
  }
}

async function adaptedRequest<Wire, Model>(path: string, options: DataRequestOptions, adapt: (dto: Wire) => Model): Promise<ApiResult<Model>> {
  const result = await dataRequest<Wire>(path, options);
  return { data: adapt(result.data), etag: result.etag };
}

export interface DatasourceRead {
  id: string;
  version?: string;
  code: string;
  name: string;
  engine: 'MYSQL' | 'POSTGRESQL' | 'SQLSERVER' | 'ORACLE' | 'MONGODB' | 'CLICKHOUSE' | 'SQLITE' | string;
  host: string;
  port: number;
  databaseName: string;
  username: string;
  tlsMode: string;
  poolMax: number;
  status: string;
  configVersion?: string;
  resourceVersion?: string;
  activeSnapshotId: string | null;
  lastSyncAt: string | null;
  lastSyncResult?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
  credentialConfigured: boolean;
  health: string;
}

export interface DatasourcePage {
  items: DatasourceRead[];
  nextCursor: string | null;
}

function identifier(value: string | number): string {
  if (typeof value === 'string') return value;
  if (typeof value === 'number' && Number.isSafeInteger(value)) return String(value);
  throw new Error('服务端资源标识或版本无效，请重新读取。');
}

function adaptDatasource(dto: DatasourceRead & { id: string | number }): DatasourceRead {
  return {
    ...dto,
    id: identifier(dto.id),
    version: dto.version === undefined ? undefined : identifier(dto.version as string | number),
    activeSnapshotId: dto.activeSnapshotId === null ? null : identifier(dto.activeSnapshotId as string | number),
  };
}

function adaptDatasourcePage(dto: { items: (DatasourceRead & { id: string | number })[]; nextCursor: string | null }): DatasourcePage {
  return { items: dto.items.map(adaptDatasource), nextCursor: dto.nextCursor };
}

export async function listDatasources(
  params: { keyword?: string; cursor?: string; pageSize?: number; signal?: AbortSignal } = {},
): Promise<ApiResult<DatasourcePage>> {
  const query = new URLSearchParams();
  if (params.keyword) query.set('keyword', params.keyword);
  if (params.cursor) query.set('cursor', params.cursor);
  query.set('pageSize', String(params.pageSize ?? 20));
  return adaptedRequest(`/api/v1/admin/datasources?${query}`, { signal: params.signal }, adaptDatasourcePage);
}

export interface MetadataColumn {
  ordinal: number;
  name: string;
  databaseType: string;
  jdbcType?: number;
  nullable: boolean;
  precision?: number | null;
  scale?: number | null;
  maxLength?: number | null;
  comment?: string | null;
  primaryKey?: boolean;
  foreignKey?: { refObjectName?: string; refColumnName?: string } | null;
}

export interface MetadataObject {
  id: string;
  datasourceId: string;
  namespaceId: string;
  databaseName: string;
  schemaName: string | null;
  objectName: string;
  tableName: string;
  objectType: string;
  tableComment?: string | null;
  generation: string;
  snapshotId?: string | null;
  snapshotVersion?: string;
  authorized?: boolean;
  metadataVisible?: boolean;
  columns?: MetadataColumn[];
  dictionary?: {
    title?: string;
    description?: string;
    ownerUserId?: string | null;
    version?: string;
    columns?: { name: string; displayName?: string; description?: string; sensitiveTags?: string[] }[];
  };
  foreignKeys?: { name: string; columns: string[]; refObjectName?: string; refColumns?: string[] }[];
  foreignKeysRestricted?: boolean;
}

export interface MetadataPage {
  items: MetadataObject[];
  nextCursor: string | null;
}

function adaptMetadataObject(dto: MetadataObject & { id: string | number }): MetadataObject {
  return {
    ...dto,
    id: identifier(dto.id),
    datasourceId: identifier(dto.datasourceId as string | number),
    namespaceId: identifier(dto.namespaceId as string | number),
    generation: String(dto.generation),
  };
}

function adaptMetadataPage(dto: { items: (MetadataObject & { id: string | number })[]; nextCursor: string | null }): MetadataPage {
  return { items: dto.items.map(adaptMetadataObject), nextCursor: dto.nextCursor };
}

export async function listMetadata(params: {
  datasourceId: string;
  pageSize?: number;
  cursor?: string;
  signal?: AbortSignal;
}): Promise<ApiResult<MetadataPage>> {
  const query = new URLSearchParams({ datasourceId: params.datasourceId, pageSize: String(params.pageSize ?? 100) });
  if (params.cursor) query.set('cursor', params.cursor);
  return adaptedRequest(`/api/v1/admin/metadata/objects?${query}`, { signal: params.signal }, adaptMetadataPage);
}

export async function getMetadataObject(id: string, signal?: AbortSignal): Promise<ApiResult<MetadataObject>> {
  return adaptedRequest(`/api/v1/admin/metadata/objects/${encodeURIComponent(id)}`, { signal }, adaptMetadataObject);
}

/* ─── 数据源读写契约:乐观并发,PUT/DELETE 必须携带 If-Match ─── */

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

export interface DatasourceDelete {
  deleted: boolean;
  id: string;
  version: string;
}

function adaptDatasourceDelete(dto: DatasourceDelete & { id: string | number }): DatasourceDelete {
  return { ...dto, id: identifier(dto.id), version: String(dto.version) };
}

export function getDatasource(id: string, signal?: AbortSignal): Promise<ApiResult<DatasourceRead>> {
  return adaptedRequest(`/api/v1/admin/datasources/${encodeURIComponent(id)}`, { signal }, adaptDatasource);
}

export function updateDatasource(id: string, body: DatasourceWrite, etag: string, idempotencyKey?: string): Promise<ApiResult<DatasourceRead>> {
  return adaptedRequest(`/api/v1/admin/datasources/${encodeURIComponent(id)}`, { method: 'PUT', body, etag, idempotencyKey }, adaptDatasource);
}

export function deleteDatasource(id: string, etag: string, idempotencyKey?: string): Promise<ApiResult<DatasourceDelete>> {
  return adaptedRequest(`/api/v1/admin/datasources/${encodeURIComponent(id)}`, { method: 'DELETE', etag, idempotencyKey }, adaptDatasourceDelete);
}

export function syncDatasource(id: string, idempotencyKey?: string): Promise<ApiResult<{ id: string; state: string }>> {
  return adaptedRequest(
    `/api/v1/admin/datasources/${encodeURIComponent(id)}/sync`,
    { method: 'POST', body: { mode: 'FULL' }, idempotencyKey },
    (dto: { id: string | number; state: string }) => ({ ...dto, id: identifier(dto.id) }),
  );
}
