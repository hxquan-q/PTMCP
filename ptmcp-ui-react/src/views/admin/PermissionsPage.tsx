import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  Alert,
  Badge,
  CellIcon,
  Dialog,
  EmptyState,
  Field,
  PageHeader,
  Pagination,
  SearchInput,
  Select,
  StatusDot,
  Tabs,
  useDebounced,
} from '../../components/common';
import {
  BarChartIcon,
  BuildingIcon,
  ChevronRightIcon,
  ClockIcon,
  DatabaseIcon,
  DocumentIcon,
  ExpandIcon,
  ListIcon,
  RefreshIcon,
  TagIcon,
  UserIcon,
  UsersIcon,
} from '../../components/Icons';
import { createGrant, deleteGrant, listGrants, previewAccess, type Grant, type Preview, type SubjectType } from '../../services/access';
import { decodeResourceId, encodeObjectIds } from '../../services/resourceIds';
import { listDatasources, listMetadata, type DatasourceRead, type MetadataObject } from '../../services/data';
import { buildTree, type ResourceNode } from './resourceTree';
import { useSensitivePermissions } from '../../permissionsContext';
import { resizeColumns } from './columnResize';
import { mePortalApi, type PlatformItem } from '../../services/mePortalApi';
import { listDepartments, listRoles, listUsers, type DepartmentRow, type RoleRow, type UserRow } from '../../services/orgPortalApi';

/* ─── 资源树 ─── */
const SUBJECT_TABS = [
  { key: 'USER', label: '用户' },
  { key: 'ROLE', label: '角色' },
  { key: 'DEPT', label: '部门' },
];

interface SubjectItem {
  id: string;
  name: string;
  sub: string;
}

export function PermissionsPage() {
  const { can } = useSensitivePermissions();
  const [subjectType, setSubjectType] = useState<SubjectType>('USER');
  const [subjects, setSubjects] = useState<SubjectItem[]>([]);
  const [subjectId, setSubjectId] = useState('');
  const [subjectKeyword, setSubjectKeyword] = useState('');
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  const [datasourceId, setDatasourceId] = useState('');
  const [metadataObjects, setMetadataObjects] = useState<MetadataObject[]>([]);
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [metadataError, setMetadataError] = useState('');
  const [subjectsError, setSubjectsError] = useState('');
  const [subjectsTick, setSubjectsTick] = useState(0);
  const [grantsError, setGrantsError] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [resourceKey, setResourceKey] = useState('');
  const [treeActiveKey, setTreeActiveKey] = useState('');
  const [grants, setGrants] = useState<Grant[]>([]);
  const [grantsLoading, setGrantsLoading] = useState(false);
  const [grantPage, setGrantPage] = useState(1);
  const [focus, setFocus] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [platforms, setPlatforms] = useState<PlatformItem[]>([]);
  const [platformId, setPlatformId] = useState('');
  const [previewAt, setPreviewAt] = useState('');
  const [previewResult, setPreviewResult] = useState<Preview | null>(null);
  const [previewRequestedAt, setPreviewRequestedAt] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState('');
  const [colWidths, setColWidths] = useState<[number, number]>([25, 25]); // 前两个栏 %,第三栏取余
  const [grantDialogOpen, setGrantDialogOpen] = useState(false);
  const [formResourceId, setFormResourceId] = useState('');
  const [formAction, setFormAction] = useState<'META' | 'READ'>('READ');
  const [formEffect, setFormEffect] = useState<'ALLOW' | 'DENY'>('ALLOW');
  const [formValidTo, setFormValidTo] = useState('');
  const [formReason, setFormReason] = useState('');
  const [grantSubmitting, setGrantSubmitting] = useState(false);
  const [grantFormError, setGrantFormError] = useState('');
  const [revokingId, setRevokingId] = useState('');
  const debouncedSubject = useDebounced(subjectKeyword, 150);

  /* 主体加载 */
  useEffect(() => {
    let cancelled = false;
    setSubjectsError('');
    const load = async (): Promise<SubjectItem[]> => {
      if (subjectType === 'USER') {
        const users = await listUsers();
        return users.map((u: UserRow) => ({ id: u.id, name: u.displayName, sub: `${u.username} · ID ${u.id}` }));
      }
      if (subjectType === 'ROLE') {
        const roles = await listRoles();
        return roles.map((r: RoleRow) => ({ id: r.id, name: r.name, sub: `${r.key} · ID ${r.id}` }));
      }
      const depts = await listDepartments();
      return depts.map((d: DepartmentRow) => ({ id: d.id, name: d.name, sub: `ID ${d.id}` }));
    };
    load()
      .then((items) => {
        if (cancelled) return;
        setSubjects(items);
        setSubjectId(items[0]?.id ?? '');
      })
      .catch(() => {
        if (cancelled) return;
        // 加载失败不能伪装成“没有主体”：清空选择，避免对未知主体继续读写规则。
        setSubjects([]);
        setSubjectId('');
        setSubjectsError('主体目录加载失败，当前无法确认已有的授权对象。');
      });
    return () => {
      cancelled = true;
    };
  }, [subjectType, subjectsTick]);

  /* 数据源 + 平台加载 */
  useEffect(() => {
    let cancelled = false;
    listDatasources({ pageSize: 100 })
      .then(({ data }) => {
        if (cancelled) return;
        setDatasources(data.items);
        setDatasourceId((cur) => cur || data.items[0]?.id || '');
      })
      .catch(() => undefined);
    mePortalApi.accessContext
      .platforms()
      .then((ctx) => {
        if (cancelled) return;
        setPlatforms(ctx.items);
        setPlatformId((cur) => cur || ctx.items[0]?.id || '');
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, []);

  /* 元数据对象加载 */
  useEffect(() => {
    if (!datasourceId) {
      setMetadataObjects([]);
      setMetadataError('');
      return;
    }
    let cancelled = false;
    setMetadataLoading(true);
    setMetadataError('');
    listMetadata({ datasourceId, pageSize: 200 })
      .then(({ data }) => {
        if (cancelled) return;
        setMetadataObjects(data.items);
        // 默认展开第一个 schema，并让树里始终有一个可 Tab 进入的行
        const first = data.items[0];
        const firstKey = first ? `schema:${first.schemaName ?? first.databaseName ?? 'default'}` : '';
        if (firstKey) setExpanded(new Set([firstKey]));
        setTreeActiveKey(firstKey);
        setResourceKey('');
      })
      .catch(() => {
        if (cancelled) return;
        setMetadataObjects([]);
        setMetadataError('资源树加载失败，无法确认资源版本；在重新读取成功前不能提交规则。');
      })
      .finally(() => !cancelled && setMetadataLoading(false));
    return () => {
      cancelled = true;
    };
  }, [datasourceId]);

  /* 规则加载 */
  const refreshGrants = useCallback(() => {
    if (!subjectId) {
      setGrants([]);
      return Promise.resolve();
    }
    return listGrants(subjectType, subjectId)
      .then((rows) => {
        setGrants(rows);
        setGrantsError('');
      })
      .catch(() => {
        setGrants([]);
        setGrantsError('规则读取失败，列表当前不可信；请勿据此判断主体已有或没有权限。');
      });
  }, [subjectType, subjectId]);

  useEffect(() => {
    let cancelled = false;
    setGrantsLoading(true);
    setGrantsError('');
    if (!subjectId) {
      setGrants([]);
      setGrantsLoading(false);
      return;
    }
    listGrants(subjectType, subjectId)
      .then((rows) => !cancelled && setGrants(rows))
      .catch(() => {
        if (cancelled) return;
        setGrants([]);
        setGrantsError('规则读取失败，列表当前不可信；请勿据此判断主体已有或没有权限。');
      })
      .finally(() => !cancelled && setGrantsLoading(false));
    return () => {
      cancelled = true;
    };
  }, [subjectType, subjectId]);

  /* 切换主体、平台或数据源后旧预览立即失效，避免把上一组结论读成当前配置。 */
  useEffect(() => {
    setPreviewResult(null);
    setPreviewError('');
    setPreviewRequestedAt('');
  }, [subjectType, subjectId, platformId, datasourceId]);

  const tree = useMemo<ResourceNode[]>(() => buildTree(metadataObjects), [metadataObjects]);
  const selectedResource = useMemo(() => findNode(tree, resourceKey), [tree, resourceKey]);

  /* 新增规则 */
  const openGrantDialog = useCallback(() => {
    setFormResourceId(selectedResource?.kind === 'OBJECT' ? (selectedResource.resourceId ?? '') : '');
    setFormAction('READ');
    setFormEffect('ALLOW');
    setFormValidTo('');
    setFormReason('');
    setGrantFormError('');
    setGrantDialogOpen(true);
  }, [selectedResource]);

  const submitGrant = useCallback(() => {
    if (!subjectId || !formResourceId || grantSubmitting) return;
    const validTo = formValidTo ? new Date(formValidTo) : null;
    if (validTo && Number.isNaN(validTo.getTime())) {
      setGrantFormError('请选择有效的到期时间');
      return;
    }
    if (validTo && validTo.getTime() <= Date.now()) {
      setGrantFormError('有效期须晚于当前时间：左闭右开区间已经结束的规则不会生效');
      return;
    }
    setGrantSubmitting(true);
    setGrantFormError('');
    createGrant({
      subjectType,
      subjectId,
      resourceType: 'OBJECT',
      resourceId: formResourceId,
      resourceGeneration: metadataObjects.find((o) => o.id === formResourceId)?.generation ?? '1',
      action: formAction,
      effect: formEffect,
      // 对象级资源没有下级；数据源/命名空间级授权的服务端语义尚未开放，不发送无法核验的继承标记。
      includeDescendants: false,
      validFrom: new Date().toISOString(),
      validTo: validTo ? validTo.toISOString() : null,
      reason: formReason.trim(),
    })
      .then(() => {
        setGrantDialogOpen(false);
        return refreshGrants();
      })
      .catch((e: unknown) => setGrantFormError(e instanceof Error ? e.message : '保存失败'))
      .finally(() => setGrantSubmitting(false));
  }, [subjectId, subjectType, formResourceId, formAction, formEffect, formValidTo, formReason, metadataObjects, refreshGrants, grantSubmitting]);

  const revokeGrant = useCallback(
    (id: string) => {
      if (revokingId) return;
      setRevokingId(id);
      deleteGrant(id)
        .then(() => refreshGrants())
        .catch(() => undefined)
        .finally(() => setRevokingId(''));
    },
    [refreshGrants, revokingId],
  );

  const filteredSubjects = useMemo(
    () => subjects.filter((s) => !debouncedSubject || `${s.name}${s.sub}`.toLowerCase().includes(debouncedSubject.toLowerCase())),
    [subjects, debouncedSubject],
  );
  const grantRows = useMemo(() => {
    if (!selectedResource) return grants;
    if (selectedResource.kind === 'OBJECT') return grants.filter((g) => g.resourceType === 'OBJECT' && g.resourceId === selectedResource.resourceId);
    if (selectedResource.kind === 'SCHEMA') return grants.filter((g) => g.resourceType !== 'DATASOURCE');
    return grants;
  }, [grants, selectedResource]);
  const grantPageSize = 10;
  const grantPageRows = grantRows.slice((grantPage - 1) * grantPageSize, grantPage * grantPageSize);
  const subject = subjects.find((s) => s.id === subjectId);

  /* 预览 resourceIds — 取当前树中所有 OBJECT 节点，编码进 ResourceIds 空间（服务端上限 100）。 */
  const rawObjectIds = useMemo(() => tree.flatMap((schema) => (schema.children ?? []).map((c) => c.resourceId).filter(Boolean) as string[]), [tree]);
  const encodedObjects = useMemo(() => encodeObjectIds(rawObjectIds.slice(0, 100)), [rawObjectIds]);
  const allObjectIds = encodedObjects.ids;
  const previewSubjectSupported = subjectType === 'USER';
  const previewTruncated = rawObjectIds.length > 100;

  /* 拖拽分栏 */
  const containerRef = useRef<HTMLDivElement>(null);
  const dragState = useRef<{ index: 0 | 1; startX: number; start: [number, number] } | null>(null);
  const onResizerDown = useCallback(
    (index: 0 | 1) => (e: React.PointerEvent) => {
      dragState.current = { index, startX: e.clientX, start: [...colWidths] as [number, number] };
      (e.target as HTMLElement).setPointerCapture(e.pointerId);
    },
    [colWidths],
  );
  const onResizerMove = useCallback((e: React.PointerEvent) => {
    const d = dragState.current;
    const el = containerRef.current;
    if (!d || !el) return;
    const deltaPct = ((e.clientX - d.startX) / el.getBoundingClientRect().width) * 100;
    setColWidths(resizeColumns(d.start, d.index, deltaPct));
  }, []);
  const onResizerUp = useCallback(() => {
    dragState.current = null;
  }, []);

  /** 键盘调宽：方向键按 4% 步进，交由 resizeColumns 钳制在 [14,50]。 */
  const nudgeColumn = useCallback((index: 0 | 1) => (deltaPct: number) => setColWidths((cur) => resizeColumns(cur, index, deltaPct)), []);

  const toggleNode = (key: string) =>
    setExpanded((s) => {
      const next = new Set(s);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  /* 资源树键盘导航：单 Tab 焦点 + ↑↓/←→/Home/End（ARIA tree 模式）。 */
  const onTreeKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLDivElement>) => {
      const rows = Array.from(e.currentTarget.querySelectorAll<HTMLElement>('.tree-row'));
      const idx = rows.indexOf(document.activeElement as HTMLElement);
      if (idx < 0) return;
      const row = rows[idx];
      const depth = Number(row.dataset.depth ?? 0);
      const branch = row.dataset.branch === 'true';
      const open = row.dataset.open === 'true';
      const move = (i: number) => {
        const next = rows[Math.max(0, Math.min(rows.length - 1, i))];
        if (next) next.focus();
        e.preventDefault();
      };
      if (e.key === 'ArrowDown') move(idx + 1);
      else if (e.key === 'ArrowUp') move(idx - 1);
      else if (e.key === 'Home') move(0);
      else if (e.key === 'End') move(rows.length - 1);
      else if (e.key === 'ArrowRight') {
        if (branch && !open) toggleNode(row.dataset.key ?? '');
        else move(idx + 1);
        e.preventDefault();
      } else if (e.key === 'ArrowLeft') {
        if (branch && open) toggleNode(row.dataset.key ?? '');
        else {
          let parent = idx - 1;
          while (parent >= 0 && Number(rows[parent].dataset.depth ?? 0) >= depth) parent -= 1;
          if (parent >= 0) rows[parent].focus();
          e.preventDefault();
        }
      }
    },
    [toggleNode],
  );

  const gridCols = focus ? '1fr' : `${colWidths[0]}% 6px ${colWidths[1]}% 6px 1fr`;
  const previewIsFuture = !!previewRequestedAt && new Date(previewRequestedAt).getTime() > Date.now();
  /** datetime-local 的 min：默认给到下一分钟，减少“刚选好就已在过去”的无效提交。 */
  const validToMin = useMemo(() => {
    const d = new Date(Date.now() + 60_000);
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
  }, []);

  return (
    <>
      <PageHeader
        title="权限矩阵"
        description="选择主体与资源，管理规则并计算平台范围内的有效权限。"
        actions={
          <button
            className="btn btn--primary"
            disabled={!subjectId || !!metadataError || !can('aclGrant')}
            title={metadataError ? '资源树读取失败，重新读取成功后才能提交规则' : undefined}
            onClick={openGrantDialog}
          >
            + 新增规则
          </button>
        }
      />
      <div
        ref={containerRef}
        className="perm-workspace"
        onPointerMove={onResizerMove}
        onPointerUp={onResizerUp}
        style={{ display: 'grid', gridTemplateColumns: gridCols, gap: 0, flex: 1, minHeight: 0 }}
      >
        {/* ─── 栏 1:授权主体 ─── */}
        {!focus ? (
          <Pane
            icon={<CellIcon icon={UserIcon} />}
            title="授权主体"
            subtitle="选择需要配置权限的主体"
            footer={<span>共 {filteredSubjects.length} 个主体</span>}
          >
            <div style={{ padding: '0 14px 10px', flex: 'none' }}>
              <Tabs tabs={SUBJECT_TABS} active={subjectType} onChange={(k) => setSubjectType(k as SubjectType)} />
              <SearchInput value={subjectKeyword} onChange={setSubjectKeyword} placeholder="搜索主体名称或 ID" width="100%" />
            </div>
            <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 8px 8px' }}>
              {subjectsError ? (
                <div style={{ padding: '8px 6px' }}>
                  <Alert kind="danger" title="主体目录读取失败">
                    {subjectsError}
                    <div style={{ marginTop: 8 }}>
                      <button className="btn btn--secondary" onClick={() => setSubjectsTick((t) => t + 1)}>
                        <RefreshIcon /> 重新读取
                      </button>
                    </div>
                  </Alert>
                </div>
              ) : (
                filteredSubjects.map((s) => (
                  <button
                    key={s.id}
                    className={`subject-row${subjectId === s.id ? ' is-selected' : ''}`}
                    onClick={() => {
                      setSubjectId(s.id);
                      setGrantPage(1);
                    }}
                    aria-pressed={subjectId === s.id}
                  >
                    <CellIcon icon={subjectType === 'USER' ? UserIcon : subjectType === 'ROLE' ? TagIcon : UsersIcon} />
                    <span className="cell-stack">
                      <strong>{s.name}</strong>
                      <span>{s.sub}</span>
                    </span>
                  </button>
                ))
              )}
              {!subjectsError && filteredSubjects.length === 0 ? (
                <EmptyState title="没有匹配的主体" description="清除搜索词，或到用户、角色、部门页面确认目录内容。" />
              ) : null}
            </div>
          </Pane>
        ) : null}
        {!focus ? (
          <Resizer onPointerDown={onResizerDown(0)} onNudge={nudgeColumn(0)} value={colWidths[0]} label="调整“授权主体”与“资源范围”栏宽度" />
        ) : null}
        {/* ─── 栏 2:资源范围 ─── */}
        {!focus ? (
          <Pane
            icon={<CellIcon icon={DatabaseIcon} />}
            title="资源范围"
            subtitle="选择要授权的数据资源范围"
            footer={
              <span>
                {datasources.length} 个数据源 · {tree.reduce((n, s) => n + (s.children?.length ?? 0), 0)} 个对象
              </span>
            }
          >
            <div style={{ padding: '0 14px 10px', flex: 'none', display: 'grid', gap: 8 }}>
              <label style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>数据源</label>
              <Select
                value={datasourceId}
                onChange={setDatasourceId}
                width="100%"
                options={datasources.map((d) => ({ value: d.id, label: `${d.name} (${d.engine})` }))}
              />
            </div>
            <div
              role="tree"
              aria-label="资源对象树"
              onKeyDown={onTreeKeyDown}
              style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 8px 8px' }}
            >
              {metadataLoading ? (
                <p style={{ padding: '12px 14px', fontSize: 13, color: 'var(--ptmcp-text-faint)' }}>加载元数据…</p>
              ) : metadataError ? (
                <div style={{ padding: '8px 6px' }}>
                  <Alert kind="danger" title="资源树读取失败">
                    {metadataError}
                  </Alert>
                </div>
              ) : tree.length === 0 ? (
                <p style={{ padding: '12px 14px', fontSize: 13, color: 'var(--ptmcp-text-faint)' }}>
                  {datasourceId ? '该数据源当前没有可见元数据对象，可能仍在“待同步”。先完成同步再回来授权。' : '先选择数据源以读取当前资源。'}
                </p>
              ) : (
                tree.map((node) => (
                  <TreeBranch
                    key={node.key}
                    node={node}
                    depth={0}
                    expanded={expanded}
                    selected={resourceKey}
                    activeKey={treeActiveKey}
                    onToggle={toggleNode}
                    onActivate={setTreeActiveKey}
                    onSelect={(k) => {
                      setResourceKey(k);
                      setGrantPage(1);
                    }}
                  />
                ))
              )}
            </div>
          </Pane>
        ) : null}
        {!focus ? (
          <Resizer onPointerDown={onResizerDown(1)} onNudge={nudgeColumn(1)} value={colWidths[1]} label="调整“资源范围”与“授权规则”栏宽度" />
        ) : null}
        {/* ─── 栏 3:授权规则 ─── */}
        <Pane
          icon={<CellIcon icon={ListIcon} />}
          title="授权规则"
          subtitle={subject ? `${subject.name} 的直接规则` : '选择左侧主体查看规则'}
          headerExtra={
            <span style={{ display: 'inline-flex', gap: 8 }}>
              <button className="btn btn--secondary" style={{ padding: '5px 10px' }} onClick={() => void refreshGrants()}>
                <RefreshIcon /> 刷新
              </button>
              <button
                className="btn btn--secondary"
                style={{ padding: '5px 10px' }}
                title={focus ? '退出专注模式' : '专注模式:规则表占满工作区'}
                aria-pressed={focus}
                onClick={() => setFocus((f) => !f)}
              >
                <ExpandIcon />
              </button>
            </span>
          }
          footer={
            <>
              <span>
                共 {grantRows.length} 条规则{selectedResource ? ` · 已过滤:${selectedResource.label}` : ''}
              </span>
              <Pagination total={grantRows.length} pageSize={grantPageSize} page={grantPage} onPage={setGrantPage} />
            </>
          }
        >
          <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
            {!subjectId ? (
              <EmptyState
                title="尚未选择授权主体"
                description="先在左侧选择主体，再读取直接规则。"
                action={
                  <Alert kind="info">
                    规则列表展示直接配置；有效权限还受平台范围、继承、DENY 和有效期约束。READ ALLOW 隐含 META，META DENY 阻断 READ。
                  </Alert>
                }
              />
            ) : grantsLoading ? (
              <p style={{ padding: 20, fontSize: 13, color: 'var(--ptmcp-text-muted)' }}>加载规则中…</p>
            ) : grantsError ? (
              <div style={{ padding: 16 }}>
                <Alert kind="danger" title="规则读取失败">
                  {grantsError}
                  <div style={{ marginTop: 8 }}>
                    <button className="btn btn--secondary" onClick={() => void refreshGrants()}>
                      <RefreshIcon /> 重新读取
                    </button>
                  </div>
                </Alert>
              </div>
            ) : grantPageRows.length === 0 ? (
              <EmptyState
                title="暂无授权规则"
                description={selectedResource ? `该主体在“${selectedResource.label}”下还没有直接规则。` : '该主体还没有直接规则。'}
              />
            ) : (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>资源名称</th>
                    <th>类型</th>
                    <th>权限</th>
                    <th>效果</th>
                    <th>含下级</th>
                    <th>有效期</th>
                    <th>状态</th>
                    <th style={{ width: 90 }}>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {grantPageRows.map((g) => {
                    const obj = metadataObjects.find((o) => o.id === g.resourceId);
                    const display = obj?.objectName ?? obj?.tableName ?? g.resourceId;
                    return (
                      <tr key={g.id}>
                        <td>
                          <span className="cell-lead">
                            <CellIcon
                              icon={g.resourceType === 'OBJECT' ? DocumentIcon : g.resourceType === 'NAMESPACE' ? BuildingIcon : DatabaseIcon}
                            />
                            <span className="cell-stack">
                              <strong>{display}</strong>
                              <span>
                                ID {g.id} · 资源 {g.resourceId}
                              </span>
                            </span>
                          </span>
                        </td>
                        <td style={{ color: 'var(--ptmcp-text-muted)' }}>{g.resourceType}</td>
                        <td>
                          <Badge tone={g.action === 'READ' ? 'accent' : 'neutral'}>{g.action}</Badge>
                        </td>
                        <td>
                          <StatusDot tone={g.effect === 'ALLOW' ? 'success' : 'danger'}>{g.effect === 'ALLOW' ? '允许' : '拒绝'}</StatusDot>
                        </td>
                        <td style={{ color: 'var(--ptmcp-text-muted)' }}>{g.includeDescendants ? '是' : '否'}</td>
                        <td style={{ color: 'var(--ptmcp-text-muted)', whiteSpace: 'nowrap' }}>{validityLabel(g.validTo)}</td>
                        <td>
                          <StatusDot tone={g.status === 'ACTIVE' ? 'success' : 'neutral'}>{g.status === 'ACTIVE' ? '生效中' : '已撤销'}</StatusDot>
                        </td>
                        <td>
                          {g.status === 'ACTIVE' ? (
                            <button className="link" disabled={revokingId === g.id} onClick={() => revokeGrant(g.id)}>
                              {revokingId === g.id ? '撤销中…' : '撤销'}
                            </button>
                          ) : (
                            <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>—</span>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            )}
          </div>
        </Pane>
      </div>
      {/* ─── 底部:有效权限预览(默认折叠为一行) ─── */}
      <div className="table-card" style={{ flex: 'none', marginTop: 12 }}>
        <button
          onClick={() => setPreviewOpen((o) => !o)}
          aria-expanded={previewOpen}
          aria-controls="perm-preview-panel"
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            width: '100%',
            padding: '11px 16px',
            border: 'none',
            background: 'none',
            textAlign: 'left',
          }}
        >
          <CellIcon icon={BarChartIcon} />
          <span className="cell-stack" style={{ flex: 1 }}>
            <strong>有效权限预览</strong>
            <span>由服务端计算当前选中资源，不执行查询。</span>
          </span>
          <ChevronRightIcon
            style={{ transform: previewOpen ? 'rotate(90deg)' : undefined, transition: 'transform .15s', color: 'var(--ptmcp-text-faint)' }}
          />
        </button>
        {previewOpen ? (
          <div
            id="perm-preview-panel"
            role="region"
            aria-label="有效权限预览"
            style={{ borderTop: '1px solid var(--ptmcp-border)', padding: '14px 16px' }}
          >
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                padding: '10px 14px',
                background: 'var(--ptmcp-chrome)',
                borderRadius: 'var(--ptmcp-radius-control)',
                fontSize: 13,
                color: 'var(--ptmcp-text)',
                fontWeight: 600,
              }}
            >
              平台 Scope ∩ 用户规则（含继承） = 最终权限
            </div>
            <p style={{ margin: '8px 0 14px', fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
              由服务端按有效期计算；DENY 优先，META DENY 阻断 READ，预览不代替执行时授权。
            </p>
            <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', flexWrap: 'wrap' }}>
              <Select
                label="平台"
                value={platformId}
                onChange={setPlatformId}
                width={280}
                options={[{ value: '', label: '选择当前可见平台' }, ...platforms.map((p) => ({ value: p.id, label: p.name }))]}
              />
              <label style={{ display: 'inline-flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--ptmcp-text)' }}>
                计算时点（留空为当前）
                <span className="input-wrap" style={{ width: 180 }}>
                  <ClockIcon className="input-icon" />
                  <input
                    className="input"
                    style={{ width: '100%' }}
                    type="datetime-local"
                    value={previewAt}
                    onChange={(e) => setPreviewAt(e.target.value)}
                  />
                </span>
              </label>
              <button
                className="btn btn--primary"
                disabled={!previewSubjectSupported || !subjectId || !platformId || !allObjectIds.length || previewLoading || !!encodedObjects.error}
                title={previewSubjectSupported ? undefined : '服务端预览接口只支持用户主体；角色/部门预览暂不可用'}
                onClick={() => {
                  setPreviewLoading(true);
                  setPreviewError('');
                  setPreviewResult(null);
                  const requestedAt = previewAt ? new Date(previewAt).toISOString() : '';
                  previewAccess({
                    subjectUserId: subjectId,
                    platformId,
                    resourceIds: allObjectIds,
                    ...(requestedAt ? { at: requestedAt } : {}),
                  })
                    .then((res) => {
                      setPreviewResult(res);
                      setPreviewRequestedAt(requestedAt);
                    })
                    .catch(() => setPreviewError('预览计算失败，请确认用户主体与平台已选择。'))
                    .finally(() => setPreviewLoading(false));
                }}
              >
                <BarChartIcon /> {previewLoading ? '计算中…' : '计算有效权限'}
              </button>
            </div>
            {!previewSubjectSupported ? (
              <p style={{ margin: '8px 0 0', fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
                当前主体为{subjectType === 'ROLE' ? '角色' : '部门'}：服务端预览接口只接受用户主体，请切换到用户后再计算。
              </p>
            ) : null}
            {encodedObjects.error ? <p style={{ margin: '8px 0 0', fontSize: 12, color: 'var(--ptmcp-red)' }}>{encodedObjects.error}</p> : null}
            {previewTruncated && !encodedObjects.error ? (
              <p style={{ margin: '8px 0 0', fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>当前对象数超过服务端单次上限，仅预览前 100 个对象。</p>
            ) : null}
            {previewError ? (
              <div style={{ marginTop: 12 }}>
                <Alert kind="danger" title="计算失败">
                  {previewError}
                </Alert>
              </div>
            ) : null}
            {previewResult ? (
              <>
                <p
                  style={{
                    margin: '12px 0 0',
                    fontSize: 12,
                    color: 'var(--ptmcp-text-muted)',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    flexWrap: 'wrap',
                  }}
                >
                  <span>服务端计算时点 {fmtStamp(previewResult.evaluatedAt)}</span>
                  <span>{previewResult.nextBoundaryAt ? `下一次权限边界 ${fmtStamp(previewResult.nextBoundaryAt)}` : '无待生效的权限边界'}</span>
                  {previewIsFuture ? <Badge tone="warning">未来时点模拟，不可据此执行查询</Badge> : null}
                </p>
                <div style={{ marginTop: 8, overflowX: 'auto' }}>
                  <table className="data-table" style={{ width: '100%', minWidth: 480 }}>
                    <thead>
                      <tr>
                        <th style={{ textAlign: 'left' }}>资源</th>
                        <th style={{ textAlign: 'center', width: 90 }}>META</th>
                        <th style={{ textAlign: 'center', width: 90 }}>READ</th>
                        <th style={{ textAlign: 'left' }}>依据</th>
                      </tr>
                    </thead>
                    <tbody>
                      {previewResult.decisions.map((d) => {
                        const decoded = decodeResourceId(d.resourceId);
                        const obj = decoded?.type === 'OBJECT' ? metadataObjects.find((o) => o.id === decoded.tableId) : undefined;
                        const label = obj?.objectName ?? obj?.tableName ?? `${decoded?.type ?? 'RESOURCE'} #${decoded?.tableId ?? d.resourceId}`;
                        return (
                          <tr key={d.resourceId}>
                            <td style={{ fontWeight: 500 }}>{label}</td>
                            <td style={{ textAlign: 'center' }}>
                              <StatusDot tone={d.meta ? 'success' : 'danger'}>{d.meta ? '允许' : '拒绝'}</StatusDot>
                            </td>
                            <td style={{ textAlign: 'center' }}>
                              <StatusDot tone={d.read ? 'success' : 'danger'}>{d.read ? '允许' : '拒绝'}</StatusDot>
                            </td>
                            <td style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>
                              {d.sourceGrantIds.length ? `授权 ${d.sourceGrantIds.join(', ')}` : '无命中规则'}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </>
            ) : null}
          </div>
        ) : null}
      </div>
      <Dialog
        open={grantDialogOpen}
        title="新增授权规则"
        onClose={() => !grantSubmitting && setGrantDialogOpen(false)}
        footer={
          <>
            <button className="btn btn--secondary" disabled={grantSubmitting} onClick={() => setGrantDialogOpen(false)}>
              取消
            </button>
            <button className="btn btn--primary" disabled={!formResourceId || grantSubmitting} onClick={submitGrant}>
              {grantSubmitting ? '保存中…' : '保存规则'}
            </button>
          </>
        }
      >
        <Field label="授权主体" hint={subject ? `${subject.name}(${subject.sub})` : undefined}>
          <span style={{ fontSize: 13 }}>{subject?.name ?? '—'}</span>
        </Field>
        <Field label="资源对象">
          <select className="select" style={{ width: '100%' }} value={formResourceId} onChange={(e) => setFormResourceId(e.target.value)}>
            <option value="">选择资源对象</option>
            {metadataObjects.map((o) => (
              <option key={o.id} value={o.id}>
                {o.objectName ?? o.tableName}({o.objectType})
              </option>
            ))}
          </select>
        </Field>
        <Alert kind="info">对象级规则不含下级资源。READ ALLOW 隐含 META；META DENY 阻断 READ，且 DENY 始终优先于同资源的 ALLOW。</Alert>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <Field label="权限动作">
            <select
              className="select"
              style={{ width: '100%' }}
              value={formAction}
              onChange={(e) => setFormAction(e.target.value as 'META' | 'READ')}
            >
              <option value="META">META</option>
              <option value="READ">READ</option>
            </select>
          </Field>
          <Field label="效果">
            <select
              className="select"
              style={{ width: '100%' }}
              value={formEffect}
              onChange={(e) => setFormEffect(e.target.value as 'ALLOW' | 'DENY')}
            >
              <option value="ALLOW">允许</option>
              <option value="DENY">拒绝</option>
            </select>
          </Field>
        </div>
        <Field label="有效期至（留空为永久）" hint="生效区间左闭右开，到期时间须晚于当前时间">
          <span className="input-wrap" style={{ width: '100%' }}>
            <ClockIcon className="input-icon" />
            <input
              className="input"
              style={{ width: '100%' }}
              type="datetime-local"
              value={formValidTo}
              min={validToMin}
              onChange={(e) => setFormValidTo(e.target.value)}
            />
          </span>
        </Field>
        <Field label="授权理由" hint="会写入规则与审计，建议记录业务依据">
          <input
            className="input"
            style={{ width: '100%' }}
            value={formReason}
            maxLength={500}
            onChange={(e) => setFormReason(e.target.value)}
            placeholder="可选，记录授权原因"
          />
        </Field>
        {grantFormError ? <Alert kind="danger">{grantFormError}</Alert> : null}
      </Dialog>
    </>
  );
}

const stampFmt = new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });

/** 非法或缺失的服务端时间显示为占位符，不伪造时点。 */
function fmtStamp(at: string | null | undefined): string {
  if (!at) return '—';
  const d = new Date(at);
  return Number.isNaN(d.getTime()) ? '—' : stampFmt.format(d);
}

/** 规则有效期左闭右开；无到期即永久。 */
function validityLabel(validTo: string | null): string {
  return validTo ? `至 ${fmtStamp(validTo)}` : '永久';
}

function findNode(nodes: ResourceNode[], key: string): ResourceNode | null {
  for (const n of nodes) {
    if (n.key === key) return n;
    const hit = n.children?.length ? findNode(n.children, key) : null;
    if (hit) return hit;
  }
  return null;
}

function Pane({
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
      <header style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '12px 14px 10px', flex: 'none' }}>
        {icon}
        <span className="cell-stack" style={{ flex: 1 }}>
          <strong style={{ fontSize: 14 }}>{title}</strong>
          {subtitle ? <span>{subtitle}</span> : null}
        </span>
        {headerExtra}
      </header>
      {children}
      {footer ? (
        <footer
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: 10,
            padding: '8px 14px',
            borderTop: '1px solid var(--ptmcp-border)',
            fontSize: 12,
            color: 'var(--ptmcp-text-muted)',
            flex: 'none',
          }}
        >
          {footer}
        </footer>
      ) : null}
    </section>
  );
}

function Resizer({
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
      <span style={{ width: 3, height: 36, borderRadius: 2, background: 'var(--ptmcp-border-strong)' }} />
    </div>
  );
}

function TreeBranch({
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
