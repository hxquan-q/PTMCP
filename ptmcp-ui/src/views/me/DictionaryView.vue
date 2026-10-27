<script setup lang="ts">
import CursorFooter from '../../components/common/CursorFooter.vue';
import PageHeader from '../../components/common/PageHeader.vue';
import { onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { mePortalApi, type DatasourceItem, type NamespaceItem, type ObjectSummary, type PlatformItem } from '../../services/mePortalApi';
import { errorText, usePortalTask } from '../portalUtils';

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
const platformCursor = ref<string | null>(null);
const datasourceCursor = ref<string | null>(null);
const platformTask = usePortalTask(() => {
  platforms.value = [];
  platformId.value = '';
  platformCursor.value = null;
});
const datasourceTask = usePortalTask(() => {
  datasources.value = [];
  datasourceId.value = '';
  datasourceCursor.value = null;
});
const namespaceTask = usePortalTask(() => {
  namespaces.value = [];
  namespaceId.value = '';
  namespaceCursor.value = null;
});
const objectTask = usePortalTask(() => {
  objects.value = [];
  nextCursor.value = null;
});

function clearObjects(): void {
  objectTask.cancel();
  objects.value = [];
  nextCursor.value = null;
  loading.value = false;
}

async function loadPlatforms(append = false): Promise<void> {
  loadingContext.value = true;
  try {
    const page = await platformTask.run((signal) =>
      mePortalApi.accessContext.platforms(append ? (platformCursor.value ?? undefined) : undefined, signal),
    );
    if (!page) return;
    platforms.value = append ? [...platforms.value, ...page.items] : page.items;
    platformCursor.value = page.nextCursor;
    if (!platformId.value && page.items[0]) platformId.value = page.items[0].id;
  } catch (error) {
    loadError.value = errorText(error);
  } finally {
    loadingContext.value = false;
  }
}
async function loadDatasources(append = false): Promise<void> {
  if (!append) {
    datasourceTask.cancel();
    namespaceTask.cancel();
    clearObjects();
    datasources.value = [];
    namespaces.value = [];
    datasourceId.value = '';
    namespaceId.value = '';
    datasourceCursor.value = null;
    namespaceCursor.value = null;
    databaseName.value = '';
    schemaName.value = '';
  }
  if (!platformId.value) return;
  try {
    const page = await datasourceTask.run((signal) =>
      mePortalApi.accessContext.datasources(platformId.value, append ? (datasourceCursor.value ?? undefined) : undefined, signal),
    );
    if (!page) return;
    datasources.value = append ? [...datasources.value, ...page.items] : page.items;
    datasourceCursor.value = page.nextCursor;
  } catch (error) {
    ElMessage.error(errorText(error));
  }
}
async function loadNamespaces(append = false): Promise<void> {
  if (!append) {
    namespaceTask.cancel();
    clearObjects();
    namespaces.value = [];
    namespaceId.value = '';
    namespaceCursor.value = null;
    databaseName.value = '';
    schemaName.value = '';
  }
  if (!platformId.value || !datasourceId.value) return;
  try {
    const page = await namespaceTask.run((signal) =>
      mePortalApi.accessContext.namespaces(platformId.value, datasourceId.value, append ? (namespaceCursor.value ?? undefined) : undefined, signal),
    );
    if (!page) return;
    namespaces.value = append ? [...namespaces.value, ...page.items] : page.items;
    namespaceCursor.value = page.nextCursor;
  } catch (error) {
    ElMessage.error(errorText(error));
  }
}
async function loadObjects(append = false): Promise<void> {
  if (!append) {
    nextCursor.value = null;
    objects.value = [];
  }
  if (!platformId.value || !datasourceId.value) {
    objects.value = [];
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    const page = await objectTask.run((signal) =>
      mePortalApi.dictionary.list(
        {
          platformId: platformId.value,
          datasourceId: datasourceId.value,
          databaseName: databaseName.value || undefined,
          schemaName: schemaName.value || undefined,
          keyword: keyword.value.trim() || undefined,
          cursor: append ? (nextCursor.value ?? undefined) : undefined,
        },
        signal,
      ),
    );
    if (!page) return;
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
  void loadObjects();
});
watch(namespaceId, (value) => {
  const item = namespaces.value.find((entry) => entry.id === value);
  databaseName.value = item?.databaseName ?? '';
  schemaName.value = item?.schemaName ?? '';
  void loadObjects();
});
watch(keyword, clearObjects);
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
    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError" />
    <ElCard shadow="never" class="portal-card context-card ptmcp-filter-bar"
      ><div class="filters">
        <ElFormItem label="平台"
          ><ElSelect v-model="platformId" :loading="loadingContext" placeholder="选择平台" class="select"
            ><ElOption v-for="item in platforms" :key="item.id" :label="`${item.name} · Tier ${item.tier}`" :value="item.id" /><template
              v-if="platformCursor"
              #footer
              ><ElButton link type="primary" @click="loadPlatforms(true)">加载更多平台</ElButton></template
            ></ElSelect
          ></ElFormItem
        ><ElFormItem label="数据源"
          ><ElSelect v-model="datasourceId" :disabled="!platformId" placeholder="选择数据源" class="select"
            ><ElOption v-for="item in datasources" :key="item.id" :label="`${item.name} · ${item.engine}`" :value="item.id" /><template
              v-if="datasourceCursor"
              #footer
              ><ElButton link type="primary" @click="loadDatasources(true)">加载更多数据源</ElButton></template
            ></ElSelect
          ></ElFormItem
        ><ElFormItem label="命名空间"
          ><ElSelect v-model="namespaceId" :disabled="!datasourceId" clearable placeholder="全部命名空间" class="select"
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
      <ElSkeleton v-if="loading" :rows="6" animated /><ElEmpty v-else-if="!datasourceId" description="先选择平台和数据源" /><ElEmpty
        v-else-if="!objects.length"
        description="当前筛选没有可见对象"
      /><ElTable v-else :data="objects" stripe row-key="objectId" aria-label="个人数据字典"
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
      <CursorFooter :count="objects.length" :has-more="!!nextCursor" :loading="loading" @more="loadObjects(true)" />
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
