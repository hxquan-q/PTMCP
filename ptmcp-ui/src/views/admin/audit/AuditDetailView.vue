<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import PageHeader from '../../../components/common/PageHeader.vue';
import EvidenceFields from '../../../components/common/EvidenceFields.vue';
import StatusBadge from '../../../components/common/StatusBadge.vue';
import { useSessionStore } from '../../../stores/session';
import { controlPlane, type AdminAuditDetail } from '../../../services/controlPlane';
import { auditApiReady } from '../../../services/frontendMode';
import { errorText, formatDate, usePortalTask } from '../../portalUtils';
import AuditDecryptDialog from './AuditDecryptDialog.vue';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
/** 审计详情/解密后端已实现；预览模式继续用合成数据。 */
const auditReady = auditApiReady;
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
  if (!auditReady) return;
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
const trail = ref<{ id: string; operatorId: string; reason: string; fields: string[]; createdAt: string }[]>([]);
const trailLoading = ref(false);
const trailError = ref('');
let trailSequence = 0;
async function loadTrail(): Promise<void> {
  if (!auditReady) return;
  const own = ++trailSequence;
  trailLoading.value = true;
  trailError.value = '';
  try {
    const rows = await controlPlane.audit.sensitiveAccess(requestId.value);
    if (own === trailSequence) trail.value = rows.map((row) => ({ ...row, id: String(row.id), operatorId: String(row.operatorId) }));
  } catch (reason) {
    if (own === trailSequence) trailError.value = errorText(reason);
  } finally {
    if (own === trailSequence) trailLoading.value = false;
  }
}
watch(
  requestId,
  () => {
    void load();
    void loadTrail();
  },
  { immediate: true, flush: 'sync' },
);
</script>
<template>
  <div class="admin-page">
    <PageHeader title="审计详情" description="调用上下文、脱敏请求、资源版本与持久结局。"
      ><template #actions
        ><ElButton @click="router.push('/admin/audit')">返回审计</ElButton
        ><ElButton v-if="session.can('ptmcp:audit:decrypt')" type="danger" plain :disabled="!item || !auditReady" @click="decrypt = true"
          >申请查看原始 SQL</ElButton
        ></template
      ></PageHeader
    >
    <ElAlert v-if="error" type="error" :title="error" :closable="false"><ElButton @click="load">重新读取</ElButton></ElAlert>
    <ElSkeleton v-if="loading" :rows="9" animated />
    <template v-else-if="item">
      <ElCard shadow="never"
        ><div class="heading">
          <h2>{{ item.requestId }}</h2>
          <StatusBadge domain="execution" :value="item.result" />
        </div>
        <ElDescriptions :column="1" border class="descriptions"
          ><ElDescriptionsItem label="员工 / 平台 / 数据源"
            >{{ item.userId }} / {{ item.platformId ?? '—' }} / {{ item.datasourceId ?? '—' }}</ElDescriptionsItem
          ><ElDescriptionsItem label="工具 / 判定">{{ item.tool }} / <StatusBadge domain="verdict" :value="item.verdict" /></ElDescriptionsItem
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
        <EvidenceFields :value="item.resourceRefs" />
        <h3>使用版本</h3>
        <EvidenceFields :value="item.versions" />
      </ElCard>
      <ElCard v-if="session.can('ptmcp:audit:decrypt')" shadow="never">
        <div class="section-heading">
          <h3>明文访问记录</h3>
          <ElButton :loading="trailLoading" @click="loadTrail">重新读取</ElButton>
        </div>
        <p class="helper">每次解密都会在此留下操作者、理由与字段；该记录本身不可删除。</p>
        <ElAlert v-if="trailError" type="error" :title="trailError" :closable="false" />
        <ElSkeleton v-if="trailLoading" :rows="2" animated />
        <template v-else-if="trail.length">
          <div v-for="row in trail" :key="row.id" class="trail-row">
            <span class="trail-who">{{ row.operatorId }}</span>
            <span class="trail-when">{{ row.createdAt }}</span>
            <span class="trail-fields">{{ row.fields.join(' / ') }}</span>
            <p class="trail-reason">{{ row.reason }}</p>
          </div>
        </template>
        <p v-if="!trailLoading && !trail.length" class="helper">尚无明文访问记录。</p>
      </ElCard>
    </template>
    <PageState v-else-if="!error" description="审计详情尚未读取，路由 ID 不代表记录已存在。" />
    <AuditDecryptDialog
      v-if="session.can('ptmcp:audit:decrypt') && item"
      :key="requestId"
      v-model="decrypt"
      :request-id="requestId"
      @revealed="loadTrail"
    />
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
<style scoped>
.section-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.trail-row {
  padding: 10px 0;
  border-bottom: 1px solid var(--ptmcp-border);
  display: grid;
  grid-template-columns: auto auto 1fr;
  gap: 4px 12px;
  font-size: 13px;
}
.trail-who {
  font-weight: 600;
}
.trail-when,
.trail-fields {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
}
.trail-reason {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--ptmcp-text-muted);
}
.helper {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
</style>
