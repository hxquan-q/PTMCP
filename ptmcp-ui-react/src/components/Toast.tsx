import { useEffect, useState } from 'react';
import { dismissToast, subscribeToasts, type ToastMessage } from '../toast';

const TONE_STYLE: Record<ToastMessage['tone'], React.CSSProperties> = {
  success: { borderLeft: '3px solid var(--ptmcp-green)' },
  warning: { borderLeft: '3px solid var(--ptmcp-orange)' },
  danger: { borderLeft: '3px solid var(--ptmcp-red)' },
  info: { borderLeft: '3px solid var(--ptmcp-blue)' },
};

/** 右上角 toast 容器：订阅 toast 队列，4s 自动消失，点击提前关闭。 */
export function ToastContainer() {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  useEffect(() => subscribeToasts(setToasts), []);
  if (!toasts.length) return null;
  return (
    <div
      role="status"
      aria-live="polite"
      style={{ position: 'fixed', top: 60, right: 20, zIndex: 100, display: 'flex', flexDirection: 'column', gap: 8, pointerEvents: 'auto' }}
    >
      {toasts.map((t) => (
        <div
          key={t.id}
          className="table-card"
          style={{ padding: '10px 16px', minWidth: 220, maxWidth: 360, fontSize: 13, cursor: 'pointer', ...TONE_STYLE[t.tone] }}
          onClick={() => dismissToast(t.id)}
        >
          {t.text}
        </div>
      ))}
    </div>
  );
}
