<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { mePortalApi, type AuditSummary } from '../../services/mePortalApi';
import { errorText, formatDate, isAccessError, usePortalTask } from '../portalUtils';

const router = useRouter();
const items = ref<AuditSummary[]>([]);
const loading = ref(false);
const loadError = ref('');
const nextCursor = ref<string | null>(null);
const hasLoaded = ref(false);
const failedAppend = ref(false);
let readVersion = 0;
const now = new Date();
const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
const filters = reactive({ from: weekAgo as Date | null, to: now as Date | null, tool: '', result: '', verdict: '' });
const historyTask = usePortalTask(() => {
  readVersion += 1;
  items.value = [];
  nextCursor.value = null;
  loading.value = false;
  hasLoaded.value = false;
  loadError.value = '';
});
const hasCriteria = computed(() => Boolean(filters.tool || filters.result || filters.verdict));
const emptyText = computed(() => {
  if (!hasLoaded.value) return '筛选条件已修改，请点击查询';
  return hasCriteria.value ? '没有符合筛选条件的记录，可调整条件或重置查询' : '所选时间范围内暂无查询记录';
});
const inputTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone;

function validWindow(): boolean {
  const from = filters.from?.getTime() ?? NaN;
  const to = filters.to?.getTime() ?? NaN;
  if (!Number.isFinite(from) || !Number.isFinite(to) || to <= from) {
    ElMessage.warning('结束时间必须晚于开始时间');
    return false;
  }
  if (to - from > 31 * 24 * 60 * 60 * 1000) {
    ElMessage.warning('查询窗口不能超过 31 天');
    return false;
  }
  return true;
}
async function load(append = false): Promise<void> {
  if (loading.value || (append && !nextCursor.value)) return;
  if (!validWindow()) return;
  const ownVersion = ++readVersion;
  if (!append) {
    nextCursor.value = null;
    items.value = [];
    hasLoaded.value = false;
  }
  failedAppend.value = append;
  loading.value = true;
  loadError.value = '';
  try {
    const page = await historyTask.run((signal) =>
      mePortalApi.history.list(
        {
          from: filters.from?.toISOString(),
          to: filters.to?.toISOString(),
          tool: filters.tool || undefined,
          result: filters.result || undefined,
          verdict: filters.verdict || undefined,
          cursor: append ? (nextCursor.value ?? undefined) : undefined,
        },
        signal,
      ),
    );
    if (!page || ownVersion !== readVersion) return;
    items.value = append ? [...items.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
    hasLoaded.value = true;
  } catch (error) {
    if (ownVersion !== readVersion) return;
    if (isAccessError(error)) {
      items.value = [];
      nextCursor.value = null;
      failedAppend.value = false;
      hasLoaded.value = false;
    }
    loadError.value = errorText(error);
  } finally {
    if (ownVersion === readVersion) loading.value = false;
  }
}
async function retry(): Promise<void> {
  await load(failedAppend.value);
}
async function resetFilters(): Promise<void> {
  const end = new Date();
  Object.assign(filters, { from: new Date(end.getTime() - 7 * 24 * 60 * 60 * 1000), to: end, tool: '', result: '', verdict: '' });
  await load();
}
watch(
  () => [filters.from, filters.to, filters.tool, filters.result, filters.verdict],
  () => {
    readVersion += 1;
    historyTask.cancel();
    items.value = [];
    nextCursor.value = null;
    loading.value = false;
    hasLoaded.value = false;
    loadError.value = '';
  },
  { flush: 'sync' },
);
onMounted(() => {
  void load();
});
</script>

<template>
  <section class="portal-page" aria-labelledby="history-title">
    <div class="page-heading">
      <div>
        <h1 id="history-title">我的查询历史</h1>
        <p class="page-description">仅显示本人脱敏记录。原始 SQL、结果集和下载能力不会在个人门户展示。</p>
      </div>
    </div>
    <ElCard shadow="never" class="portal-card">
      <ElForm class="filters" label-position="top" @submit.prevent="load()">
        <ElFormItem label="开始时间（本机时区）"><ElDatePicker v-model="filters.from" type="datetime" /></ElFormItem
        ><ElFormItem label="结束时间（本机时区）"><ElDatePicker v-model="filters.to" type="datetime" /></ElFormItem
        ><ElFormItem label="工具"><ElInput v-model="filters.tool" clearable placeholder="可选" /></ElFormItem
        ><ElFormItem label="结果"><ElInput v-model="filters.result" clearable placeholder="可选" /></ElFormItem
        ><ElFormItem label="判定"><ElInput v-model="filters.verdict" clearable placeholder="可选" /></ElFormItem>
        <div class="filter-actions">
          <ElButton type="primary" native-type="submit" :loading="loading">查询</ElButton
          ><ElButton :disabled="loading" @click="resetFilters">重置</ElButton>
        </div>
      </ElForm>
      <p class="time-note">筛选时间：{{ inputTimezone }}；记录时间：Asia/Taipei。每次最多查询 31 天。</p>
      <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="failedAppend ? '加载更多失败，已保留当前记录' : '查询失败'">
        <p>{{ loadError }}</p>
        <ElButton :disabled="loading" @click="retry">{{ failedAppend ? '重试加载更多' : '重试查询' }}</ElButton>
      </ElAlert>
      <div :aria-busy="loading" aria-live="polite">
        <ElSkeleton v-if="loading && !items.length" :rows="6" animated /><ElEmpty
          v-else-if="!items.length && !loadError"
          :description="emptyText"
        /><ElTable v-else-if="items.length" :data="items" stripe row-key="requestId" aria-label="我的查询历史"
          ><ElTableColumn label="时间" min-width="170"
            ><template #default="{ row }">{{ formatDate(row.startedAt) }}</template></ElTableColumn
          ><ElTableColumn label="平台 / 数据源" min-width="160"
            ><template #default="{ row }">{{ row.platformId ?? '—' }} / {{ row.datasourceId ?? '—' }}</template></ElTableColumn
          ><ElTableColumn prop="tool" label="工具" width="130" /><ElTableColumn label="脱敏 SQL" min-width="260" show-overflow-tooltip
            ><template #default="{ row }"
              ><code>{{ row.sqlMasked || '—' }}</code></template
            ></ElTableColumn
          ><ElTableColumn prop="result" label="结果" width="110" /><ElTableColumn prop="verdict" label="判定" width="120" /><ElTableColumn
            label="行数"
            width="90"
            ><template #default="{ row }"
              >{{ row.rowsReturned ?? '—' }}<ElTag v-if="row.truncated" size="small" type="warning" class="truncated">已截断</ElTag></template
            ></ElTableColumn
          ><ElTableColumn prop="durationMs" label="耗时(ms)" width="110" /><ElTableColumn
            prop="requestId"
            label="requestId"
            min-width="220"
          /><ElTableColumn label="详情" width="90"
            ><template #default="{ row }"
              ><ElButton link type="primary" @click="router.push({ name: 'me-history-detail', params: { requestId: row.requestId } })"
                >查看</ElButton
              ></template
            ></ElTableColumn
          ></ElTable
        >
        <div v-if="nextCursor && !loadError" class="load-more"><ElButton :loading="loading" @click="load(true)">加载更多</ElButton></div>
        <p v-else-if="items.length && !loadError" class="time-note">已显示所选条件下的全部 {{ items.length }} 条记录</p>
      </div></ElCard
    >
  </section>
</template>

<style scoped>
.page-heading {
  margin-bottom: 24px;
}
h1 {
  margin: 0;
  color: var(--ptmcp-heading);
  font-size: 28px;
}
.page-description {
  margin: 8px 0 0;
  color: var(--ptmcp-muted);
  font-size: 14px;
}
.portal-card {
  border-color: var(--ptmcp-border);
}
.filters {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 18px;
}
.filters :deep(.el-form-item) {
  margin: 0;
  min-width: 0;
  flex: 1 1 180px;
}
.filters :deep(.el-input),
.filters :deep(.el-date-editor) {
  width: 100%;
}
.filter-actions {
  display: flex;
  padding-bottom: 1px;
}
.time-note {
  margin: 0 0 16px;
  color: var(--ptmcp-muted);
  font-size: 13px;
  line-height: 1.6;
}
.truncated {
  margin-left: 4px;
}
.load-more {
  padding-top: 16px;
  text-align: center;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
