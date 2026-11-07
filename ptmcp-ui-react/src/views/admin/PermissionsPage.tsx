import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Button } from 'antd';
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
  Tabs,
  TextInput,
  useDebounced,
} from '../../components/common';
import {
  BarChartIcon,
  ChevronRightIcon,
  DatabaseIcon,
  ExpandIcon,
  ListIcon,
  RefreshIcon,
  TagIcon,
  UserIcon,
  UsersIcon,
} from '../../components/Icons';
import { createGrant, deleteGrant, listGrants, previewAccess, type Grant, type Preview, type SubjectType } from '../../services/access';
import { encodeObjectIds } from '../../services/resourceIds';
import { listDatasources, type DatasourceRead, type MetadataObject } from '../../services/data';
import { buildTree, type ResourceNode } from './resourceTree';
import { useAdminGate } from '../../adminGateContext';
import { resizeColumns } from './columnResize';
import { mePortalApi, type PlatformItem } from '../../services/mePortalApi';
import { listDepartments, listRoles, listUsers, type DepartmentRow, type RoleRow, type UserRow } from '../../services/orgPortalApi';
import { findNode, fmtStamp, Pane, Resizer, SkeletonRows, TreeBranch } from './permissions/parts';
import { GrantsTable, PreviewTable } from './permissions/tables';
import { SUBJECT_TABS, type SubjectItem } from './permissions/shared';
import { listAllMetadata } from './permissions/metadataLoader';

/* ─── 资源树 ─── */

export function PermissionsPage() {
  const isAdmin = useAdminGate();
  const [subjectType, setSubjectType] = useState<SubjectType>('ROLE');
  const [subjects, setSubjects] = useState<SubjectItem[]>([]);
  const [subjectId, setSubjectId] = useState('');
  const [subjectKeyword, setSubjectKeyword] = useState('');
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  const [datasourceId, setDatasourceId] = useState('');
  const [metadataObjects, setMetadataObjects] = useState<MetadataObject[]>([]);
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [metadataError, setMetadataError] = useState('');
  const [subjectsError, setSubjectsError] = useState('');
  const [subjectsLoading, setSubjectsLoading] = useState(true);
  const [subjectsTick, setSubjectsTick] = useState(0);
  const [grantsError, setGrantsError] = useState('');
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [resourceKey, setResourceKey] = useState('');
  const [treeActiveKey, setTreeActiveKey] = useState('');
  const [grants, setGrants] = useState<Grant[]>([]);
  const [grantsLoading, setGrantsLoading] = useState(false);
  const [grantPage, setGrantPage] = useState(1);
  const [focus, setFocus] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(true);
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
    setSubjectsLoading(true);
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
        setSubjectsLoading(false);
      })
      .catch(() => {
        if (cancelled) return;
        // 加载失败不能伪装成“没有主体”：清空选择，避免对未知主体继续读写规则。
        setSubjects([]);
        setSubjectId('');
        setSubjectsError('主体目录加载失败，当前无法确认已有的授权对象。');
        setSubjectsLoading(false);
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
    const controller = new AbortController();
    setMetadataLoading(true);
    setMetadataError('');
    listAllMetadata(datasourceId, controller.signal)
      .then((items) => {
        if (cancelled) return;
        setMetadataObjects(items);
        // 默认展开第一个 schema，并让树里始终有一个可 Tab 进入的行
        const first = items[0];
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
      controller.abort();
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

  /* 拖拽分栏：dragIdx 让被拖分隔条常显强调色，并抑制工作区文本选区 */
  const containerRef = useRef<HTMLDivElement>(null);
  const dragState = useRef<{ index: 0 | 1; startX: number; start: [number, number] } | null>(null);
  const [dragIdx, setDragIdx] = useState<0 | 1 | null>(null);
  const onResizerDown = useCallback(
    (index: 0 | 1) => (e: React.PointerEvent) => {
      dragState.current = { index, startX: e.clientX, start: [...colWidths] as [number, number] };
      setDragIdx(index);
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
    setDragIdx(null);
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

  const gridCols = focus ? '1fr' : `${colWidths[0]}% 14px ${colWidths[1]}% 14px 1fr`;
  const previewIsFuture = !!previewRequestedAt && new Date(previewRequestedAt).getTime() > Date.now();
  const previewReadCount = previewResult?.decisions.filter((d) => d.read).length ?? 0;
  const previewMetaOnlyCount = previewResult?.decisions.filter((d) => d.meta && !d.read).length ?? 0;
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
          <Button
            type="primary"
            disabled={!subjectId || !!metadataError || !isAdmin}
            title={metadataError ? '资源树读取失败，重新读取成功后才能提交规则' : undefined}
            onClick={openGrantDialog}
          >
            + 新增规则
          </Button>
        }
      />
      <div
        ref={containerRef}
        className="perm-workspace"
        data-dragging={dragIdx !== null ? 'true' : undefined}
        onPointerMove={onResizerMove}
        onPointerUp={onResizerUp}
        style={{ display: 'grid', gridTemplateColumns: gridCols, gap: 0, flex: 1, minHeight: 360 }}
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
            <div key={subjectType} className="pane-swap" style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 8px 8px' }}>
              {subjectsLoading ? (
                <SkeletonRows variant="subjects" rows={4} />
              ) : subjectsError ? (
                <div style={{ padding: '8px 6px' }}>
                  <Alert kind="danger" title="主体目录读取失败">
                    {subjectsError}
                    <div style={{ marginTop: 8 }}>
                      <Button onClick={() => setSubjectsTick((t) => t + 1)}>
                        <RefreshIcon /> 重新读取
                      </Button>
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
              {!subjectsLoading && !subjectsError && filteredSubjects.length === 0 ? (
                <EmptyState kind="filtered" title="没有匹配的主体" description="清除搜索词，或到用户、角色、部门页面确认目录内容。" />
              ) : null}
            </div>
          </Pane>
        ) : null}
        {!focus ? (
          <Resizer
            onPointerDown={onResizerDown(0)}
            onNudge={nudgeColumn(0)}
            value={colWidths[0]}
            active={dragIdx === 0}
            label="调整“授权主体”与“资源范围”栏宽度"
          />
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
                ariaLabel="数据源"
                value={datasourceId}
                onChange={setDatasourceId}
                width="100%"
                options={datasources.map((d) => ({ value: d.id, label: `${d.name} (${d.engine})` }))}
              />
            </div>
            <div
              key={datasourceId}
              className="pane-swap"
              role="tree"
              aria-label="资源对象树"
              aria-busy={metadataLoading}
              onKeyDown={onTreeKeyDown}
              style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 8px 8px' }}
            >
              {metadataLoading ? (
                <SkeletonRows variant="tree" rows={5} />
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
          <Resizer
            onPointerDown={onResizerDown(1)}
            onNudge={nudgeColumn(1)}
            value={colWidths[1]}
            active={dragIdx === 1}
            label="调整“资源范围”与“授权规则”栏宽度"
          />
        ) : null}
        {/* ─── 栏 3:授权规则 ─── */}
        <Pane
          icon={<CellIcon icon={ListIcon} />}
          title="授权规则"
          subtitle={subject ? `${subject.name} 的直接规则` : '选择左侧主体查看规则'}
          headerExtra={
            <span style={{ display: 'inline-flex', gap: 8 }}>
              <Button style={{ padding: '5px 10px' }} onClick={() => void refreshGrants()}>
                <RefreshIcon /> 刷新
              </Button>
              <Button
                style={{ padding: '5px 10px' }}
                title={focus ? '退出专注模式' : '专注模式:规则表占满工作区'}
                aria-pressed={focus}
                onClick={() => setFocus((f) => !f)}
              >
                <ExpandIcon />
              </Button>
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
          <div key={`${subjectType}:${subjectId}`} className="pane-swap" style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
            {!subjectId ? (
              /* antd Empty 的 children 区是 text-align:center，说明文字左对齐的 Alert 不能塞进 action，独立成行。 */
              <div style={{ padding: '16px 0' }}>
                <EmptyState title="尚未选择授权主体" description="先在左侧选择主体，再读取直接规则。" />
                <div style={{ width: '100%', maxWidth: 640, margin: '0 auto', textAlign: 'left' }}>
                  <Alert kind="info">
                    规则列表展示直接配置；有效权限还受平台范围、继承、DENY 和有效期约束。READ ALLOW 隐含 META，META DENY 阻断 READ。
                  </Alert>
                </div>
              </div>
            ) : grantsLoading ? (
              <div style={{ padding: '4px 16px 12px' }}>
                <SkeletonRows variant="table" rows={4} />
              </div>
            ) : grantsError ? (
              <div style={{ padding: 16 }}>
                <Alert kind="danger" title="规则读取失败">
                  {grantsError}
                  <div style={{ marginTop: 8 }}>
                    <Button onClick={() => void refreshGrants()}>
                      <RefreshIcon /> 重新读取
                    </Button>
                  </div>
                </Alert>
              </div>
            ) : grantPageRows.length === 0 ? (
              <EmptyState
                title="暂无授权规则"
                description={selectedResource ? `该主体在“${selectedResource.label}”下还没有直接规则。` : '该主体还没有直接规则。'}
              />
            ) : (
              <GrantsTable rows={grantPageRows} metadataObjects={metadataObjects} revokingId={revokingId} onRevoke={revokeGrant} />
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
            style={{
              transform: previewOpen ? 'rotate(90deg)' : undefined,
              transition: 'transform var(--ptmcp-dur-fast) ease',
              color: 'var(--ptmcp-text-faint)',
            }}
          />
        </button>
        <div className="perm-preview-body" data-open={previewOpen ? 'true' : 'false'}>
          <div
            id="perm-preview-panel"
            role="region"
            aria-label="有效权限预览"
            className="perm-preview-inner"
            inert={!previewOpen}
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
                <TextInput type="datetime-local" value={previewAt} onChange={setPreviewAt} width={180} />
              </label>
              <Button
                type="primary"
                loading={previewLoading}
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
                <BarChartIcon /> 计算有效权限
              </Button>
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
              /* 以计算时点为 key：每次重新计算让结论整组重新揭示一次 */
              <div key={`${previewResult.evaluatedAt}:${previewRequestedAt}`} className="perm-preview-result">
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
                  <span>
                    共 {previewResult.decisions.length} 项：READ 可读 {previewReadCount} · 仅 META {previewMetaOnlyCount} · 拒绝{' '}
                    {previewResult.decisions.length - previewReadCount - previewMetaOnlyCount}
                  </span>
                  {previewIsFuture ? <Badge tone="warning">未来时点模拟，不可据此执行查询</Badge> : null}
                </p>
                <div style={{ marginTop: 8 }}>
                  <PreviewTable decisions={previewResult.decisions} metadataObjects={metadataObjects} />
                </div>
              </div>
            ) : null}
          </div>
        </div>
      </div>
      <Dialog
        open={grantDialogOpen}
        title="新增授权规则"
        onClose={() => !grantSubmitting && setGrantDialogOpen(false)}
        footer={
          <>
            <Button disabled={grantSubmitting} onClick={() => setGrantDialogOpen(false)}>
              取消
            </Button>
            <Button type="primary" disabled={!formResourceId || grantSubmitting} onClick={submitGrant}>
              {grantSubmitting ? '保存中…' : '保存规则'}
            </Button>
          </>
        }
      >
        <Field label="授权主体" hint={subject ? `${subject.name}(${subject.sub})` : undefined}>
          <span style={{ fontSize: 13 }}>{subject?.name ?? '—'}</span>
        </Field>
        <Field label="资源对象">
          <Select
            ariaLabel="资源对象"
            width="100%"
            showSearch
            value={formResourceId}
            onChange={setFormResourceId}
            options={[
              { value: '', label: '选择资源对象' },
              ...metadataObjects.map((o) => ({ value: o.id, label: `${o.objectName ?? o.tableName}(${o.objectType})` })),
            ]}
          />
        </Field>
        <Alert kind="info">对象级规则不含下级资源。READ ALLOW 隐含 META；META DENY 阻断 READ，且 DENY 始终优先于同资源的 ALLOW。</Alert>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <Field label="权限动作">
            <Select
              ariaLabel="权限动作"
              width="100%"
              value={formAction}
              onChange={(v) => setFormAction(v as 'META' | 'READ')}
              options={[
                { value: 'META', label: 'META' },
                { value: 'READ', label: 'READ' },
              ]}
            />
          </Field>
          <Field label="效果">
            <Select
              ariaLabel="效果"
              width="100%"
              value={formEffect}
              onChange={(v) => setFormEffect(v as 'ALLOW' | 'DENY')}
              options={[
                { value: 'ALLOW', label: '允许' },
                { value: 'DENY', label: '拒绝' },
              ]}
            />
          </Field>
        </div>
        <Field label="有效期至（留空为永久）" hint="生效区间左闭右开，到期时间须晚于当前时间">
          <TextInput type="datetime-local" value={formValidTo} min={validToMin} onChange={setFormValidTo} />
        </Field>
        <Field label="授权理由" hint="会写入规则与审计，建议记录业务依据">
          <TextInput value={formReason} maxLength={500} onChange={setFormReason} placeholder="可选，记录授权原因" />
        </Field>
        {grantFormError ? <Alert kind="danger">{grantFormError}</Alert> : null}
      </Dialog>
    </>
  );
}

/** 非法或缺失的服务端时间显示为占位符，不伪造时点。 */
