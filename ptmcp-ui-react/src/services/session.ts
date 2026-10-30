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
  const { data } = await request<SessionInfo>('/api/v1/session/me', { signal });
  return data;
}

export async function login(username: string, password: string, signal?: AbortSignal): Promise<void> {
  await request('/api/v1/session/login', { method: 'POST', body: { username, password }, signal });
  await refreshCsrf();
}

export async function logout(signal?: AbortSignal): Promise<void> {
  await request('/api/v1/session/logout', { method: 'POST', signal }).catch(() => undefined);
}

/** 修改密码：服务端会让全部会话失效（preview 同样模拟登出），调用方应预期 401/重新登录。 */
export async function changePassword(oldPassword: string, newPassword: string, signal?: AbortSignal): Promise<void> {
  const path = '/api/v1/session/password';
  await request(path, { method: 'PUT', body: { oldPassword, newPassword }, signal });
}
