import { normalizeMeResponse } from './portalIds';
import { request as transportRequest } from './http';

export interface PlatformItem {
  type: 'PLATFORM';
  id: string;
  name: string;
  tier: number;
  catalogMode: string;
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
  },
  mcpConfig: (platformId: string, clientType: string, signal?: AbortSignal) =>
    request<McpConfig>(`/api/v1/me/mcp-config${query({ platformId, clientType })}`, { signal }),
};
