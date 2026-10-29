import { useEffect, useRef, useState, type ReactNode } from 'react';
import { CheckIcon, ChevronRightIcon, CloseIcon, InfoIcon, SearchIcon, WarningIcon } from './Icons';
import type { IconComponent } from './Icons';

export function Dialog({
  open,
  title,
  onClose,
  footer,
  width,
  children,
}: {
  open: boolean;
  title: string;
  onClose: () => void;
  footer?: ReactNode;
  width?: number;
  children: ReactNode;
}) {
  const trapRef = useFocusTrap(open);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div
      role="presentation"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
      style={{
        position: 'fixed',
        inset: 0,
        zIndex: 60,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        background: 'rgba(15,23,42,.38)',
        backdropFilter: 'blur(3px)',
      }}
    >
      <div
        ref={trapRef}
        role="dialog"
        aria-modal="true"
        aria-label={title}
        className="table-card"
        style={{
          width: width ?? 480,
          maxWidth: 'calc(100vw - 48px)',
          maxHeight: 'calc(100vh - 96px)',
          display: 'flex',
          flexDirection: 'column',
          boxShadow: '0 24px 64px rgba(15,23,42,.24)',
        }}
      >
        <header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 12,
            padding: '14px 18px',
            borderBottom: '1px solid var(--ptmcp-border)',
            flex: 'none',
          }}
        >
          <strong style={{ fontSize: 15, color: 'var(--ptmcp-heading)' }}>{title}</strong>
          <button className="btn btn--link" aria-label="关闭" onClick={onClose} style={{ color: 'var(--ptmcp-text-muted)', fontSize: 16 }}>
            <CloseIcon />
          </button>
        </header>
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '16px 18px', display: 'grid', gap: 12, alignContent: 'start' }}>
          {children}
        </div>
        {footer ? (
          <footer
            style={{
              display: 'flex',
              justifyContent: 'flex-end',
              gap: 10,
              padding: '12px 18px',
              borderTop: '1px solid var(--ptmcp-border)',
              flex: 'none',
            }}
          >
            {footer}
          </footer>
        ) : null}
      </div>
    </div>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label style={{ display: 'grid', gap: 5, fontSize: 13, color: 'var(--ptmcp-text)' }}>
      <span style={{ fontWeight: 600 }}>{label}</span>
      {children}
      {hint ? <span style={{ fontSize: 12, color: 'var(--ptmcp-text-faint)', fontWeight: 400 }}>{hint}</span> : null}
    </label>
  );
}

export function TextInput({
  value,
  onChange,
  placeholder,
  type,
  disabled,
  maxLength,
  autoComplete,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  type?: string;
  disabled?: boolean;
  maxLength?: number;
  autoComplete?: string;
}) {
  return (
    <input
      className="input"
      style={{ width: '100%' }}
      type={type ?? 'text'}
      value={value}
      disabled={disabled}
      maxLength={maxLength}
      autoComplete={autoComplete}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value)}
    />
  );
}

export function CellIcon({ icon: Icon, engine, large }: { icon: IconComponent; engine?: string; large?: boolean }) {
  return (
    <span className={`cell-icon${large ? ' cell-icon--lg' : ''}`} {...(engine ? { 'data-engine': engine } : {})}>
      <Icon />
    </span>
  );
}

export function StatusDot({ tone, children }: { tone: 'success' | 'warning' | 'danger' | 'neutral'; children: ReactNode }) {
  return <span className={`status-dot is-${tone}`}>{children}</span>;
}

export function Badge({ tone, children }: { tone: 'success' | 'warning' | 'danger' | 'neutral' | 'accent'; children: ReactNode }) {
  return <span className={`badge badge--${tone}`}>{children}</span>;
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16, marginBottom: 18, flexWrap: 'wrap' }}>
      <div>
        <h1 style={{ margin: 0, fontSize: 24, fontWeight: 700, color: 'var(--ptmcp-heading)', lineHeight: 1.25 }}>{title}</h1>
        {description ? <p style={{ margin: '5px 0 0', fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>{description}</p> : null}
      </div>
      {actions}
    </div>
  );
}

export function StatCard({
  icon: Icon,
  value,
  label,
  hint,
  tone,
}: {
  icon: IconComponent;
  value: ReactNode;
  label: string;
  hint?: string;
  tone?: 'success' | 'warning' | 'danger';
}) {
  const accent =
    tone === 'success'
      ? 'var(--ptmcp-green)'
      : tone === 'warning'
        ? 'var(--ptmcp-orange)'
        : tone === 'danger'
          ? 'var(--ptmcp-red)'
          : 'var(--ptmcp-blue)';
  return (
    <div className="table-card" style={{ flexDirection: 'row', alignItems: 'center', gap: 12, padding: '14px 16px' }}>
      <CellIcon icon={Icon} large />
      <div className="cell-stack">
        <strong style={{ fontSize: 20, color: accent }}>{value}</strong>
        <span>
          {label}
          {hint ? ` · ${hint}` : ''}
        </span>
      </div>
    </div>
  );
}

export function SearchInput({
  value,
  onChange,
  placeholder,
  width,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  width?: number | string;
}) {
  return (
    <span className="input-wrap" style={{ width }}>
      <SearchIcon className="input-icon" />
      <input className="input" style={{ width: '100%' }} value={value} placeholder={placeholder} onChange={(e) => onChange(e.target.value)} />
    </span>
  );
}

export function Select({
  value,
  onChange,
  options,
  width,
  label,
  disabled,
}: {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  width?: number | string;
  label?: string;
  disabled?: boolean;
}) {
  return (
    <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--ptmcp-text)' }}>
      {label}
      <select className="select" style={{ width }} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}

export function Tabs({ tabs, active, onChange }: { tabs: { key: string; label: ReactNode }[]; active: string; onChange: (k: string) => void }) {
  return (
    <div role="tablist" style={{ display: 'flex', gap: 2, borderBottom: '1px solid var(--ptmcp-border)', marginBottom: 14 }}>
      {tabs.map((t) => (
        <button
          key={t.key}
          role="tab"
          aria-selected={active === t.key}
          onClick={() => onChange(t.key)}
          style={{
            padding: '8px 14px',
            border: 'none',
            borderBottom: active === t.key ? '2px solid var(--ptmcp-blue)' : '2px solid transparent',
            background: 'none',
            color: active === t.key ? 'var(--ptmcp-blue)' : 'var(--ptmcp-text-muted)',
            fontWeight: 600,
            fontSize: 13,
            marginBottom: -1,
          }}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

/** 步骤指示条:已完成✓ / 当前蓝点编号 / 后续灰 */
export function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return (
    <ol style={{ display: 'flex', alignItems: 'center', gap: 0, listStyle: 'none', margin: '0 0 20px', padding: 0, flexWrap: 'wrap' }}>
      {steps.map((label, i) => {
        const done = i < current;
        const active = i === current;
        return (
          <li key={label} style={{ display: 'flex', alignItems: 'center' }}>
            <span
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                justifyContent: 'center',
                width: 24,
                height: 24,
                borderRadius: '50%',
                fontSize: 12,
                fontWeight: 700,
                background: done || active ? 'var(--ptmcp-blue)' : 'var(--ptmcp-surface)',
                color: done || active ? '#fff' : 'var(--ptmcp-text-faint)',
                border: done || active ? 'none' : '1px solid var(--ptmcp-border-strong)',
              }}
            >
              {done ? <CheckIcon /> : i + 1}
            </span>
            <span
              style={{
                marginLeft: 8,
                fontSize: 13,
                fontWeight: active ? 700 : 500,
                color: active ? 'var(--ptmcp-blue)' : done ? 'var(--ptmcp-text)' : 'var(--ptmcp-text-faint)',
              }}
            >
              {label}
            </span>
            {i < steps.length - 1 ? <span style={{ width: 42, height: 1, margin: '0 12px', background: 'var(--ptmcp-border-strong)' }} /> : null}
          </li>
        );
      })}
    </ol>
  );
}

export function OptionCard({
  icon: Icon,
  title,
  description,
  tag,
  selected,
  onSelect,
}: {
  icon: IconComponent;
  title: ReactNode;
  description?: string;
  tag?: ReactNode;
  selected: boolean;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        width: '100%',
        padding: '13px 16px',
        textAlign: 'left',
        background: selected ? 'var(--ptmcp-selected)' : 'var(--ptmcp-surface)',
        border: `1px solid ${selected ? 'var(--ptmcp-blue)' : 'var(--ptmcp-border)'}`,
        borderRadius: 'var(--ptmcp-radius-panel)',
        transition: 'border-color .15s ease, background .15s ease',
      }}
    >
      <CellIcon icon={Icon} large />
      <span className="cell-stack" style={{ flex: 1 }}>
        <strong style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {title}
          {tag}
        </strong>
        {description ? <span>{description}</span> : null}
      </span>
      {selected ? (
        <span style={{ width: 20, height: 20, borderRadius: '50%', border: '6px solid var(--ptmcp-blue)', flex: 'none' }} />
      ) : (
        <span style={{ width: 20, height: 20, borderRadius: '50%', border: '2px solid var(--ptmcp-border-strong)', flex: 'none' }} />
      )}
    </button>
  );
}

export function Alert({ kind, title, children }: { kind: 'info' | 'warning' | 'danger'; title?: string; children?: ReactNode }) {
  const styles = {
    info: { bg: 'var(--ptmcp-bg-accent)', fg: 'var(--ptmcp-blue-dark)', Icon: InfoIcon },
    warning: { bg: 'var(--ptmcp-bg-warning)', fg: 'var(--ptmcp-orange)', Icon: WarningIcon },
    danger: { bg: 'var(--ptmcp-bg-danger)', fg: 'var(--ptmcp-red)', Icon: WarningIcon },
  }[kind];
  return (
    <div
      role="status"
      style={{
        display: 'flex',
        gap: 10,
        padding: '12px 14px',
        borderRadius: 'var(--ptmcp-radius-panel)',
        background: styles.bg,
        fontSize: 13,
        color: 'var(--ptmcp-text)',
        margin: '14px 0',
      }}
    >
      <styles.Icon style={{ color: styles.fg, flex: 'none', fontSize: 17, marginTop: 1 }} />
      <div>
        {title ? <strong style={{ display: 'block', color: styles.fg, marginBottom: 3 }}>{title}</strong> : null}
        {children}
      </div>
    </div>
  );
}

export function Drawer({
  open,
  onClose,
  title,
  subtitle,
  icon,
  badge,
  children,
  footer,
  width = 420,
}: {
  open: boolean;
  onClose: () => void;
  title: ReactNode;
  subtitle?: ReactNode;
  icon?: ReactNode;
  badge?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  width?: number;
}) {
  const trapRef = useFocusTrap(open);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <>
      <div onClick={onClose} style={{ position: 'fixed', inset: 0, background: 'rgba(11,27,40,.28)', zIndex: 40 }} />
      <aside
        ref={trapRef}
        role="dialog"
        aria-modal="true"
        style={{
          position: 'fixed',
          top: 0,
          right: 0,
          bottom: 0,
          width,
          maxWidth: '92vw',
          background: 'var(--ptmcp-surface)',
          boxShadow: 'var(--ptmcp-shadow-pop)',
          zIndex: 41,
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <header style={{ display: 'flex', alignItems: 'flex-start', gap: 12, padding: '16px 18px', borderBottom: '1px solid var(--ptmcp-border)' }}>
          {icon}
          <div className="cell-stack" style={{ flex: 1 }}>
            <strong style={{ fontSize: 15, display: 'flex', alignItems: 'center', gap: 8 }}>
              {title}
              {badge}
            </strong>
            {subtitle ? <span>{subtitle}</span> : null}
          </div>
          <button className="btn btn--link" aria-label="关闭" onClick={onClose} style={{ color: 'var(--ptmcp-text-muted)', fontSize: 16 }}>
            <CloseIcon />
          </button>
        </header>
        <div style={{ flex: 1, overflowY: 'auto', padding: '14px 18px' }}>{children}</div>
        {footer ? (
          <footer style={{ padding: '12px 18px', borderTop: '1px solid var(--ptmcp-border)', display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
            {footer}
          </footer>
        ) : null}
      </aside>
    </>
  );
}

export function DrawerFields({ fields }: { fields: { label: string; value: ReactNode }[] }) {
  return (
    <dl style={{ margin: 0, display: 'grid', gridTemplateColumns: 'auto 1fr', columnGap: 18, rowGap: 12, fontSize: 13 }}>
      {fields.map((f) => (
        <div key={f.label} style={{ display: 'contents' }}>
          <dt style={{ color: 'var(--ptmcp-text-muted)' }}>{f.label}</dt>
          <dd style={{ margin: 0, color: 'var(--ptmcp-text-strong)', fontWeight: 500 }}>{f.value}</dd>
        </div>
      ))}
    </dl>
  );
}

export function Pagination({ total, pageSize, page, onPage }: { total: number; pageSize: number; page: number; onPage: (p: number) => void }) {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <button className="btn btn--link" disabled={page <= 1} onClick={() => onPage(page - 1)} aria-label="上一页">
        <ChevronRightIcon style={{ transform: 'rotate(180deg)' }} />
      </button>
      {Array.from({ length: pages }, (_, i) => i + 1).map((p) => (
        <button
          key={p}
          onClick={() => onPage(p)}
          aria-current={p === page}
          style={{
            minWidth: 26,
            height: 26,
            borderRadius: 6,
            border: p === page ? '1px solid var(--ptmcp-blue)' : '1px solid transparent',
            background: p === page ? 'var(--ptmcp-blue-soft)' : 'none',
            color: p === page ? 'var(--ptmcp-blue)' : 'var(--ptmcp-text-muted)',
            fontWeight: p === page ? 700 : 500,
            fontSize: 12,
          }}
        >
          {p}
        </button>
      ))}
      <button className="btn btn--link" disabled={page >= pages} onClick={() => onPage(page + 1)} aria-label="下一页">
        <ChevronRightIcon />
      </button>
    </span>
  );
}

export function EmptyState({ title, description, action }: { title: string; description?: string; action?: ReactNode }) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        padding: '48px 20px',
        textAlign: 'center',
        flex: 1,
      }}
    >
      <DocumentSvg />
      <strong style={{ color: 'var(--ptmcp-text-strong)', fontSize: 15, marginTop: 6 }}>{title}</strong>
      {description ? <span style={{ color: 'var(--ptmcp-text-muted)', fontSize: 13, maxWidth: 380 }}>{description}</span> : null}
      {action}
    </div>
  );
}
const DocumentSvg = () => (
  <svg width="64" height="64" viewBox="0 0 64 64" fill="none" aria-hidden>
    <rect x="14" y="10" width="30" height="40" rx="4" fill="var(--ptmcp-bg-neutral)" stroke="var(--ptmcp-border-strong)" />
    <path d="M21 21h16M21 28h16M21 35h10" stroke="var(--ptmcp-text-faint)" strokeWidth="2" strokeLinecap="round" />
    <circle cx="46" cy="44" r="11" fill="var(--ptmcp-blue)" />
    <path d="M46 39v10M41 44h10" stroke="#fff" strokeWidth="2.4" strokeLinecap="round" />
  </svg>
);

/** ⌘K 风格全局搜索输入(纯 UI,数据源由调用方注入) */
export function GlobalSearch({ onOpen }: { onOpen: () => void }) {
  return (
    <button
      onClick={onOpen}
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 8,
        height: 32,
        padding: '0 10px',
        borderRadius: 'var(--ptmcp-radius-pill)',
        border: '1px solid var(--ptmcp-border)',
        background: 'var(--ptmcp-chrome)',
        color: 'var(--ptmcp-text-faint)',
        fontSize: 13,
      }}
    >
      <SearchIcon />
      <span>搜索功能、资源或关键词</span>
      <kbd
        style={{
          marginLeft: 8,
          padding: '1px 6px',
          borderRadius: 4,
          border: '1px solid var(--ptmcp-border-strong)',
          background: 'var(--ptmcp-surface)',
          fontSize: 11,
        }}
      >
        ⌘K
      </kbd>
    </button>
  );
}

/** 焦点圈禁：打开时聚焦首个可操作元素、Tab 循环、关闭时归还焦点。供 Dialog/Drawer 共用。 */
export function useFocusTrap(open: boolean) {
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (!open) return;
    const container = ref.current;
    if (!container) return;
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const focusables = () =>
      Array.from(container.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')).filter(
        (el) => !el.hasAttribute('disabled') && el.offsetParent !== null,
      );
    window.setTimeout(() => focusables()[0]?.focus(), 0);
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Tab') return;
      const items = focusables();
      if (items.length === 0) return;
      const first = items[0]!;
      const last = items[items.length - 1]!;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    container.addEventListener('keydown', onKeyDown);
    return () => {
      container.removeEventListener('keydown', onKeyDown);
      previouslyFocused?.focus?.();
    };
  }, [open]);
  return ref;
}

export function useDebounced<T>(value: T, ms = 300): T {
  const [v, setV] = useState(value);
  const t = useRef<ReturnType<typeof setTimeout>>(undefined);
  useEffect(() => {
    clearTimeout(t.current);
    t.current = setTimeout(() => setV(value), ms);
    return () => clearTimeout(t.current);
  }, [value, ms]);
  return v;
}
