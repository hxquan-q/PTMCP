import { normalizeMeResponse } from './portalIds';
import { request as transportRequest } from './http';

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
  databaseName: string;
}

/** 个人字典目录行（Vue ObjectSummary 对应）。 */
export interface DictionaryItem {
  id: string;
  platformId?: string;
  datasourceId: string;
  databaseName: string;
  schemaName: string | null;
  objectName: string;
  objectType: string;
  authorized?: boolean;
  metadataVisible?: boolean;
  dictionaryTitle?: string | null;
  generation: string;
}

export interface AccessContext<T> {
  items: T[];
  nextCursor: string | null;
  evaluatedAt: string;
  versions: Record<string, string>;
}

export interface McpConfig {
  transport: string;
  url: string | null;
  authentication: string[];
  configTemplate: string;
  compatibilityStatus: string;
  validatedVersion: string | null;
}

async function request<T>(path: string, init: { signal?: AbortSignal } = {}): Promise<T> {
  const result = await transportRequest<T>(path, { signal: init.signal });
  return normalizeMeResponse(path, result.data) as T;
}

const query = (values: Record<string, string | undefined>): string => {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) if (value) params.set(key, value);
  const encoded = params.toString();
  return encoded ? `?${encoded}` : '';
};

export const mePortalApi = {
  accessContext: {
    platforms: (cursor?: string, signal?: AbortSignal) =>
      request<AccessContext<PlatformItem>>(`/api/v1/me/access-context${query({ level: 'PLATFORM', cursor })}`, { signal }),
    datasources: (platformId: string, cursor?: string, signal?: AbortSignal) =>
      request<AccessContext<DatasourceItem>>(`/api/v1/me/access-context${query({ level: 'DATASOURCE', platformId, cursor })}`, { signal }),
  },
  dictionaryList: (
    params: {
      platformId?: string;
      datasourceId?: string;
      databaseName?: string;
      schemaName?: string;
      keyword?: string;
      cursor?: string;
      pageSize?: number;
    } = {},
    signal?: AbortSignal,
  ) =>
    request<{ items: DictionaryItem[]; nextCursor: string | null }>(
      `/api/v1/me/dictionary${query({ ...params, pageSize: String(params.pageSize ?? 20) })}`,
      { signal },
    ),
  mcpConfig: (platformId: string, clientType: string, signal?: AbortSignal): Promise<McpConfig> =>
    request<McpConfig>(`/api/v1/me/mcp-config${query({ platformId, clientType })}`, { signal }),
  dictionaryDetail: <T>(objectId: string, platformId: string, signal?: AbortSignal): Promise<T> =>
    request<T>(`/api/v1/me/dictionary/${encodeURIComponent(objectId)}${query({ platformId })}`, { signal }),
};
