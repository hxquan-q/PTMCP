<script setup lang="ts">
import CursorFooter from '../../components/common/CursorFooter.vue';
import PageHeader from '../../components/common/PageHeader.vue';
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { mePortalApi } from '../../services/mePortalApi';
import { useCursorPage } from '../../composables/useCursorPage';
import { usePortalTask } from '../portalUtils';

const router = useRouter();
const platformId = ref('');
const datasourceId = ref('');
const namespaceId = ref('');
const keyword = ref('');
const databaseName = ref('');
const schemaName = ref('');
const {
  rows: platforms,
  cursor: platformCursor,
  loading: loadingPlatforms,
  error: platformError,
  requestId: platformRequestId,
  load: loadPlatformPage,
  retry: retryPlatforms,
} = useCursorPage((cursor, signal) => mePortalApi.accessContext.platforms(cursor, signal));
const {
  rows: datasources,
  cursor: datasourceCursor,
  loading: loadingDatasources,
  error: datasourceError,
  requestId: datasourceRequestId,
  reset: resetDatasources,
  load: loadDatasourcePage,
  retry: retryDatasources,
} = useCursorPage((cursor, signal) => mePortalApi.accessContext.datasources(platformId.value, cursor, signal));
const {
  rows: namespaces,
  cursor: namespaceCursor,
  loading: loadingNamespaces,
  error: namespaceError,
  requestId: namespaceRequestId,
  reset: resetNamespaces,
  load: loadNamespacePage,
  retry: retryNamespaces,
} = useCursorPage((cursor, signal) => mePortalApi.accessContext.namespaces(platformId.value, datasourceId.value, cursor, signal));
const {
  rows: objects,
  cursor: nextCursor,
  loading,
  loaded: objectsLoaded,
  error: loadError,
  requestId: objectRequestId,
  reset: clearObjects,
  load: loadObjectPage,
  retry: retryObjects,
} = useCursorPage((cursor, signal) =>
  mePortalApi.dictionary.list(
    {
      platformId: platformId.value,
      datasourceId: datasourceId.value,
      databaseName: databaseName.value || undefined,
      schemaName: schemaName.value || undefined,
      keyword: keyword.value.trim() || undefined,
      cursor,
    },
    signal,
  ),
);
usePortalTask(() => {
  platformId.value = '';
  datasourceId.value = '';
  namespaceId.value = '';
});

async function loadPlatforms(append = false): Promise<void> {
  await loadPlatformPage(append);
  if (!platformId.value && platforms.value[0]) platformId.value = platforms.value[0].id;
}
async function loadDatasources(append = false): Promise<void> {
  if (platformId.value) await loadDatasourcePage(append);
}
async function loadNamespaces(append = false): Promise<void> {
  if (platformId.value && datasourceId.value) await loadNamespacePage(append);
}
async function loadObjects(append = false): Promise<void> {
  if (platformId.value && datasourceId.value) await loadObjectPage(append);
}
watch(
  platformId,
  () => {
    resetDatasources();
    resetNamespaces();
    clearObjects();
    datasourceId.value = '';
    namespaceId.value = '';
    databaseName.value = '';
    schemaName.value = '';
    void loadDatasources();
  },
  { flush: 'sync' },
);
watch(
  datasourceId,
  () => {
    resetNamespaces();
    clearObjects();
    namespaceId.value = '';
    databaseName.value = '';
    schemaName.value = '';
    void loadNamespaces();
    void loadObjects();
  },
  { flush: 'sync' },
);
watch(
  namespaceId,
  (value) => {
    const item = namespaces.value.find((entry) => entry.id === value);
    databaseName.value = item?.databaseName ?? '';
    schemaName.value = item?.schemaName ?? '';
    clearObjects();
    void loadObjects();
  },
  { flush: 'sync' },
);
watch(keyword, clearObjects, { flush: 'sync' });
onMounted(() => {
  void loadPlatforms();
});
</script>

<template>
  <section class="portal-page" aria-labelledby="dictionary-title">
    <PageHeader
      title-id="dictionary-title"
      title="我的数据字典"
      description="目录只展示你在当前平台范围内可发现的对象，读取详情仍由服务端再次鉴权。"
    />
    <PageState
      v-if="platformError"
      kind="error"
      title="平台目录读取失败"
      :description="platformError"
      :request-id="platformRequestId"
      action-label="重试平台目录"
      @action="retryPlatforms"
    />
    <PageState
      v-if="datasourceError"
      kind="error"
      title="数据源目录读取失败"
      :description="datasourceError"
      :request-id="datasourceRequestId"
      action-label="重试数据源目录"
      @action="retryDatasources"
    />
    <PageState
      v-if="namespaceError"
      kind="error"
      title="命名空间读取失败"
      :description="namespaceError"
      :request-id="namespaceRequestId"
      action-label="重试命名空间"
      @action="retryNamespaces"
    />
    <PageState
      v-if="loadError"
      kind="error"
      title="字典读取失败"
      :description="loadError"
      :request-id="objectRequestId"
      action-label="重试字典"
      @action="retryObjects"
    />
    <ElCard shadow="never" class="portal-card context-card ptmcp-filter-bar"
      ><div class="filters">
        <ElFormItem label="平台"
          ><ElSelect v-model="platformId" :loading="loadingPlatforms" placeholder="选择平台" class="select"
            ><template #loading><span class="cascade-loading">正在读取平台目录…</span></template
            ><ElOption v-for="item in platforms" :key="item.id" :label="`${item.name} · Tier ${item.tier}`" :value="item.id" /><template
              v-if="platformCursor"
              #footer
              ><ElButton link type="primary" @click="loadPlatforms(true)">加载更多平台</ElButton></template
            ></ElSelect
          ></ElFormItem
        ><ElFormItem label="数据源"
          ><ElSelect v-model="datasourceId" :loading="loadingDatasources" :disabled="!platformId" placeholder="选择数据源"
            ><template #loading><span class="cascade-loading">正在读取数据源目录…</span></template
            ><ElOption v-for="item in datasources" :key="item.id" :label="`${item.name} · ${item.engine}`" :value="item.id" /><template
              v-if="datasourceCursor"
              #footer
              ><ElButton link type="primary" @click="loadDatasources(true)">加载更多数据源</ElButton></template
            ></ElSelect
          ></ElFormItem
        ><ElFormItem label="命名空间"
          ><ElSelect v-model="namespaceId" :loading="loadingNamespaces" :disabled="!datasourceId" placeholder="全部命名空间"
            ><template #loading><span class="cascade-loading">正在读取命名空间…</span></template
            ><ElOption v-for="item in namespaces" :key="item.id" :label="item.displayName" :value="item.id" /><template v-if="namespaceCursor" #footer
              ><ElButton link type="primary" @click="loadNamespaces(true)">加载更多命名空间</ElButton></template
            ></ElSelect
          ></ElFormItem
        >
      </div></ElCard
    >
    <ElCard shadow="never" class="portal-card ptmcp-table-section">
      <h2 class="ptmcp-section-title">可见对象</h2>
      <div class="filters second ptmcp-filter-bar">
        <ElInput v-model="keyword" placeholder="搜索表名或业务说明" clearable class="keyword" @keyup.enter="loadObjects()" /><ElButton
          type="primary"
          plain
          :disabled="!datasourceId"
          @click="loadObjects()"
          >查询</ElButton
        >
      </div>
      <ElSkeleton v-if="loading && !objects.length" :rows="6" animated /><PageState
        v-else-if="!datasourceId"
        kind="idle"
        description="先选择平台和数据源，再读取可见对象。"
      /><PageState v-else-if="!objects.length && !loadError" kind="empty" description="当前筛选没有可见对象" /><ElTable
        v-else-if="objects.length"
        :data="objects"
        row-key="objectId"
        aria-label="个人数据字典"
        ><ElTableColumn label="对象" min-width="220"
          ><template #default="{ row }"
            ><button
              class="object-link"
              type="button"
              :disabled="!row.authorized"
              @click="router.push({ name: 'me-dictionary-detail', params: { objectId: row.objectId }, query: { platformId } })"
            >
              {{ row.tableName }}</button
            ><span class="subtext">{{ [row.databaseName, row.schemaName].filter(Boolean).join(' / ') }}</span></template
          ></ElTableColumn
        ><ElTableColumn prop="objectType" label="类型" width="100" /><ElTableColumn
          prop="tableComment"
          label="业务说明"
          min-width="260"
          show-overflow-tooltip
        /><ElTableColumn label="可读取" width="110"
          ><template #default="{ row }"
            ><ElTag :type="row.authorized ? 'success' : 'warning'">{{ row.authorized ? '是' : '仅目录可见' }}</ElTag></template
          ></ElTableColumn
        ></ElTable
      >
      <CursorFooter v-if="objectsLoaded" :count="objects.length" :has-more="!!nextCursor" :loading="loading" @more="loadObjects(true)" />
    </ElCard>
  </section>
</template>

<style scoped>
.subtext {
  color: var(--ptmcp-muted);
  font-size: 14px;
}
.portal-card {
  margin-bottom: 16px;
  border-color: var(--ptmcp-border);
}
.filters {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 16px;
}
.filters :deep(.el-form-item) {
  margin: 0;
}
.cascade-loading {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ptmcp-text-muted);
  font-size: 12px;
}
.select {
  min-width: 210px;
}
.filters :deep(.el-select) {
  width: 100%;
}
@media (max-width: 640px) {
  .filters {
    align-items: stretch;
    flex-direction: column;
  }
  .select {
    min-width: 0;
    width: 100%;
  }
}
.second {
  margin-bottom: 18px;
}
.keyword {
  max-width: 360px;
}
.object-link {
  display: block;
  padding: 0;
  border: 0;
  color: var(--ptmcp-accent);
  background: none;
  font: inherit;
  font-weight: 600;
  cursor: pointer;
  text-align: left;
}
.subtext {
  display: block;
  margin-top: 4px;
  font-size: 12px;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
