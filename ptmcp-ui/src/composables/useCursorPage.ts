import { ref, shallowRef } from 'vue';
import { usePortalTask, errorText, isAccessError } from '../views/portalUtils';
import type { Page } from '../services/controlPlane';

/** Owns list reads and loading/error state together so stale reads cannot alter a newer page. */
export function useCursorPage<T>(read: (cursor: string | undefined, signal: AbortSignal) => Promise<Page<T>>) {
  const rows = shallowRef<T[]>([]);
  const cursor = ref<string | null>(null);
  const loading = ref(false);
  const error = ref('');
  const requestId = ref<string | null>(null);
  const loaded = ref(false);
  let sequence = 0;
  let retryAppend = false;
  const task = usePortalTask(() => reset());
  function reset(): void {
    sequence++;
    task.cancel();
    rows.value = [];
    cursor.value = null;
    loading.value = false;
    loaded.value = false;
    error.value = '';
    requestId.value = null;
  }
  /** Rehydrate a list read by a page that intentionally leaves and returns. */
  function restore(state: { rows: T[]; cursor: string | null; loaded: boolean }): void {
    sequence++;
    task.cancel();
    rows.value = [...state.rows];
    cursor.value = state.cursor;
    loaded.value = state.loaded;
    loading.value = false;
    error.value = '';
    requestId.value = null;
  }
  async function load(append = false): Promise<void> {
    if (loading.value || (append && !cursor.value)) return;
    const own = ++sequence;
    retryAppend = append;
    loading.value = true;
    error.value = '';
    requestId.value = null;
    if (!append) {
      cursor.value = null;
    }
    try {
      const result = await task.run((signal) => read(append ? (cursor.value ?? undefined) : undefined, signal));
      if (!result || own !== sequence) return;
      rows.value = append ? [...rows.value, ...result.items] : result.items;
      cursor.value = result.nextCursor;
      loaded.value = true;
    } catch (reason) {
      if (own !== sequence) return;
      if (append && reason && typeof reason === 'object' && 'code' in reason && reason.code === 'CURSOR_STALE') {
        reset();
        await load();
        return;
      }
      if (isAccessError(reason)) {
        rows.value = [];
        cursor.value = null;
        retryAppend = false;
      }
      error.value = errorText(reason, false);
      if (reason && typeof reason === 'object' && 'requestId' in reason && typeof reason.requestId === 'string') requestId.value = reason.requestId;
    } finally {
      if (own === sequence) loading.value = false;
    }
  }
  return { rows, cursor, loading, error, requestId, loaded, reset, restore, load, retry: () => load(retryAppend) };
}
