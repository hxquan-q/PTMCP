import { useEffect, useId, useImperativeHandle, useRef, useState, type CSSProperties, type ReactNode, type Ref } from 'react';
import {
  Alert as AntdAlert,
  Drawer as AntdDrawer,
  Empty,
  Input,
  Modal,
  Pagination as AntdPagination,
  Segmented,
  Select as AntdSelect,
  Steps,
  Tag,
} from 'antd';
import type { InputRef } from 'antd';
import { DocumentIcon, InfoIcon, SearchIcon, WarningIcon, type IconComponent } from './Icons';
import loginBgPosterUrl from '../assets/login-bg-poster.jpg?url';
import loginBgVideoUrl from '../assets/login-bg.mp4?url';

/**
 * Ant Design 6 承载的基础控件层：Modal/Drawer/Input/Select/Segmented/Steps/Alert/
 * Pagination/Empty/Tag 直接复用 antd 的弹层焦点管理、表单状态与样式 token；
 * 对外 API 与旧自研版本逐字兼容，页面调用方无需感知替换。
 * Field/PageHeader/StatCard/GlobalSearch 等业务组合仍是 Tailwind/token 布局，见各组件注释。
 */

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
  return (
    <Modal
      open={open}
      title={title}
      onCancel={onClose}
      /* 旧 Dialog 没有页脚时不渲染页脚区，不出现 antd 默认的「确定/取消」。 */
      footer={footer === undefined ? null : footer}
      width={width ?? 520}
      /* 旧实现 !open 返回 null：关闭后子树卸载，敏感输入不残留。 */
      destroyOnHidden
    >
      {children}
    </Modal>
  );
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: ReactNode }) {
  return (
    <label style={{ display: 'grid', gap: 4, fontSize: 13, color: 'var(--ptmcp-text)' }}>
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
  /* 可见名由外层 <label> 或 aria-label 提供，这里只补可编程标识（自动填充、按 id 关联的脚本）。 */
  const autoId = useId();
  const innerRef = useRef<InputRef | null>(null);
  /* antd Input 的 ref 是 InputRef 句柄；把 nativeElement 桥接成旧契约的 HTMLInputElement ref。 */
  useImperativeHandle(inputRef, () => (innerRef.current?.nativeElement ?? null) as HTMLInputElement);
  return (
    <Input
      id={autoId}
      ref={innerRef}
      status={invalid ? 'error' : undefined}
      style={{ width: width ?? '100%' }}
      type={type ?? 'text'}
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

type Tone = 'success' | 'warning' | 'danger' | 'neutral' | 'accent';

/* antd Tag 的预设色文字对比度不达 AA，沿用调色板里为 AA 校准过的组合。
   alignSelf 防止在 .cell-stack 等 flex column 容器里被拉伸成横条；
   胶囊几何沿用旧 .pill（圆角、内边距、加粗小字号）——浅色底 (#fffaeb/#f2f4f7)
   只有配上胶囊形状才在白卡上可辨识。 */
const TAG_PILL: CSSProperties = {
  marginInlineEnd: 0,
  alignSelf: 'flex-start',
  borderRadius: 999,
  paddingInline: 10,
  fontWeight: 600,
  fontSize: 12.5,
  lineHeight: '22px',
};

const TAG_TONE: Record<Tone, { color: string; background: string }> = {
  success: { color: 'var(--ptmcp-green)', background: 'var(--ptmcp-bg-success)' },
  warning: { color: 'var(--ptmcp-orange)', background: 'var(--ptmcp-bg-warning)' },
  danger: { color: 'var(--ptmcp-red)', background: 'var(--ptmcp-bg-danger)' },
  neutral: { color: 'var(--ptmcp-text-muted)', background: 'var(--ptmcp-bg-neutral)' },
  accent: { color: 'var(--ptmcp-blue-dark)', background: 'var(--ptmcp-bg-accent)' },
};

export function StatusDot({ tone, children }: { tone: Tone; children: ReactNode }) {
  const t = TAG_TONE[tone];
  return <Tag style={{ ...TAG_PILL, color: t.color, background: t.background, borderColor: t.background }}>{children}</Tag>;
}

export function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  const t = TAG_TONE[tone];
  return <Tag style={{ ...TAG_PILL, color: t.color, background: t.background, borderColor: t.background }}>{children}</Tag>;
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

/* 统计数字从当前值计数到目标值：只对纯数字生效，节点与字符串原样输出。
   系统开启「减少动态效果」时直接落位，不播中间帧。 */
function useCountUp(value: ReactNode): ReactNode {
  const [display, setDisplay] = useState(0);
  const current = useRef(0);
  useEffect(() => {
    if (typeof value !== 'number') return;
    const target = value;
    if (typeof window.matchMedia === 'function' && window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      current.current = target;
      setDisplay(target);
      return;
    }
    const from = current.current;
    const started = performance.now();
    let frame = 0;
    const tick = (now: number) => {
      const t = Math.min(1, (now - started) / 640);
      const eased = 1 - (1 - t) ** 3;
      const next = typeof target === 'number' && typeof from === 'number' ? Math.round(from + (target - from) * eased) : target;
      setDisplay(next);
      if (t < 1) frame = requestAnimationFrame(tick);
      else current.current = target;
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [value]);
  return typeof value === 'number' ? display : value;
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
  const shown = useCountUp(value);
  return (
    <div
      className={`table-card stat-card${tone ? ` stat-card--${tone}` : ''}`}
      onPointerMove={(e) => {
        /* 柔光跟随指针：只写两个自定义属性，不触发布局重排。 */
        const rect = e.currentTarget.getBoundingClientRect();
        e.currentTarget.style.setProperty('--ptmcp-mx', `${e.clientX - rect.left}px`);
        e.currentTarget.style.setProperty('--ptmcp-my', `${e.clientY - rect.top}px`);
      }}
    >
      <span className="stat-card-icon" aria-hidden>
        <Icon />
      </span>
      <span className="stat-card-copy">
        <strong className="stat-card-value">{shown}</strong>
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
      <Input
        id={autoId}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        allowClear
        prefix={<SearchIcon />}
        style={{ width: '100%' }}
      />
    </span>
  );
}

type SelectBaseProps = {
  value: string;
  onChange: (v: string) => void;
  options: { value: string; label: string }[];
  width?: number | string;
  disabled?: boolean;
};
type SelectNameProps = { label: string; ariaLabel?: string } | { label?: undefined; ariaLabel: string };

export function Select({ value, onChange, options, width, label, ariaLabel, disabled }: SelectBaseProps & SelectNameProps) {
  const control = (
    <AntdSelect
      value={value}
      disabled={disabled}
      options={options}
      style={width !== undefined ? { width } : undefined}
      onChange={(v) => onChange(v)}
      {...(label ? {} : { 'aria-label': ariaLabel })}
    />
  );
  if (label) {
    /* 与旧实现一致：有可见名时不再叠 aria-label，避免读屏读两遍。 */
    return (
      <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--ptmcp-text)' }}>{label}</span>
        {control}
      </label>
    );
  }
  return control;
}

/**
 * 视图切换。旧实现是外置内容的按钮组（内容渲染在控件外，没有对应 tabpanel），
 * 因此映射到 Segmented（radiogroup 语义）而不是 antd Tabs，避免「tab 没有 panel」的假 tablist。
 */
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
  return (
    <Segmented
      value={active}
      options={tabs.map((t) => ({ label: t.label, value: t.key }))}
      onChange={(v) => onChange(String(v))}
      aria-label={label}
    />
  );
}

export function Stepper({ steps, current }: { steps: string[]; current: number }) {
  return <Steps size="small" current={current} items={steps.map((title) => ({ title }))} />;
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
    <button type="button" className={`option-card${selected ? ' is-selected' : ''}`} aria-pressed={selected} onClick={onSelect}>
      <CellIcon icon={Icon} large />
      <span className="option-card-copy">
        <span className="option-card-title">
          {title}
          {tag ? <span className="option-card-tag">{tag}</span> : null}
        </span>
        {description ? <span className="option-card-desc">{description}</span> : null}
      </span>
      <span className="option-card-check" aria-hidden="true" data-selected={selected ? 'true' : 'false'} />
    </button>
  );
}

export function Alert({ kind, title, id, children }: { kind: 'info' | 'warning' | 'danger'; title?: string; id?: string; children?: ReactNode }) {
  /* danger→error 映射保持「失败必须可感知」；antd Alert 自带 role=alert 播报。 */
  return (
    <AntdAlert id={id} type={kind === 'danger' ? 'error' : kind === 'warning' ? 'warning' : 'info'} showIcon message={title} description={children} />
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
  const heading = (
    <span style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
      {icon}
      <span style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
        <span>{title}</span>
        {subtitle ? <span style={{ fontSize: 12, fontWeight: 400, color: 'var(--ptmcp-text-muted)' }}>{subtitle}</span> : null}
      </span>
      {badge ? <span style={{ marginLeft: 'auto' }}>{badge}</span> : null}
    </span>
  );
  return (
    <AntdDrawer
      open={open}
      onClose={onClose}
      /* antd 6 弃用 width，统一走 size（接受像素数字）。 */
      size={width}
      destroyOnHidden
      footer={footer}
      title={heading}
      styles={{ body: { paddingTop: 16 } }}
    >
      {children}
    </AntdDrawer>
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
  return (
    /* nav 让读屏能在 landmark 列表里直达分页。 */
    <nav className="pagination" aria-label="分页">
      <AntdPagination
        size="small"
        current={page}
        pageSize={pageSize}
        total={total}
        onChange={(p) => onPage(p)}
        showSizeChanger={false}
        showLessItems
        hideOnSinglePage={false}
      />
    </nav>
  );
}

/**
 * 空态/错误态。四种 kind 走四套图标与语气：
 * `empty` 真的没有数据、`filtered` 被筛选条件挡住、`error` 读取失败、`unavailable` 端点不可用。
 * 读取失败必须用 `error`/`unavailable`，不能画成「暂无数据」——否则用户会以为数据被删了。
 */
export type EmptyKind = 'empty' | 'filtered' | 'error' | 'unavailable';

const EMPTY_ICON: Record<EmptyKind, IconComponent> = {
  empty: DocumentIcon,
  filtered: SearchIcon,
  error: WarningIcon,
  unavailable: InfoIcon,
};

export function EmptyState({
  title,
  description,
  hint,
  action,
  kind = 'empty',
}: {
  title: string;
  description?: string;
  hint?: string;
  action?: ReactNode;
  kind?: EmptyKind;
}) {
  const Icon = EMPTY_ICON[kind];
  return (
    <Empty
      style={{ padding: '36px 24px' }}
      image={
        <span className={`empty-state-mark empty-state--${kind}`} aria-hidden="true">
          <Icon />
        </span>
      }
      description={
        <>
          <p className="empty-state-title">{title}</p>
          {description ? <p className="empty-state-desc">{description}</p> : null}
          {hint ? <p className="empty-state-hint">{hint}</p> : null}
        </>
      }
    >
      {action ? <div className="empty-state-actions">{action}</div> : null}
    </Empty>
  );
}

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
 * 供 AppShell 搜索浮层使用；Dialog/Drawer 的焦点管理已由 antd Modal/Drawer 内建圈禁承担。
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
      /* 关闭后焦点归还触发元素；此前由遮罩锁统一归还，现在浮层自身负责。 */
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

/** 跟随系统「减少动态效果」偏好，并在偏好切换时实时更新。 */
function usePrefersReducedMotion(): boolean {
  const [reduced, setReduced] = useState(() => window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  useEffect(() => {
    const query = window.matchMedia('(prefers-reduced-motion: reduce)');
    const onChange = () => setReduced(query.matches);
    query.addEventListener('change', onChange);
    return () => query.removeEventListener('change', onChange);
  }, []);
  return reduced;
}

/**
 * P-01 登录页背景 CG：工厂视频铺满视口，静音自动播放，素材本身正反双向无缝循环。
 * 纯装饰，不进入可访问性树；「减少动态效果」偏好下暂停在海报帧，偏好切换实时生效。
 * 主控台登录页与 OAuth 登录/同意页共用（OAuthShell），视频经打包器带哈希分发。
 */
export function LoginBackdrop() {
  const videoRef = useRef<HTMLVideoElement>(null);
  const reduced = usePrefersReducedMotion();
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    if (reduced) {
      video.pause();
    } else {
      // 自动播放可能被浏览器策略拒绝（如省流模式）：失败就停在海报帧，不再重试。
      void video.play().catch(() => undefined);
    }
  }, [reduced]);
  return (
    <div className="login-media" aria-hidden="true">
      <video
        ref={videoRef}
        className="login-media-video"
        src={loginBgVideoUrl}
        poster={loginBgPosterUrl}
        muted
        loop
        playsInline
        autoPlay={!reduced}
        disablePictureInPicture
        preload="auto"
      />
    </div>
  );
}

/**
 * 标题逐字入场（交互参考 react-bits 的 SplitText/BlurText）：文本拆成带序号的字符 span，
 * 由 CSS 按序做模糊上浮级联；完整文本以 sr-only 并列，读屏只读一次整词。
 * 仅一次性入场动画，之后零运行成本。
 */
export function CharReveal({ text, delay = 0, step = 35 }: { text: string; delay?: number; step?: number }) {
  return (
    <>
      <span className="char-reveal" aria-hidden="true">
        {Array.from(text).map((ch, i) => (
          <span key={`${ch}-${i}`} className="char-reveal-char" style={{ '--char-delay': `${delay + i * step}ms` } as CSSProperties}>
            {ch}
          </span>
        ))}
      </span>
      <span className="sr-only">{text}</span>
    </>
  );
}

/**
 * 磁吸容器（交互参考 react-bits 的 Magnet）：鼠标在容器内移动时内容轻微跟随指针，
 * 移出即回弹；只写 transform 不触发布局重排。触屏与「减少动态效果」偏好下不启用。
 */
export function useMagnetic<T extends HTMLElement>(max = 3) {
  const ref = useRef<T | null>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    const onMove = (e: PointerEvent) => {
      if (e.pointerType !== 'mouse') return;
      const rect = el.getBoundingClientRect();
      const dx = (e.clientX - (rect.left + rect.width / 2)) / (rect.width / 2);
      const dy = (e.clientY - (rect.top + rect.height / 2)) / (rect.height / 2);
      el.style.transform = `translate(${dx * max}px, ${dy * max}px)`;
    };
    const onLeave = () => {
      el.style.transform = '';
    };
    el.addEventListener('pointermove', onMove);
    el.addEventListener('pointerleave', onLeave);
    return () => {
      el.removeEventListener('pointermove', onMove);
      el.removeEventListener('pointerleave', onLeave);
    };
  }, [max]);
  return ref;
}
