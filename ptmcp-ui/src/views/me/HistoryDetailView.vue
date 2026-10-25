<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { mePortalApi, type AuditDetail } from '../../services/mePortalApi';
import { errorText, formatDate } from '../portalUtils';

const route = useRoute();
const router = useRouter();
const item = ref<AuditDetail | null>(null);
const loading = ref(true);
const error = ref('');
const requestId = String(route.params.requestId ?? '');
async function load(): Promise<void> {
  try {
    item.value = await mePortalApi.history.detail(requestId);
  } catch (reason) {
    error.value = errorText(reason);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
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
        <ElAlert type="info" :closable="false" title="排障请使用 requestId 联系管理员。个人门户不提供原始 SQL 解密、结果集下载或自动重试。" /></ElCard
    ></template>
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
