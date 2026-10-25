<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { DataRequestError, listDatasources, listMetadata, type DatasourceRead, type MetadataItem } from '../../../services/data';
import { useSessionStore } from '../../../stores/session';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const datasources = ref<DatasourceRead[]>([]);
const datasourceId = ref(String(route.query.datasourceId ?? ''));
const rows = ref<MetadataItem[]>([]);
const nextCursor = ref<string | null>(null);
const loading = ref(false);
const sourceLoading = ref(false);
const sourceNextCursor = ref<string | null>(null);
const awaitingSnapshot = ref(false);
const error = ref('');
const requestId = ref<string>();
const sourceError = ref('');
const filters = reactive({ namespaceId: '', keyword: '', kind: '' });
let sourceController: AbortController | null = null;
let listController: AbortController | null = null;
let generation = 0;
let sourceGeneration = 0;
let active = true;
function errorMessage(value: unknown): string {
  if (value instanceof DataRequestError) {
    requestId.value = value.requestId;
    return `${value.message}${value.code ? `（${value.code}）` : ''}`;
  }
  return value instanceof Error ? value.message : '请求失败，请稍后重试';
}
function sourceLabel(source: DatasourceRead): string {
  return `${source.name} · ${source.engine}`;
}
function formatObject(item: MetadataItem): string {
  return item.schemaName ? `${item.schemaName}.${item.objectName}` : item.objectName;
}
function kindType(kind: string): 'success' | 'info' {
  return kind === 'TABLE' ? 'success' : 'info';
}
function aborted(value: unknown): boolean {
  return value instanceof DOMException && value.name === 'AbortError';
}
async function loadSources(cursor: string | null = null): Promise<void> {
  sourceController?.abort();
  const current = ++sourceGeneration;
  if (!session.can('ptmcp:datasource:list')) {
    datasources.value = [];
    sourceNextCursor.value = null;
    sourceLoading.value = false;
    sourceError.value = '你没有数据源列表权限。请通过可访问对象入口进入元数据。';
    return;
  }
  sourceController = new AbortController();
  sourceLoading.value = true;
  sourceError.value = '';
  try {
    const result = await listDatasources({ cursor, pageSize: 100, signal: sourceController.signal });
    if (!active || current !== sourceGeneration) return;
    const prior = cursor ? datasources.value : [];
    const knownIds = new Set(prior.map((source) => source.id));
    datasources.value = [...prior, ...result.data.items.filter((source) => !knownIds.has(source.id))];
    sourceNextCursor.value = result.data.nextCursor;
    if (!datasourceId.value && datasources.value.length) datasourceId.value = datasources.value[0].id;
  } catch (cause) {
    if (!active || current !== sourceGeneration || aborted(cause)) return;
    if (cause instanceof DataRequestError && cause.code === 'CURSOR_STALE' && cursor) {
      sourceNextCursor.value = null;
      await loadSources();
      return;
    }
    sourceError.value = errorMessage(cause);
    if (cause instanceof DataRequestError && [401, 403, 404].includes(cause.status)) {
      datasources.value = [];
      sourceNextCursor.value = null;
    }
  } finally {
    if (active && current === sourceGeneration) sourceLoading.value = false;
  }
}
function moreSources(): void {
  if (sourceNextCursor.value && !sourceLoading.value) void loadSources(sourceNextCursor.value);
}
async function load(cursor: string | null = null): Promise<void> {
  listController?.abort();
  const current = ++generation;
  listController = new AbortController();
  awaitingSnapshot.value = false;
  error.value = '';
  requestId.value = undefined;
  if (!datasourceId.value || !session.can('ptmcp:metadata:list')) {
    rows.value = [];
    nextCursor.value = null;
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await listMetadata({
      datasourceId: datasourceId.value,
      namespaceId: filters.namespaceId || undefined,
      keyword: filters.keyword || undefined,
      kind: filters.kind || undefined,
      cursor,
      pageSize: 20,
      signal: listController.signal
    });
    if (!active || current !== generation) return;
    rows.value = result.data.items ?? [];
    nextCursor.value = result.data.nextCursor ?? null;
  } catch (cause) {
    if (!active || current !== generation || aborted(cause)) return;
    if (cause instanceof DataRequestError && cause.code === 'CURSOR_STALE' && cursor) {
      nextCursor.value = null;
      await load();
      return;
    }
    if (cause instanceof DataRequestError && cause.code === 'SCHEMA_STALE') {
      rows.value = [];
      nextCursor.value = null;
      awaitingSnapshot.value = true;
      requestId.value = cause.requestId;
      return;
    }
    error.value = errorMessage(cause);
    if (cause instanceof DataRequestError && [401, 403, 404, 503].includes(cause.status)) {
      rows.value = [];
      nextCursor.value = null;
    }
  } finally {
    if (active && current === generation) loading.value = false;
  }
}
function selectSource(): void {
  filters.namespaceId = '';
  nextCursor.value = null;
  rows.value = [];
  void router.replace({ query: { ...route.query, datasourceId: datasourceId.value || undefined } });
  void load();
}
function applyFilters(): void {
  nextCursor.value = null;
  void load();
}
function nextPage(): void {
  if (nextCursor.value && !loading.value) void load(nextCursor.value);
}
function openDetail(row: MetadataItem): void {
  if (session.can('ptmcp:metadata:read')) void router.push(`/admin/metadata/${row.id}`);
}
function clearSensitive(): void {
  sourceController?.abort();
  listController?.abort();
  generation += 1;
  sourceGeneration += 1;
  rows.value = [];
  datasources.value = [];
  nextCursor.value = null;
  sourceNextCursor.value = null;
  loading.value = false;
  sourceLoading.value = false;
  awaitingSnapshot.value = false;
}
watch(
  () => route.query.datasourceId,
  (value) => {
    const selected = typeof value === 'string' ? value : '';
    if (selected !== datasourceId.value) {
      datasourceId.value = selected;
      filters.namespaceId = '';
      nextCursor.value = null;
      rows.value = [];
      void load();
    }
  }
);
onMounted(async () => {
  window.addEventListener('ptmcp:sensitive-clear', clearSensitive);
  const current = sourceGeneration + 1;
  await loadSources();
  if (active && current === sourceGeneration) await load();
});
onBeforeUnmount(() => {
  active = false;
  clearSensitive();
  window.removeEventListener('ptmcp:sensitive-clear', clearSensitive);
});
</script>

<template>
  <section class="page-shell" aria-labelledby="metadata-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">平台管理 / 结构目录</p>
        <h1 id="metadata-title">元数据与业务字典</h1>
        <p class="subtitle">查看已发布快照中的物理结构，并维护纯文本业务说明。</p>
      </div>
    </div>
    <ElAlert v-if="sourceError" type="error" :closable="false" show-icon class="state-alert">{{ sourceError }}</ElAlert>
    <ElCard class="filter-card" shadow="never">
      <ElForm :inline="true">
        <ElFormItem label="数据源"
          ><ElSelect v-model="datasourceId" filterable :loading="sourceLoading" placeholder="先选择数据源" @change="selectSource"
            ><ElOption v-for="source in datasources" :key="source.id" :label="sourceLabel(source)" :value="source.id" /></ElSelect
        ></ElFormItem>
        <ElFormItem label="命名空间"
          ><ElInput v-model="filters.namespaceId" clearable placeholder="namespace ID" @keyup.enter="applyFilters"
        /></ElFormItem>
        <ElFormItem label="关键词"><ElInput v-model="filters.keyword" clearable placeholder="表名或评论" @keyup.enter="applyFilters" /></ElFormItem>
        <ElFormItem label="类型"
          ><ElSelect v-model="filters.kind" clearable placeholder="全部"
            ><ElOption label="表" value="TABLE" /><ElOption label="视图" value="VIEW" /></ElSelect
        ></ElFormItem>
        <ElButton type="primary" :disabled="!datasourceId" @click="applyFilters">查询</ElButton>
      </ElForm>
    </ElCard>
    <div v-if="sourceNextCursor" class="source-pages">
      <span>已加载 {{ datasources.length }} 个数据源</span><ElButton :loading="sourceLoading" @click="moreSources">加载更多数据源</ElButton>
    </div>
    <ElAlert v-if="awaitingSnapshot" type="warning" :closable="false" show-icon class="state-alert">
      当前数据源暂无可用的元数据快照，处于待同步状态。请同步后重新读取。
      <span v-if="requestId">（请求 ID：{{ requestId }}）</span>
      <ElButton v-if="session.can('ptmcp:datasource:read')" link type="primary" @click="router.push(`/admin/datasources/${datasourceId}`)"
        >前往数据源同步</ElButton
      >
      <ElButton link type="primary" @click="applyFilters">重新读取</ElButton>
    </ElAlert>
    <ElAlert v-if="error" type="error" :closable="false" show-icon class="state-alert"
      ><span
        >{{ error }}<span v-if="requestId">（请求 ID：{{ requestId }}）</span></span
      ></ElAlert
    >
    <ElCard shadow="never" class="table-card" :body-style="{ padding: '0' }">
      <ElTable v-loading="loading" :data="rows" row-key="id" empty-text="">
        <ElTableColumn label="对象" min-width="270"
          ><template #default="scope"
            ><button class="link-button" type="button" :disabled="!session.can('ptmcp:metadata:read')" @click="openDetail(scope.row as MetadataItem)">
              <strong>{{ formatObject(scope.row as MetadataItem) }}</strong
              ><small>{{ scope.row.databaseName }} · ID {{ scope.row.id }} · 命名空间 {{ scope.row.namespaceId }}</small>
            </button></template
          ></ElTableColumn
        >
        <ElTableColumn label="类型" width="110"
          ><template #default="scope"
            ><ElTag :type="kindType(scope.row.objectType)" effect="light">{{ scope.row.objectType === 'TABLE' ? '表' : '视图' }}</ElTag></template
          ></ElTableColumn
        >
        <ElTableColumn prop="tableComment" label="数据库注释" min-width="230" show-overflow-tooltip />
        <ElTableColumn label="快照" width="150"
          ><template #default="scope">{{ scope.row.snapshotVersion }} / {{ scope.row.generation }}</template></ElTableColumn
        >
        <ElTableColumn label="操作" width="100" fixed="right"
          ><template #default="scope"
            ><ElButton v-if="session.can('ptmcp:metadata:read')" link type="primary" @click="openDetail(scope.row as MetadataItem)"
              >查看</ElButton
            ></template
          ></ElTableColumn
        >
        <template #empty
          ><ElEmpty
            :description="
              !datasourceId
                ? '选择数据源后查看元数据'
                : loading
                  ? '正在加载…'
                  : awaitingSnapshot
                    ? '元数据待同步'
                    : error
                      ? '元数据读取失败，请重试'
                      : '当前快照中没有匹配对象'
            "
        /></template>
      </ElTable>
      <div class="table-footer">
        <span>{{ datasourceId ? `已加载 ${rows.length} 条` : '尚未选择数据源' }}</span
        ><ElButton :disabled="!nextCursor || loading" @click="nextPage">加载下一页</ElButton>
      </div>
    </ElCard>
    <p class="privacy-note">结构与数据库注释为只读数据；保存业务字典不会执行 DDL。没有活动快照时会显示待同步状态。</p>
  </section>
</template>

<style scoped>
.page-shell {
  max-width: 1440px;
  margin: 0 auto;
  padding: 28px clamp(16px, 3vw, 40px) 44px;
}
.page-heading {
  margin-bottom: 24px;
}
.eyebrow {
  margin: 0 0 7px;
  color: #0f6f68;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
h1 {
  margin: 0;
  color: #1d2938;
  font-size: clamp(26px, 3vw, 34px);
}
.subtitle {
  margin: 8px 0 0;
  color: #627080;
}
.filter-card {
  margin-bottom: 16px;
  border-color: #dce5e9;
  background: #f8fafb;
}
.filter-card :deep(.el-form-item) {
  margin-bottom: 0;
}
.source-pages {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  margin: 0 0 16px;
  color: #6b7785;
  font-size: 13px;
}
.table-card {
  border-color: #dce5e9;
  overflow: hidden;
}
.table-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 13px 16px;
  color: #6b7785;
  font-size: 13px;
  border-top: 1px solid #edf1f3;
}
.link-button {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  border: 0;
  padding: 0;
  background: transparent;
  color: #126c72;
  cursor: pointer;
  text-align: left;
}
.link-button:hover strong,
.link-button:focus-visible strong {
  text-decoration: underline;
}
.link-button small {
  color: #778492;
  font-size: 12px;
  font-weight: 400;
}
.state-alert {
  margin-bottom: 16px;
}
.privacy-note {
  margin: 15px 2px;
  color: #7b8792;
  font-size: 12px;
}
@media (max-width: 680px) {
  .page-shell {
    padding-inline: 12px;
  }
  .filter-card :deep(.el-form) {
    display: grid;
    grid-template-columns: 1fr;
  }
  .filter-card :deep(.el-form-item) {
    margin-right: 0;
  }
}
</style>
