import { request, type RequestOptions } from './http';

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
export interface Preview {
  decisions: { resourceId: string; meta: boolean; read: boolean; sourceGrantIds: string[]; reasonCodes?: string[] }[];
  versions?: Record<string, string>;
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
    version: String(row.version)
  };
}
export async function listGrants(subjectType: SubjectType, subjectId: string, signal?: AbortSignal): Promise<Grant[]> {
  const params = new URLSearchParams({ subjectType, subjectId });
  const { data } = await request<Grant[]>(`/api/v1/admin/acl/grants?${params}`, { signal });
  return data.map(grantIds);
}
export async function getGrant(id: string): Promise<Grant> {
  const { data } = await request<Grant>(`/api/v1/admin/acl/grants/${encodeURIComponent(id)}`);
  return grantIds(data);
}
export async function saveGrant(body: GrantWrite, key: string, editing?: Grant): Promise<Grant> {
  const headers: Record<string, string> = { 'Idempotency-Key': key };
  if (editing) headers['If-Match'] = `"${editing.version}"`;
  const { data } = await request<Grant>(editing ? `/api/v1/admin/acl/grants/${encodeURIComponent(editing.id)}` : '/api/v1/admin/acl/grants', {
    method: editing ? 'PUT' : 'POST',
    body,
    headers
  });
  return grantIds(data);
}
export async function revokeGrantApi(row: Grant): Promise<void> {
  await request(`/api/v1/admin/acl/grants/${encodeURIComponent(row.id)}`, { method: 'DELETE', headers: { 'If-Match': `"${row.version}"` } });
}
export async function previewAccess(
  subjectUserId: string,
  platformId: string,
  resourceIds: string[],
  at?: string,
  options: RequestOptions = {}
): Promise<Preview> {
  const { data } = await request<Preview>('/api/v1/admin/acl/preview', {
    ...options,
    method: 'POST',
    body: { subjectUserId, platformId, resourceIds, ...(at ? { at } : {}) }
  });
  return {
    ...data,
    decisions: data.decisions.map((item) => ({ ...item, resourceId: String(item.resourceId), sourceGrantIds: item.sourceGrantIds.map(String) }))
  };
}
