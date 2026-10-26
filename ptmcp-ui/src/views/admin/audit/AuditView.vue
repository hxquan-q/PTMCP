<script setup lang="ts">
import { computed, reactive, ref, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDatePicker,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag
} from 'element-plus';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import { localOnlyReason, validateDateRange } from '../integrations/adminSurface';
import { controlPlane, type Platform } from '../../../services/controlPlane';
import { controlPlaneAvailable } from '../../../services/frontendMode';
import { listDatasources, type DatasourceRead } from '../../../services/data';
import { useCursorPage } from '../../../composables/useCursorPage';
import { errorText, formatDate, usePortalTask } from '../../portalUtils';
import { useSessionStore } from '../../../stores/session';

const filters = reactive({ userId: '', platformId: '', datasourceId: '', tool: '', result: '', verdict: '', errorCode: '' });
const from = ref<Date | null>(new Date(Date.now() - 7 * 86400000));
const to = ref<Date | null>(new Date());
const validation = ref('');
const router = useRouter();
const session = useSessionStore();
const platforms = ref<Platform[]>([]);
const sources = ref<DatasourceRead[]>([]);
const directoryError = ref('');
const directoryTask = usePortalTask(() => {
  platforms.value = [];
  sources.value = [];
});
const { rows, cursor, loading, error, loaded, reset, load, retry } = useCursorPage((cursor, signal) =>
  controlPlane.audit.list({ ...filters, from: from.value?.toISOString(), to: to.value?.toISOString(), cursor }, signal)
);
async function query(): Promise<void> {
  validateFilters();
  if (!from.value || !to.value || dateRange.value || !controlPlaneAvailable) return;
  await load();
}
async function loadDirectories(): Promise<void> {
  try {
    const result = await directoryTask.run(async (signal) =>
      Promise.all([controlPlane.platforms.list({ pageSize: '100' }, signal), listDatasources({ signal, pageSize: 100 })])
    );
    if (result) {
      platforms.value = result[0].items;
      sources.value = result[1].data.items;
      directoryError.value = '';
    }
  } catch (reason) {
    directoryError.value = errorText(reason);
  }
}
watch(
  [filters, from, to],
  () => {
    reset();
    validation.value = '';
  },
  { flush: 'sync' }
);
onMounted(() => {
  if (controlPlaneAvailable) {
    void query();
    void loadDirectories();
  }
});
const dateRange = computed(() => {
  const start = from.value?.getTime();
  const end = to.value?.getTime();
  if ((start !== undefined && !Number.isFinite(start)) || (end !== undefined && !Number.isFinite(end))) return '日期格式无效。';
  return validateDateRange(from.value?.toISOString() ?? '', to.value?.toISOString() ?? '');
});
const filterSummary = computed(() =>
  Object.values(filters).filter(Boolean).length ? '筛选条件已准备，查询接口接入后才会发送。' : '未设置筛选条件。'
);
function validateFilters(): void {
  validation.value =
    !from.value || !to.value ? '请选择开始和结束时间，单次查询不超过31天。' : (dateRange.value ?? '筛选校验完成。审计服务尚未接入，查询暂不可用。');
}
function clearFilters(): void {
  Object.keys(filters).forEach((key) => {
    filters[key as keyof typeof filters] = '';
  });
  from.value = null;
  to.value = null;
  validation.value = '';
}
</script>

<template>
  <div class="admin-page">
    <PageHeader title="全局审计" description="按时间、员工、平台和结果追踪工具调用；默认只展示脱敏摘要和可见版本。" />
    <ElAlert
      v-if="!controlPlaneAvailable"
      type="warning"
      show-icon
      :closable="false"
      title="全局审计接口待接入"
      :description="localOnlyReason('全局审计')"
    />
    <ElAlert v-if="directoryError" type="error" :closable="false" :title="directoryError"
      ><ElButton @click="loadDirectories">重试加载选项</ElButton></ElAlert
    >
    <ElCard class="filter-card" shadow="never">
      <div class="filter-heading">
        <div>
          <h2>查询范围</h2>
          <p>日期范围最多31天，按浏览器本地时区编辑；接入后以 UTC 传输、Asia/Taipei 展示。</p>
        </div>
        <span>{{ filterSummary }}</span>
      </div>
      <ElForm label-position="top" @submit.prevent="query"
        ><div class="filter-grid">
          <ElFormItem label="开始时间" required><ElDatePicker v-model="from" type="datetime" placeholder="选择日期和时间" /></ElFormItem>
          <ElFormItem label="结束时间" required :error="dateRange ?? undefined"
            ><ElDatePicker v-model="to" type="datetime" placeholder="选择日期和时间"
          /></ElFormItem>
          <ElFormItem label="员工"><ElInput v-model="filters.userId" clearable placeholder="按员工 ID 筛选" /></ElFormItem>
          <ElFormItem label="平台"
            ><ElSelect v-model="filters.platformId" :disabled="!controlPlaneAvailable || !!directoryError" clearable placeholder="全部可见平台"
              ><ElOption v-for="row in platforms" :key="row.id" :value="row.id" :label="row.name" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="数据源"
            ><ElSelect v-model="filters.datasourceId" :disabled="!controlPlaneAvailable || !!directoryError" clearable placeholder="全部可见数据源"
              ><ElOption v-for="row in sources" :key="row.id" :value="row.id" :label="row.name" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="工具"><ElInput v-model="filters.tool" clearable placeholder="工具名称" /></ElFormItem>
          <ElFormItem label="结果"
            ><ElSelect v-model="filters.result" clearable placeholder="全部结果"
              ><ElOption label="执行中" value="RUNNING" /><ElOption label="成功" value="SUCCESS" /><ElOption label="错误" value="ERROR" /><ElOption
                label="未知"
                value="UNKNOWN" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="判定"
            ><ElSelect v-model="filters.verdict" clearable placeholder="全部判定"
              ><ElOption label="允许" value="ALLOW" /><ElOption label="拒绝" value="DENY" /><ElOption label="错误" value="ERROR" /><ElOption
                label="未知"
                value="UNKNOWN" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="错误码"><ElInput v-model="filters.errorCode" clearable placeholder="稳定错误码" /></ElFormItem></div
      ></ElForm>
      <div class="filter-actions">
        <span v-if="validation" class="validation-message" role="status">{{ validation }}</span
        ><ElButton @click="clearFilters">清空</ElButton><ElButton @click="validateFilters">校验筛选</ElButton
        ><ElButton type="primary" :disabled="!controlPlaneAvailable || !!dateRange" :loading="loading" @click="query">查询审计</ElButton>
      </div>
    </ElCard>
    <ElCard class="table-card" shadow="never">
      <div class="section-heading">
        <div>
          <h2>调用记录</h2>
          <p>列表应由服务端按审计范围过滤后返回；前端不先拉全量再筛选。</p>
        </div>
        <ElTag type="info" effect="plain">{{ controlPlaneAvailable ? '开发预览' : '未连接' }}</ElTag>
      </div>
      <ElAlert v-if="error" :title="error" type="error" :closable="false"><ElButton @click="retry">重试</ElButton></ElAlert>
      <ElSkeleton v-if="loading && !rows.length" :rows="6" animated />
      <ElTable v-else :data="rows" class="empty-table" aria-label="调用记录">
        <ElTableColumn label="时间（台北）" min-width="170"
          ><template #default="{ row }">{{ formatDate(row.startedAt) }}</template></ElTableColumn
        >
        <ElTableColumn prop="userId" label="员工" min-width="130" />
        <ElTableColumn prop="platformId" label="平台" min-width="130" />
        <ElTableColumn prop="tool" label="工具" min-width="130" />
        <ElTableColumn prop="datasourceId" label="数据源" min-width="180" />
        <ElTableColumn prop="sqlMasked" label="脱敏 SQL 摘要" min-width="210" />
        <ElTableColumn prop="result" label="结果" width="100" />
        <ElTableColumn prop="verdict" label="判定" width="100" />
        <ElTableColumn prop="errorCode" label="错误码" min-width="170" />
        <ElTableColumn prop="durationMs" label="耗时(ms)" width="100" />
        <ElTableColumn prop="rowsReturned" label="行数" width="100" />
        <ElTableColumn prop="requestId" label="Request ID" min-width="230" />
        <ElTableColumn label="详情" width="90"
          ><template #default="{ row }"
            ><ElButton
              v-if="session.can('ptmcp:audit:read')"
              link
              type="primary"
              @click="router.push(`/admin/audit/${encodeURIComponent(String(row.requestId))}`)"
              >查看</ElButton
            ></template
          ></ElTableColumn
        >
        <template #empty
          ><PageState :title="loaded ? '当前条件下没有审计记录' : '审计记录尚未读取'" description="可调整时间和筛选条件；未读取不代表没有记录。"
        /></template>
      </ElTable>
      <ElButton v-if="cursor" :loading="loading" @click="load(true)">加载更多</ElButton>
    </ElCard>
    <ElCard class="legend-card" shadow="never">
      <h2>字段说明</h2>
      <div class="legend-grid">
        <div><strong>脱敏 SQL 摘要</strong><span>只展示服务端返回的摘要，不允许在筛选框输入原 SQL。</span></div>
        <div><strong>UNKNOWN</strong><span>启动记录缺少终态时保留未知结论，不推断为失败。</span></div>
        <div><strong>解密</strong><span>需要独立解密权限及理由，明文只限时展示；正常模式在接口未接入时禁用。</span></div>
      </div>
    </ElCard>
  </div>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}
.filter-card,
.table-card,
.legend-card {
  border-radius: 8px;
}
.filter-heading,
.section-heading {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: flex-start;
}
h2 {
  margin: 0;
  color: var(--ptmcp-heading);
  font-size: 16px;
}
.filter-heading p,
.section-heading p,
.filter-heading > span,
.legend-grid span {
  margin: 6px 0 0;
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.filter-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(150px, 1fr));
  gap: 10px;
  margin-top: 18px;
}
.filter-grid :deep(.el-date-editor),
.filter-grid .el-select {
  width: 100%;
  min-width: 0;
}
.filter-grid .el-form-item {
  margin-bottom: 8px;
}
.filter-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
}
.empty-table {
  margin-top: 16px;
}
.validation-message {
  margin-right: auto;
  color: var(--ptmcp-text-muted);
  font-size: 12px;
}
.legend-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 14px;
}
.legend-grid div {
  display: grid;
  gap: 5px;
  padding: 13px;
  border: 1px solid var(--ptmcp-border);
  border-radius: 6px;
  background: var(--ptmcp-chrome);
}
.legend-grid strong {
  color: var(--ptmcp-text);
  font-size: 13px;
}
.detail-link {
  display: inline-block;
  margin-top: 16px;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
  text-decoration: none;
}
@media (max-width: 850px) {
  .filter-grid {
    grid-template-columns: repeat(2, minmax(150px, 1fr));
  }
  .legend-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 520px) {
  .filter-grid {
    grid-template-columns: 1fr;
  }
  .filter-heading,
  .section-heading {
    flex-direction: column;
  }
}
</style>
