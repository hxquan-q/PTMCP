<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
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

const filters = reactive({ userId: '', platformId: '', datasourceId: '', tool: '', result: '', verdict: '', errorCode: '' });
const from = ref<Date | null>(null);
const to = ref<Date | null>(null);
const validation = ref('');
const dateRange = computed(() => {
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
    <ElAlert type="warning" show-icon :closable="false" title="全局审计接口待接入" :description="localOnlyReason('全局审计')" />
    <ElCard class="filter-card" shadow="never">
      <div class="filter-heading">
        <div>
          <h2>查询范围</h2>
          <p>日期范围最多31天，按浏览器本地时区编辑；接入后以 UTC 传输、Asia/Taipei 展示。</p>
        </div>
        <span>{{ filterSummary }}</span>
      </div>
      <ElForm label-position="top" @submit.prevent="validateFilters"
        ><div class="filter-grid">
          <ElFormItem label="开始时间" required><ElDatePicker v-model="from" type="datetime" placeholder="选择日期和时间" /></ElFormItem>
          <ElFormItem label="结束时间" required :error="dateRange ?? undefined"
            ><ElDatePicker v-model="to" type="datetime" placeholder="选择日期和时间"
          /></ElFormItem>
          <ElFormItem label="员工"><ElInput v-model="filters.userId" clearable placeholder="按员工 ID 筛选" /></ElFormItem>
          <ElFormItem label="平台"><ElSelect v-model="filters.platformId" disabled placeholder="受控平台目录待接入" /></ElFormItem>
          <ElFormItem label="数据源"><ElSelect v-model="filters.datasourceId" disabled placeholder="审计范围目录待接入" /></ElFormItem>
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
        ><ElButton type="primary" disabled title="后端 audit:list 尚未提供">查询审计</ElButton>
      </div>
    </ElCard>
    <ElCard class="table-card" shadow="never">
      <div class="section-heading">
        <div>
          <h2>调用记录</h2>
          <p>列表应由服务端按审计范围过滤后返回；前端不先拉全量再筛选。</p>
        </div>
        <ElTag type="info" effect="plain">未连接</ElTag>
      </div>
      <ElTable :data="[]" class="empty-table" aria-label="调用记录">
        <ElTableColumn prop="startedAt" label="时间" min-width="170" />
        <ElTableColumn prop="user" label="员工" min-width="130" />
        <ElTableColumn prop="platform" label="平台" min-width="130" />
        <ElTableColumn prop="tool" label="工具" min-width="130" />
        <ElTableColumn prop="target" label="目标" min-width="180" />
        <ElTableColumn prop="sqlMasked" label="脱敏 SQL 摘要" min-width="210" />
        <ElTableColumn prop="result" label="结果" width="100" />
        <ElTableColumn prop="verdict" label="判定" width="100" />
        <ElTableColumn prop="errorCode" label="错误码" min-width="170" />
        <ElTableColumn prop="duration" label="耗时" width="100" />
        <ElTableColumn prop="rowsReturned" label="行数" width="100" />
        <ElTableColumn prop="requestId" label="Request ID" min-width="230" />
        <template #empty
          ><PageState title="审计记录尚未读取" description="全局审计服务尚未接入，暂时无法查询记录。此状态不代表没有审计记录。"
        /></template>
      </ElTable>
    </ElCard>
    <ElCard class="legend-card" shadow="never">
      <h2>字段说明</h2>
      <div class="legend-grid">
        <div><strong>脱敏 SQL 摘要</strong><span>只展示服务端返回的摘要，不允许在筛选框输入原 SQL。</span></div>
        <div><strong>UNKNOWN</strong><span>启动记录缺少终态时保留未知结论，不推断为失败。</span></div>
        <div><strong>解密</strong><span>当前没有 decrypt 端点，不提供原 SQL 输入或解密操作。</span></div>
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
  color: var(--ptmcp-text);
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
  background: #f8fafc;
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
