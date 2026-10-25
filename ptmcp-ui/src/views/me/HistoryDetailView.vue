<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { mePortalApi, type AuditDetail } from '../../services/mePortalApi';
import { errorText, formatDate, usePortalTask } from '../portalUtils';

const route = useRoute();
const router = useRouter();
const item = ref<AuditDetail | null>(null);
const loading = ref(true);
const error = ref('');
const requestId = computed(() => (typeof route.params.requestId === 'string' ? route.params.requestId : ''));
const detailTask = usePortalTask(() => {
  item.value = null;
});
const guidance = computed(() => {
  if (!item.value) return '';
  if (item.value.result === 'UNKNOWN' || item.value.result === 'START' || item.value.errorCode?.includes('TIMEOUT'))
    return '执行结局不确定或请求超时，请按 requestId 排查；不要直接认定未执行或自动重复查询。';
  if (item.value.verdict === 'DENY' || item.value.errorCode?.includes('AUTHORIZED'))
    return '请求被权限规则拒绝，请联系管理员核对平台范围与个人授权。';
  if (item.value.truncated) return '服务端已报告截断，请让 Agent 收窄条件或使用聚合查询。';
  return '排障请使用 requestId 联系管理员。语法或只读规则拒绝时，请让 Agent 修正请求。';
});
async function load(): Promise<void> {
  detailTask.cancel();
  item.value = null;
  loading.value = true;
  error.value = '';
  try {
    const result = await detailTask.run((signal) => mePortalApi.history.detail(requestId.value, signal));
    if (result) item.value = result;
  } catch (reason) {
    error.value = errorText(reason);
  } finally {
    loading.value = false;
  }
}
watch(
  requestId,
  () => {
    void load();
  },
  { immediate: true }
);
</script>

<template>
  <section class="portal-page" aria-labelledby="history-detail-title">
    <ElButton link type="primary" class="back" @click="router.back()">← 返回查询历史</ElButton
    ><ElSkeleton v-if="loading" :rows="8" animated /><ElAlert v-else-if="error" type="error" :closable="false" :title="error"
      ><template #default><ElButton link type="danger" @click="load">重试</ElButton></template></ElAlert
    ><template v-else-if="item"
      ><div class="page-heading">
        <div>
          <p class="eyebrow">查询记录</p>
          <h1 id="history-detail-title">{{ item.requestId }}</h1>
          <p class="page-description">{{ formatDate(item.startedAt) }} · {{ item.tool }}</p>
        </div>
        <ElTag :type="item.result === 'SUCCESS' ? 'success' : 'warning'">{{ item.result }}</ElTag>
      </div>
      <ElCard shadow="never" class="portal-card"
        ><div class="metrics">
          <div>
            <span>判定</span><strong>{{ item.verdict }}</strong>
          </div>
          <div>
            <span>耗时</span><strong>{{ item.durationMs ?? '—' }} ms</strong>
          </div>
          <div>
            <span>返回行数</span><strong>{{ item.rowsReturned ?? '—' }}</strong>
          </div>
          <div>
            <span>截断</span><strong>{{ item.truncated ? '是' : '否' }}</strong>
          </div>
        </div></ElCard
      ><ElCard shadow="never" class="portal-card"
        ><template #header>脱敏请求摘要</template>
        <pre class="plain-text">{{ item.sqlMasked || '服务端未提供摘要' }}</pre>
        <p v-if="item.errorCode" class="error-text">错误码：{{ item.errorCode }}</p>
        <ElAlert type="info" :closable="false" :title="guidance" />
        <p class="page-description">个人门户不提供原始 SQL 解密、结果集下载或自动重试。</p></ElCard
      ><ElCard shadow="never" class="portal-card"
        ><template #header>调用上下文与执行证据</template
        ><ElDescriptions :column="2" border
          ><ElDescriptionsItem label="平台 ID">{{ item.platformId ?? '—' }}</ElDescriptionsItem
          ><ElDescriptionsItem label="数据源 ID">{{ item.datasourceId ?? '—' }}</ElDescriptionsItem
          ><ElDescriptionsItem label="完成时间（台北）">{{ formatDate(item.completedAt) }}</ElDescriptionsItem
          ><ElDescriptionsItem label="响应字节">{{ item.responseBytes ?? '—' }}</ElDescriptionsItem
          ><ElDescriptionsItem label="排队时间">{{ item.queueMs ?? '—' }} ms</ElDescriptionsItem
          ><ElDescriptionsItem label="数据库时间">{{ item.dbMs ?? '—' }} ms</ElDescriptionsItem></ElDescriptions
        >
        <h2>可见资源引用</h2>
        <pre class="plain-text">{{ JSON.stringify(item.resourceRefs, null, 2) ?? '—' }}</pre>
        <h2>使用版本</h2>
        <pre class="plain-text">{{ JSON.stringify(item.versions, null, 2) ?? '—' }}</pre>
      </ElCard></template
    >
  </section>
</template>

<style scoped>
.back {
  margin-bottom: 18px;
}
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
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
  margin-bottom: 16px;
  border-color: var(--ptmcp-border, #d9e2ec);
}
.metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 18px;
}
.metrics span {
  display: block;
  color: var(--ptmcp-muted, #667085);
  font-size: 12px;
  margin-bottom: 4px;
}
.metrics strong {
  color: var(--ptmcp-ink, #1d2939);
}
.plain-text {
  margin: 0 0 16px;
  white-space: pre-wrap;
  word-break: break-word;
}
h2 {
  font-size: 14px;
  margin: 18px 0 8px;
}
.error-text {
  color: var(--el-color-danger);
}
@media (max-width: 640px) {
  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .page-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
