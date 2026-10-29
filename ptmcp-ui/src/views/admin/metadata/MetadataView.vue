<script setup lang="ts">
import CursorFooter from '../../../components/common/CursorFooter.vue';
import PageHeader from '../../../components/common/PageHeader.vue';
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { DataRequestError, listDatasources, listMetadata, type DatasourceRead, type MetadataItem } from '../../../services/data';
import { useCursorPage } from '../../../composables/useCursorPage';
import { useSessionStore } from '../../../stores/session';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const datasourceId = ref(String(route.query.datasourceId ?? ''));
const awaitingSnapshot = ref(false);
const sourceDenied = computed(() => !session.can('ptmcp:datasource:list'));
const filters = reactive({ namespaceId: '', keyword: '', kind: '' });
let active = true;
function toPageError(cause: unknown): Error {
  if (cause instanceof DataRequestError) {
    const error = new Error(`${cause.message}${cause.code ? `（${cause.code}）` : ''}`);
    return Object.assign(error, { requestId: cause.requestId, code: cause.code, status: cause.status });
  }
  return cause instanceof Error ? cause : new Error('请求失败，请稍后重试');
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
const {
  rows: datasources,
  cursor: sourceNextCursor,
  loading: sourceLoading,
  error: sourceFailure,
  requestId: sourceRequestId,
  reset: resetSources,
  load: loadSourcePage,
} = useCursorPage<DatasourceRead>(async (cursor, signal) => {
  if (!session.can('ptmcp:datasource:list')) return { items: [], nextCursor: null };
  try {
    const result = await listDatasources({ cursor, pageSize: 100, signal });
    return { items: result.data.items ?? [], nextCursor: result.data.nextCursor ?? null };
  } catch (cause) {
    throw toPageError(cause);
  }
});
const {
  rows,
  cursor: nextCursor,
  loading,
  error,
  requestId,
  reset: resetList,
  load: loadListPage,
} = useCursorPage<MetadataItem>(async (cursor, signal) => {
  if (!datasourceId.value || !session.can('ptmcp:metadata:list')) return { items: [], nextCursor: null };
  try {
    const result = await listMetadata({
      datasourceId: datasourceId.value,
      namespaceId: filters.namespaceId || undefined,
      keyword: filters.keyword || undefined,
      kind: filters.kind || undefined,
      cursor,
      pageSize: 20,
      signal,
    });
    return { items: result.data.items ?? [], nextCursor: result.data.nextCursor ?? null };
  } catch (cause) {
    if (cause instanceof DataRequestError && cause.code === 'SCHEMA_STALE') awaitingSnapshot.value = true;
    throw toPageError(cause);
  }
});
async function loadSources(append = false): Promise<void> {
  await loadSourcePage(append);
  if (!datasourceId.value && datasources.value.length) datasourceId.value = datasources.value[0].id;
}
function moreSources(): void {
  if (sourceNextCursor.value && !sourceLoading.value) void loadSources(true);
}
async function load(): Promise<void> {
  awaitingSnapshot.value = false;
  await loadListPage();
}
function selectSource(): void {
  filters.namespaceId = '';
  resetList();
  void router.replace({ query: { ...route.query, datasourceId: datasourceId.value || undefined } });
  void load();
}
function applyFilters(): void {
  resetList();
  void load();
}
function nextPage(): void {
  if (nextCursor.value && !loading.value) void loadListPage(true);
}
function openDetail(row: MetadataItem): void {
  if (session.can('ptmcp:metadata:read')) void router.push(`/admin/metadata/${row.id}`);
}
function clearSensitive(): void {
  resetSources();
  resetList();
  awaitingSnapshot.value = false;
}
watch(
  () => route.query.datasourceId,
  (value) => {
    const selected = typeof value === 'string' ? value : '';
    if (selected !== datasourceId.value) {
      datasourceId.value = selected;
      filters.namespaceId = '';
      resetList();
      void load();
    }
  },
);
onMounted(async () => {
  window.addEventListener('ptmcp:sensitive-clear', clearSensitive);
  await loadSources();
  if (active && datasourceId.value) await load();
});
onBeforeUnmount(() => {
  active = false;
  clearSensitive();
  window.removeEventListener('ptmcp:sensitive-clear', clearSensitive);
});
</script>

<template>
  <section class="page-shell fill-page" aria-labelledby="metadata-title">
    <PageHeader title-id="metadata-title" title="元数据与业务字典" description="查看已发布快照中的物理结构，并维护纯文本业务说明。" />
    <ElAlert v-if="sourceDenied" type="warning" :closable="false" show-icon class="state-alert"
      >你没有数据源列表权限。请通过可访问对象入口进入元数据。</ElAlert
    >
    <PageState
      v-else-if="sourceFailure"
      kind="error"
      title="数据源目录读取失败"
      :description="sourceFailure"
      :request-id="sourceRequestId"
      action-label="重试数据源目录"
      @action="loadSources()"
    />
    <div class="ptmcp-filter-strip is-dense">
      <ElForm :inline="true" @submit.prevent="applyFilters">
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
        <ElButton type="primary" plain :disabled="!datasourceId" native-type="submit">查询</ElButton>
      </ElForm>
    </div>
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
    <PageState
      v-if="error && !awaitingSnapshot"
      kind="error"
      title="元数据读取失败"
      :description="error"
      :request-id="requestId"
      action-label="重新读取"
      @action="applyFilters"
    />
    <ElCard shadow="never" class="table-card ptmcp-table-section ptmcp-fill-card" :body-style="{ padding: '0' }">
      <div class="ptmcp-table-heading">
        <h2 class="ptmcp-section-title">元数据对象</h2>
        <span class="ptmcp-meta-note">共 {{ rows.length }} 条</span>
      </div>
      <div class="ptmcp-table-wrap">
        <ElTable class="ptmcp-data-table" v-loading="loading" :data="rows" row-key="id" size="small" height="100%" empty-text="">
          <ElTableColumn label="对象" min-width="270"
            ><template #default="scope"
              ><button
                class="link-button ptmcp-cell-lead"
                type="button"
                :disabled="!session.can('ptmcp:metadata:read')"
                @click="openDetail(scope.row as MetadataItem)"
              >
                <span class="ptmcp-cell-icon">
                  <IconEpDocument aria-hidden="true" />
                </span>
                <span class="ptmcp-cell-stack">
                  <strong>{{ formatObject(scope.row as MetadataItem) }}</strong
                  ><span>{{ scope.row.databaseName }} · ID {{ scope.row.id }} · 命名空间 {{ scope.row.namespaceId }}</span>
                </span>
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
          <ElTableColumn label="操作" width="90" align="right" fixed="right"
            ><template #default="scope"
              ><div class="ptmcp-row-actions">
                <ElButton v-if="session.can('ptmcp:metadata:read')" link type="primary" @click="openDetail(scope.row as MetadataItem)">查看</ElButton>
              </div></template
            ></ElTableColumn
          >
          <template #empty
            ><PageState
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
      </div>
      <CursorFooter v-if="datasourceId" :count="rows.length" :has-more="!!nextCursor" :loading="loading" @more="nextPage" />
    </ElCard>
    <p class="privacy-note">结构与数据库注释为只读数据；保存业务字典不会执行 DDL。没有活动快照时会显示待同步状态。</p>
  </section>
</template>

<style scoped>
.filter-card {
  margin-bottom: 16px;
  border-color: var(--ptmcp-border);
  background: var(--ptmcp-chrome);
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
  color: var(--ptmcp-text-muted);
  font-size: 13px;
}
.table-card {
  border-color: var(--ptmcp-border);
  overflow: hidden;
}
.link-button {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--ptmcp-blue);
  cursor: pointer;
  text-align: left;
}
.link-button:hover strong,
.link-button:focus-visible strong {
  text-decoration: underline;
}
.link-button small {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  font-weight: 400;
}
.link-button.ptmcp-cell-lead {
  flex-direction: row;
  align-items: center;
}
.state-alert {
  margin-bottom: 16px;
}
.privacy-note {
  margin: 15px 2px;
  color: var(--ptmcp-text-muted);
  font-size: 12px;
}
@media (max-width: 680px) {
  .filter-card :deep(.el-form) {
    display: grid;
    grid-template-columns: 1fr;
  }
  .filter-card :deep(.el-form-item) {
    margin-right: 0;
  }
}
</style>
