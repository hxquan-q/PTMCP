/** 轻量 toast 队列 — 纯模块（无 React 依赖），React 容器订阅渲染。 */

export interface ToastMessage {
  id: number;
  text: string;
  tone: 'success' | 'warning' | 'danger' | 'info';
  createdAt: number;
}

let nextId = 1;
let active: ToastMessage[] = [];
const listeners = new Set<(toasts: ToastMessage[]) => void>();

function notify(): void {
  for (const listener of listeners) listener(getToasts());
}

export function showToast(text: string, tone: ToastMessage['tone'] = 'success'): void {
  const toast: ToastMessage = { id: nextId++, text, tone, createdAt: Date.now() };
  active = [...active, toast];
  notify();
  setTimeout(() => dismissToast(toast.id), 4000);
}

export function dismissToast(id: number): void {
  const before = active.length;
  active = active.filter((t) => t.id !== id);
  if (active.length !== before) notify();
}

export function getToasts(): ToastMessage[] {
  return [...active];
}

export function subscribeToasts(listener: (toasts: ToastMessage[]) => void): () => void {
  listeners.add(listener);
  listener(getToasts());
  return () => {
    listeners.delete(listener);
  };
}

/** 纯函数：过滤过期 toast（测试与容器层共用）。 */
export function filterExpired(toasts: ToastMessage[], now: number, maxAgeMs = 4000): ToastMessage[] {
  return toasts.filter((t) => now - t.createdAt < maxAgeMs);
}

/** 纯函数：去重（同文本同 tone 的相邻 toast 合并为一条，避免连击刷屏）。 */
export function deduplicateToasts(toasts: ToastMessage[]): ToastMessage[] {
  return toasts.filter((t, i) => {
    const prev = toasts[i - 1];
    return !prev || prev.text !== t.text || prev.tone !== t.tone;
  });
}

/** 测试辅助：清空队列。 */
export function resetToasts(): void {
  active = [];
  nextId = 1;
  notify();
}

/** 组合：先过滤过期再去重（顺序重要——过期重复不应遮蔽新鲜唯一）。供 ToastContainer 渲染前调用。 */
export function composeToastDisplay(toasts: ToastMessage[], now: number, maxAgeMs = 4000): ToastMessage[] {
  return deduplicateToasts(filterExpired(toasts, now, maxAgeMs));
}
