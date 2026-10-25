<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { DataRequestError, getJob, type JobView } from '../../../services/data';

const route = useRoute();
const router = useRouter();
const id = String(route.params.id);
const job = ref<JobView | null>(null);
const loading = ref(true);
const error = ref('');
const requestId = ref<string>();
let timer: number | undefined;
let active = true;

function errorMessage(value: unknown): string {
  if (value instanceof DataRequestError) {
    requestId.value = value.requestId;
    return value.message;
  }
  return value instanceof Error ? value.message : '任务状态读取失败';
}
function stateLabel(value: string): string {
  return { QUEUED: '排队中', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败' }[value] ?? value;
}
function stateType(value: string): 'success' | 'warning' | 'danger' | 'info' {
  return value === 'SUCCEEDED' ? 'success' : value === 'FAILED' ? 'danger' : value === 'RUNNING' ? 'warning' : 'info';
}
function date(value: string | null): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(value)) : '—';
}
function isTerminal(value: string): boolean {
  return ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(value);
}
function schedule(): void {
  if (!active || !job.value || isTerminal(job.value.state)) return;
  timer = window.setTimeout(() => void load(false), 2000);
}
async function load(initial = true): Promise<void> {
  if (!active) return;
  if (initial) loading.value = true;
  error.value = '';
  try {
    const result = await getJob(id);
    if (!active) return;
    job.value = result.data;
    schedule();
  } catch (cause) {
    if (!active) return;
    error.value = errorMessage(cause);
  } finally {
    if (initial) loading.value = false;
  }
}
function openDatasource(): void {
  if (job.value?.datasourceId) void router.push(`/admin/datasources/${job.value.datasourceId}`);
}
function openMetadata(): void {
  if (job.value?.datasourceId) void router.push({ path: '/admin/metadata', query: { datasourceId: job.value.datasourceId } });
}
onMounted(() => void load());
onBeforeUnmount(() => {
  active = false;
  if (timer) window.clearTimeout(timer);
});
</script>

<template>
  <section class="page-shell" aria-labelledby="job-title">
    <div class="page-heading">
      <div>
        <ElButton link class="back-link" @click="router.push(`/admin/datasources/${job?.datasourceId || ''}`)">← 返回数据源</ElButton>
        <p class="eyebrow">异步任务 / 状态</p>
        <h1 id="job-title">{{ job?.type === 'SYNC' ? '元数据同步' : '连接测试' }}</h1>
        <p class="subtitle">任务 ID {{ id }}</p>
      </div>
      <ElTag v-if="job" :type="stateType(job.state)" size="large" effect="light">{{ stateLabel(job.state) }}</ElTag>
    </div>
    <ElAlert v-if="error" type="error" show-icon :closable="false" class="state-alert"
      ><span
        >{{ error }}<span v-if="requestId">（请求 ID：{{ requestId }}）</span></span
      ></ElAlert
    >
    <ElSkeleton v-if="loading" :rows="7" animated />
    <template v-else-if="job">
      <ElCard shadow="never" class="progress-card">
        <div class="progress-heading">
          <div>
            <span class="label">执行进度</span
            ><strong v-if="job.progress?.total !== null && job.progress?.total !== undefined"
              >{{ job.progress.processed }} / {{ job.progress.total }}</strong
            ><strong v-else>{{ job.progress?.processed ?? 0 }} 条已处理</strong>
          </div>
          <span class="muted">{{ job.state === 'RUNNING' || job.state === 'QUEUED' ? '页面会自动刷新' : '任务已停止轮询' }}</span>
        </div>
        <ElProgress
          v-if="job.progress?.total !== null && job.progress?.total !== undefined"
          :percentage="Math.min(100, Math.round((job.progress.processed / Math.max(1, job.progress.total)) * 100))"
          :status="job.state === 'FAILED' ? 'exception' : job.state === 'SUCCEEDED' ? 'success' : undefined"
        />
        <div v-else class="indeterminate"><span></span></div>
        <p v-if="job.state === 'FAILED'" class="failure">任务失败：{{ job.errorCode || 'UNKNOWN_ERROR' }}。未发布新的元数据快照。</p>
        <p v-else-if="job.state === 'SUCCEEDED' && job.snapshotId" class="success">快照 {{ job.snapshotId }} 已发布，可查看元数据。</p>
      </ElCard>
      <ElCard shadow="never" class="detail-card">
        <template #header><strong>任务信息</strong></template>
        <dl class="facts">
          <div>
            <dt>类型</dt>
            <dd>{{ job.type }}</dd>
          </div>
          <div>
            <dt>数据源</dt>
            <dd>
              <button class="link-button" type="button" @click="openDatasource">{{ job.datasourceId }}</button>
            </dd>
          </div>
          <div>
            <dt>开始时间</dt>
            <dd>{{ date(job.startedAt) }}</dd>
          </div>
          <div>
            <dt>结束时间</dt>
            <dd>{{ date(job.finishedAt) }}</dd>
          </div>
          <div>
            <dt>快照</dt>
            <dd>{{ job.snapshotId || '—' }}</dd>
          </div>
          <div>
            <dt>错误码</dt>
            <dd>{{ job.errorCode || '—' }}</dd>
          </div>
        </dl>
      </ElCard>
      <div class="bottom-actions">
        <ElButton @click="openDatasource">返回数据源</ElButton
        ><ElButton v-if="job.state === 'SUCCEEDED' && job.snapshotId" type="primary" @click="openMetadata">查看元数据</ElButton>
      </div>
    </template>
    <ElEmpty v-else description="任务不存在或你已失去访问权限"
      ><ElButton type="primary" @click="router.push('/admin/datasources')">返回数据源</ElButton></ElEmpty
    >
  </section>
</template>

<style scoped>
.page-shell {
  max-width: 980px;
  margin: 0 auto;
  padding: 26px clamp(16px, 3vw, 40px) 44px;
}
.page-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 16px;
  margin-bottom: 22px;
}
.back-link {
  margin-bottom: 14px;
  padding: 0;
  color: #467078;
}
.eyebrow {
  margin: 0 0 6px;
  color: #0f6f68;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
h1 {
  margin: 0;
  color: #1d2938;
  font-size: clamp(26px, 3vw, 34px);
}
.subtitle {
  margin: 7px 0 0;
  color: #6d7986;
}
.state-alert {
  margin-bottom: 16px;
}
.progress-card,
.detail-card {
  border-color: #dce5e9;
}
.detail-card {
  margin-top: 16px;
}
.progress-heading {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin-bottom: 18px;
}
.progress-heading div {
  display: flex;
  gap: 18px;
  align-items: baseline;
}
.label {
  color: #667583;
  font-size: 13px;
}
.progress-heading strong {
  color: #1f3443;
  font-size: 20px;
}
.muted {
  color: #87919b;
  font-size: 12px;
}
.indeterminate {
  height: 6px;
  overflow: hidden;
  border-radius: 4px;
  background: #e6eeef;
}
.indeterminate span {
  display: block;
  width: 35%;
  height: 100%;
  border-radius: inherit;
  background: #0d7d75;
  animation: progress 1.5s ease-in-out infinite;
}
@keyframes progress {
  from {
    transform: translateX(-100%);
  }
  to {
    transform: translateX(300%);
  }
}
.failure,
.success {
  margin: 15px 0 0;
  font-size: 13px;
}
.failure {
  color: #b13c3c;
}
.success {
  color: #1b7e5a;
}
.facts {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 22px 30px;
  margin: 0;
}
dt {
  color: #7b8792;
  font-size: 12px;
}
dd {
  margin: 5px 0 0;
  color: #263646;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.link-button {
  padding: 0;
  border: 0;
  color: #116e75;
  background: transparent;
  cursor: pointer;
}
.link-button:hover,
.link-button:focus-visible {
  text-decoration: underline;
}
.bottom-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 20px;
}
@media (max-width: 680px) {
  .page-heading {
    align-items: flex-start;
    flex-direction: column;
  }
  .facts {
    grid-template-columns: repeat(2, 1fr);
  }
  .bottom-actions {
    justify-content: stretch;
  }
  .bottom-actions .el-button {
    flex: 1;
  }
}
</style>
