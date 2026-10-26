import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { ApiError, invalidateSessionRequests, onSessionExpired, refreshCsrf, request } from '../services/http';

export interface SessionUser {
  userId: string;
  username: string;
  displayName: string;
  department: { id?: string; name: string } | null;
  roles: { id?: string; name: string; key?: string }[];
  permissions: string[];
  managedScopes: string[];
  expiresAt: string;
}

export const useSessionStore = defineStore('session', () => {
  const user = ref<SessionUser | null>(null);
  const initialized = ref(false);
  const checking = ref(false);
  const statusError = ref<string | null>(null);
  let initPromise: Promise<void> | null = null;
  let identityGeneration = 0;
  let refreshGeneration = 0;
  const isAuthenticated = computed(() => user.value !== null);
  const authenticated = isAuthenticated;

  function can(permission: string): boolean {
    return !!user.value?.permissions.includes(permission);
  }

  function clear(): void {
    identityGeneration += 1;
    refreshGeneration += 1;
    user.value = null;
    invalidateSessionRequests();
    window.dispatchEvent(new Event('ptmcp:sensitive-clear'));
  }

  onSessionExpired(() => {
    clear();
    initialized.value = true;
    window.dispatchEvent(new Event('ptmcp:session-expired'));
  });

  async function refresh(): Promise<void> {
    const identity = identityGeneration;
    const generation = ++refreshGeneration;
    const result = await request<SessionUser>('/api/v1/session/me');
    if (identity !== identityGeneration || generation !== refreshGeneration) return;
    user.value = { ...result.data, userId: String(result.data.userId) };
    statusError.value = null;
  }

  async function init(): Promise<void> {
    if (initialized.value) return;
    if (!initPromise) {
      const generation = identityGeneration;
      checking.value = true;
      initPromise = refresh()
        .catch((error: unknown) => {
          if (generation !== identityGeneration) return;
          clear();
          if (!(error instanceof ApiError && error.status === 401)) {
            statusError.value = error instanceof ApiError ? error.message : '暂时无法检查会话。';
          }
        })
        .finally(() => {
          initialized.value = true;
          checking.value = false;
          initPromise = null;
        });
    }
    return initPromise;
  }

  async function login(username: string, password: string): Promise<void> {
    clear();
    const generation = identityGeneration;
    const requireIdentity = () => {
      if (generation !== identityGeneration) throw new DOMException('登录流程已失效，请重新登录。', 'AbortError');
    };
    await refreshCsrf();
    requireIdentity();
    await request('/api/v1/session/login', { method: 'POST', body: { username, password } });
    requireIdentity();
    await refreshCsrf();
    requireIdentity();
    await refresh();
    requireIdentity();
    initialized.value = true;
  }

  async function logout(): Promise<void> {
    const generation = identityGeneration;
    await request('/api/v1/session/logout', { method: 'POST' });
    if (generation !== identityGeneration) return;
    clear();
    initialized.value = true;
  }

  async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
    const generation = identityGeneration;
    await request('/api/v1/session/password', { method: 'PUT', body: { oldPassword, newPassword } });
    if (generation !== identityGeneration) return;
    clear();
    initialized.value = true;
  }

  return { user, initialized, checking, statusError, isAuthenticated, authenticated, can, clear, init, refresh, login, logout, changePassword };
});
