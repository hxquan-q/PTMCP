import type { ApiError } from '../services/orgPortalApi';
import { onBeforeUnmount } from 'vue';
import { PortalRequest } from '../services/portalRequest';

/** A page owns its read requests; stale or cleared responses never repopulate page memory. */
export function usePortalTask(clear: () => void = () => undefined): {
  run: <T>(work: (signal: AbortSignal) => Promise<T>) => Promise<T | undefined>;
  cancel: () => void;
} {
  const task = new PortalRequest();
  const clearState = (): void => {
    task.dispose();
    clear();
  };
  window.addEventListener('ptmcp:sensitive-clear', clearState);
  onBeforeUnmount(() => {
    clearState();
    window.removeEventListener('ptmcp:sensitive-clear', clearState);
  });
  return { cancel: () => task.cancel(), run: <T>(work: (signal: AbortSignal) => Promise<T>) => task.run(work) };
}

export function errorText(error: unknown): string {
  if (error && typeof error === 'object' && 'message' in error) {
    const message = String((error as { message: unknown }).message);
    const requestId = (error as ApiError).requestId;
    return requestId ? `${message}（requestId: ${requestId}）` : message;
  }
  return '请求失败，请稍后重试';
}

export function formatDate(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Taipei',
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

export function isConflict(error: unknown): boolean {
  return Boolean(error && typeof error === 'object' && 'status' in error && [409, 412].includes((error as { status?: number }).status ?? 0));
}

export function isAccessError(error: unknown): boolean {
  return Boolean(error && typeof error === 'object' && 'status' in error && [401, 403, 404].includes((error as { status?: number }).status ?? 0));
}
