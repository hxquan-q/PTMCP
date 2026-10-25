<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { mePortalApi, type DatasourceItem, type NamespaceItem, type ObjectSummary, type PlatformItem } from '../../services/mePortalApi';
import { errorText } from '../portalUtils';

const router = useRouter();
const platforms = ref<PlatformItem[]>([]);
const datasources = ref<DatasourceItem[]>([]);
const namespaces = ref<NamespaceItem[]>([]);
const objects = ref<ObjectSummary[]>([]);
const platformId = ref('');
const datasourceId = ref('');
const namespaceId = ref('');
const keyword = ref('');
const databaseName = ref('');
const schemaName = ref('');
const loading = ref(false);
const loadingContext = ref(false);
const loadError = ref('');
const nextCursor = ref<string | null>(null);
const namespaceCursor = ref<string | null>(null);

async function loadPlatforms(): Promise<void> {
  loadingContext.value = true;
  try {
    const page = await mePortalApi.accessContext.platforms();
    platforms.value = page.items;
    if (!platformId.value && page.items[0]) platformId.value = page.items[0].id;
  } catch (error) {
    loadError.value = errorText(error);
  } finally {
    loadingContext.value = false;
  }
}
async function loadDatasources(): Promise<void> {
  datasources.value = [];
  namespaces.value = [];
  datasourceId.value = '';
  namespaceId.value = '';
  objects.value = [];
  nextCursor.value = null;
  if (!platformId.value) return;
  try {
    datasources.value = (await mePortalApi.accessContext.datasources(platformId.value)).items;
  } catch (error) {
    ElMessage.error(errorText(error));
  }
}
async function loadNamespaces(): Promise<void> {
  namespaces.value = [];
  namespaceId.value = '';
  objects.value = [];
  namespaceCursor.value = null;
  nextCursor.value = null;
  if (!platformId.value || !datasourceId.value) return;
  try {
    const page = await mePortalApi.accessContext.namespaces(platformId.value, datasourceId.value);
    namespaces.value = page.items;
    namespaceCursor.value = page.nextCursor;
  } catch (error) {
    ElMessage.error(errorText(error));
  }
}
async function loadObjects(append = false): Promise<void> {
  if (!platformId.value || !datasourceId.value) {
    objects.value = [];
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    const page = await mePortalApi.dictionary.list({
      platformId: platformId.value,
      datasourceId: datasourceId.value,
      databaseName: databaseName.value || undefined,
      schemaName: schemaName.value || undefined,
      keyword: keyword.value.trim() || undefined,
      cursor: append ? (nextCursor.value ?? undefined) : undefined
    });
    objects.value = append ? [...objects.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
  } catch (error) {
    loadError.value = errorText(error);
    if (!append) objects.value = [];
  } finally {
    loading.value = false;
  }
}
watch(platformId, () => {
  void loadDatasources();
});
watch(datasourceId, () => {
  void loadNamespaces();
});
watch(namespaceId, (value) => {
  const item = namespaces.value.find((entry) => entry.id === value);
  databaseName.value = item?.databaseName ?? '';
  schemaName.value = item?.schemaName ?? '';
  void loadObjects();
});
onMounted(loadPlatforms);
</script>

<template>
  <section class="portal-page" aria-labelledby="dictionary-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">个人目录</p>
        <h1 id="dictionary-title">我的数据字典</h1>
        <p class="page-description">目录只展示你在当前平台范围内可发现的对象，读取详情仍由服务端再次鉴权。</p>
      </div>
    </div>
    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError" />
    <ElCard shadow="never" class="portal-card context-card"
      ><div class="filters">
        <ElFormItem label="平台"
          ><ElSelect v-model="platformId" :loading="loadingContext" placeholder="选择平台" class="select"
            ><ElOption v-for="item in platforms" :key="item.id" :label="`${item.name} · Tier ${item.tier}`" :value="item.id" /></ElSelect></ElFormItem
        ><ElFormItem label="数据源"
          ><ElSelect v-model="datasourceId" :disabled="!platformId" placeholder="选择数据源" class="select"
            ><ElOption v-for="item in datasources" :key="item.id" :label="`${item.name} · ${item.engine}`" :value="item.id" /></ElSelect></ElFormItem
        ><ElFormItem label="命名空间"
          ><ElSelect v-model="namespaceId" :disabled="!datasourceId" clearable placeholder="全部命名空间" class="select"
            ><ElOption v-for="item in namespaces" :key="item.id" :label="item.displayName" :value="item.id" /></ElSelect
        ></ElFormItem></div
    ></ElCard>
    <ElCard shadow="never" class="portal-card">
      <div class="filters second">
        <ElInput v-model="keyword" placeholder="搜索表名或业务说明" clearable class="keyword" @keyup.enter="loadObjects()" /><ElButton
          type="primary"
          plain
          :disabled="!datasourceId"
          @click="loadObjects()"
          >查询</ElButton
        >
      </div>
      <ElSkeleton v-if="loading" :rows="6" animated /><ElEmpty v-else-if="!datasourceId" description="先选择平台和数据源" /><ElEmpty
        v-else-if="!objects.length"
        description="当前筛选没有可见对象"
      /><ElTable v-else :data="objects" stripe row-key="objectId" aria-label="个人数据字典"
        ><ElTableColumn label="对象" min-width="220"
          ><template #default="{ row }"
            ><button
              class="object-link"
              type="button"
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
      <div v-if="nextCursor" class="load-more"><ElButton link type="primary" @click="loadObjects(true)">加载更多</ElButton></div>
    </ElCard>
  </section>
</template>

<style scoped>
.page-heading {
  margin-bottom: 24px;
}
.eyebrow {
  margin: 0 0 6px;
  color: var(--ptmcp-accent, #0070c9);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
h1 {
  margin: 0;
  color: var(--ptmcp-ink, #1d2939);
  font-size: 28px;
}
.page-description,
.subtext {
  color: var(--ptmcp-muted, #667085);
  font-size: 14px;
}
.page-description {
  margin: 8px 0 0;
}
.portal-card {
  margin-bottom: 16px;
  border-color: var(--ptmcp-border, #d9e2ec);
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
.select {
  min-width: 210px;
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
  color: var(--ptmcp-accent, #0070c9);
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
.load-more {
  padding-top: 16px;
  text-align: center;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
