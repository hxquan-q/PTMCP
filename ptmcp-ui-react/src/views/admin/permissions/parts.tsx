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
}: {
  onPointerDown: (e: React.PointerEvent) => void;
  onNudge: (deltaPct: number) => void;
  value: number;
  label: string;
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
      <span style={{ width: 3, height: 36, borderRadius: 'var(--ptmcp-radius-xs)', background: 'var(--ptmcp-border-strong)' }} />
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
        <div role="group">
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
