import { ref, shallowRef } from 'vue';
import { errorText, usePortalTask } from '../views/portalUtils';

/** Read-only master/detail selection; changing context invalidates every prior response. */
export function useRecordPreview<T>(read: (id: string, signal: AbortSignal) => Promise<T>) {
  const selectedId = ref('');
  const item = shallowRef<T | null>(null);
  const loading = ref(false);
  const error = ref('');
  const requestId = ref<string | null>(null);
  let sequence = 0;
  const task = usePortalTask(clear);
  function clear(): void {
    sequence++;
    task.cancel();
    selectedId.value = '';
    item.value = null;
    loading.value = false;
    error.value = '';
    requestId.value = null;
  }
  async function select(id: string): Promise<void> {
    if (!id) {
      clear();
      return;
    }
    const own = ++sequence;
    task.cancel();
    selectedId.value = id;
    item.value = null;
    loading.value = true;
    error.value = '';
    requestId.value = null;
    try {
      const result = await task.run((signal) => read(id, signal));
      if (own === sequence && result !== undefined) item.value = result;
    } catch (reason) {
      if (own !== sequence) return;
      error.value = errorText(reason, false);
      if (reason && typeof reason === 'object' && 'requestId' in reason && typeof reason.requestId === 'string') requestId.value = reason.requestId;
    } finally {
      if (own === sequence) loading.value = false;
    }
  }
  return { selectedId, item, loading, error, requestId, clear, select, retry: () => select(selectedId.value) };
}
