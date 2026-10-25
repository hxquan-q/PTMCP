<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { DataRequestError, listDatasources, listMetadata, type DatasourceRead, type MetadataItem } from '../../../services/data';

const route = useRoute();
const router = useRouter();
const datasources = ref<DatasourceRead[]>([]);
const datasourceId = ref(String(route.query.datasourceId ?? ''));
const rows = ref<MetadataItem[]>([]);
const nextCursor = ref<string | null>(null);
const loading = ref(false);
const sourceLoading = ref(false);
const error = ref('');
const requestId = ref<string>();
const sourceError = ref('');
const filters = reactive({ namespaceId: '', keyword: '', kind: '' });

function errorMessage(value: unknown): string {
  if (value instanceof DataRequestError) {
    requestId.value = value.requestId;
    return value.message;
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
async function loadSources(): Promise<void> {
  sourceLoading.value = true;
  sourceError.value = '';
  try {
    const result = await listDatasources({ pageSize: 100 });
    datasources.value = result.data.items ?? [];
    if (!datasourceId.value && datasources.value.length) datasourceId.value = datasources.value[0].id;
  } catch (cause) {
    sourceError.value = errorMessage(cause);
  } finally {
    sourceLoading.value = false;
  }
}
async function load(): Promise<void> {
  if (!datasourceId.value) {
    rows.value = [];
    nextCursor.value = null;
    return;
  }
  loading.value = true;
  error.value = '';
  try {
    const result = await listMetadata({
      datasourceId: datasourceId.value,
      namespaceId: filters.namespaceId || undefined,
      keyword: filters.keyword || undefined,
      kind: filters.kind || undefined,
      pageSize: 20
    });
    rows.value = result.data.items ?? [];
    nextCursor.value = result.data.nextCursor ?? null;
  } catch (cause) {
    error.value = errorMessage(cause);
  } finally {
    loading.value = false;
  }
}
function selectSource(): void {
  void router.replace({ query: { ...route.query, datasourceId: datasourceId.value || undefined } });
  void load();
}
function applyFilters(): void {
  void load();
}
function nextPage(): void {
  if (!nextCursor.value || !datasourceId.value) return;
  loading.value = true;
  void listMetadata({
    datasourceId: datasourceId.value,
    namespaceId: filters.namespaceId || undefined,
    keyword: filters.keyword || undefined,
    kind: filters.kind || undefined,
    cursor: nextCursor.value,
    pageSize: 20
  })
    .then((result) => {
      rows.value = result.data.items ?? [];
      nextCursor.value = result.data.nextCursor ?? null;
    })
    .catch((cause) => {
      error.value = errorMessage(cause);
    })
    .finally(() => {
      loading.value = false;
    });
}
function openDetail(row: MetadataItem): void {
  void router.push(`/admin/metadata/${row.id}`);
}
watch(
  () => route.query.datasourceId,
  (value) => {
    if (typeof value === 'string' && value !== datasourceId.value) {
      datasourceId.value = value;
      void load();
    }
  }
);
onMounted(async () => {
  await loadSources();
  await load();
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
    <ElAlert v-if="error" type="error" :closable="false" show-icon class="state-alert"
      ><span
        >{{ error }}<span v-if="requestId">（请求 ID：{{ requestId }}）</span></span
      ></ElAlert
    >
    <ElCard shadow="never" class="table-card" :body-style="{ padding: '0' }">
      <ElTable v-loading="loading" :data="rows" row-key="id" empty-text="">
        <ElTableColumn label="对象" min-width="270"
          ><template #default="scope"
            ><button class="link-button" type="button" @click="openDetail(scope.row)">
              <strong>{{ formatObject(scope.row) }}</strong
              ><small>{{ scope.row.databaseName }} · ID {{ scope.row.id }}</small>
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
          ><template #default="scope"><ElButton link type="primary" @click="openDetail(scope.row)">查看</ElButton></template></ElTableColumn
        >
        <template #empty
          ><ElEmpty :description="!datasourceId ? '选择数据源后查看元数据' : loading ? '正在加载…' : '当前快照中没有匹配对象'"
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
