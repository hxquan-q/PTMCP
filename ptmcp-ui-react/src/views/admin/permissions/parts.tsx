import { type ReactNode } from 'react';
import { Badge } from '../../../components/common';
import { ChevronRightIcon, DatabaseIcon, DocumentIcon } from '../../../components/Icons';
import { type ResourceNode } from '../resourceTree';

const stampFmt = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });

export function fmtStamp(at: string | null | undefined): string {
  if (!at) return '—';
  const d = new Date(at);
  return Number.isNaN(d.getTime()) ? '—' : stampFmt.format(d);
}

/** 规则有效期左闭右开；无到期即永久。 */
export function validityLabel(validTo: string | null): string {
  return validTo ? `至 ${fmtStamp(validTo)}` : '永久';
}

export function findNode(nodes: ResourceNode[], key: string): ResourceNode | null {
  for (const n of nodes) {
    if (n.key === key) return n;
    const hit = n.children?.length ? findNode(n.children, key) : null;
    if (hit) return hit;
  }
  return null;
}

export function Pane({
  icon,
  title,
  subtitle,
  headerExtra,
  footer,
  children,
}: {
  icon: ReactNode;
  title: string;
  subtitle?: string;
  headerExtra?: ReactNode;
  footer?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="table-card" style={{ minHeight: 0, marginRight: 0 }}>
      <header className="pane-head">
        {icon}
        <span className="pane-head-copy" style={{ flex: 1 }}>
          <h2 className="pane-title">{title}</h2>
          {subtitle ? <p className="pane-sub">{subtitle}</p> : null}
        </span>
        {headerExtra}
      </header>
      {children}
      {footer ? <footer className="pane-foot">{footer}</footer> : null}
    </section>
  );
}

export function Resizer({
  onPointerDown,
  onNudge,
  value,
  label,
  active,
}: {
  onPointerDown: (e: React.PointerEvent) => void;
  onNudge: (deltaPct: number) => void;
  value: number;
  label: string;
  active?: boolean;
}) {
  const pct = Math.round(value);
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      aria-label={label}
      aria-valuenow={pct}
      aria-valuemin={14}
      aria-valuemax={50}
      aria-valuetext={`左侧栏 ${pct}%`}
      tabIndex={0}
      className="perm-resizer"
      data-active={active ? 'true' : undefined}
      onPointerDown={onPointerDown}
      onKeyDown={(e) => {
        if (e.key === 'ArrowLeft') {
          onNudge(-4);
          e.preventDefault();
        } else if (e.key === 'ArrowRight') {
          onNudge(4);
          e.preventDefault();
        }
      }}
      style={{ cursor: 'col-resize', display: 'flex', alignItems: 'center', justifyContent: 'center' }}
    >
      <span
        style={{
          width: 3,
          height: 48,
          borderRadius: 'var(--ptmcp-radius-xs)',
          transform: active ? undefined : 'scaleY(0.75)',
          transition: 'transform var(--ptmcp-dur-fast) ease, background-color var(--ptmcp-dur-fast) ease',
        }}
      />
    </div>
  );
}

/** 载入骨架：几何与终态行一致（主体行 56px / 树行 28px / 表行 44px），纯装饰、对 AT 隐藏。 */
export function SkeletonRows({ variant, rows = 4 }: { variant: 'subjects' | 'tree' | 'table'; rows?: number }) {
  return (
    <div aria-hidden style={variant === 'subjects' ? { padding: '0 8px 8px' } : undefined}>
      {Array.from({ length: rows }, (_, i) =>
        variant === 'subjects' ? (
          <div
            key={i}
            className="skel-row"
            style={{ border: '1px solid var(--ptmcp-border)', borderRadius: 'var(--ptmcp-radius-panel)', minHeight: 56, padding: '10px 12px' }}
          >
            <span className="skel-block" style={{ width: 32, height: 32, borderRadius: 'var(--ptmcp-radius-circle)' }} />
            <span style={{ display: 'grid', gap: 6, flex: 1 }}>
              <span className="skel-block" style={{ width: '48%', height: 12 }} />
              <span className="skel-block" style={{ width: '34%', height: 10 }} />
            </span>
          </div>
        ) : variant === 'tree' ? (
          <div key={i} className="skel-row" style={{ padding: '4px 8px', paddingLeft: 8 + (i % 3) * 18, margin: 0 }}>
            <span className="skel-block" style={{ width: 148 - (i % 3) * 24, height: 10 }} />
          </div>
        ) : (
          <div key={i} className="skel-row" style={{ minHeight: 44, padding: '10px 12px' }}>
            <span className="skel-block" style={{ width: 24, height: 24, borderRadius: 'var(--ptmcp-radius-control)' }} />
            <span className="skel-block" style={{ width: 176, height: 12 }} />
            <span className="skel-block" style={{ width: 64, height: 10, marginLeft: 'auto' }} />
          </div>
        ),
      )}
    </div>
  );
}

export function TreeBranch({
  node,
  depth,
  expanded,
  selected,
  activeKey,
  onToggle,
  onSelect,
  onActivate,
}: {
  node: ResourceNode;
  depth: number;
  expanded: Set<string>;
  selected: string;
  activeKey: string;
  onToggle: (k: string) => void;
  onSelect: (k: string) => void;
  onActivate: (k: string) => void;
}) {
  const open = expanded.has(node.key);
  const isSel = selected === node.key;
  const branch = !!node.children?.length;
  return (
    <div role="presentation">
      <button
        type="button"
        className={`tree-row${isSel ? ' is-selected' : ''}`}
        role="treeitem"
        data-key={node.key}
        data-depth={depth}
        data-branch={branch ? 'true' : 'false'}
        data-open={open ? 'true' : 'false'}
        aria-expanded={branch ? open : undefined}
        aria-selected={isSel}
        aria-level={depth + 1}
        tabIndex={activeKey === node.key ? 0 : -1}
        style={{ paddingLeft: 8 + depth * 18 }}
        onFocus={() => onActivate(node.key)}
        onClick={() => {
          if (branch) onToggle(node.key);
          onSelect(node.key);
        }}
      >
        <ChevronRightIcon
          aria-hidden
          style={{
            fontSize: 12,
            color: 'var(--ptmcp-text-faint)',
            transform: open ? 'rotate(90deg)' : undefined,
            transition: 'transform var(--ptmcp-dur-fast) ease',
            visibility: branch ? 'visible' : 'hidden',
          }}
        />
        {node.kind === 'OBJECT' ? (
          <DocumentIcon aria-hidden style={{ color: 'var(--ptmcp-text-faint)', fontSize: 14 }} />
        ) : (
          <DatabaseIcon aria-hidden style={{ color: 'var(--ptmcp-text-faint)', fontSize: 14 }} />
        )}
        <span className="tree-label">{node.label}</span>
        {node.sub ? <Badge tone="neutral">{node.sub}</Badge> : null}
      </button>
      {open && node.children ? (
        <div role="group" className="tree-children">
          {node.children.map((c) => (
            <TreeBranch
              key={c.key}
              node={c}
              depth={depth + 1}
              expanded={expanded}
              selected={selected}
              activeKey={activeKey}
              onToggle={onToggle}
              onSelect={onSelect}
              onActivate={onActivate}
            />
          ))}
        </div>
      ) : null}
    </div>
  );
}
