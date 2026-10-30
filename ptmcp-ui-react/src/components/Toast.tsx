import { useEffect, useState } from 'react';
import { CheckIcon, CloseIcon, InfoIcon, WarningIcon, type IconComponent } from './Icons';
import { composeToastDisplay, dismissToast, pauseToast, resumeToast, subscribeToasts, type ToastMessage } from '../toast';

const TONE_META: Record<ToastMessage['tone'], { Icon: IconComponent; label: string }> = {
  success: { Icon: CheckIcon, label: '成功提示' },
  warning: { Icon: WarningIcon, label: '警告提示' },
  danger: { Icon: WarningIcon, label: '失败提示' },
  info: { Icon: InfoIcon, label: '通知' },
};

/**
 * 右下角通知栈。两个实时区域常驻挂载（只有内容会变），否则屏幕阅读器会漏掉第一条公告：
 * 失败走 assertive，其余走 polite。悬停或键盘聚焦会冻结自动消失，关闭按钮是真实按钮，键盘可达。
 */
export function ToastContainer() {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  useEffect(() => subscribeToasts(setToasts), []);
  const visible = composeToastDisplay(toasts, Date.now());
  const failed = visible.filter((t) => t.tone === 'danger');
  const others = visible.filter((t) => t.tone !== 'danger');
  return (
    <div className="toast-stack">
      <div className="toast-group" role="alert" aria-live="assertive" aria-label="失败通知">
        {failed.map((t) => (
          <ToastItem key={t.id} toast={t} />
        ))}
      </div>
      <div className="toast-group" role="status" aria-live="polite" aria-label="操作通知">
        {others.map((t) => (
          <ToastItem key={t.id} toast={t} />
        ))}
      </div>
    </div>
  );
}

function ToastItem({ toast }: { toast: ToastMessage }) {
  const { Icon, label } = TONE_META[toast.tone];
  return (
    <div
      className={`toast toast--${toast.tone}`}
      onMouseEnter={() => pauseToast(toast.id)}
      onMouseLeave={() => resumeToast(toast.id)}
      onFocusCapture={() => pauseToast(toast.id)}
      onBlurCapture={() => resumeToast(toast.id)}
    >
      <Icon className="toast-icon" />
      <span className="toast-text">{toast.text}</span>
      <button type="button" className="toast-close" aria-label={`关闭${label}`} onClick={() => dismissToast(toast.id)}>
        <CloseIcon />
      </button>
    </div>
  );
}
