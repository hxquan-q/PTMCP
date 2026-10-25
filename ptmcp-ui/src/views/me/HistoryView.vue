<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { mePortalApi, type AuditSummary } from '../../services/mePortalApi';
import { errorText, formatDate } from '../portalUtils';

const router = useRouter();
const items = ref<AuditSummary[]>([]);
const loading = ref(false);
const loadError = ref('');
const nextCursor = ref<string | null>(null);
const now = new Date();
const weekAgo = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000);
const filters = reactive({ from: weekAgo.toISOString().slice(0, 16), to: now.toISOString().slice(0, 16), tool: '', result: '', verdict: '' });

function validWindow(): boolean {
  const from = new Date(filters.from).getTime();
  const to = new Date(filters.to).getTime();
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
  if (!validWindow()) return;
  loading.value = true;
  loadError.value = '';
  try {
    const page = await mePortalApi.history.list({
      from: new Date(filters.from).toISOString(),
      to: new Date(filters.to).toISOString(),
      tool: filters.tool || undefined,
      result: filters.result || undefined,
      verdict: filters.verdict || undefined,
      cursor: append ? (nextCursor.value ?? undefined) : undefined
    });
    items.value = append ? [...items.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
  } catch (error) {
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<template>
  <section class="portal-page" aria-labelledby="history-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">个人记录</p>
        <h1 id="history-title">我的查询历史</h1>
        <p class="page-description">仅显示本人脱敏记录。原始 SQL、结果集和下载能力不会在个人门户展示。</p>
      </div>
    </div>
    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError" /><ElCard shadow="never" class="portal-card"
      ><div class="filters">
        <ElFormItem label="开始时间"><ElDatePicker v-model="filters.from" type="datetime" value-format="YYYY-MM-DDTHH:mm" /></ElFormItem
        ><ElFormItem label="结束时间"><ElDatePicker v-model="filters.to" type="datetime" value-format="YYYY-MM-DDTHH:mm" /></ElFormItem
        ><ElFormItem label="工具"><ElInput v-model="filters.tool" clearable placeholder="可选" /></ElFormItem
        ><ElFormItem label="结果"><ElInput v-model="filters.result" clearable placeholder="可选" /></ElFormItem
        ><ElButton type="primary" @click="load()">查询</ElButton>
      </div>
      <ElSkeleton v-if="loading" :rows="6" animated /><ElEmpty v-else-if="!items.length" description="当前窗口没有查询记录" /><ElTable
        v-else
        :data="items"
        stripe
        row-key="requestId"
        aria-label="我的查询历史"
        ><ElTableColumn label="时间" min-width="170"
          ><template #default="{ row }">{{ formatDate(row.startedAt) }}</template></ElTableColumn
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
        ><ElTableColumn prop="durationMs" label="耗时(ms)" width="110" /><ElTableColumn label="详情" width="90"
          ><template #default="{ row }"
            ><ElButton link type="primary" @click="router.push({ name: 'me-history-detail', params: { requestId: row.requestId } })"
              >查看</ElButton
            ></template
          ></ElTableColumn
        ></ElTable
      >
      <div v-if="nextCursor" class="load-more"><ElButton link type="primary" @click="load(true)">加载更多</ElButton></div></ElCard
    >
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
.page-description {
  margin: 8px 0 0;
  color: var(--ptmcp-muted, #667085);
  font-size: 14px;
}
.portal-card {
  border-color: var(--ptmcp-border, #d9e2ec);
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
