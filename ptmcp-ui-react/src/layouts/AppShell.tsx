import { useEffect, useRef, useState, type ReactNode } from 'react';
import { BellIcon, DotsIcon, LogoIcon, SearchIcon, UserIcon } from '../components/Icons';
import { GlobalSearch } from '../components/common';
import { CloseIcon, DocumentIcon } from '../components/Icons';
import { listDatasources } from '../services/data';

export interface NavItem {
  key: string;
  label: string;
  icon?: ReactNode;
}
export interface NavGroup {
  title?: string;
  items: NavItem[];
}

interface SearchHit {
  id: string;
  title: string;
  subtitle: string;
  path: string;
}

export function AppShell({
  groups,
  active,
  onNavigate,
  breadcrumb,
  children,
}: {
  groups: NavGroup[];
  active: string;
  onNavigate: (key: string) => void;
  breadcrumb: ReactNode;
  children: ReactNode;
}) {
  const [searchOpen, setSearchOpen] = useState(false);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', overflow: 'hidden' }}>
      <header
        className="app-shell-top"
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 14,
          height: 52,
          padding: '0 16px',
          background: 'var(--ptmcp-surface)',
          borderBottom: '1px solid var(--ptmcp-border)',
          flex: 'none',
        }}
      >
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 9, fontSize: 17, fontWeight: 800, color: 'var(--ptmcp-heading)' }}>
          <LogoIcon style={{ fontSize: 24, color: 'var(--ptmcp-blue)' }} />
          PTMCP
        </span>
        <span style={{ width: 1, height: 20, background: 'var(--ptmcp-border-strong)' }} />
        <span className="app-shell-subtitle" style={{ fontSize: 14, color: 'var(--ptmcp-text-muted)' }}>
          数据访问管理平台
        </span>
        <div className="app-shell-search" style={{ flex: 1, display: 'flex', justifyContent: 'center' }}>
          <GlobalSearch onOpen={() => setSearchOpen(true)} />
        </div>
        <button className="btn btn--link" aria-label="通知" style={{ color: 'var(--ptmcp-text-muted)', fontSize: 17 }}>
          <BellIcon />
        </button>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 8 }}>
          <span
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 28,
              height: 28,
              borderRadius: '50%',
              background: 'var(--ptmcp-blue-soft)',
              color: 'var(--ptmcp-blue)',
            }}
          >
            <UserIcon />
          </span>
          <span className="cell-stack">
            <strong style={{ fontSize: 12 }}>前端演示管理员</strong>
            <span style={{ fontSize: 11 }}>演示总部</span>
          </span>
        </span>
        <button className="btn btn--link" aria-label="更多" style={{ color: 'var(--ptmcp-text-muted)', fontSize: 17 }}>
          <DotsIcon />
        </button>
      </header>
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <nav
          className="app-shell-nav"
          style={{
            width: 208,
            flex: 'none',
            background: 'var(--ptmcp-surface)',
            borderRight: '1px solid var(--ptmcp-border)',
            padding: '12px 8px',
            overflowY: 'auto',
            display: 'flex',
            flexDirection: 'column',
          }}
        >
          <div style={{ flex: 1 }}>
            {groups.map((g, gi) => (
              <div key={gi} style={{ marginBottom: 10 }}>
                {g.title ? <div style={{ padding: '6px 12px 4px', fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>{g.title}</div> : null}
                {g.items.map((item) => {
                  const selected = item.key === active;
                  return (
                    <button
                      key={item.key}
                      onClick={() => onNavigate(item.key)}
                      aria-current={selected}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 10,
                        width: '100%',
                        padding: '8px 12px',
                        margin: '1px 0',
                        border: 'none',
                        borderRadius: 8,
                        background: selected ? 'var(--ptmcp-selected)' : 'none',
                        color: selected ? 'var(--ptmcp-blue)' : 'var(--ptmcp-text-muted)',
                        fontWeight: selected ? 700 : 500,
                        fontSize: 13,
                        textAlign: 'left',
                        position: 'relative',
                      }}
                    >
                      {selected ? (
                        <span
                          style={{ position: 'absolute', left: -8, top: 6, bottom: 6, width: 3, borderRadius: 2, background: 'var(--ptmcp-blue)' }}
                        />
                      ) : null}
                      <span style={{ fontSize: 15, opacity: 0.9 }}>{item.icon}</span>
                      <span className="nav-label">{item.label}</span>
                    </button>
                  );
                })}
              </div>
            ))}
          </div>
          <div
            className="app-shell-workspace"
            style={{
              flex: 'none',
              margin: '8px 4px 4px',
              padding: '10px 12px',
              borderRadius: 10,
              background: 'linear-gradient(180deg, var(--ptmcp-blue-bg) 0%, transparent 100%)',
              border: '1px solid var(--ptmcp-border)',
            }}
          >
            <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--ptmcp-heading)' }}>PTMCP 管理工作区</div>
            <div style={{ fontSize: 11, color: 'var(--ptmcp-text-muted)', marginTop: 2 }}>权限、资源与运行状态</div>
          </div>
        </nav>
        <main style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          <div style={{ padding: '12px 20px 0', fontSize: 12, color: 'var(--ptmcp-text-faint)', flex: 'none' }}>{breadcrumb}</div>
          <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '10px 20px 20px', display: 'flex', flexDirection: 'column' }}>
            {children}
          </div>
        </main>
      </div>
      {searchOpen ? <SearchOverlay onClose={() => setSearchOpen(false)} onNavigate={onNavigate} groups={groups} /> : null}
    </div>
  );
}

function SearchOverlay({ onClose, onNavigate, groups }: { onClose: () => void; onNavigate: (k: string) => void; groups: NavGroup[] }) {
  const [keyword, setKeyword] = useState('');
  const [hits, setHits] = useState<SearchHit[]>([]);
  const inputRef = useRef<HTMLInputElement>(null);
  useEffect(() => inputRef.current?.focus(), []);
  useEffect(() => {
    let cancelled = false;
    const navHits: SearchHit[] = groups
      .flatMap((g) => g.items)
      .filter((i) => i.label.includes(keyword))
      .map((i) => ({ id: i.key, title: i.label, subtitle: '导航', path: i.key }));
    if (!keyword) {
      setHits([]);
      return;
    }
    listDatasources({ keyword, pageSize: 5 })
      .then(({ data }) => {
        if (cancelled) return;
        setHits([
          ...navHits,
          ...data.items.map((d) => ({ id: `ds-${d.id}`, title: d.name, subtitle: `${d.code} · ${d.engine}`, path: 'admin-datasources' })),
        ]);
      })
      .catch(() => {
        if (!cancelled) setHits(navHits);
      });
    return () => {
      cancelled = true;
    };
  }, [keyword, groups]);
  return (
    <div
      onClick={onClose}
      style={{
        position: 'fixed',
        inset: 0,
        background: 'rgba(11,27,40,.3)',
        zIndex: 50,
        display: 'flex',
        justifyContent: 'center',
        paddingTop: '14vh',
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        style={{
          width: 520,
          maxHeight: '60vh',
          background: 'var(--ptmcp-surface)',
          borderRadius: 'var(--ptmcp-radius-panel)',
          boxShadow: 'var(--ptmcp-shadow-pop)',
          display: 'flex',
          flexDirection: 'column',
          overflow: 'hidden',
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px', borderBottom: '1px solid var(--ptmcp-border)' }}>
          <SearchIcon style={{ color: 'var(--ptmcp-text-faint)' }} />
          <input
            ref={inputRef}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="搜索功能、资源或关键词"
            style={{ flex: 1, border: 'none', outline: 'none', fontSize: 14, background: 'none' }}
          />
          <button className="btn btn--link" aria-label="关闭" onClick={onClose} style={{ color: 'var(--ptmcp-text-faint)' }}>
            <CloseIcon />
          </button>
        </div>
        <div style={{ overflowY: 'auto' }}>
          {hits.length === 0 ? (
            <div style={{ padding: '22px 16px', fontSize: 13, color: 'var(--ptmcp-text-faint)' }}>
              {keyword ? '没有匹配的结果。' : '输入以搜索导航项和数据源。'}
            </div>
          ) : (
            hits.map((h) => (
              <button
                key={h.id}
                onClick={() => {
                  onNavigate(h.path);
                  onClose();
                }}
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  width: '100%',
                  padding: '10px 16px',
                  border: 'none',
                  background: 'none',
                  textAlign: 'left',
                }}
                onMouseEnter={(e) => (e.currentTarget.style.background = 'var(--ptmcp-hover)')}
                onMouseLeave={(e) => (e.currentTarget.style.background = 'none')}
              >
                <DocumentIcon style={{ color: 'var(--ptmcp-text-faint)' }} />
                <span className="cell-stack">
                  <strong>{h.title}</strong>
                  <span>{h.subtitle}</span>
                </span>
              </button>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
