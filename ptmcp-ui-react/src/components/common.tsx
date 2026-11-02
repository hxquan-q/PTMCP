import { useEffect, useId, useRef, useState, type ReactNode, type Ref } from 'react';
import { createPortal } from 'react-dom';
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
  const titleId = useId();
  const trapRef = useFocusTrap(open);
  useOverlayLock(open);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return createPortal(
    <div
      className="overlay-scrim overlay-scrim--dialog"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={trapRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="table-card overlay-dialog"
        style={width ? { width } : undefined}
      >
        <header className="overlay-header">
          <h2 id={titleId} className="overlay-title" tabIndex={-1}>
            {title}
          </h2>
          <button type="button" className="btn btn--icon" aria-label="关闭" onClick={onClose}>
            <CloseIcon />
          </button>
        </header>
        <div className="overlay-body">{children}</div>
        {footer ? <footer className="overlay-footer">{footer}</footer> : null}
      </div>
    </div>,
    document.body,
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
  label,
  invalid,
  describedBy,
  inputRef,
  width,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  type?: string;
  disabled?: boolean;
  maxLength?: number;
  autoComplete?: string;
  label?: string;
  invalid?: boolean;
  describedBy?: string;
  inputRef?: Ref<HTMLInputElement>;
  /* 筛选项需要固定宽度；表单里的输入框仍默认铺满所在单元格。 */
  width?: number | string;
}) {
  /* 控件此前没有 id：浏览器的表单启发式（自动填充、Chrome 的 “field should have an id or name” 告警）与任何按 id 关联的脚本都无从下手。
     可见名仍由外层 <label> 或 aria-label 提供，这里只补可编程标识。 */
  const autoId = useId();
  return (
    <input
      id={autoId}
      className={invalid ? 'input input--invalid' : 'input'}
      style={{ width: width ?? '100%' }}
      type={type ?? 'text'}
      ref={inputRef}
      value={value}
      disabled={disabled}
      maxLength={maxLength}
      autoComplete={autoComplete}
      placeholder={placeholder}
      aria-label={label}
      aria-invalid={invalid || undefined}
      aria-describedby={describedBy}
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

export function StatusDot({ tone, children }: { tone: 'success' | 'warning' | 'danger' | 'neutral' | 'accent'; children: ReactNode }) {
  return <span className={`pill pill--${tone}`}>{children}</span>;
}

export function Badge({ tone, children }: { tone: 'success' | 'warning' | 'danger' | 'neutral' | 'accent'; children: ReactNode }) {
  return <span className={`badge badge--${tone}`}>{children}</span>;
}

export function PageHeader({ title, description, actions }: { title: string; description?: string; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div className="page-header-copy">
        <h1>{title}</h1>
        {description ? <p>{description}</p> : null}
      </div>
      {actions ? <div className="page-header-actions">{actions}</div> : null}
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
  return (
    <div className={`table-card stat-card${tone ? ` stat-card--${tone}` : ''}`}>
      <span className="stat-card-icon" aria-hidden>
        <Icon />
      </span>
      <span className="stat-card-copy">
        <strong className="stat-card-value">{value}</strong>
        <span className="stat-card-label">{label}</span>
        {hint ? <span className="stat-card-hint">{hint}</span> : null}
      </span>
    </div>
  );
}

export function SearchInput({
  value,
  onChange,
  placeholder,
  width,
  label,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  width?: number | string;
  label?: string;
}) {
  const autoId = useId();
  return (
    <span className="input-wrap" style={{ width }}>
      {/* 搜索框只有占位符可见，占位符会在输入后消失且不算可编程标签：补一个仅供辅助技术的 <label>，可编程名与自动填充都从此处取。 */}
      <label className="sr-only" htmlFor={autoId}>
        {label ?? placeholder}
      </label>
      <SearchIcon className="input-icon" />
      <input
        id={autoId}
        className="input"
        style={{ width: '100%' }}
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
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
  const autoId = useId();
  return (
    <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--ptmcp-text)' }}>
      {label}
      <select id={autoId} className="select" style={{ width }} value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </label>
  );
}

export function Tabs({
  tabs,
  active,
  onChange,
  label = '视图切换',
}: {
  tabs: { key: string; label: ReactNode }[];
  active: string;
  onChange: (k: string) => void;
  label?: string;
}) {
  const refs = useRef<Record<string, HTMLButtonElement | null>>({});
  const move = (dir: number) => {
    const i = tabs.findIndex((t) => t.key === active);
    const next = tabs[(i + dir + tabs.length) % tabs.length];
    if (!next) return;
    onChange(next.key);
    refs.current[next.key]?.focus();
  };
  return (
    <div
      /* 五个消费方都在控件外面各自渲染内容，全站没有任何 tabpanel：挂 tab 语义会让读屏播报“标签页”却找不到对应面板，
         也会让人以为只有选中的那个能 Tab 到。现按“带名分组 + aria-current”播报，每个视图都是原生可达的按钮。 */
      role="group"
      aria-label={label}
      className="tab-row"
      onKeyDown={(e) => {
        if (e.key === 'ArrowRight') {
          e.preventDefault();
          move(1);
        } else if (e.key === 'ArrowLeft') {
          e.preventDefault();
          move(-1);
        }
      }}
    >
      {tabs.map((t) => (
        <button
          key={t.key}
          type="button"
          ref={(el) => {
            refs.current[t.key] = el;
          }}
          aria-current={active === t.key}
          className={`tab-btn${active === t.key ? ' is-active' : ''}`}
          onClick={() => onChange(t.key)}
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
    <ol
      aria-label="配置步骤"
      style={{ display: 'flex', alignItems: 'center', gap: 0, listStyle: 'none', margin: '0 0 20px', padding: 0, flexWrap: 'wrap' }}
    >
      {steps.map((label, i) => {
        const done = i < current;
        const active = i === current;
        return (
          <li key={label} aria-current={active ? 'step' : undefined} style={{ display: 'flex', alignItems: 'center' }}>
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
              <span className="sr-only">{done ? '（已完成）' : active ? '（当前步骤）' : '（未完成）'}</span>
            </span>
            {i < steps.length - 1 ? (
              <span
                style={{
                  width: 42,
                  height: 1,
                  margin: '0 12px',
                  background: done ? 'var(--ptmcp-blue)' : 'var(--ptmcp-border-strong)',
                }}
              />
            ) : null}
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
    <button type="button" onClick={onSelect} aria-pressed={selected} className={`option-card${selected ? ' is-selected' : ''}`}>
      <CellIcon icon={Icon} large />
      <span className="cell-stack" style={{ flex: 1 }}>
        <strong className="option-card-title">
          {title}
          {/* 标签必须包在元素里：裸文本会和标题连在一起，flex 的 gap 不适用于匿名文本。 */}
          {tag ? <span className="option-card-tag">{tag}</span> : null}
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

export function Alert({ kind, title, id, children }: { kind: 'info' | 'warning' | 'danger'; title?: string; id?: string; children?: ReactNode }) {
  const styles = {
    info: { bg: 'var(--ptmcp-bg-accent)', fg: 'var(--ptmcp-blue-dark)', Icon: InfoIcon },
    warning: { bg: 'var(--ptmcp-bg-warning)', fg: 'var(--ptmcp-orange)', Icon: WarningIcon },
    danger: { bg: 'var(--ptmcp-bg-danger)', fg: 'var(--ptmcp-red)', Icon: WarningIcon },
  }[kind];
  return (
    <div
      id={id}
      role={kind === 'danger' ? 'alert' : 'status'}
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

export function RequestIdText({ requestId }: { requestId: string }) {
  if (!requestId) return null;
  return (
    <span style={{ display: 'block', marginTop: 6, fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
      requestId：<code>{requestId}</code>（可凭此向管理员排查该次请求）
    </span>
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
  const titleId = useId();
  const trapRef = useFocusTrap(open);
  useOverlayLock(open);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return createPortal(
    <>
      <div className="overlay-scrim" onClick={onClose} />
      <div ref={trapRef} role="dialog" aria-modal="true" aria-labelledby={titleId} className="overlay-drawer" style={{ width }}>
        <header className="overlay-header overlay-header--start">
          {icon}
          <div className="cell-stack overlay-heading">
            <h2 id={titleId} className="overlay-title" tabIndex={-1}>
              {title}
              {badge}
            </h2>
            {subtitle ? <span>{subtitle}</span> : null}
          </div>
          <button type="button" className="btn btn--icon" aria-label="关闭" onClick={onClose}>
            <CloseIcon />
          </button>
        </header>
        <div className="overlay-body">{children}</div>
        {footer ? <footer className="overlay-footer">{footer}</footer> : null}
      </div>
    </>,
    document.body,
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
    /* 分页此前只是一个裸 span：读屏的 landmark 列表里找不到它，键盘用户要逐个 Tab 才知道自己在第几页。 */
    <nav className="pagination" aria-label="分页">
      <button type="button" className="page-btn" disabled={page <= 1} onClick={() => onPage(page - 1)} aria-label="上一页">
        <ChevronRightIcon style={{ transform: 'rotate(180deg)' }} />
      </button>
      {pageNums(page, pages).map((p, i) =>
        p === 'gap' ? (
          <span key={`gap-${i}`} className="page-gap" aria-hidden>
            …
          </span>
        ) : (
          <button
            key={p}
            type="button"
            className={`page-btn${p === page ? ' is-active' : ''}`}
            onClick={() => onPage(p)}
            aria-current={p === page ? 'page' : undefined}
            aria-label={`第 ${p} 页，共 ${pages} 页`}
          >
            {p}
          </button>
        ),
      )}
      <button type="button" className="page-btn" disabled={page >= pages} onClick={() => onPage(page + 1)} aria-label="下一页">
        <ChevronRightIcon />
      </button>
    </nav>
  );
}

/** 页码窗口：页数多时只显示首、尾、当前附近，其余折叠为省略号。 */
function pageNums(page: number, pages: number): (number | 'gap')[] {
  if (pages <= 7) return Array.from({ length: pages }, (_, i) => i + 1);
  const from = Math.max(2, page - 1);
  const to = Math.min(pages - 1, page + 1);
  const out: (number | 'gap')[] = [1];
  if (from > 2) out.push('gap');
  for (let p = from; p <= to; p++) out.push(p);
  if (to < pages - 1) out.push('gap');
  out.push(pages);
  return out;
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
    <button type="button" className="search-trigger" aria-haspopup="dialog" aria-keyshortcuts="Control+K Meta+K" onClick={onOpen}>
      <SearchIcon />
      <span>搜索功能、资源或关键词</span>
      <kbd>⌘K</kbd>
    </button>
  );
}

/**
 * 焦点圈禁：打开时先落标题（只读详情没有比「关闭」更该先读的控件），Tab 只在层内循环，关闭后归还焦点。
 * 供 Dialog/Drawer 共用。
 */
export function useFocusTrap(open: boolean) {
  const ref = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    if (!open) return;
    const container = ref.current;
    if (!container) return;
    const previouslyFocused = document.activeElement as HTMLElement | null;
    /* 圈禁边界要和浏览器原生 Tab 序列一致：tabindex="-1" 的原生不可达，若仍算作 first/last，Tab 会从弹层漏到背后的页面。 */
    const focusables = () =>
      Array.from(container.querySelectorAll<HTMLElement>('button, [href], input, select, textarea, [tabindex]:not([tabindex="-1"])')).filter(
        (el) => !el.hasAttribute('disabled') && el.offsetParent !== null && el.tabIndex >= 0,
      );
    /* 标题 tabindex=-1，不进 Tab 序列，但打开时先读它；没有标题才退回第一个控件。 */
    const title = container.querySelector<HTMLElement>('.overlay-title');
    window.setTimeout(() => (title ?? focusables()[0])?.focus(), 0);
    /* 归还由 useOverlayLock 在解开 inert 后调用：外壳还 inert 时 focus() 会被浏览器丢掉。 */
    pendingRestores.push(() => previouslyFocused?.focus?.());
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
    };
  }, [open]);
  return ref;
}

/**
 * 打开期间把背后页面标成 inert，并锁住 body 滚动。
 * aria-modal 只是声明，Chrome 仍会把 Tab 送进背后的侧栏和面包屑；inert 才真正挡住。
 * 多层叠加时按计数归还，避免内层关闭把外层的锁一起解开。
 */
let overlayLocks = 0;
const pendingRestores: Array<() => void> = [];
function useOverlayLock(open: boolean) {
  useEffect(() => {
    if (!open) return;
    /* 浮层通过 portal 挂在 body 下，不在 #root 里；锁 #root 会把浮层自己也锁死。 */
    const shell = document.querySelector('.app-shell');
    overlayLocks += 1;
    shell?.setAttribute('inert', '');
    document.body.style.overflow = 'hidden';
    return () => {
      overlayLocks = Math.max(0, overlayLocks - 1);
      if (overlayLocks === 0) {
        shell?.removeAttribute('inert');
        document.body.style.overflow = '';
        pendingRestores.splice(0).forEach((restore) => restore());
      }
    };
  }, [open]);
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
