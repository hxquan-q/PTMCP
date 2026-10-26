<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import PageHeader from '../../../components/common/PageHeader.vue';
import { useSessionStore } from '../../../stores/session';
import { controlPlane, type AdminAuditDetail } from '../../../services/controlPlane';
import { controlPlaneAvailable } from '../../../services/frontendMode';
import { errorText, formatDate, usePortalTask } from '../../portalUtils';
import AuditDecryptDialog from './AuditDecryptDialog.vue';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const requestId = computed(() => String(route.params.requestId ?? ''));
const item = ref<AdminAuditDetail | null>(null);
const error = ref('');
const loading = ref(false);
const decrypt = ref(false);
let sequence = 0;
const task = usePortalTask(() => {
  sequence++;
  item.value = null;
  decrypt.value = false;
  loading.value = false;
});
async function load(): Promise<void> {
  const own = ++sequence;
  task.cancel();
  item.value = null;
  decrypt.value = false;
  error.value = '';
  if (!controlPlaneAvailable) return;
  loading.value = true;
  try {
    const result = await task.run((signal) => controlPlane.audit.get(requestId.value, signal));
    if (result && own === sequence) item.value = result;
  } catch (reason) {
    if (own === sequence) error.value = errorText(reason);
  } finally {
    if (own === sequence) loading.value = false;
  }
}
const guidance = computed(() =>
  item.value?.result === 'UNKNOWN' || item.value?.result === 'START'
    ? '执行结局不确定。按 requestId 排查，不能认定未执行或自动重试。'
    : item.value?.verdict === 'DENY'
      ? '权限拒绝不代表系统故障。核对平台 Scope、用户授权和 DENY 规则。'
      : item.value?.truncated
        ? '结果被截断，请收窄条件或使用聚合。'
        : '使用 requestId 关联排查，时间均按 Asia/Taipei 展示。',
);
watch(requestId, () => void load(), { immediate: true, flush: 'sync' });
</script>
<template>
  <div class="admin-page">
    <PageHeader title="审计详情" description="调用上下文、脱敏请求、资源版本与持久结局。"
      ><template #actions
        ><ElButton @click="router.push('/admin/audit')">返回审计</ElButton
        ><ElButton v-if="session.can('ptmcp:audit:decrypt')" type="danger" plain :disabled="!item || !controlPlaneAvailable" @click="decrypt = true"
          >申请查看原始 SQL</ElButton
        ></template
      ></PageHeader
    >
    <ElAlert
      v-if="!controlPlaneAvailable"
      type="warning"
      :closable="false"
      title="审计接口待接入，正常模式不读取或解密。独立开发预览仅展示合成记录。"
    />
    <ElAlert v-if="error" type="error" :title="error" :closable="false"><ElButton @click="load">重新读取</ElButton></ElAlert>
    <ElSkeleton v-if="loading" :rows="9" animated />
    <template v-else-if="item">
      <ElCard shadow="never"
        ><div class="heading">
          <h2>{{ item.requestId }}</h2>
          <ElTag :type="item.result === 'SUCCESS' ? 'success' : 'warning'">{{ item.result }}</ElTag>
        </div>
        <ElDescriptions :column="1" border class="descriptions"
          ><ElDescriptionsItem label="员工 / 平台 / 数据源"
            >{{ item.userId }} / {{ item.platformId ?? '—' }} / {{ item.datasourceId ?? '—' }}</ElDescriptionsItem
          ><ElDescriptionsItem label="工具 / 判定">{{ item.tool }} / {{ item.verdict }}</ElDescriptionsItem
          ><ElDescriptionsItem label="开始时间（台北）">{{ formatDate(item.startedAt) }}</ElDescriptionsItem
          ><ElDescriptionsItem label="完成时间（台北）">{{ formatDate(item.completedAt) }}</ElDescriptionsItem
          ><ElDescriptionsItem label="错误码">{{ item.errorCode ?? '—' }}</ElDescriptionsItem></ElDescriptions
        ><ElAlert type="info" :closable="false" :title="guidance" class="notice"
      /></ElCard>
      <ElCard shadow="never"
        ><h2>脱敏请求</h2>
        <pre>{{ item.sqlMasked || '服务端未提供脱敏摘要' }}</pre>
        <p class="helper">指纹：{{ item.fingerprint ?? '未提供' }}。默认不含 sqlRaw、sqlFinal 或原始结果集。</p></ElCard
      >
      <ElCard shadow="never"
        ><h2>执行证据</h2>
        <ElDescriptions :column="1" border class="descriptions"
          ><ElDescriptionsItem label="总耗时 / 排队 / 数据库"
            >{{ item.durationMs ?? '—' }} / {{ item.queueMs ?? '—' }} / {{ item.dbMs ?? '—' }} ms</ElDescriptionsItem
          ><ElDescriptionsItem label="行数 / 响应字节">{{ item.rowsReturned ?? '—' }} / {{ item.responseBytes ?? '—' }}</ElDescriptionsItem
          ><ElDescriptionsItem label="截断">{{ item.truncated ? '是' : '否' }}</ElDescriptionsItem></ElDescriptions
        >
        <h3>可见对象</h3>
        <pre>{{ JSON.stringify(item.resourceRefs, null, 2) }}</pre>
        <h3>使用版本</h3>
        <pre>{{ JSON.stringify(item.versions, null, 2) }}</pre>
      </ElCard>
    </template>
    <ElEmpty v-else-if="!error" description="审计详情尚未读取，路由 ID 不代表记录已存在。" />
    <AuditDecryptDialog v-if="session.can('ptmcp:audit:decrypt') && item" :key="requestId" v-model="decrypt" :request-id="requestId" />
  </div>
</template>
<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}
.heading {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: flex-start;
}
h2 {
  margin: 0;
  font-size: 16px;
  overflow-wrap: anywhere;
}
h3 {
  font-size: 14px;
  margin: 20px 0 8px;
}
.descriptions,
.notice {
  margin-top: 18px;
}
pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: var(--ptmcp-blue-soft);
  padding: 16px;
  font-size: 13px;
  line-height: 1.6;
}
.helper {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
