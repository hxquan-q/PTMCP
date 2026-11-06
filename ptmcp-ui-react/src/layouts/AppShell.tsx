import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Button, Dropdown } from 'antd';
import { LogoIcon, SearchIcon, UserIcon } from '../components/Icons';
import { GlobalSearch, useFocusTrap } from '../components/common';
import { ChevronRightIcon, CloseIcon, DocumentIcon, DotsIcon } from '../components/Icons';
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
  /* 从根到当前页的面包屑；末项是当前页，带 navKey 的中间层可点回上层列表。 */
  breadcrumb?: { label: string; navKey?: string }[];
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
  const contentRef = useRef<HTMLDivElement>(null);
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
    <div className="app-shell">
      {/* 全站用 hash 路由，href="#…" 会被当成路由变更，所以跳过导航用按钮把焦点送进内容区。 */}
      <button type="button" className="skip-link" onClick={() => contentRef.current?.focus()}>
        跳到主内容
      </button>
      <header className="app-shell-top">
        <span className="app-shell-brand">
          <LogoIcon />
          PTMCP
        </span>
        <span className="app-shell-rule" />
        <span className="app-shell-subtitle">数据访问管理平台</span>
        {onSwitchRealm ? (
          <div className="realm-switch" role="group" aria-label="视角切换">
            <button
              type="button"
              className="realm-switch-btn"
              aria-current={realm === 'admin'}
              aria-disabled={!adminHomePath}
              title={adminHomePath ? '进入管理工作区' : '当前账号没有管理权限'}
              onClick={() => {
                if (!adminHomePath) return;
                onSwitchRealm('admin');
              }}
            >
              管理控制台
            </button>
            <button type="button" className="realm-switch-btn" aria-current={realm === 'me'} onClick={() => onSwitchRealm('me')}>
              个人门户
            </button>
          </div>
        ) : null}
        <div className="app-shell-search">
          <GlobalSearch onOpen={() => setSearchOpen(true)} />
        </div>
        <span className="chip-btn app-shell-user">
          <span className="app-shell-avatar">
            <UserIcon />
          </span>
          <span className="cell-stack">
            <strong style={{ fontSize: 12 }}>{user?.displayName ?? '未登录'}</strong>
            <span style={{ fontSize: 11 }}>{user?.department ?? ''}</span>
          </span>
        </span>
        {onLogout ? (
          /* 稿面右上角是“⋯”而不是一个醒目的退出按钮：账户级动作收进菜单，顶栏只留身份与检索。
             弹出定位、点外部关闭、Esc 与菜单内键盘导航交给 antd Dropdown，不再自维护监听。 */
          <Dropdown
            trigger={['click']}
            placement="bottomRight"
            classNames={{ root: 'topbar-menu' }}
            menu={{ items: [{ key: 'logout', label: '退出登录', onClick: () => onLogout() }] }}
          >
            <Button type="text" className="icon-btn topbar-more" icon={<DotsIcon />} aria-label="更多账户操作" aria-haspopup="menu" />
          </Dropdown>
        ) : null}
      </header>
      <div className="app-shell-body">
        <nav className="app-shell-nav" aria-label={realm === 'admin' ? '管理工作区导航' : '个人门户导航'}>
          {groups.map((g, gi) => (
            <div key={gi} className="nav-group">
              {g.title ? <div className="nav-group-title">{g.title}</div> : null}
              {g.items.map((item) => (
                <button
                  key={item.key}
                  type="button"
                  className="nav-item"
                  onClick={() => onNavigate(item.key)}
                  aria-current={item.key === active}
                  title={item.label}
                >
                  <span className="nav-icon">{item.icon}</span>
                  <span className="nav-label">{item.label}</span>
                </button>
              ))}
            </div>
          ))}
          <div className="nav-workspace">
            <strong>PTMCP {realm === 'admin' ? '管理工作区' : '个人门户'}</strong>
            <span>{realm === 'admin' ? '权限、资源与运行状态' : '接入、令牌与查询记录'}</span>
          </div>
        </nav>
        <main className="app-shell-main">
          {breadcrumb?.length ? (
            <nav className="app-shell-crumb" aria-label="面包屑">
              {/* 有序列表 + aria-current="page" 才让读屏报出“第 2 个，共 3 个”与当前页；分隔符只是图形，进可访问名会变成噪音。 */}
              <ol className="crumb-list">
                {breadcrumb.map((crumb, i) => {
                  const key = crumb.navKey;
                  const current = i === breadcrumb.length - 1;
                  return (
                    <li key={`${crumb.label}-${i}`} className="crumb-item">
                      {i > 0 ? <ChevronRightIcon className="crumb-sep" aria-hidden /> : null}
                      {current ? (
                        <span className="crumb-current" aria-current="page">
                          {crumb.label}
                        </span>
                      ) : key ? (
                        <button type="button" className="crumb-link" onClick={() => onNavigate(key)}>
                          {crumb.label}
                        </button>
                      ) : (
                        <span>{crumb.label}</span>
                      )}
                    </li>
                  );
                })}
              </ol>
            </nav>
          ) : null}
          {/* 内容区是本页唯一的纵向滚动容器；给可聚焦命名区域，键盘用户才能滚动并跳过导航直达正文。 */}
          <div className="app-shell-scroll" ref={contentRef} role="region" aria-label="页面内容" tabIndex={0}>
            {/* 切换导航分组时整页入场；详情页共享同一 active，避免重挂载清空页面状态。 */}
            <div className="app-shell-page" key={active}>
              {children}
            </div>
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
  const listRef = useRef<HTMLDivElement | null>(null);
  /* 弹层打开即圈禁焦点：首个可操作元素是搜索框，关闭后焦点归还给顶栏触发按钮。 */
  const trapRef = useFocusTrap(true);
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
      <div ref={trapRef} onClick={(e) => e.stopPropagation()} role="dialog" aria-modal="true" aria-label="全局搜索" className="cmdk-panel">
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '12px 16px', borderBottom: '1px solid var(--ptmcp-border)' }}>
          <SearchIcon style={{ color: 'var(--ptmcp-text-faint)' }} />
          <input
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="搜索功能、资源或关键词"
            role="combobox"
            aria-expanded={hits.length > 0}
            aria-autocomplete="list"
            aria-controls="cmdk-results"
            aria-activedescendant={hits.length > 0 ? `cmdk-opt-${active}` : undefined}
            aria-label="搜索关键词"
            style={{ flex: 1, minWidth: 0, border: 'none', fontSize: 14, background: 'none' }}
          />
          <Button type="text" className="icon-btn" icon={<CloseIcon />} aria-label="关闭搜索" onClick={onClose} />
        </div>
        <div id="cmdk-results" ref={listRef} role="listbox" aria-label="搜索结果" style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
          {hits.length === 0 ? (
            <div role="presentation" style={{ padding: '22px 16px', fontSize: 13, color: 'var(--ptmcp-text-faint)' }}>
              {keyword ? '没有匹配的结果。' : '输入以搜索导航项和数据源。'}
            </div>
          ) : (
            hits.map((h, i) => (
              <button
                key={h.id}
                id={`cmdk-opt-${i}`}
                type="button"
                /* 结果行不进 Tab 序列：焦点始终留在搜索框，用 aria-activedescendant 指向高亮行，避免“聚焦行”和“高亮行”两套状态互相矛盾。 */
                tabIndex={-1}
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
