import { useEffect, useMemo, useState, type DragEvent, type KeyboardEvent, type ReactNode } from 'react';
import { Button, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { Alert, Badge, Dialog, EmptyState, PageHeader, RequestIdText, SearchInput, StatusDot, useDebounced } from '../../components/common';
import { BuildingIcon, ChevronRightIcon, CloseIcon, EditIcon, PlusIcon, UsersIcon } from '../../components/Icons';
import {
  deleteDepartment,
  deleteUser,
  listDepartments,
  listRoles,
  listUsers,
  setUserDepartment,
  setUserStatus,
  updateDepartment,
  type DepartmentRow,
  type RoleRow,
  type UserRow,
} from '../../services/orgPortalApi';
import { readFailure, type Failure } from '../../services/http';
import { useAdminGate } from '../../adminGateContext';
import { showToast } from '../../toast';
import { canDeleteDepartment, departmentDescendants } from './orgForms';
import { buildDepartmentTree, departmentPath, type DepartmentTreeNode } from './orgTree';
import { DeptDialog, MemberDrawer, MemberFormDialog } from './orgDialogs';

const DEPT_MIME = 'application/x-ptmcp-department';
const USER_MIME = 'application/x-ptmcp-user';
const ROOT_DROP = '__root__';

const memberName = (u: UserRow) => u.displayName || u.username;
const initialOf = (u: UserRow) => memberName(u).slice(0, 1).toUpperCase();

/**
 * 人员组织：左树右表。
 * 左侧部门树负责选择范围与调整层级；右侧成员表负责账号、角色、启停与详情。
 * 拖动是鼠标快捷路径，键盘用户仍可通过部门编辑框和成员抽屉完成同样的移动。
 */
export function OrgPage() {
  const isAdmin = useAdminGate();
  const canCreateUser = isAdmin;
  const canDeleteUser = isAdmin;
  const canCreateDept = isAdmin;
  const canUpdateDept = isAdmin;
  const canDeleteDept = isAdmin;
  const canMoveUser = isAdmin;

  const [departments, setDepartments] = useState<DepartmentRow[]>([]);
  const [users, setUsers] = useState<UserRow[]>([]);
  const [roles, setRoles] = useState<RoleRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [deptFailure, setDeptFailure] = useState<Failure | null>(null);
  const [usersFailure, setUsersFailure] = useState<Failure | null>(null);
  const [rolesFailure, setRolesFailure] = useState<Failure | null>(null);
  const [reloadTick, setReloadTick] = useState(0);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [treeKeyword, setTreeKeyword] = useState('');
  const [keyword, setKeyword] = useState('');
  const treeDebounced = useDebounced(treeKeyword, 200);
  const debounced = useDebounced(keyword);

  const [creatingUser, setCreatingUser] = useState(false);
  const [editingUser, setEditingUser] = useState<UserRow | null>(null);
  const [deletingUser, setDeletingUser] = useState<UserRow | null>(null);
  const [creatingDept, setCreatingDept] = useState<{ parentId: string | null } | null>(null);
  const [editingDept, setEditingDept] = useState<DepartmentRow | null>(null);
  const [deletingDept, setDeletingDept] = useState<DepartmentRow | null>(null);

  const [busy, setBusy] = useState(false);
  const [busyUserId, setBusyUserId] = useState<string | null>(null);
  const [actionFailure, setActionFailure] = useState<Failure | null>(null);
  const [deleteFailure, setDeleteFailure] = useState<Failure | null>(null);
  const [draggingDeptId, setDraggingDeptId] = useState<string | null>(null);
  const [draggingUserId, setDraggingUserId] = useState<string | null>(null);
  const [dropTarget, setDropTarget] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    /* 三份目录独立失败：部门失败会挡住树，成员失败会挡住表，角色失败只影响展示与保存门禁。 */
    Promise.allSettled([listDepartments(), listUsers(), listRoles()]).then(([d, u, r]) => {
      if (cancelled) return;
      if (d.status === 'fulfilled') {
        setDepartments(d.value);
        setDeptFailure(null);
      } else {
        setDepartments([]);
        setDeptFailure(readFailure(d.reason, '部门目录加载失败，请稍后重试。'));
      }
      if (u.status === 'fulfilled') {
        setUsers(u.value);
        setUsersFailure(null);
      } else {
        setUsers([]);
        setUsersFailure(readFailure(u.reason, '成员列表加载失败，请稍后重试。'));
      }
      if (r.status === 'fulfilled') {
        setRoles(r.value);
        setRolesFailure(null);
      } else {
        setRoles([]);
        setRolesFailure(readFailure(r.reason, '角色目录加载失败，请稍后重试。'));
      }
      setLoading(false);
    });
    return () => {
      cancelled = true;
    };
  }, [reloadTick]);

  const reload = () => setReloadTick((t) => t + 1);

  /* 部门被删除或移出可见范围后，选择不能停留在已不存在的节点上。 */
  useEffect(() => {
    if (selectedId && !departments.some((d) => d.id === selectedId)) setSelectedId(null);
  }, [departments, selectedId]);

  const tree = useMemo(() => buildDepartmentTree(departments), [departments]);
  const directCounts = useMemo(() => {
    const map = new Map<string, number>();
    for (const u of users) {
      if (u.deptId) map.set(u.deptId, (map.get(u.deptId) ?? 0) + 1);
    }
    return map;
  }, [users]);
  const subtreeCounts = useMemo(() => {
    const map = new Map<string, number>();
    const walk = (node: DepartmentTreeNode<DepartmentRow>): number => {
      let total = directCounts.get(node.item.id) ?? 0;
      for (const child of node.children) total += walk(child);
      map.set(node.item.id, total);
      return total;
    };
    for (const root of tree) walk(root);
    return map;
  }, [tree, directCounts]);

  const deptPathLabel = useMemo(() => {
    const map = new Map<string, string>();
    for (const d of departments) map.set(d.id, departmentPath(departments, d.id).join(' / '));
    return map;
  }, [departments]);

  const scope = selectedId ? (departments.find((d) => d.id === selectedId) ?? null) : null;
  const scopeIds = useMemo(() => (selectedId ? departmentDescendants(departments, selectedId) : null), [departments, selectedId]);
  const scopedUsers = useMemo(() => (scopeIds ? users.filter((u) => u.deptId && scopeIds.has(u.deptId)) : users), [users, scopeIds]);
  const filtered = useMemo(() => {
    const needle = debounced.trim().toLowerCase();
    if (!needle) return scopedUsers;
    return scopedUsers.filter((u) => u.username.toLowerCase().includes(needle) || u.displayName.toLowerCase().includes(needle));
  }, [scopedUsers, debounced]);

  const treeMatches = useMemo(() => {
    const needle = treeDebounced.trim().toLowerCase();
    if (!needle) return null;
    return departments
      .filter((d) => d.name.toLowerCase().includes(needle))
      .sort((a, b) => (deptPathLabel.get(a.id) ?? a.name).localeCompare(deptPathLabel.get(b.id) ?? b.name, 'zh'));
  }, [departments, treeDebounced, deptPathLabel]);

  const roleName = (id: string) => roles.find((r) => r.id === id)?.name ?? `角色 ${id}`;
  const deptLabel = (id: string | null) => (id ? (deptPathLabel.get(id) ?? '未知部门') : '未分配');

  const toggleNode = (id: string) => {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const toggleStatus = async (u: UserRow) => {
    setActionFailure(null);
    setBusyUserId(u.id);
    try {
      await setUserStatus(u.id, !u.enabled, u.version);
      showToast(`成员「${memberName(u)}」已${u.enabled ? '禁止' : '开启'}`);
      reload();
    } catch (e) {
      setActionFailure(readFailure(e, `成员「${memberName(u)}」状态未变更`));
    } finally {
      setBusyUserId(null);
    }
  };

  /** 返回禁止删除的原因；null 表示允许删除。成员清单不可用时不能按 0 人放行。 */
  const deptDeleteBlock = (d: DepartmentRow): string | null => {
    if (deptFailure) return '部门目录未加载，无法确认删除条件';
    if (usersFailure) return `${usersFailure.message}，无法确认该部门没有成员`;
    const check = canDeleteDepartment(d, departments, directCounts.get(d.id) ?? 0);
    return check.allowed ? null : (check.reason ?? '该部门不满足删除条件');
  };

  const moveDepartment = async (deptId: string, parentId: string | null) => {
    if (!canUpdateDept) return;
    const dept = departments.find((d) => d.id === deptId);
    if (!dept || dept.parentId === parentId) return;
    if (parentId && departmentDescendants(departments, deptId).has(parentId)) {
      showToast('不能把部门移动到自己的子部门下');
      return;
    }
    setActionFailure(null);
    try {
      await updateDepartment(dept.id, { parentId, name: dept.name, enabled: dept.enabled }, dept.version);
      showToast(`部门「${dept.name}」已移动`);
      reload();
    } catch (e) {
      setActionFailure(readFailure(e, `部门「${dept.name}」移动未完成`));
    }
  };

  const moveMember = async (userId: string, target: DepartmentRow) => {
    if (!canMoveUser) return;
    const user = users.find((u) => u.id === userId);
    if (!user || user.deptId === target.id) return;
    setActionFailure(null);
    try {
      await setUserDepartment(user.id, target.id, user.version);
      showToast(`成员「${memberName(user)}」已移动到「${target.name}」`);
      reload();
    } catch (e) {
      const status = (e as { status?: number }).status;
      setActionFailure(
        status === 412
          ? { message: '该成员已被他人修改，请刷新列表后重试。', requestId: '' }
          : readFailure(e, `成员「${memberName(user)}」移动未完成`),
      );
    }
  };

  const deptDropBlocked = (target: DepartmentRow): boolean => {
    if (!draggingDeptId || !canUpdateDept) return true;
    if (draggingDeptId === target.id) return true;
    const dragged = departments.find((d) => d.id === draggingDeptId);
    if (!dragged || dragged.parentId === target.id) return true;
    return departmentDescendants(departments, draggingDeptId).has(target.id);
  };

  const onDeptDragOver = (e: DragEvent<HTMLDivElement>, target: DepartmentRow) => {
    if (draggingDeptId) {
      if (deptDropBlocked(target)) {
        e.dataTransfer.dropEffect = 'none';
        return;
      }
    } else if (draggingUserId) {
      const user = users.find((u) => u.id === draggingUserId);
      if (!canMoveUser || !user || user.deptId === target.id) {
        e.dataTransfer.dropEffect = 'none';
        return;
      }
    } else {
      return;
    }
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    setDropTarget(target.id);
  };

  const onDeptDrop = (e: DragEvent<HTMLDivElement>, target: DepartmentRow) => {
    e.preventDefault();
    e.stopPropagation();
    setDropTarget(null);
    if (draggingUserId) {
      const userId = draggingUserId;
      setDraggingUserId(null);
      void moveMember(userId, target);
      return;
    }
    if (draggingDeptId) {
      const deptId = draggingDeptId;
      setDraggingDeptId(null);
      void moveDepartment(deptId, target.id);
    }
  };

  const onRootDragOver = (e: DragEvent<HTMLDivElement>) => {
    if (!draggingDeptId || !canUpdateDept) return;
    const dragged = departments.find((d) => d.id === draggingDeptId);
    if (!dragged || dragged.parentId === null) {
      e.dataTransfer.dropEffect = 'none';
      return;
    }
    e.preventDefault();
    e.dataTransfer.dropEffect = 'move';
    setDropTarget(ROOT_DROP);
  };

  const onRootDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDropTarget(null);
    if (!draggingDeptId) return;
    const deptId = draggingDeptId;
    setDraggingDeptId(null);
    void moveDepartment(deptId, null);
  };

  const endDrag = () => {
    setDraggingDeptId(null);
    setDraggingUserId(null);
    setDropTarget(null);
  };

  /* 成员表：整行拖动由 onRow 承载，行键用用户 id；操作列链接按钮保留原 aria-label。 */
  const memberColumns: ColumnsType<UserRow> = [
    {
      title: '账号',
      dataIndex: 'username',
      width: 190,
      render: (_v: string, u) => (
        <span className="org-account">
          <span className="org-avatar" aria-hidden="true">
            {initialOf(u)}
          </span>
          <code className="org-username">@{u.username}</code>
        </span>
      ),
    },
    {
      title: '姓名',
      dataIndex: 'displayName',
      width: 170,
      render: (_v: string, u) => (
        <span className="cell-stack">
          <span className="org-member-name">{u.displayName}</span>
          {selectedId === null ? <span className="org-member-dept">{deptLabel(u.deptId)}</span> : null}
        </span>
      ),
    },
    {
      title: '角色',
      dataIndex: 'roleIds',
      render: (_v: string[], u) =>
        rolesFailure ? (
          <span className="org-muted">{`已分配 ${u.roleIds.length} 个（角色目录未加载）`}</span>
        ) : u.roleIds.length === 0 ? (
          <span className="org-muted">未分配</span>
        ) : (
          <span className="org-role-tags">
            {u.roleIds.map((id) => (
              <Badge key={id} tone="accent">
                {roleName(id)}
              </Badge>
            ))}
          </span>
        ),
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      width: 96,
      render: (v: boolean) => <StatusDot tone={v ? 'success' : 'neutral'}>{v ? '启用' : '停用'}</StatusDot>,
    },
    {
      title: '操作',
      width: 200,
      align: 'right',
      render: (_v, u) => (
        <span className="row-actions org-row-actions">
          <Button
            type="link"
            size="small"
            style={{ padding: 0 }}
            disabled={busyUserId === u.id}
            onClick={() => toggleStatus(u)}
            aria-label={`${u.enabled ? '禁止' : '开启'}成员 ${memberName(u)}`}
          >
            {u.enabled ? '禁止' : '开启'}
          </Button>
          <Button
            type="link"
            size="small"
            danger
            style={{ padding: 0 }}
            disabled={!canDeleteUser}
            aria-label={`删除成员 ${memberName(u)}`}
            title={canDeleteUser ? undefined : '缺少 ptmcp:user:delete 权限'}
            onClick={() => {
              setDeletingUser(u);
              setDeleteFailure(null);
            }}
          >
            删除
          </Button>
          <Button type="link" size="small" style={{ padding: 0 }} onClick={() => setEditingUser(u)} aria-label={`更多设置：${memberName(u)}`}>
            更多
          </Button>
        </span>
      ),
    },
  ];

  const selectOnKey = (e: KeyboardEvent<HTMLDivElement>, id: string | null) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      setSelectedId(id);
    }
  };

  const renderNode = (node: DepartmentTreeNode<DepartmentRow>): ReactNode => {
    const d = node.item;
    const hasChildren = node.children.length > 0;
    const expanded = !collapsed.has(d.id);
    const selected = selectedId === d.id;
    return (
      <div key={d.id} role="none">
        <div
          role="treeitem"
          aria-level={node.depth + 1}
          aria-selected={selected}
          aria-expanded={hasChildren ? expanded : undefined}
          /* 行内还有展开/新建/编辑/删除按钮：显式命名，避免读屏把按钮标签拼进部门名。 */
          aria-label={`${d.name}${d.enabled ? '' : '（已停用）'}，${usersFailure ? '成员数未知' : `${subtreeCounts.get(d.id) ?? 0} 名成员`}`}
          tabIndex={0}
          className={`org-tree-row${selected ? ' is-selected' : ''}${dropTarget === d.id ? ' is-drop-target' : ''}${draggingDeptId === d.id ? ' is-dragging' : ''}`}
          style={{ paddingLeft: 8 + node.depth * 16 }}
          onClick={() => setSelectedId(d.id)}
          onKeyDown={(e) => {
            selectOnKey(e, d.id);
            if (e.key === 'ArrowRight' && hasChildren && !expanded) toggleNode(d.id);
            if (e.key === 'ArrowLeft' && hasChildren && expanded) toggleNode(d.id);
          }}
          draggable={canUpdateDept}
          onDragStart={(e) => {
            if (!canUpdateDept) return;
            e.stopPropagation();
            e.dataTransfer.setData(DEPT_MIME, d.id);
            e.dataTransfer.setData('text/plain', d.name);
            e.dataTransfer.effectAllowed = 'move';
            setDraggingDeptId(d.id);
          }}
          onDragEnd={endDrag}
          onDragOver={(e) => onDeptDragOver(e, d)}
          onDragLeave={(e) => {
            if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDropTarget((cur) => (cur === d.id ? null : cur));
          }}
          onDrop={(e) => onDeptDrop(e, d)}
        >
          {hasChildren ? (
            <button
              type="button"
              className={`org-tree-toggle${expanded ? ' is-open' : ''}`}
              aria-label={expanded ? `折叠 ${d.name}` : `展开 ${d.name}`}
              tabIndex={-1}
              onClick={(e) => {
                e.stopPropagation();
                toggleNode(d.id);
              }}
            >
              <ChevronRightIcon />
            </button>
          ) : (
            <span className="org-tree-toggle org-tree-toggle--empty" aria-hidden="true" />
          )}
          <BuildingIcon className="org-tree-icon" />
          <span className="org-tree-name">{d.name}</span>
          {d.enabled ? null : <span className="org-tree-off">停用</span>}
          <span className="org-tree-count" title="含子部门成员数">
            {usersFailure ? '—' : (subtreeCounts.get(d.id) ?? 0)}
          </span>
          <span className="org-tree-actions">
            {canCreateDept ? (
              <button
                type="button"
                className="org-icon-btn"
                aria-label={`在 ${d.name} 下新建子部门`}
                title="新建子部门"
                onClick={(e) => {
                  e.stopPropagation();
                  setCreatingDept({ parentId: d.id });
                }}
              >
                <PlusIcon />
              </button>
            ) : null}
            {canUpdateDept ? (
              <button
                type="button"
                className="org-icon-btn"
                aria-label={`编辑部门 ${d.name}`}
                title="编辑部门"
                onClick={(e) => {
                  e.stopPropagation();
                  setEditingDept(d);
                }}
              >
                <EditIcon />
              </button>
            ) : null}
            {canDeleteDept ? (
              <button
                type="button"
                className="org-icon-btn org-icon-btn--danger"
                aria-label={`删除部门 ${d.name}`}
                title="删除部门"
                onClick={(e) => {
                  e.stopPropagation();
                  setDeletingDept(d);
                  setDeleteFailure(null);
                }}
              >
                <CloseIcon />
              </button>
            ) : null}
          </span>
        </div>
        {hasChildren && expanded ? <div role="group">{node.children.map(renderNode)}</div> : null}
      </div>
    );
  };

  const statusText = loading
    ? '正在加载成员列表'
    : usersFailure
      ? '成员列表加载失败'
      : debounced
        ? `匹配 ${filtered.length} 名成员`
        : `${filtered.length} 名成员`;

  return (
    <>
      <PageHeader
        title="人员组织"
        description="左侧选择部门，右侧管理成员；拖动部门或成员即可调整归属。"
        actions={
          <>
            <Button
              onClick={() => setCreatingDept({ parentId: selectedId })}
              disabled={!canCreateDept}
              title={canCreateDept ? undefined : '缺少 ptmcp:department:create 权限'}
            >
              <PlusIcon /> 新建部门
            </Button>
            <Button
              type="primary"
              onClick={() => setCreatingUser(true)}
              disabled={!canCreateUser}
              title={canCreateUser ? undefined : '缺少 ptmcp:user:create 权限'}
            >
              <PlusIcon /> 新建成员
            </Button>
          </>
        }
      />

      <div className="org-layout">
        <aside className="org-tree-panel" aria-label="部门">
          <header className="org-panel-head">
            <div className="org-panel-title">
              <h2>部门</h2>
              <span>{departments.length} 个</span>
            </div>
            <button
              type="button"
              className="org-icon-btn"
              aria-label="新建根级部门"
              title="新建根级部门"
              disabled={!canCreateDept}
              onClick={() => setCreatingDept({ parentId: null })}
            >
              <PlusIcon />
            </button>
          </header>
          <div className="org-tree-search">
            <SearchInput value={treeKeyword} onChange={setTreeKeyword} placeholder="搜索部门…" label="搜索部门" width="100%" />
          </div>
          {deptFailure ? (
            <div className="org-tree-state">
              <p>{deptFailure.message}</p>
              <Button type="link" size="small" style={{ padding: 0 }} onClick={reload}>
                重新读取
              </Button>
            </div>
          ) : loading && departments.length === 0 ? (
            <p className="org-tree-state">正在读取部门…</p>
          ) : (
            <div className="org-tree-scroll" role="tree" aria-label="部门树">
              {treeMatches ? (
                treeMatches.length ? (
                  treeMatches.map((d) => (
                    <div
                      key={d.id}
                      role="treeitem"
                      aria-selected={selectedId === d.id}
                      aria-label={`${d.name}，${deptPathLabel.get(d.id) ?? ''}，${usersFailure ? '成员数未知' : `${subtreeCounts.get(d.id) ?? 0} 名成员`}`}
                      tabIndex={0}
                      className={`org-tree-row org-tree-row--flat${selectedId === d.id ? ' is-selected' : ''}`}
                      onClick={() => setSelectedId(d.id)}
                      onKeyDown={(e) => selectOnKey(e, d.id)}
                    >
                      <BuildingIcon className="org-tree-icon" />
                      <span className="org-tree-name">{d.name}</span>
                      <span className="org-tree-path">{deptPathLabel.get(d.id) ?? ''}</span>
                      <span className="org-tree-count">{usersFailure ? '—' : (subtreeCounts.get(d.id) ?? 0)}</span>
                    </div>
                  ))
                ) : (
                  <p className="org-tree-state">没有匹配的部门。</p>
                )
              ) : (
                <>
                  <div
                    role="treeitem"
                    aria-selected={selectedId === null}
                    aria-label={`全部成员，${usersFailure ? '成员数未知' : `${users.length} 名成员`}`}
                    tabIndex={0}
                    className={`org-tree-row org-tree-all${selectedId === null ? ' is-selected' : ''}${dropTarget === ROOT_DROP ? ' is-drop-target' : ''}`}
                    onClick={() => setSelectedId(null)}
                    onKeyDown={(e) => selectOnKey(e, null)}
                    onDragOver={onRootDragOver}
                    onDragLeave={() => setDropTarget((cur) => (cur === ROOT_DROP ? null : cur))}
                    onDrop={onRootDrop}
                  >
                    <span className="org-tree-toggle org-tree-toggle--empty" aria-hidden="true" />
                    <UsersIcon className="org-tree-icon" />
                    <span className="org-tree-name">全部成员</span>
                    <span className="org-tree-count">{usersFailure ? '—' : users.length}</span>
                  </div>
                  {tree.map(renderNode)}
                </>
              )}
            </div>
          )}
          <footer className="org-tree-foot">
            <span>拖动部门调整层级</span>
            {canMoveUser ? <span>拖动成员调整归属</span> : null}
          </footer>
        </aside>

        <section className="org-main" aria-label="成员">
          <div className="table-card org-table-card" style={{ flex: 1, minHeight: 0 }}>
            <div className="table-toolbar org-table-toolbar">
              <div className="org-scope">
                <strong>{scope?.name ?? '全部成员'}</strong>
                <span>{scope ? departmentPath(departments, scope.id).join(' / ') : `${departments.length} 个部门`}</span>
              </div>
              <SearchInput value={keyword} onChange={setKeyword} placeholder="搜索账号或姓名…" label="搜索账号或姓名" width={220} />
              <span className="toolbar-status" aria-hidden="true">
                {loading ? '加载中…' : `${filtered.length} 人`}
              </span>
            </div>
            <p className="sr-only" aria-live="polite">
              {statusText}
            </p>

            {usersFailure ? (
              <div className="org-alert">
                <Alert kind="danger" title="成员列表加载失败">
                  {usersFailure.message}
                  <RequestIdText requestId={usersFailure.requestId} />
                  <Button type="link" size="small" style={{ padding: 0, marginLeft: 8 }} onClick={reload}>
                    重新读取
                  </Button>
                </Alert>
              </div>
            ) : null}
            {rolesFailure && !usersFailure ? (
              <div className="org-alert">
                <Alert kind="warning" title="角色目录加载失败">
                  {rolesFailure.message}；角色列按 ID 显示，成员详情中的角色保存已禁用。
                  <RequestIdText requestId={rolesFailure.requestId} />
                  <Button type="link" size="small" style={{ padding: 0, marginLeft: 8 }} onClick={reload}>
                    重新读取
                  </Button>
                </Alert>
              </div>
            ) : null}
            {actionFailure ? (
              <div className="org-alert">
                <Alert kind="danger" title="操作未完成">
                  {actionFailure.message}
                  <RequestIdText requestId={actionFailure.requestId} />
                </Alert>
              </div>
            ) : null}

            {loading && users.length === 0 && !usersFailure ? <p className="table-state">正在读取成员…</p> : null}

            {!loading && !usersFailure && filtered.length === 0 ? (
              <EmptyState
                kind={debounced ? 'filtered' : 'empty'}
                title={debounced ? '没有匹配的成员' : scope ? '该部门暂无成员' : '还没有成员'}
                description={
                  debounced
                    ? '换个关键词，或清除搜索查看全部成员。'
                    : scope
                      ? '成员可直接落在当前部门，也可稍后拖动调整。'
                      : '先创建部门，再把成员分配到部门下。'
                }
                action={
                  debounced ? (
                    <Button onClick={() => setKeyword('')}>清除搜索</Button>
                  ) : canCreateUser ? (
                    <Button type="primary" onClick={() => setCreatingUser(true)}>
                      <PlusIcon /> 新建成员
                    </Button>
                  ) : undefined
                }
              />
            ) : null}

            {!usersFailure && filtered.length > 0 ? (
              <div className="table-scroll org-table-scroll" role="region" aria-label="成员列表" tabIndex={0}>
                <Table<UserRow>
                  rowKey="id"
                  className="org-table"
                  size="middle"
                  dataSource={filtered}
                  columns={memberColumns}
                  pagination={false}
                  onRow={(u) => ({
                    draggable: canMoveUser,
                    onDragStart: (e) => {
                      if (!canMoveUser) return;
                      e.dataTransfer.setData(USER_MIME, u.id);
                      e.dataTransfer.setData('text/plain', memberName(u));
                      e.dataTransfer.effectAllowed = 'move';
                      setDraggingUserId(u.id);
                    },
                    onDragEnd: endDrag,
                    className: draggingUserId === u.id ? 'is-dragging' : undefined,
                  })}
                />
              </div>
            ) : null}
          </div>
        </section>
      </div>

      {creatingUser ? (
        <MemberFormDialog
          defaultDeptId={selectedId ?? ''}
          roles={roles}
          rolesFailure={rolesFailure}
          depts={departments}
          deptsFailure={deptFailure}
          onClose={() => setCreatingUser(false)}
          onSaved={reload}
        />
      ) : null}

      {editingUser ? (
        <MemberDrawer
          user={editingUser}
          departmentLabel={deptLabel(editingUser.deptId)}
          defaultDeptId={selectedId ?? ''}
          roles={roles}
          rolesFailure={rolesFailure}
          depts={departments}
          deptsFailure={deptFailure}
          onClose={() => setEditingUser(null)}
          onSaved={reload}
        />
      ) : null}

      {creatingDept ? (
        <DeptDialog dept={null} defaultParentId={creatingDept.parentId} all={departments} onClose={() => setCreatingDept(null)} onSaved={reload} />
      ) : null}

      {editingDept ? (
        <DeptDialog
          dept={editingDept}
          defaultParentId={editingDept.parentId}
          all={departments}
          onClose={() => setEditingDept(null)}
          onSaved={reload}
        />
      ) : null}

      <Dialog
        open={!!deletingUser}
        title={`删除成员 · ${deletingUser ? memberName(deletingUser) : ''}`}
        onClose={() => {
          setDeletingUser(null);
          setDeleteFailure(null);
        }}
        width={440}
        footer={
          <>
            <Button
              onClick={() => {
                setDeletingUser(null);
                setDeleteFailure(null);
              }}
              disabled={busy}
            >
              取消
            </Button>
            <Button
              type="primary"
              style={{ background: 'var(--ptmcp-red)' }}
              disabled={busy || !canDeleteUser}
              onClick={async () => {
                if (!deletingUser) return;
                setBusy(true);
                setDeleteFailure(null);
                try {
                  await deleteUser(deletingUser.id, deletingUser.version);
                  showToast('成员已删除');
                  setDeletingUser(null);
                  reload();
                } catch (e) {
                  setDeleteFailure(readFailure(e, '删除请求未完成'));
                } finally {
                  setBusy(false);
                }
              }}
            >
              {busy ? '删除中…' : '确认删除'}
            </Button>
          </>
        }
      >
        {deleteFailure ? (
          <Alert kind="danger" title="删除失败">
            {deleteFailure.message}
            <RequestIdText requestId={deleteFailure.requestId} />
          </Alert>
        ) : null}
        {!canDeleteUser ? (
          <Alert kind="warning" title="删除不可用">
            当前账号缺少 ptmcp:user:delete 权限，请改用「禁止」。
          </Alert>
        ) : null}
        <p className="org-dialog-text">确认删除成员「{deletingUser ? memberName(deletingUser) : ''}」？该账号将立即无法登录，历史审计记录保留。</p>
      </Dialog>

      <Dialog
        open={!!deletingDept}
        title={`删除部门 · ${deletingDept?.name ?? ''}`}
        onClose={() => {
          setDeletingDept(null);
          setDeleteFailure(null);
        }}
        width={440}
        footer={
          <>
            <Button
              onClick={() => {
                setDeletingDept(null);
                setDeleteFailure(null);
              }}
              disabled={busy}
            >
              取消
            </Button>
            <Button
              type="primary"
              style={{ background: 'var(--ptmcp-red)' }}
              disabled={busy || !canDeleteDept || (deletingDept ? !!deptDeleteBlock(deletingDept) : true)}
              onClick={async () => {
                if (!deletingDept) return;
                setBusy(true);
                setDeleteFailure(null);
                try {
                  await deleteDepartment(deletingDept.id, deletingDept.version);
                  showToast('部门已删除');
                  setDeletingDept(null);
                  reload();
                } catch (e) {
                  setDeleteFailure(readFailure(e, '删除请求未完成'));
                } finally {
                  setBusy(false);
                }
              }}
            >
              {busy ? '删除中…' : '确认删除'}
            </Button>
          </>
        }
      >
        {deleteFailure ? (
          <Alert kind="danger" title="删除失败">
            {deleteFailure.message}
            <RequestIdText requestId={deleteFailure.requestId} />
          </Alert>
        ) : null}
        {deletingDept && deptDeleteBlock(deletingDept) ? (
          <Alert kind="warning" title="暂不能删除">
            {deptDeleteBlock(deletingDept)}
          </Alert>
        ) : (
          <p className="org-dialog-text">确认删除部门「{deletingDept?.name}」？只有没有子部门且没有直属成员的部门可以删除。</p>
        )}
      </Dialog>
    </>
  );
}
