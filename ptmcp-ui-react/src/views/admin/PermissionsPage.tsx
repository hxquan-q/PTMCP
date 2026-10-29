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
  TextInput,
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
import { createGrant, deleteGrant, listGrants, previewAccess, type Grant, type SubjectType } from '../../services/access';
import { listDatasources, listMetadata, type DatasourceRead, type MetadataObject } from '../../services/data';
import { buildTree, type ResourceNode } from './resourceTree';
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
  const [subjectType, setSubjectType] = useState<SubjectType>('USER');
  const [subjects, setSubjects] = useState<SubjectItem[]>([]);
  const [subjectId, setSubjectId] = useState('');
  const [subjectKeyword, setSubjectKeyword] = useState('');
  const [datasources, setDatasources] = useState<DatasourceRead[]>([]);
  const [datasourceId, setDatasourceId] = useState('');
  const [metadataObjects, setMetadataObjects] = useState<MetadataObject[]>([]);
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [expanded, setExpanded] = useState<Set<string>>(new Set());
  const [resourceKey, setResourceKey] = useState('');
  const [grants, setGrants] = useState<Grant[]>([]);
  const [grantsLoading, setGrantsLoading] = useState(false);
  const [grantPage, setGrantPage] = useState(1);
  const [focus, setFocus] = useState(false);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [platforms, setPlatforms] = useState<PlatformItem[]>([]);
  const [platformId, setPlatformId] = useState('');
  const [previewAt, setPreviewAt] = useState('');
  const [previewResult, setPreviewResult] = useState<{ resourceId: string; meta: boolean; read: boolean; reasonCodes?: string[] }[] | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState('');
  const [colWidths, setColWidths] = useState<[number, number]>([25, 25]); // 前两个栏 %,第三栏取余
  const [grantDialogOpen, setGrantDialogOpen] = useState(false);
  const [formResourceId, setFormResourceId] = useState('');
  const [formAction, setFormAction] = useState<'META' | 'READ'>('READ');
  const [formEffect, setFormEffect] = useState<'ALLOW' | 'DENY'>('ALLOW');
  const [formInherit, setFormInherit] = useState(false);
  const [formValidTo, setFormValidTo] = useState('');
  const [formReason, setFormReason] = useState('');
  const [grantSubmitting, setGrantSubmitting] = useState(false);
  const [grantFormError, setGrantFormError] = useState('');
  const [revokingId, setRevokingId] = useState('');
  const debouncedSubject = useDebounced(subjectKeyword, 150);

  /* 主体加载 */
  useEffect(() => {
    let cancelled = false;
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
      .catch(() => !cancelled && setSubjects([]));
    return () => {
      cancelled = true;
    };
  }, [subjectType]);

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
      return;
    }
    let cancelled = false;
    setMetadataLoading(true);
    listMetadata({ datasourceId, pageSize: 200 })
      .then(({ data }) => {
        if (cancelled) return;
        setMetadataObjects(data.items);
        // 默认展开第一个 schema
        const first = data.items[0];
        if (first) setExpanded(new Set([`schema:${first.schemaName ?? first.databaseName ?? 'default'}`]));
        setResourceKey('');
      })
      .catch(() => !cancelled && setMetadataObjects([]))
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
      .then((rows) => setGrants(rows))
      .catch(() => setGrants([]));
  }, [subjectType, subjectId]);

  useEffect(() => {
    let cancelled = false;
    setGrantsLoading(true);
    if (!subjectId) {
      setGrants([]);
      setGrantsLoading(false);
      return;
    }
    listGrants(subjectType, subjectId)
      .then((rows) => !cancelled && setGrants(rows))
      .catch(() => !cancelled && setGrants([]))
      .finally(() => !cancelled && setGrantsLoading(false));
    return () => {
      cancelled = true;
    };
  }, [subjectType, subjectId]);

  const tree = useMemo<ResourceNode[]>(() => buildTree(metadataObjects), [metadataObjects]);
  const selectedResource = useMemo(() => findNode(tree, resourceKey), [tree, resourceKey]);

  /* 新增规则 */
  const openGrantDialog = useCallback(() => {
    setFormResourceId(selectedResource?.kind === 'OBJECT' ? (selectedResource.resourceId ?? '') : '');
    setFormAction('READ');
    setFormEffect('ALLOW');
    setFormInherit(false);
    setFormValidTo('');
    setFormReason('');
    setGrantFormError('');
    setGrantDialogOpen(true);
  }, [selectedResource]);

  const submitGrant = useCallback(() => {
    if (!subjectId || !formResourceId || grantSubmitting) return;
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
      includeDescendants: formInherit,
      validFrom: new Date().toISOString(),
      validTo: formValidTo ? new Date(formValidTo).toISOString() : null,
      reason: formReason.trim(),
    })
      .then(() => {
        setGrantDialogOpen(false);
        return refreshGrants();
      })
      .catch((e: unknown) => setGrantFormError(e instanceof Error ? e.message : '保存失败'))
      .finally(() => setGrantSubmitting(false));
  }, [
    subjectId,
    subjectType,
    formResourceId,
    formAction,
    formEffect,
    formInherit,
    formValidTo,
    formReason,
    metadataObjects,
    refreshGrants,
    grantSubmitting,
  ]);

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

  /* 预览 resourceIds — 取当前树中所有 OBJECT 节点 */
  const allObjectIds = useMemo(() => tree.flatMap((schema) => (schema.children ?? []).map((c) => c.resourceId).filter(Boolean) as string[]), [tree]);

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

  const toggleNode = (key: string) =>
    setExpanded((s) => {
      const next = new Set(s);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const gridCols = focus ? '1fr' : `${colWidths[0]}% 6px ${colWidths[1]}% 6px 1fr`;

  return (
    <>
      <PageHeader
        title="权限矩阵"
        description="选择主体与资源，管理规则并计算平台范围内的有效权限。"
        actions={
          <button className="btn btn--primary" disabled={!subjectId} onClick={openGrantDialog}>
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
              {filteredSubjects.map((s) => (
                <button
                  key={s.id}
                  onClick={() => {
                    setSubjectId(s.id);
                    setGrantPage(1);
                  }}
                  aria-pressed={subjectId === s.id}
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    width: '100%',
                    padding: '9px 10px',
                    margin: '2px 0',
                    border: `1px solid ${subjectId === s.id ? 'var(--ptmcp-blue)' : 'transparent'}`,
                    borderRadius: 'var(--ptmcp-radius-control)',
                    background: subjectId === s.id ? 'var(--ptmcp-selected)' : 'none',
                    textAlign: 'left',
                  }}
                >
                  <CellIcon icon={subjectType === 'USER' ? UserIcon : subjectType === 'ROLE' ? TagIcon : UsersIcon} />
                  <span className="cell-stack">
                    <strong>{s.name}</strong>
                    <span>{s.sub}</span>
                  </span>
                </button>
              ))}
              {filteredSubjects.length === 0 ? <EmptyState title="没有匹配的主体" /> : null}
            </div>
          </Pane>
        ) : null}
        {!focus ? <Resizer onPointerDown={onResizerDown(0)} /> : null}
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
            <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 8px 8px' }}>
              {metadataLoading ? (
                <p style={{ padding: '12px 14px', fontSize: 13, color: 'var(--ptmcp-text-faint)' }}>加载元数据…</p>
              ) : tree.length === 0 ? (
                <p style={{ padding: '12px 14px', fontSize: 13, color: 'var(--ptmcp-text-faint)' }}>先选择数据源以读取当前资源。</p>
              ) : (
                tree.map((node) => (
                  <TreeBranch
                    key={node.key}
                    node={node}
                    depth={0}
                    expanded={expanded}
                    selected={resourceKey}
                    onToggle={toggleNode}
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
        {!focus ? <Resizer onPointerDown={onResizerDown(1)} /> : null}
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
            ) : grantPageRows.length === 0 ? (
              <EmptyState title="暂无授权规则" description="该主体在当前资源范围内还没有直接规则。" />
            ) : (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>资源名称</th>
                    <th>类型</th>
                    <th>权限</th>
                    <th>效果</th>
                    <th>含下级</th>
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
                          <StatusDot tone={g.effect === 'ALLOW' ? 'success' : 'danger'}>{g.effect}</StatusDot>
                        </td>
                        <td style={{ color: 'var(--ptmcp-text-muted)' }}>{g.includeDescendants ? '是' : '否'}</td>
                        <td>
                          <StatusDot tone={g.status === 'ACTIVE' ? 'success' : 'neutral'}>{g.status}</StatusDot>
                        </td>
                        <td>
                          {g.status === 'ACTIVE' ? (
                            <button className="link" disabled={revokingId === g.id} onClick={() => revokeGrant(g.id)}>
                              {revokingId === g.id ? '撤销中…' : '撤销'}
                            </button>
                          ) : (
                            <span style={{ color: 'var(--ptmcp-text-faint)', fontSize: 12 }}>已撤销</span>
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
          <div style={{ borderTop: '1px solid var(--ptmcp-border)', padding: '14px 16px' }}>
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
                disabled={!subjectId || !platformId || !allObjectIds.length || previewLoading}
                onClick={() => {
                  setPreviewLoading(true);
                  setPreviewError('');
                  setPreviewResult(null);
                  previewAccess({
                    subjectType,
                    subjectId,
                    platformId,
                    resourceIds: allObjectIds,
                    ...(previewAt ? { at: new Date(previewAt).toISOString() } : {}),
                  })
                    .then((res) => setPreviewResult(res.decisions))
                    .catch(() => setPreviewError('预览计算失败，请确认主体与平台已选择。'))
                    .finally(() => setPreviewLoading(false));
                }}
              >
                <BarChartIcon /> {previewLoading ? '计算中…' : '计算有效权限'}
              </button>
            </div>
            {previewError ? (
              <div style={{ marginTop: 12 }}>
                <Alert kind="danger" title="计算失败">
                  {previewError}
                </Alert>
              </div>
            ) : null}
            {previewResult ? (
              <div style={{ marginTop: 12, overflowX: 'auto' }}>
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
                    {previewResult.map((d) => {
                      const obj = metadataObjects.find((o) => o.id === d.resourceId);
                      return (
                        <tr key={d.resourceId}>
                          <td style={{ fontWeight: 500 }}>{obj?.objectName ?? obj?.tableName ?? d.resourceId}</td>
                          <td style={{ textAlign: 'center' }}>
                            <StatusDot tone={d.meta ? 'success' : 'danger'}>{d.meta ? '允许' : '拒绝'}</StatusDot>
                          </td>
                          <td style={{ textAlign: 'center' }}>
                            <StatusDot tone={d.read ? 'success' : 'danger'}>{d.read ? '允许' : '拒绝'}</StatusDot>
                          </td>
                          <td style={{ fontSize: 12, color: 'var(--ptmcp-text-muted)' }}>{(d.reasonCodes ?? []).join(', ')}</td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
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
        <label style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, cursor: 'pointer' }}>
          <input type="checkbox" checked={formInherit} onChange={(e) => setFormInherit(e.target.checked)} />
          包含下级对象
        </label>
        <Field label="有效期至(留空为永久)">
          <TextInput type="datetime-local" value={formValidTo} onChange={setFormValidTo} />
        </Field>
        <Field label="授权理由">
          <TextInput value={formReason} onChange={setFormReason} placeholder="可选,记录授权原因" />
        </Field>
        {grantFormError ? <Alert kind="danger">{grantFormError}</Alert> : null}
      </Dialog>
    </>
  );
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

function Resizer({ onPointerDown }: { onPointerDown: (e: React.PointerEvent) => void }) {
  return (
    <div
      role="separator"
      aria-orientation="vertical"
      className="perm-resizer"
      onPointerDown={onPointerDown}
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
  onToggle,
  onSelect,
}: {
  node: ResourceNode;
  depth: number;
  expanded: Set<string>;
  selected: string;
  onToggle: (k: string) => void;
  onSelect: (k: string) => void;
}) {
  const open = expanded.has(node.key);
  const isSel = selected === node.key;
  return (
    <div>
      <div
        role="treeitem"
        aria-expanded={node.children ? open : undefined}
        aria-selected={isSel}
        onClick={() => {
          if (node.children) onToggle(node.key);
          onSelect(node.key);
        }}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 6,
          padding: `6px 8px 6px ${8 + depth * 18}px`,
          borderRadius: 6,
          cursor: 'pointer',
          fontSize: 13,
          background: isSel ? 'var(--ptmcp-selected)' : 'none',
          color: isSel ? 'var(--ptmcp-blue)' : 'var(--ptmcp-text)',
          fontWeight: isSel ? 600 : 500,
        }}
      >
        <ChevronRightIcon
          style={{
            fontSize: 12,
            color: 'var(--ptmcp-text-faint)',
            transform: open ? 'rotate(90deg)' : undefined,
            visibility: node.children ? 'visible' : 'hidden',
            transition: 'transform .12s',
          }}
        />
        {node.kind === 'OBJECT' ? (
          <DocumentIcon style={{ color: 'var(--ptmcp-text-faint)', fontSize: 14 }} />
        ) : (
          <DatabaseIcon style={{ color: 'var(--ptmcp-text-faint)', fontSize: 14 }} />
        )}
        <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{node.label}</span>
        {node.sub ? <Badge tone="neutral">{node.sub}</Badge> : null}
      </div>
      {open && node.children
        ? node.children.map((c) => (
            <TreeBranch key={c.key} node={c} depth={depth + 1} expanded={expanded} selected={selected} onToggle={onToggle} onSelect={onSelect} />
          ))
        : null}
    </div>
  );
}
