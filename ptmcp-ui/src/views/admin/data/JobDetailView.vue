<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { DataRequestError, getJob, type JobView } from '../../../services/data';
import { useSessionStore } from '../../../stores/session';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const id = computed(() => String(route.params.id));
const job = ref<JobView | null>(null);
const loading = ref(true);
const error = ref('');
const requestId = ref<string>();
let timer: number | undefined;
let active = true;
let controller: AbortController | null = null;
let generation = 0;
function errorMessage(value: unknown): string {
  if (value instanceof DataRequestError) {
    requestId.value = value.requestId;
    return `${value.message}${value.code ? `（${value.code}）` : ''}`;
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
function stop(): void {
  if (timer) window.clearTimeout(timer);
  timer = undefined;
  controller?.abort();
}
function schedule(): void {
  if (active && !document.hidden && job.value && !isTerminal(job.value.state)) timer = window.setTimeout(() => void load(false), 2000);
}
async function load(initial = true): Promise<void> {
  if (!active || document.hidden || !session.can('ptmcp:job:read')) {
    if (initial) loading.value = false;
    return;
  }
  stop();
  controller = new AbortController();
  const current = ++generation;
  if (initial) loading.value = true;
  error.value = '';
  try {
    const result = await getJob(id.value, controller.signal);
    if (!active || current !== generation) return;
    job.value = result.data;
    schedule();
  } catch (cause) {
    if (!active || current !== generation || (cause instanceof DOMException && cause.name === 'AbortError')) return;
    error.value = errorMessage(cause);
    if (cause instanceof DataRequestError && [401, 403, 404].includes(cause.status)) job.value = null;
  } finally {
    if (initial && current === generation) loading.value = false;
  }
}
function visibilityChanged(): void {
  stop();
  if (!document.hidden) void load(true);
}
function clearSensitive(): void {
  stop();
  generation += 1;
  job.value = null;
}
function openDatasource(): void {
  if (job.value?.datasourceId && session.can('ptmcp:datasource:read')) void router.push(`/admin/datasources/${job.value.datasourceId}`);
  else if (session.can('ptmcp:datasource:list')) void router.push('/admin/datasources');
}
function openMetadata(): void {
  if (job.value?.datasourceId && session.can('ptmcp:metadata:list'))
    void router.push({ path: '/admin/metadata', query: { datasourceId: job.value.datasourceId } });
}
watch(id, () => {
  clearSensitive();
  void load();
});
onMounted(() => {
  document.addEventListener('visibilitychange', visibilityChanged);
  window.addEventListener('ptmcp:sensitive-clear', clearSensitive);
  void load();
});
onBeforeUnmount(() => {
  active = false;
  clearSensitive();
  document.removeEventListener('visibilitychange', visibilityChanged);
  window.removeEventListener('ptmcp:sensitive-clear', clearSensitive);
});
</script>

<template>
  <section class="page-shell" aria-labelledby="job-title">
    <div class="page-heading">
      <div>
        <ElButton v-if="session.can('ptmcp:datasource:read') || session.can('ptmcp:datasource:list')" link class="back-link" @click="openDatasource"
          >← 返回数据源</ElButton
        >
        <p class="eyebrow">异步任务 / 状态</p>
        <h1 id="job-title">{{ job?.type === 'SYNC' ? '元数据同步' : job?.type === 'TEST' ? '连接测试' : '任务详情' }}</h1>
        <p class="subtitle">任务 ID {{ id }}</p>
      </div>
      <ElTag v-if="job" :type="stateType(job.state)" size="large" effect="light">{{ stateLabel(job.state) }}</ElTag>
    </div>
    <ElAlert v-if="error" type="error" show-icon :closable="false" class="state-alert"
      ><span
        >{{ error }}<span v-if="requestId">（请求 ID：{{ requestId }}）</span></span
      ></ElAlert
    >
    <ElButton v-if="error && !loading" class="retry-button" @click="load(true)">重新读取任务状态</ElButton>
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
        <div v-else-if="!isTerminal(job.state)" class="indeterminate"><span></span></div>
        <p v-else class="muted">服务端未提供处理总量，以任务状态为准。</p>
        <p v-if="job.state === 'FAILED'" class="failure">
          任务失败：{{ job.errorCode || 'UNKNOWN_ERROR' }}。{{ job.type === 'SYNC' ? '未发布新的元数据快照。' : '请检查连接配置后手动重新测试。' }}
        </p>
        <p v-else-if="job.state === 'SUCCEEDED' && job.snapshotId && session.can('ptmcp:metadata:list')" class="success">
          快照 {{ job.snapshotId }} 已发布，可查看元数据。
        </p>
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
              <button class="link-button" type="button" :disabled="!session.can('ptmcp:datasource:read')" @click="openDatasource">
                {{ job.datasourceId }}
              </button>
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
        <ElButton v-if="session.can('ptmcp:datasource:read') || session.can('ptmcp:datasource:list')" @click="openDatasource">返回数据源</ElButton
        ><ElButton v-if="job.state === 'SUCCEEDED' && job.snapshotId && session.can('ptmcp:metadata:list')" type="primary" @click="openMetadata"
          >查看元数据</ElButton
        >
      </div>
    </template>
    <ElEmpty v-else :description="error ? '暂时无法读取任务状态，请按错误原因重试' : '任务不存在或你已失去访问权限'"
      ><ElButton v-if="session.can('ptmcp:datasource:list')" type="primary" @click="openDatasource">返回数据源</ElButton></ElEmpty
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
.retry-button {
  margin-bottom: 16px;
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
