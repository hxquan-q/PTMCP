<script setup lang="ts">
import { computed, nextTick, reactive, ref, onMounted, watch } from 'vue';
import { onBeforeRouteLeave, useRouter } from 'vue-router';
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
  ElTag,
} from 'element-plus';
import CursorFooter from '../../../components/common/CursorFooter.vue';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import StatusBadge from '../../../components/common/StatusBadge.vue';
import { validateDateRange } from '../integrations/adminSurface';
import { controlPlane, type Platform } from '../../../services/controlPlane';
import { auditApiReady, frontendPreview } from '../../../services/frontendMode';
import { listDatasources, type DatasourceRead } from '../../../services/data';
import { useRecordPreview } from '../../../composables/useRecordPreview';
import { queryErrorText } from '../../../services/queryErrorText';
import { useCursorPage } from '../../../composables/useCursorPage';
import { errorText, formatDate, usePortalTask } from '../../portalUtils';
import { useSessionStore } from '../../../stores/session';
import { forgetAuditQuery, recallAuditQuery, rememberAuditQuery } from './auditQueryMemory';

const filters = reactive({ userId: '', platformId: '', datasourceId: '', tool: '', result: '', verdict: '', errorCode: '' });
const from = ref<Date | null>(new Date(Date.now() - 7 * 86400000));
const to = ref<Date | null>(new Date());
const router = useRouter();
const session = useSessionStore();
/** 审计列表/详情/解密后端已实现（T17-api 切片）；预览模式继续用合成数据。 */
const auditReady = auditApiReady;
const platforms = ref<Platform[]>([]);
const sources = ref<DatasourceRead[]>([]);
const directoryError = ref('');
const directoryTask = usePortalTask(() => {
  platforms.value = [];
  sources.value = [];
});
const { rows, cursor, loading, error, requestId, loaded, reset, restore, load, retry } = useCursorPage((cursor, signal) =>
  controlPlane.audit.list({ ...filters, from: from.value?.toISOString(), to: to.value?.toISOString(), cursor }, signal),
);
const {
  selectedId: quickId,
  item: quickItem,
  loading: quickLoading,
  error: quickError,
  requestId: quickRequestId,
  clear: clearQuick,
  select: selectQuick,
  retry: retryQuick,
} = useRecordPreview(controlPlane.audit.get);
const quickIndex = computed(() => rows.value.findIndex((row) => row.requestId === quickId.value));
function showQuick(row: unknown): void {
  if (!row || typeof row !== 'object' || !('requestId' in row) || typeof row.requestId !== 'string') return;
  if (!session.can('ptmcp:audit:read') || (quickLoading.value && quickId.value === row.requestId)) return;
  void selectQuick(row.requestId);
}
function stepQuick(offset: number): void {
  const row = rows.value[quickIndex.value + offset];
  if (row) showQuick(row);
}
watch(rows, () => {
  if (quickId.value && !rows.value.some((row) => row.requestId === quickId.value)) clearQuick();
});
async function query(): Promise<void> {
  if (!from.value || !to.value || dateRange.value || !auditReady) return;
  await load();
}
async function loadDirectories(): Promise<void> {
  try {
    const result = await directoryTask.run(async (signal) =>
      Promise.all([controlPlane.platforms.list({ pageSize: '100' }, signal), listDatasources({ signal, pageSize: 100 })]),
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
let restoring = false;
watch(
  [filters, from, to],
  () => {
    if (restoring) return;
    reset();
    clearQuick();
  },
  { flush: 'sync' },
);
onMounted(async () => {
  if (!auditReady) return;
  const state = recallAuditQuery();
  if (state) {
    restoring = true;
    Object.assign(filters, state.filters);
    from.value = state.from ? new Date(state.from) : null;
    to.value = state.to ? new Date(state.to) : null;
    await nextTick();
    restoring = false;
    restore({ rows: state.rows, cursor: state.cursor, loaded: state.loaded });
    if (state.quickId) void selectQuick(state.quickId);
    requestAnimationFrame(() => window.scrollTo({ top: state.scrollY }));
  } else void query();
  void loadDirectories();
});
onBeforeRouteLeave((destination) => {
  // Only the detail round trip is continuous; unrelated navigation starts fresh.
  if (destination.name !== 'admin-audit-detail') {
    forgetAuditQuery();
    return;
  }
  rememberAuditQuery({
    filters: { ...filters },
    from: from.value?.getTime() ?? null,
    to: to.value?.getTime() ?? null,
    rows: rows.value,
    cursor: cursor.value,
    loaded: loaded.value,
    quickId: quickId.value,
    scrollY: window.scrollY,
  });
});
const dateRange = computed(() => {
  const start = from.value?.getTime();
  const end = to.value?.getTime();
  if ((start !== undefined && !Number.isFinite(start)) || (end !== undefined && !Number.isFinite(end))) return '日期格式无效。';
  return validateDateRange(from.value?.toISOString() ?? '', to.value?.toISOString() ?? '');
});
const filterSummary = computed(() => (Object.values(filters).filter(Boolean).length ? '已设置筛选条件；修改后需重新查询。' : '未设置额外筛选条件。'));
function clearFilters(): void {
  Object.keys(filters).forEach((key) => {
    filters[key as keyof typeof filters] = '';
  });
  from.value = null;
  to.value = null;
}
const advancedOpen = ref(false);
const advancedCount = computed(() => [filters.userId, filters.platformId, filters.datasourceId, filters.errorCode].filter(Boolean).length);
function platformName(id: string | null): string {
  return platforms.value.find((row) => row.id === id)?.name ?? id ?? '—';
}
function sourceName(id: string | null): string {
  return sources.value.find((row) => row.id === id)?.name ?? id ?? '—';
}
</script>

<template>
  <div class="admin-page">
    <PageHeader title="全局审计" description="按时间、员工、平台和结果追踪工具调用；默认只展示脱敏摘要和可见版本。" />
    <ElAlert v-if="directoryError" type="error" :closable="false" :title="directoryError"
      ><ElButton @click="loadDirectories">重试加载选项</ElButton></ElAlert
    >
    <ElCard class="filter-card ptmcp-filter-bar" shadow="never">
      <div class="filter-heading">
        <div>
          <h2>查询范围</h2>
          <p>日期范围最多31天，按浏览器本地时区编辑；以 UTC 传输、Asia/Taipei 展示。</p>
        </div>
        <span>{{ filterSummary }}</span>
      </div>
      <ElForm label-position="top" @submit.prevent="query">
        <div class="filter-grid">
          <ElFormItem label="开始时间" required><ElDatePicker v-model="from" type="datetime" placeholder="选择日期和时间" /></ElFormItem>
          <ElFormItem label="结束时间" required :error="dateRange ?? undefined"
            ><ElDatePicker v-model="to" type="datetime" placeholder="选择日期和时间"
          /></ElFormItem>
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
        </div>
        <div v-show="advancedOpen" class="filter-grid advanced-grid">
          <ElFormItem label="员工 ID"><ElInput v-model="filters.userId" clearable placeholder="按员工 ID 筛选" /></ElFormItem>
          <ElFormItem label="平台"
            ><ElSelect v-model="filters.platformId" :disabled="!auditReady || !!directoryError" clearable placeholder="全部可见平台"
              ><ElOption v-for="row in platforms" :key="row.id" :value="row.id" :label="row.name" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="数据源"
            ><ElSelect v-model="filters.datasourceId" :disabled="!auditReady || !!directoryError" clearable placeholder="全部可见数据源"
              ><ElOption v-for="row in sources" :key="row.id" :value="row.id" :label="row.name" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="错误码"><ElInput v-model="filters.errorCode" clearable placeholder="稳定错误码" /></ElFormItem>
        </div>
      </ElForm>
      <div class="filter-actions">
        <ElButton @click="advancedOpen = !advancedOpen"
          >{{ advancedOpen ? '收起高级筛选' : '高级筛选' }}<span v-if="advancedCount" class="advanced-count">{{ advancedCount }}</span></ElButton
        ><ElButton @click="clearFilters">清空</ElButton
        ><ElButton type="primary" :disabled="!auditReady || !!dateRange" :loading="loading" @click="query">查询审计</ElButton>
      </div>
    </ElCard>
    <div class="audit-workspace" :class="{ 'has-preview': session.can('ptmcp:audit:read') && !!quickId }">
      <ElCard class="table-card ptmcp-table-section ptmcp-table-region" shadow="never">
        <div class="section-heading">
          <div>
            <h2>调用记录</h2>
            <p>列表应由服务端按审计范围过滤后返回；前端不先拉全量再筛选。</p>
          </div>
          <ElTag v-if="frontendPreview || !auditReady" type="info" effect="plain">{{ frontendPreview ? '合成数据预览' : '审计服务未就绪' }}</ElTag>
          <span v-else>已加载 {{ rows.length }} 条</span>
        </div>
        <p class="ptmcp-scroll-hint">左右滑动表格查看结果、判定与操作列。</p>
        <PageState v-if="error" kind="error" title="审计读取失败" :description="error" :request-id="requestId" action-label="重试" @action="retry" />
        <ElSkeleton v-if="loading && !rows.length" :rows="6" animated />
        <ElTable
          v-else-if="!error || rows.length"
          :data="rows"
          class="empty-table audit-table"
          aria-label="调用记录"
          row-key="requestId"
          scrollbar-tabindex="0"
          highlight-current-row
          :current-row-key="quickId"
          @row-click="showQuick"
        >
          <ElTableColumn label="时间（台北）" width="150"
            ><template #default="{ row }">{{ formatDate(row.startedAt) }}</template></ElTableColumn
          >
          <ElTableColumn label="工具 / 请求" width="150"
            ><template #default="{ row }"
              ><span class="cell-primary">{{ row.tool }}</span
              ><span class="cell-secondary" :title="row.requestId">{{ row.requestId }}</span></template
            ></ElTableColumn
          >
          <ElTableColumn label="结果" width="120"
            ><template #default="{ row }"><StatusBadge domain="execution" :value="row.result" /></template
          ></ElTableColumn>
          <ElTableColumn label="判定" width="120"
            ><template #default="{ row }"><StatusBadge domain="verdict" :value="row.verdict" /></template
          ></ElTableColumn>
          <ElTableColumn label="员工" width="90"
            ><template #default="{ row }">{{ row.userId || '—' }}</template></ElTableColumn
          >
          <ElTableColumn label="平台" width="120"
            ><template #default="{ row }">{{ platformName(row.platformId) }}</template></ElTableColumn
          >
          <ElTableColumn label="数据源" width="130"
            ><template #default="{ row }">{{ sourceName(row.datasourceId) }}</template></ElTableColumn
          >
          <ElTableColumn label="错误码" width="120"
            ><template #default="{ row }">{{ row.errorCode || '—' }}</template></ElTableColumn
          >
          <ElTableColumn label="操作" width="70" align="right" fixed="right"
            ><template #default="{ row }"
              ><ElButton
                v-if="session.can('ptmcp:audit:read')"
                link
                type="primary"
                :aria-label="`速览调用 ${row.requestId}`"
                @click.stop="showQuick(row)"
                >速览</ElButton
              ></template
            ></ElTableColumn
          >
          <template #empty
            ><PageState :title="loaded ? '当前条件下没有审计记录' : '审计记录尚未读取'" description="可调整时间和筛选条件；未读取不代表没有记录。"
          /></template>
        </ElTable>
        <CursorFooter v-if="loaded || rows.length" :count="rows.length" :has-more="!!cursor" :loading="loading" @more="load(true)" />
      </ElCard>
      <aside v-if="session.can('ptmcp:audit:read') && quickId" class="audit-preview" aria-label="审计排障速览" :aria-busy="quickLoading">
        <div class="section-heading">
          <h2>排障速览</h2>
          <ElButton text @click="clearQuick">关闭速览</ElButton>
        </div>
        <div class="preview-navigation">
          <ElButton :disabled="quickIndex <= 0" @click="stepQuick(-1)">上一条</ElButton
          ><ElButton :disabled="quickIndex < 0 || quickIndex >= rows.length - 1" @click="stepQuick(1)">下一条</ElButton>
        </div>
        <PageState
          v-if="quickError"
          kind="error"
          title="速览读取失败"
          :description="quickError"
          :request-id="quickRequestId"
          action-label="重试速览"
          @action="retryQuick"
        />
        <ElSkeleton v-else-if="quickLoading" :rows="6" animated />
        <template v-else-if="quickItem">
          <p class="preview-request">{{ quickItem.requestId }}</p>
          <div class="preview-status">
            <StatusBadge domain="execution" :value="quickItem.result" /><StatusBadge domain="verdict" :value="quickItem.verdict" />
          </div>
          <ElDescriptions :column="1" border>
            <ElDescriptionsItem label="开始时间（台北）">{{ formatDate(quickItem.startedAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="总耗时">{{ quickItem.durationMs ?? '未提供' }} ms</ElDescriptionsItem>
            <ElDescriptionsItem label="排队">{{ quickItem.queueMs ?? '未提供' }} ms</ElDescriptionsItem>
            <ElDescriptionsItem label="数据库">{{ quickItem.dbMs ?? '未提供' }} ms</ElDescriptionsItem>
            <ElDescriptionsItem label="错误码">{{ quickItem.errorCode ?? '无' }}</ElDescriptionsItem>
          </ElDescriptions>
          <p v-if="queryErrorText(quickItem.errorCode)" class="preview-guidance">{{ queryErrorText(quickItem.errorCode) }}</p>
          <p v-if="quickItem.verdict === 'DENY'" class="preview-guidance">权限拒绝不代表系统故障。核对平台 Scope、用户授权和 DENY 规则。</p>
          <p v-if="quickItem.result === 'UNKNOWN' || quickItem.result === 'START'" class="preview-guidance">
            执行结局不确定，不能认定未执行或直接重试。
          </p>
          <h3>脱敏 SQL</h3>
          <pre class="preview-sql">{{ quickItem.sqlMasked || '服务端未提供脱敏摘要' }}</pre>
          <ElButton type="primary" @click="router.push(`/admin/audit/${encodeURIComponent(quickItem.requestId)}`)">{{
            session.can('ptmcp:audit:decrypt') ? '完整详情 / 申请解密' : '完整详情'
          }}</ElButton>
        </template>
      </aside>
    </div>
    <section class="legend-card" aria-labelledby="audit-legend-title">
      <h2 id="audit-legend-title">字段说明</h2>
      <div class="legend-grid">
        <div><strong>脱敏 SQL 摘要</strong><span>只展示服务端返回的摘要，不允许在筛选框输入原 SQL。</span></div>
        <div><strong>UNKNOWN</strong><span>启动记录缺少终态时保留未知结论，不推断为失败。</span></div>
        <div><strong>解密</strong><span>需要独立解密权限及理由，明文只限时展示，关闭或离开详情立即清除。</span></div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.audit-workspace {
  display: grid;
  min-width: 0;
  gap: 20px;
}
.audit-workspace.has-preview {
  grid-template-columns: minmax(0, 1fr) minmax(280px, 340px);
}
.table-card,
.audit-preview {
  min-width: 0;
}
.audit-preview {
  padding: 20px;
  background: var(--ptmcp-chrome);
}
.preview-navigation,
.preview-status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0;
}
.preview-request,
.preview-guidance,
.preview-sql {
  overflow-wrap: anywhere;
  font-size: 13px;
  line-height: 1.6;
}
.preview-guidance {
  color: var(--ptmcp-text-muted);
}
.preview-sql {
  white-space: pre-wrap;
  padding: 12px;
  background: var(--ptmcp-canvas);
}
.audit-preview h3 {
  font-size: 14px;
  margin-top: 20px;
}
@media (max-width: 1100px) {
  .audit-workspace.has-preview {
    grid-template-columns: minmax(0, 1fr);
  }
}
.admin-page {
  display: grid;
  gap: 16px;
}
.filter-card,
.table-card,
.legend-card {
  border-radius: 8px;
}
.legend-card {
  border: 0;
  border-top: 1px solid var(--ptmcp-border);
  padding: 20px 0 0;
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
  grid-template-columns: repeat(5, minmax(140px, 1fr));
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
.advanced-grid {
  margin-top: 0;
  padding-top: 10px;
  border-top: 1px solid var(--ptmcp-border);
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
.audit-table :deep(.status-badge) {
  white-space: nowrap;
}
.advanced-count {
  display: inline-grid;
  place-items: center;
  min-width: 18px;
  height: 18px;
  margin-left: 6px;
  border-radius: 9px;
  background: var(--ptmcp-blue);
  color: #fff;
  font-size: 11px;
}
.cell-primary,
.cell-secondary {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cell-primary {
  color: var(--ptmcp-text);
}
.cell-secondary {
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
  padding: 13px 0;
  border-top: 1px solid var(--ptmcp-border);
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
