import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { ApiError, onSessionExpired, refreshCsrf, request, setCsrfToken } from '../services/http';

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
  const isAuthenticated = computed(() => user.value !== null);
  const authenticated = isAuthenticated;

  function can(permission: string): boolean {
    return !!user.value?.permissions.includes(permission);
  }

  function clear(): void {
    user.value = null;
    setCsrfToken(null);
    window.dispatchEvent(new Event('ptmcp:sensitive-clear'));
  }

  onSessionExpired(() => {
    clear();
    initialized.value = true;
    window.dispatchEvent(new Event('ptmcp:session-expired'));
  });

  async function refresh(): Promise<void> {
    const result = await request<SessionUser>('/api/v1/session/me');
    user.value = { ...result.data, userId: String(result.data.userId) };
    statusError.value = null;
  }

  async function init(): Promise<void> {
    if (initialized.value) return;
    if (!initPromise) {
      checking.value = true;
      initPromise = refresh()
        .catch((error: unknown) => {
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
    await refreshCsrf();
    await request('/api/v1/session/login', { method: 'POST', body: { username, password } });
    await refreshCsrf();
    await refresh();
    initialized.value = true;
  }

  async function logout(): Promise<void> {
    await request('/api/v1/session/logout', { method: 'POST' });
    clear();
    initialized.value = true;
  }

  async function changePassword(oldPassword: string, newPassword: string): Promise<void> {
    await request('/api/v1/session/password', { method: 'PUT', body: { oldPassword, newPassword } });
    clear();
    initialized.value = true;
  }

  return { user, initialized, checking, statusError, isAuthenticated, authenticated, can, clear, init, refresh, login, logout, changePassword };
});
