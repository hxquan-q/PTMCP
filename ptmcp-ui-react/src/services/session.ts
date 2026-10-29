import { refreshCsrf, request } from './http';

export interface SessionInfo {
  userId: string;
  username: string;
  displayName: string;
  department?: { id: string; name: string } | null;
  roles?: { id: string; name: string }[];
  permissions: string[];
  platformAdmin?: boolean;
  expiresAt?: string;
}

/** 探测当前会话；未登录时抛 401 ApiError。 */
export async function getSession(signal?: AbortSignal): Promise<SessionInfo> {
  const { data } = await request<SessionInfo>('/api/v1/session', { signal });
  return data;
}

export async function login(username: string, password: string, signal?: AbortSignal): Promise<void> {
  await request('/api/v1/session/login', { method: 'POST', body: { username, password }, signal });
  await refreshCsrf();
}

export async function logout(signal?: AbortSignal): Promise<void> {
  await request('/api/v1/session/logout', { method: 'POST', signal }).catch(() => undefined);
}
