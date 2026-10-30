/** 轻量 toast 队列 — 纯模块（无 React 依赖），React 容器订阅渲染。 */

export interface ToastMessage {
  id: number;
  text: string;
  tone: 'success' | 'warning' | 'danger' | 'info';
  createdAt: number;
  /** undefined=按 createdAt+maxAgeMs 判定（兼容手工构造）；null=常驻（失败通知或悬停暂停中）；数字=到期时刻。 */
  expiresAt?: number | null;
}

interface ToastTimer {
  handle: ReturnType<typeof setTimeout> | null;
  endAt: number;
  remaining: number;
}

let nextId = 1;
let active: ToastMessage[] = [];
const timers = new Map<number, ToastTimer>();
const listeners = new Set<(toasts: ToastMessage[]) => void>();

function notify(): void {
  for (const listener of listeners) listener(getToasts());
}

export function showToast(text: string, tone: ToastMessage['tone'] = 'success', maxAgeMs = 4000): void {
  const now = Date.now();
  // 失败通知不在用户还没读完时静默消失（WCAG 2.2.1：时限必须由用户控制）。
  const sticky = tone === 'danger' || maxAgeMs <= 0;
  const toast: ToastMessage = { id: nextId++, text, tone, createdAt: now, expiresAt: sticky ? null : now + maxAgeMs };
  active = [...active, toast];
  notify();
  if (!sticky) {
    const id = toast.id;
    timers.set(id, { handle: setTimeout(() => dismissToast(id), maxAgeMs), endAt: now + maxAgeMs, remaining: maxAgeMs });
  }
}

/** 悬停或聚焦时冻结自动消失，保留剩余时长。 */
export function pauseToast(id: number): void {
  const timer = timers.get(id);
  if (!timer || timer.handle === null) return;
  clearTimeout(timer.handle);
  timers.set(id, { handle: null, endAt: timer.endAt, remaining: Math.max(0, timer.endAt - Date.now()) });
  active = active.map((t) => (t.id === id ? { ...t, expiresAt: null } : t));
  notify();
}

/** 指针移开或失去焦点后按剩余时长继续计时。 */
export function resumeToast(id: number): void {
  const timer = timers.get(id);
  if (!timer || timer.handle !== null) return;
  if (timer.remaining <= 0) {
    dismissToast(id);
    return;
  }
  const endAt = Date.now() + timer.remaining;
  timers.set(id, { handle: setTimeout(() => dismissToast(id), timer.remaining), endAt, remaining: timer.remaining });
  active = active.map((t) => (t.id === id ? { ...t, expiresAt: endAt } : t));
  notify();
}

export function dismissToast(id: number): void {
  const timer = timers.get(id);
  if (timer?.handle !== undefined && timer?.handle !== null) clearTimeout(timer.handle);
  timers.delete(id);
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
  return toasts.filter((t) => (t.expiresAt === undefined ? now - t.createdAt < maxAgeMs : t.expiresAt === null || t.expiresAt > now));
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
  for (const timer of timers.values()) if (timer.handle !== null) clearTimeout(timer.handle);
  timers.clear();
  active = [];
  nextId = 1;
  notify();
}

/** 组合：先过滤过期再去重（顺序重要——过期重复不应遮蔽新鲜唯一）。供 ToastContainer 渲染前调用。 */
export function composeToastDisplay(toasts: ToastMessage[], now: number, maxAgeMs = 4000): ToastMessage[] {
  return deduplicateToasts(filterExpired(toasts, now, maxAgeMs));
}
