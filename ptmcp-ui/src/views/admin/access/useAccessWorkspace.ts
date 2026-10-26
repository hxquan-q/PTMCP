import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useSessionStore } from '../../../stores/session';
import { ApiError, errorMessage, idempotencyKey } from '../../../services/http';
import {
  getGrant,
  listGrants,
  previewAccess,
  revokeGrantApi,
  saveGrant,
  type Grant,
  type GrantWrite,
  type Preview,
  type ResourceType,
  type SubjectType
} from '../../../services/access';
import { getDatasource, getMetadata, listDatasources, listMetadata, type DatasourceRead, type MetadataItem } from '../../../services/data';
import { orgPortalApi } from '../../../services/orgPortalApi';
import { mePortalApi, type PlatformItem } from '../../../services/mePortalApi';
import { encodePreviewResource, validGrantWindow } from '../../../adapters/access-resource';

export interface ResourceSelection {
  key: string;
  id: string;
  label: string;
  type: ResourceType | 'GROUP';
  generation: string | null;
  children?: ResourceSelection[];
}
interface SubjectOption {
  id: string;
  label: string;
}

export function useAccessWorkspace() {
  const session = useSessionStore();
  const canList = computed(() => session.can('ptmcp:acl:list'));
  const canRead = computed(() => session.can('ptmcp:acl:read'));
  const canCreate = computed(() => session.can('ptmcp:acl:create'));
  const canUpdate = computed(() => session.can('ptmcp:acl:update'));
  const canDelete = computed(() => session.can('ptmcp:acl:delete'));
  const canPreview = computed(() => session.can('ptmcp:acl:preview'));
  const subjectType = ref<SubjectType>('USER');
  const subjectId = ref('');
  const subjectOptions = reactive<Record<SubjectType, SubjectOption[]>>({ USER: [], ROLE: [], DEPT: [] });
  const lookupError = ref('');
  const grants = ref<Grant[]>([]);
  const loading = ref(false);
  const pageError = ref('');
  const sources = ref<DatasourceRead[]>([]);
  const sourceCursor = ref<string | null>(null);
  const sourceId = ref('');
  const resourceItems = ref<MetadataItem[]>([]);
  const resourceCursor = ref<string | null>(null);
  const resourceLoading = ref(false);
  const resourceError = ref('');
  const selectedResource = ref<ResourceSelection | null>(null);
  const platforms = ref<PlatformItem[]>([]);
  const platformCursor = ref<string | null>(null);
  const platformId = ref('');
  const previewUserId = ref('');
  const previewAt = ref<Date | null>(null);
  const preview = ref<Preview | null>(null);
  const previewError = ref('');
  const previewLoading = ref(false);
  const dialogVisible = ref(false);
  const editing = ref<Grant | null>(null);
  const latestConflict = ref<Grant | null>(null);
  const saving = ref(false);
  const formError = ref('');
  const unknownOutcome = ref(false);
  const draft = reactive({
    action: 'META' as 'META' | 'READ',
    effect: 'ALLOW' as 'ALLOW' | 'DENY',
    includeDescendants: false,
    from: new Date() as Date | null,
    to: null as Date | null,
    reason: ''
  });
  const draftTarget = ref<ResourceSelection | null>(null);
  const pendingDelete = ref('');
  let intentKey = idempotencyKey();
  let active = true;
  let grantRequest: AbortController | null = null;
  let resourceRequest: AbortController | null = null;
  let previewRequest: AbortController | null = null;
  let grantGeneration = 0;
  let resourceGeneration = 0;
  let previewGeneration = 0;
  let editGeneration = 0;
  let subjectGeneration = 0;
  let boundaryTimer: ReturnType<typeof setTimeout> | null = null;

  const subjectName = computed(() => subjectOptions[subjectType.value].find((item) => item.id === subjectId.value)?.label ?? subjectId.value);
  const selectedSource = computed(() => sources.value.find((item) => item.id === sourceId.value));
  const resourceTree = computed<ResourceSelection[]>(() => {
    const source = selectedSource.value;
    if (!source) return [];
    const groups = new Map<string, ResourceSelection>();
    for (const item of resourceItems.value) {
      const key = String(item.namespaceId);
      if (!groups.has(key))
        groups.set(key, {
          key: `NAMESPACE:${key}`,
          id: key,
          label: [item.databaseName, item.schemaName].filter(Boolean).join(' / '),
          type: 'NAMESPACE',
          generation: null,
          children: []
        });
      groups.get(key)?.children?.push({
        key: `OBJECT:${item.id}`,
        id: String(item.id),
        label: item.objectName,
        type: 'OBJECT',
        generation: String(item.generation)
      });
    }
    // Datasource identities are never reused; the current grant protocol uses generation 1.
    const namespaces = [...groups.values()];
    const children: ResourceSelection[] = [];
    if (source.engine === 'POSTGRESQL') {
      const catalogs = new Map<string, ResourceSelection>();
      for (const item of resourceItems.value) {
        if (!catalogs.has(item.databaseName))
          catalogs.set(item.databaseName, {
            key: `GROUP:${item.databaseName}`,
            id: '',
            label: item.databaseName,
            type: 'GROUP',
            generation: null,
            children: []
          });
        const namespace = groups.get(String(item.namespaceId));
        const catalog = catalogs.get(item.databaseName);
        if (namespace && catalog && !catalog.children?.some((node) => node.key === namespace.key)) catalog.children?.push(namespace);
      }
      children.push(...catalogs.values());
    } else children.push(...namespaces);
    return [{ key: `DATASOURCE:${source.id}`, id: String(source.id), label: source.name, type: 'DATASOURCE', generation: '1', children }];
  });
  const validDraft = computed(() => !!draft.reason.trim() && draft.reason.length <= 500 && validGrantWindow(draft.from, draft.to));
  const changed = computed(
    () =>
      !editing.value ||
      draft.action !== editing.value.action ||
      draft.effect !== editing.value.effect ||
      draft.includeDescendants !== editing.value.includeDescendants ||
      draft.from?.getTime() !== new Date(editing.value.validFrom).getTime() ||
      (draft.to?.getTime() ?? null) !== (editing.value.validTo ? new Date(editing.value.validTo).getTime() : null) ||
      draft.reason !== editing.value.reason
  );

  function clearPreview() {
    previewGeneration += 1;
    previewRequest?.abort();
    if (boundaryTimer) clearTimeout(boundaryTimer);
    boundaryTimer = null;
    preview.value = null;
    previewError.value = '';
    previewLoading.value = false;
  }
  async function loadGrants() {
    clearPreview();
    grantRequest?.abort();
    const generation = ++grantGeneration;
    grants.value = [];
    pageError.value = '';
    if (!active || !canList.value || !subjectId.value) {
      loading.value = false;
      return;
    }
    grantRequest = new AbortController();
    loading.value = true;
    try {
      const data = await listGrants(subjectType.value, subjectId.value, grantRequest.signal);
      if (active && generation === grantGeneration) {
        grants.value = data;
        if (!dialogVisible.value) unknownOutcome.value = false;
      }
    } catch (error) {
      if (active && generation === grantGeneration && !(error instanceof DOMException && error.name === 'AbortError'))
        pageError.value = errorMessage(error);
    } finally {
      if (generation === grantGeneration) loading.value = false;
    }
  }
  async function loadSources(append = false) {
    if (!session.can('ptmcp:datasource:list')) {
      resourceError.value = '缺少数据源列表权限，无法安全选择资源。';
      return;
    }
    try {
      const result = await listDatasources({ pageSize: 100, cursor: append ? sourceCursor.value : null });
      if (!active) return;
      const items = result.data.items.map((item) => ({ ...item, id: String(item.id) }));
      sources.value = append ? [...sources.value, ...items] : items;
      sourceCursor.value = result.data.nextCursor;
    } catch (error) {
      if (active) resourceError.value = errorMessage(error);
    }
  }
  async function loadResources(append = false) {
    resourceRequest?.abort();
    const generation = ++resourceGeneration;
    if (!append) {
      resourceItems.value = [];
      resourceCursor.value = null;
      selectedResource.value = null;
    }
    resourceError.value = '';
    if (!sourceId.value) {
      resourceLoading.value = false;
      return;
    }
    if (!session.can('ptmcp:metadata:list')) {
      resourceError.value = '缺少元数据列表权限；对象资源不可选。';
      return;
    }
    resourceRequest = new AbortController();
    resourceLoading.value = true;
    try {
      const result = await listMetadata({
        datasourceId: sourceId.value,
        pageSize: 100,
        cursor: append ? resourceCursor.value : null,
        signal: resourceRequest.signal
      });
      if (!active || generation !== resourceGeneration) return;
      resourceItems.value = append ? [...resourceItems.value, ...result.data.items] : result.data.items;
      resourceCursor.value = result.data.nextCursor;
    } catch (error) {
      if (active && generation === resourceGeneration && !(error instanceof DOMException && error.name === 'AbortError'))
        resourceError.value = errorMessage(error);
    } finally {
      if (generation === resourceGeneration) resourceLoading.value = false;
    }
  }
  async function loadPlatforms(append = false) {
    try {
      const result = await mePortalApi.accessContext.platforms(append ? (platformCursor.value ?? undefined) : undefined);
      if (!active) return;
      platforms.value = append ? [...platforms.value, ...result.items] : result.items;
      platformCursor.value = result.nextCursor;
    } catch (error) {
      if (active) previewError.value = `可见平台读取失败：${errorMessage(error)}`;
    }
  }
  function selectResource(node: ResourceSelection) {
    editGeneration += 1;
    if (dialogVisible.value) {
      dialogVisible.value = false;
      editing.value = null;
      draftTarget.value = null;
    }
    selectedResource.value = node;
    clearPreview();
  }
  function openCreate() {
    if (!active || saving.value || !canCreate.value || !subjectId.value || !selectedResource.value?.generation || resourceError.value) return;
    if (selectedSource.value?.status !== 'ACTIVE') {
      ElMessage.warning('仅可向启用的数据源添加规则');
      return;
    }
    editGeneration += 1;
    editing.value = null;
    latestConflict.value = null;
    draftTarget.value = selectedResource.value;
    Object.assign(draft, { action: 'META', effect: 'ALLOW', includeDescendants: false, from: new Date(), to: null, reason: '' });
    intentKey = idempotencyKey();
    formError.value = '';
    unknownOutcome.value = false;
    dialogVisible.value = true;
  }
  async function openEdit(row: Grant) {
    if (!active || saving.value || !canUpdate.value || !canRead.value || row.status !== 'ACTIVE') return;
    const generation = ++editGeneration;
    const context = `${subjectType.value}:${subjectId.value}`;
    pageError.value = '';
    try {
      const current = await getGrant(row.id);
      if (!active || generation !== editGeneration || context !== `${subjectType.value}:${subjectId.value}`) return;
      if (current.status !== 'ACTIVE') {
        dialogVisible.value = false;
        await loadGrants();
        if (active && context === `${subjectType.value}:${subjectId.value}`) pageError.value = '这条规则已撤销，不可编辑，已刷新规则列表。';
        return;
      }
      if (current.resourceType === 'NAMESPACE') throw new Error('当前接口没有返回命名空间的实时 generation，暂不能安全编辑此规则。');
      if (current.resourceType === 'OBJECT') {
        if (!session.can('ptmcp:metadata:read')) throw new Error('需要元数据读取权限以核对资源当前 generation。');
        const result = await getMetadata(current.resourceId);
        if (String(result.data.generation) !== current.resourceGeneration)
          throw new Error('对象已重建，此规则引用旧资源。请撤销后重新选择当前对象。');
      }
      if (current.resourceType === 'DATASOURCE') {
        if (!session.can('ptmcp:datasource:read')) throw new Error('需要数据源读取权限以核对资源身份。');
        await getDatasource(current.resourceId);
        if (current.resourceGeneration !== '1') throw new Error('规则引用的资源版本不匹配，请撤销后重新选择。');
      }
      if (!active || generation !== editGeneration || context !== `${subjectType.value}:${subjectId.value}`) return;
      editing.value = current;
      latestConflict.value = null;
      draftTarget.value = {
        key: `${current.resourceType}:${current.resourceId}`,
        id: current.resourceId,
        label: resourceItems.value.find((item) => item.id === current.resourceId)?.objectName ?? current.resourceId,
        type: current.resourceType,
        generation: current.resourceGeneration
      };
      Object.assign(draft, {
        action: current.action,
        effect: current.effect,
        includeDescendants: current.includeDescendants,
        from: new Date(current.validFrom),
        to: current.validTo ? new Date(current.validTo) : null,
        reason: current.reason
      });
      intentKey = idempotencyKey();
      formError.value = '';
      unknownOutcome.value = false;
      dialogVisible.value = true;
    } catch (error) {
      if (active && generation === editGeneration && context === `${subjectType.value}:${subjectId.value}`)
        pageError.value = error instanceof Error ? error.message : errorMessage(error);
    }
  }
  async function save() {
    if (
      !active ||
      saving.value ||
      !dialogVisible.value ||
      !!latestConflict.value ||
      unknownOutcome.value ||
      !validDraft.value ||
      !changed.value ||
      !draftTarget.value ||
      !subjectId.value ||
      !(editing.value ? canUpdate.value : canCreate.value)
    )
      return;
    const target = draftTarget.value;
    if (target.type === 'GROUP' || !target.generation || !draft.from) return;
    const operationGeneration = editGeneration;
    const operationSubject = `${subjectType.value}:${subjectId.value}`;
    const edit = editing.value;
    const currentOperation = () => active && operationGeneration === editGeneration && operationSubject === `${subjectType.value}:${subjectId.value}`;
    const formatTime = (value: Date | null) => value?.toLocaleString('zh-CN', { timeZone: 'Asia/Taipei', hour12: false }) ?? '无结束时间';
    const validity = `${formatTime(draft.from)} 至 ${formatTime(draft.to)}`;
    const previousValidity = edit ? `${formatTime(new Date(edit.validFrom))} 至 ${formatTime(edit.validTo ? new Date(edit.validTo) : null)}` : null;
    saving.value = true;
    try {
      await ElMessageBox.confirm(
        `${subjectName.value} → ${target.label}：${draft.effect === 'DENY' ? '拒绝' : '允许'} ${draft.action}，${draft.includeDescendants ? '含当前和未来子级' : '仅此资源'}。有效期（台北）：${previousValidity ? `原 ${previousValidity}；改为 ` : ''}${validity}。新查询最大 5 秒生效，在途调用尽力取消。`,
        editing.value ? '确认修改规则' : '确认创建规则',
        { confirmButtonText: '确认提交', cancelButtonText: '继续编辑', type: draft.effect === 'DENY' ? 'warning' : 'info' }
      );
    } catch {
      saving.value = false;
      return;
    }
    if (!currentOperation() || !dialogVisible.value || !(edit ? canUpdate.value : canCreate.value)) {
      saving.value = false;
      return;
    }
    formError.value = '';
    latestConflict.value = null;
    clearPreview();
    const body: GrantWrite = {
      subjectType: subjectType.value,
      subjectId: subjectId.value,
      resourceType: target.type,
      resourceId: target.id,
      resourceGeneration: target.generation,
      action: draft.action,
      effect: draft.effect,
      includeDescendants: draft.includeDescendants,
      validFrom: draft.from.toISOString(),
      validTo: draft.to?.toISOString() ?? null,
      reason: draft.reason.trim()
    };
    try {
      await saveGrant(body, intentKey, edit ?? undefined);
      if (!currentOperation()) return;
      dialogVisible.value = false;
      clearPreview();
      ElMessage.success('授权规则已保存');
      await loadGrants();
    } catch (error) {
      if (!currentOperation()) return;
      formError.value = errorMessage(error);
      if (error instanceof ApiError && [404, 409, 412].includes(error.status)) clearPreview();
      if (error instanceof ApiError && (error.status === 409 || error.status === 412) && edit) {
        try {
          const latest = await getGrant(edit.id);
          if (!currentOperation()) return;
          latestConflict.value = latest;
        } catch {
          /* Keep draft and original version when current data cannot be read. */
        }
      }
      if (error instanceof ApiError && error.status === 404 && edit) {
        dialogVisible.value = false;
        const closedGeneration = editGeneration;
        editing.value = null;
        draftTarget.value = null;
        const message = '这条规则已不存在或当前不可见，已关闭编辑并刷新规则列表。';
        await loadGrants();
        if (active && closedGeneration === editGeneration && operationSubject === `${subjectType.value}:${subjectId.value}`)
          pageError.value = message;
      }
      if (error instanceof ApiError && (error.status === 0 || error.status >= 500)) {
        unknownOutcome.value = true;
        formError.value += ' 提交结果未确认，请关闭弹窗并刷新规则列表，核对后再操作。';
      }
    } finally {
      saving.value = false;
    }
  }
  async function revoke(row: Grant) {
    if (!active || !canDelete.value || pendingDelete.value || row.status !== 'ACTIVE') return;
    const generation = subjectGeneration;
    const currentSubject = () =>
      active && generation === subjectGeneration && row.subjectType === subjectType.value && row.subjectId === subjectId.value;
    pendingDelete.value = row.id;
    try {
      await ElMessageBox.confirm(`撤销规则 ${row.id}：${row.effect} ${row.action}。后续新查询最大 5 秒生效，在途调用尽力取消。`, '撤销授权规则', {
        type: 'warning',
        confirmButtonText: '撤销规则',
        cancelButtonText: '取消'
      });
    } catch {
      pendingDelete.value = '';
      return;
    }
    if (!currentSubject() || !canDelete.value) {
      pendingDelete.value = '';
      return;
    }
    pageError.value = '';
    clearPreview();
    try {
      await revokeGrantApi(row);
      if (!currentSubject()) return;
      clearPreview();
      await loadGrants();
      if (currentSubject()) ElMessage.success('规则已撤销');
    } catch (error) {
      if (!currentSubject()) return;
      if (error instanceof ApiError && [404, 409, 412].includes(error.status)) {
        clearPreview();
        await loadGrants();
        if (currentSubject()) pageError.value = `${errorMessage(error)} 已刷新规则，请核对最新状态后重新操作。`;
      } else pageError.value = errorMessage(error);
    } finally {
      pendingDelete.value = '';
    }
  }
  async function runPreview() {
    const userId = subjectType.value === 'USER' ? subjectId.value : previewUserId.value;
    const resource = selectedResource.value;
    if (
      !active ||
      !canPreview.value ||
      saving.value ||
      !!pendingDelete.value ||
      unknownOutcome.value ||
      !userId ||
      !platformId.value ||
      !resource ||
      resource.type === 'GROUP' ||
      previewLoading.value ||
      (previewAt.value && !Number.isFinite(previewAt.value.getTime()))
    )
      return;
    clearPreview();
    const generation = ++previewGeneration;
    previewRequest = new AbortController();
    previewLoading.value = true;
    try {
      const result = await previewAccess(
        userId,
        platformId.value,
        [encodePreviewResource(resource.type, resource.id)],
        previewAt.value?.toISOString(),
        { signal: previewRequest.signal }
      );
      if (active && generation === previewGeneration) {
        preview.value = result;
        if (!previewAt.value && result.nextBoundaryAt) {
          const wait = new Date(result.nextBoundaryAt).getTime() - Date.now();
          boundaryTimer = setTimeout(
            () => {
              clearPreview();
              previewError.value = '已到达规则时间边界，请重新计算当前权限。';
            },
            Math.max(0, Math.min(wait, 2147483647))
          );
        }
      }
    } catch (error) {
      if (active && generation === previewGeneration && !(error instanceof DOMException && error.name === 'AbortError'))
        previewError.value = errorMessage(error);
    } finally {
      if (generation === previewGeneration) previewLoading.value = false;
    }
  }
  watch(subjectType, () => {
    subjectGeneration += 1;
    editGeneration += 1;
    subjectId.value = '';
    grants.value = [];
    dialogVisible.value = false;
    clearPreview();
  });
  watch(subjectId, () => {
    subjectGeneration += 1;
    editGeneration += 1;
    dialogVisible.value = false;
    clearPreview();
    void loadGrants();
  });
  watch(sourceId, () => {
    editGeneration += 1;
    dialogVisible.value = false;
    clearPreview();
    void loadResources();
  });
  watch([platformId, previewUserId, previewAt], clearPreview);
  watch(
    dialogVisible,
    (visible) => {
      if (!visible) editGeneration += 1;
    },
    { flush: 'sync' }
  );
  function clearWorkspace() {
    active = false;
    grantGeneration += 1;
    resourceGeneration += 1;
    editGeneration += 1;
    grantRequest?.abort();
    resourceRequest?.abort();
    clearPreview();
    grants.value = [];
    sources.value = [];
    resourceItems.value = [];
    selectedResource.value = null;
    draftTarget.value = null;
    subjectId.value = '';
    sourceId.value = '';
    platforms.value = [];
    subjectOptions.USER = [];
    subjectOptions.ROLE = [];
    subjectOptions.DEPT = [];
    dialogVisible.value = false;
    editing.value = null;
    latestConflict.value = null;
    draft.reason = '';
  }
  onMounted(async () => {
    const results = await Promise.allSettled([
      session.can('ptmcp:user:list') ? orgPortalApi.users.list() : Promise.resolve([]),
      session.can('ptmcp:role:list') ? orgPortalApi.roles.list() : Promise.resolve([]),
      session.can('ptmcp:department:list') ? orgPortalApi.departments.list() : Promise.resolve([]),
      loadSources(),
      canPreview.value ? loadPlatforms() : Promise.resolve()
    ]);
    if (!active) return;
    const [users, roles, depts] = results;
    if (users.status === 'fulfilled')
      subjectOptions.USER = users.value.map((item) => ({ id: String(item.id), label: `${item.displayName} · ${item.username}` }));
    if (roles.status === 'fulfilled') subjectOptions.ROLE = roles.value.map((item) => ({ id: String(item.id), label: item.name }));
    if (depts.status === 'fulfilled') subjectOptions.DEPT = depts.value.map((item) => ({ id: String(item.id), label: item.name }));
    if ([users, roles, depts].some((result) => result.status === 'rejected')) lookupError.value = '部分主体目录读取失败，请检查相应组织权限后刷新。';
  });
  onMounted(() => window.addEventListener('ptmcp:sensitive-clear', clearWorkspace));
  onBeforeUnmount(() => {
    window.removeEventListener('ptmcp:sensitive-clear', clearWorkspace);
    clearWorkspace();
  });

  return {
    session,
    canList,
    canRead,
    canCreate,
    canUpdate,
    canDelete,
    canPreview,
    subjectType,
    subjectId,
    subjectOptions,
    lookupError,
    grants,
    loading,
    pageError,
    sources,
    sourceCursor,
    sourceId,
    resourceCursor,
    resourceLoading,
    resourceError,
    selectedResource,
    resourceTree,
    platforms,
    platformCursor,
    platformId,
    previewUserId,
    previewAt,
    preview,
    previewError,
    previewLoading,
    dialogVisible,
    editing,
    latestConflict,
    saving,
    formError,
    unknownOutcome,
    draft,
    draftTarget,
    pendingDelete,
    subjectName,
    validDraft,
    changed,
    loadGrants,
    loadSources,
    loadResources,
    loadPlatforms,
    selectResource,
    openCreate,
    openEdit,
    save,
    revoke,
    runPreview
  };
}
