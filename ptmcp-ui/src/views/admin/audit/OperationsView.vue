<script setup lang="ts">
import { computed, reactive, ref, watch, onMounted } from 'vue';
import CursorFooter from '../../../components/common/CursorFooter.vue';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import { controlPlane } from '../../../services/controlPlane';
import { auditApiReady } from '../../../services/frontendMode';
import { useCursorPage } from '../../../composables/useCursorPage';
import { formatDate } from '../../portalUtils';
import { OPERATION_AGGREGATE_TYPES, operationActionText, operationAggregateText } from '../../../services/operationLabels';

const ready = auditApiReady;
const filter = reactive({ action: '', aggregateType: '', actorId: '' });
const cleaned = computed(() => ({
  action: filter.action.trim() || undefined,
  aggregateType: filter.aggregateType || undefined,
  actorId: filter.actorId.trim() || undefined,
}));
interface OperationsRowView {
  id: string;
  actorId: string;
  requestId: string;
  action: string;
  aggregateType: string;
  aggregateId: string;
  beforeMasked: string | null;
  afterMasked: string | null;
  occurredAt: string;
}
const { rows, cursor, loading, error, requestId, loaded, reset, load, retry } = useCursorPage<OperationsRowView>((cursor, signal) =>
  controlPlane.audit.operations.list({ ...cleaned.value, cursor }, signal),
);
const expanded = ref<string | null>(null);

function toggle(id: string): void {
  expanded.value = expanded.value === id ? null : id;
}

watch(filter, reset, { flush: 'sync' });
onMounted(() => {
  if (ready) void load();
});
</script>

<template>
  <div class="admin-page fill-page">
    <PageHeader title="操作日志" description="管理面变更轨迹：委派、平台、客户端与身份操作。留痕不可删除。" />
    <div class="ptmcp-filter-strip">
      <ElForm :inline="true" @submit.prevent="load()">
        <ElFormItem label="动作">
          <ElInput v-model="filter.action" clearable placeholder="如 DELEGATION_CREATE" />
        </ElFormItem>
        <ElFormItem label="聚合类型">
          <ElSelect v-model="filter.aggregateType" clearable placeholder="全部类型">
            <ElOption v-for="type in OPERATION_AGGREGATE_TYPES" :key="type" :label="operationAggregateText(type)" :value="type" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="操作者 ID">
          <ElInput v-model="filter.actorId" clearable placeholder="操作者 ID" />
        </ElFormItem>
        <ElButton native-type="submit" type="primary" plain :disabled="!ready" :loading="loading">查询</ElButton>
        <ElButton
          @click="
            Object.assign(filter, { action: '', aggregateType: '', actorId: '' });
            ready && load();
          "
          >重置</ElButton
        >
      </ElForm>
    </div>
    <ElCard shadow="never" class="ptmcp-table-section ptmcp-fill-card">
      <div class="ptmcp-table-heading">
        <h2 class="ptmcp-section-title">管理操作日志</h2>
        <span class="ptmcp-meta-note">共 {{ rows.length }} 条</span>
      </div>
      <PageState
        v-if="error"
        kind="error"
        title="操作日志读取失败"
        :description="error"
        :request-id="requestId"
        action-label="重试"
        @action="retry"
      />
      <ElSkeleton v-if="loading" :rows="6" animated />
      <div class="ptmcp-table-wrap">
        <ElTable v-if="!error || rows.length" class="ptmcp-data-table" :data="rows" size="small" height="100%" aria-label="管理操作日志">
          <ElTableColumn prop="occurredAt" label="时间" min-width="170"
            ><template #default="{ row }">{{ formatDate(row.occurredAt) }}</template></ElTableColumn
          >
          <ElTableColumn prop="action" label="动作" min-width="180"
            ><template #default="{ row }"
              ><div class="ptmcp-cell-lead">
                <span class="ptmcp-cell-icon">
                  <IconEpDocument aria-hidden="true" />
                </span>
                <span class="ptmcp-cell-stack">
                  <strong>{{ operationActionText(row.action) }}</strong>
                  <span>{{ row.action }}</span>
                </span>
              </div></template
            ></ElTableColumn
          >
          <ElTableColumn label="聚合" min-width="200"
            ><template #default="{ row }">{{ operationAggregateText(row.aggregateType) }}:{{ row.aggregateId }}</template></ElTableColumn
          >
          <ElTableColumn prop="actorId" label="操作者" width="100" />
          <ElTableColumn label="变更内容" min-width="120"
            ><template #default="{ row }"
              ><ElButton link type="primary" @click="toggle(row.id)">{{ expanded === row.id ? '收起' : '查看' }}</ElButton></template
            ></ElTableColumn
          >
          <template #empty
            ><PageState :title="loaded ? '没有符合条件的操作日志' : '操作日志尚未读取'" description="可调整条件查询；留痕由管理操作自动写入。"
          /></template>
        </ElTable>
      </div>
      <CursorFooter v-if="loaded || rows.length" :count="rows.length" :has-more="!!cursor" :loading="loading" @more="load(true)" />
    </ElCard>
    <ElCard v-if="expanded !== null" shadow="never">
      <template #header><span>变更内容明细</span></template>
      <pre class="payload">{{ rows.find((row) => row.id === expanded)?.beforeMasked || '（无前置状态）' }}</pre>
      <pre class="payload">{{ rows.find((row) => row.id === expanded)?.afterMasked || '（无后置状态）' }}</pre>
    </ElCard>
  </div>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}
.action-code {
  margin-left: 6px;
  font-size: 11px;
  color: var(--ptmcp-ink-muted, #6b7280);
  overflow-wrap: anywhere;
}
.payload {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 12px;
  background: var(--ptmcp-blue-soft);
  font-size: 12px;
}
</style>
