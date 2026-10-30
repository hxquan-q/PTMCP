import { useEffect, useRef, useState, type ReactNode } from 'react';
import { LogoIcon, SearchIcon, UserIcon } from '../components/Icons';
import { GlobalSearch } from '../components/common';
import { ChevronRightIcon, CloseIcon, DocumentIcon } from '../components/Icons';
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
  onLogout,
  user,
  realm = 'me',
  adminHomePath,
  onSwitchRealm,
}: {
  groups: NavGroup[];
  active: string;
  onNavigate: (key: string) => void;
  breadcrumb: ReactNode;
  children: ReactNode;
  onLogout?: () => void;
  user?: { displayName: string; department: string };
  /* 当前视角；切换只改导航，不改变服务端授权（docs/frontend/dev-guide.md §4）。 */
  realm?: 'admin' | 'me';
  /* 首个有权访问的管理页；为空表示当前账号无管理权限，不显示管理视角入口。 */
  adminHomePath?: string | null;
  onSwitchRealm?: (realm: 'admin' | 'me') => void;
}) {
  const [searchOpen, setSearchOpen] = useState(false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setSearchOpen(true);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
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
        <span className="chip-btn" style={{ background: 'var(--ptmcp-chrome)', border: '1px solid var(--ptmcp-border)' }}>
          <span
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              width: 26,
              height: 26,
              borderRadius: '50%',
              background: 'var(--ptmcp-blue-soft)',
              color: 'var(--ptmcp-blue-text)',
              fontSize: 13,
            }}
          >
            <UserIcon />
          </span>
          <span className="cell-stack">
            <strong style={{ fontSize: 12 }}>{user?.displayName ?? '未登录'}</strong>
            <span style={{ fontSize: 11 }}>{user?.department ?? ''}</span>
          </span>
        </span>
        {onLogout ? (
          <button className="btn btn--secondary" onClick={onLogout} style={{ padding: '5px 12px', fontSize: 12.5 }}>
            退出登录
          </button>
        ) : null}
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
          {onSwitchRealm ? (
            <div
              role="tablist"
              aria-label="视角切换"
              style={{
                display: 'flex',
                gap: 4,
                padding: 4,
                margin: '0 4px 10px',
                borderRadius: 10,
                background: 'var(--ptmcp-chrome)',
                border: '1px solid var(--ptmcp-border)',
                flex: 'none',
              }}
            >
              <button
                role="tab"
                aria-selected={realm === 'admin'}
                disabled={!adminHomePath}
                title={adminHomePath ? '进入管理工作区' : '当前账号没有管理权限'}
                onClick={() => onSwitchRealm('admin')}
                style={{
                  flex: 1,
                  padding: '6px 8px',
                  border: 'none',
                  borderRadius: 7,
                  fontSize: 12.5,
                  fontWeight: realm === 'admin' ? 700 : 500,
                  color: realm === 'admin' ? 'var(--ptmcp-blue)' : 'var(--ptmcp-text-muted)',
                  background: realm === 'admin' ? 'var(--ptmcp-surface)' : 'transparent',
                  cursor: adminHomePath ? 'pointer' : 'not-allowed',
                  opacity: adminHomePath ? 1 : 0.45,
                }}
              >
                管理视角
              </button>
              <button
                role="tab"
                aria-selected={realm === 'me'}
                onClick={() => onSwitchRealm('me')}
                style={{
                  flex: 1,
                  padding: '6px 8px',
                  border: 'none',
                  borderRadius: 7,
                  fontSize: 12.5,
                  fontWeight: realm === 'me' ? 700 : 500,
                  color: realm === 'me' ? 'var(--ptmcp-blue)' : 'var(--ptmcp-text-muted)',
                  background: realm === 'me' ? 'var(--ptmcp-surface)' : 'transparent',
                  cursor: 'pointer',
                }}
              >
                个人视角
              </button>
            </div>
          ) : null}
          <div style={{ flex: 1 }}>
            {groups.map((g, gi) => (
              <div key={gi} style={{ marginBottom: 10 }}>
                {g.title ? <div style={{ padding: '6px 12px 4px', fontSize: 12, color: 'var(--ptmcp-text-faint)' }}>{g.title}</div> : null}
                {g.items.map((item) => {
                  const selected = item.key === active;
                  return (
                    <button
                      key={item.key}
                      className="nav-item"
                      onClick={() => onNavigate(item.key)}
                      aria-current={selected}
                      title={item.label}
                      style={{ position: 'relative' }}
                    >
                      {selected ? (
                        <span
                          style={{
                            position: 'absolute',
                            left: -8,
                            top: 6,
                            bottom: 6,
                            width: 3,
                            borderRadius: 2,
                            background: 'var(--ptmcp-blue)',
                          }}
                        />
                      ) : null}
                      <span style={{ fontSize: 15, display: 'inline-flex' }}>{item.icon}</span>
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
            <div style={{ fontSize: 12, fontWeight: 700, color: 'var(--ptmcp-heading)' }}>
              {realm === 'admin' ? 'PTMCP 管理工作区' : 'PTMCP 个人门户'}
            </div>
            <div style={{ fontSize: 11, color: 'var(--ptmcp-text-muted)', marginTop: 2 }}>
              {realm === 'admin' ? '权限、资源与运行状态' : '我的数据、授权与安全'}
            </div>
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
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const listRef = useRef<HTMLDivElement | null>(null);
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
  useEffect(() => setActive(0), [keyword]);
  useEffect(() => {
    listRef.current?.querySelectorAll<HTMLElement>('.cmd-row')[active]?.scrollIntoView({ block: 'nearest' });
  }, [active]);
  const openHit = (hit: SearchHit | undefined) => {
    if (!hit) return;
    onNavigate(hit.path);
    onClose();
  };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
      else if (e.key === 'ArrowDown') {
        e.preventDefault();
        if (hits.length) setActive((i) => (i + 1) % hits.length);
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        if (hits.length) setActive((i) => (i - 1 + hits.length) % hits.length);
      } else if (e.key === 'Enter') {
        e.preventDefault();
        openHit(hits[active]);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [hits, active, onClose]);
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
        aria-label="全局搜索"
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
            aria-controls="cmdk-results"
            aria-label="搜索关键词"
            style={{ flex: 1, border: 'none', outline: 'none', fontSize: 14, background: 'none' }}
          />
          <button className="btn btn--icon" aria-label="关闭搜索" onClick={onClose}>
            <CloseIcon />
          </button>
        </div>
        <div id="cmdk-results" ref={listRef} role="listbox" aria-label="搜索结果" style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
          {hits.length === 0 ? (
            <div style={{ padding: '22px 16px', fontSize: 13, color: 'var(--ptmcp-text-faint)' }}>
              {keyword ? '没有匹配的结果。' : '输入以搜索导航项和数据源。'}
            </div>
          ) : (
            hits.map((h, i) => (
              <button
                key={h.id}
                role="option"
                aria-selected={i === active}
                className={`cmd-row${i === active ? ' is-active' : ''}`}
                onMouseEnter={() => setActive(i)}
                onClick={() => openHit(h)}
              >
                <DocumentIcon style={{ color: 'var(--ptmcp-text-faint)', flex: 'none' }} />
                <span className="cell-stack">
                  <strong>{h.title}</strong>
                  <span>{h.subtitle}</span>
                </span>
                {i === active ? <ChevronRightIcon style={{ color: 'var(--ptmcp-blue-text)', flex: 'none' }} /> : null}
              </button>
            ))
          )}
        </div>
        <footer
          style={{
            display: 'flex',
            gap: 14,
            padding: '8px 16px',
            borderTop: '1px solid var(--ptmcp-border)',
            background: 'var(--ptmcp-chrome)',
            fontSize: 11.5,
            color: 'var(--ptmcp-text-faint)',
          }}
        >
          <span>↑ ↓ 选择</span>
          <span>⏎ 打开</span>
          <span>esc 关闭</span>
        </footer>
      </div>
    </div>
  );
}
