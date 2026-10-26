import { ref, shallowRef } from 'vue';
import { usePortalTask, errorText, isAccessError } from '../views/portalUtils';
import type { Page } from '../services/controlPlane';

/** Owns list reads and loading/error state together so stale reads cannot alter a newer page. */
export function useCursorPage<T>(read: (cursor: string | undefined, signal: AbortSignal) => Promise<Page<T>>) {
  const rows = shallowRef<T[]>([]);
  const cursor = ref<string | null>(null);
  const loading = ref(false);
  const error = ref('');
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
  }
  async function load(append = false): Promise<void> {
    if (loading.value || (append && !cursor.value)) return;
    const own = ++sequence;
    retryAppend = append;
    loading.value = true;
    error.value = '';
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
      error.value = errorText(reason);
    } finally {
      if (own === sequence) loading.value = false;
    }
  }
  return { rows, cursor, loading, error, loaded, reset, load, retry: () => load(retryAppend) };
}
