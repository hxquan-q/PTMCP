<script setup lang="ts">
import PageHeader from '../../../components/common/PageHeader.vue';
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import {
  DataRequestError,
  deleteDatasource,
  getDatasource,
  syncDatasource,
  testDatasource,
  updateDatasource,
  type DatasourceRead,
  type DatasourceWrite,
} from '../../../services/data';
import { newUuid } from '../../../services/uuid';
import { useSessionStore } from '../../../stores/session';
import { datasourceDifferences, isVersionConflict } from '../../../adapters/edit-conflict';
import VersionConflictPanel from '../../../components/data/VersionConflictPanel.vue';

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const id = computed(() => String(route.params.id));
const loading = ref(true);
const saving = ref(false);
const actionLoading = ref(false);
const error = ref('');
const requestId = ref<string>();
const etag = ref('');
const datasource = ref<DatasourceRead | null>(null);
const conflict = ref<{ base: DatasourceRead; latest: DatasourceRead; etag: string; resolved: boolean } | null>(null);
const conflictRequired = ref(false);
const conflictLoading = ref(false);
const conflictRows = computed(() => (conflict.value ? datasourceDifferences(conflict.value.base, conflict.value.latest, form) : []));
const formRef = ref<FormInstance>();
const form = reactive<DatasourceWrite>(emptyForm());
let controller: AbortController | null = null;
let generation = 0;
let active = true;
let saveKey = newUuid();
let testKey = newUuid();
let syncKey = newUuid();
let deleteKey = newUuid();
const rules: FormRules<DatasourceWrite> = {
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 128, message: '名称最多128字符', trigger: 'blur' },
  ],
  host: [
    { required: true, message: '请输入主机', trigger: 'blur' },
    { max: 253, message: '主机最多253字符', trigger: 'blur' },
  ],
  databaseName: [
    { required: true, message: '请输入数据库名', trigger: 'blur' },
    { max: 128, message: '数据库名最多128字符', trigger: 'blur' },
  ],
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 128, message: '用户名最多128字符', trigger: 'blur' },
  ],
  credential: [{ max: 1024, message: '连接凭据最多1024字符', trigger: 'blur' }],
  port: [{ required: true, type: 'number', min: 1, max: 65535, message: '端口必须为1～65535', trigger: 'change' }],
  poolMax: [{ required: true, type: 'number', min: 1, max: 8, message: '连接池上限必须为1～8', trigger: 'change' }],
};
function emptyForm(): DatasourceWrite {
  return {
    code: '',
    name: '',
    engine: 'MYSQL',
    host: '',
    port: 3306,
    databaseName: '',
    username: '',
    credential: '',
    tlsMode: 'VERIFY_FULL',
    poolMax: 4,
    status: 'DRAFT',
  };
}
function formatDate(value: string | null | undefined): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}
function statusLabel(value: string): string {
  return { ACTIVE: '运行中', DRAFT: '草稿', DISABLED: '已停用' }[value] ?? value;
}
function statusType(value: string): 'success' | 'warning' | 'info' {
  return value === 'ACTIVE' ? 'success' : value === 'DISABLED' ? 'info' : 'warning';
}
function errorMessage(value: unknown): string {
  if (value instanceof DataRequestError) {
    requestId.value = value.requestId;
    if (value.code === 'REFERENCES_EXIST') return '数据源仍有活动依赖。请先停用并处理相关授权，再删除。';
    return `${value.message}${value.code ? `（${value.code}）` : ''}`;
  }
  return value instanceof Error ? value.message : '请求失败，请稍后重试';
}
function resetKeys(): void {
  saveKey = newUuid();
  testKey = newUuid();
  syncKey = newUuid();
  deleteKey = newUuid();
}
function clearSensitive(): void {
  Object.assign(form, emptyForm());
  datasource.value = null;
  etag.value = '';
  conflict.value = null;
  conflictRequired.value = false;
  conflictLoading.value = false;
  saving.value = false;
  actionLoading.value = false;
  controller?.abort();
  generation += 1;
}
function recordFailure(cause: unknown): void {
  const message = errorMessage(cause);
  if (cause instanceof DataRequestError && (cause.status === 401 || cause.status === 404 || (cause.status === 403 && cause.code !== 'CSRF_INVALID')))
    clearSensitive();
  error.value = message;
}
async function readConflict(): Promise<void> {
  if (conflictLoading.value || !datasource.value || !session.can('ptmcp:datasource:read')) return;
  const current = generation;
  const base = conflict.value?.base ?? datasource.value;
  const targetId = id.value;
  conflictLoading.value = true;
  error.value = '';
  try {
    const result = await getDatasource(targetId, controller?.signal);
    if (!active || current !== generation) return;
    conflict.value = { base, latest: result.data, etag: result.etag ?? `"${result.data.configVersion}"`, resolved: false };
  } catch (cause) {
    if (active && current === generation && !aborted(cause)) recordFailure(cause);
  } finally {
    if (active && current === generation) conflictLoading.value = false;
  }
}
function resolveConflict(keepDraft: boolean): void {
  if (!conflict.value || conflictLoading.value || saving.value || actionLoading.value || !session.can('ptmcp:datasource:read')) return;
  const latest = conflict.value.latest;
  datasource.value = latest;
  etag.value = conflict.value.etag;
  form.credential = '';
  if (keepDraft) conflict.value.resolved = true;
  else {
    copyForm(latest);
    conflict.value = null;
  }
  conflictRequired.value = false;
  error.value = '';
  resetKeys();
}
function copyForm(value: DatasourceRead): void {
  Object.assign(form, {
    code: value.code,
    name: value.name,
    engine: value.engine,
    host: value.host,
    port: value.port,
    databaseName: value.databaseName,
    username: value.username,
    credential: '',
    tlsMode: 'VERIFY_FULL',
    poolMax: value.poolMax,
    status: value.status,
  });
}
function aborted(value: unknown): boolean {
  return value instanceof DOMException && value.name === 'AbortError';
}
async function load(): Promise<void> {
  controller?.abort();
  const current = ++generation;
  controller = new AbortController();
  datasource.value = null;
  etag.value = '';
  form.credential = '';
  conflict.value = null;
  conflictRequired.value = false;
  conflictLoading.value = false;
  loading.value = true;
  error.value = '';
  if (!session.can('ptmcp:datasource:read')) {
    loading.value = false;
    return;
  }
  try {
    const result = await getDatasource(id.value, controller.signal);
    if (!active || current !== generation) return;
    datasource.value = result.data;
    etag.value = result.etag ?? `"${result.data.configVersion}"`;
    copyForm(result.data);
    resetKeys();
  } catch (cause) {
    if (active && current === generation && !aborted(cause)) error.value = errorMessage(cause);
  } finally {
    if (active && current === generation) loading.value = false;
  }
}
async function validate(): Promise<boolean> {
  return formRef.value
    ? await formRef.value
        .validate()
        .then(() => true)
        .catch(() => false)
    : false;
}
async function save(): Promise<void> {
  if (!etag.value || saving.value || actionLoading.value || conflictRequired.value || !session.can('ptmcp:datasource:update')) return;
  const current = generation;
  const targetId = id.value;
  saving.value = true;
  error.value = '';
  try {
    if (!(await validate()) || !active || current !== generation || !session.can('ptmcp:datasource:update')) return;
    if (form.status === 'DISABLED' && datasource.value?.status !== 'DISABLED') {
      try {
        await ElMessageBox.confirm('停用后此数据源将不能继续用于查询或同步，相关授权的实际访问也会受影响。', '确认停用数据源', {
          type: 'warning',
          confirmButtonText: '停用并保存',
          cancelButtonText: '取消',
        });
      } catch {
        return;
      }
      if (!active || current !== generation || !session.can('ptmcp:datasource:update')) return;
    }
    const result = await updateDatasource(targetId, { ...form }, etag.value, saveKey);
    if (!active || current !== generation) return;
    datasource.value = result.data;
    etag.value = result.etag ?? `"${result.data.configVersion}"`;
    copyForm(result.data);
    conflict.value = null;
    saveKey = newUuid();
    ElMessage.success('数据源配置已保存');
  } catch (cause) {
    if (!active || current !== generation) return;
    if (cause instanceof DataRequestError && isVersionConflict(cause)) {
      form.credential = '';
      conflict.value = null;
      conflictRequired.value = true;
      await readConflict();
    } else recordFailure(cause);
  } finally {
    if (active && current === generation) {
      form.credential = '';
      saving.value = false;
    }
  }
}
async function testConnection(): Promise<void> {
  if (actionLoading.value || saving.value || conflictRequired.value || !datasource.value || !session.can('ptmcp:datasource:test')) return;
  const current = generation;
  actionLoading.value = true;
  try {
    if (!(await validate()) || !active || current !== generation || !session.can('ptmcp:datasource:test')) return;
    const result = await testDatasource(id.value, { ...form }, testKey);
    if (!active || current !== generation) return;
    form.credential = '';
    testKey = newUuid();
    ElMessage.success('连接测试已提交');
    if (session.can('ptmcp:job:read')) await router.push(`/admin/jobs/${result.data.id}`);
  } catch (cause) {
    if (active && current === generation) recordFailure(cause);
  } finally {
    if (active && current === generation) {
      form.credential = '';
      actionLoading.value = false;
    }
  }
}
async function syncMetadata(): Promise<void> {
  if (actionLoading.value || saving.value || conflictRequired.value || datasource.value?.status !== 'ACTIVE' || !session.can('ptmcp:datasource:sync'))
    return;
  const current = generation;
  actionLoading.value = true;
  try {
    const result = await syncDatasource(id.value, syncKey);
    if (!active || current !== generation) return;
    syncKey = newUuid();
    ElMessage.success('元数据同步已提交');
    if (session.can('ptmcp:job:read')) await router.push(`/admin/jobs/${result.data.id}`);
  } catch (cause) {
    if (active && current === generation) recordFailure(cause);
  } finally {
    if (active && current === generation) actionLoading.value = false;
  }
}
async function removeDatasource(): Promise<void> {
  if (!etag.value || saving.value || actionLoading.value || conflictRequired.value || !session.can('ptmcp:datasource:delete')) return;
  const current = generation;
  const targetId = id.value;
  actionLoading.value = true;
  try {
    await ElMessageBox.confirm('删除后将无法继续使用此数据源。若仍有活动授权，服务端会拒绝删除。', '确认删除数据源', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    });
  } catch {
    if (active && current === generation) actionLoading.value = false;
    return;
  }
  if (!active || current !== generation || !session.can('ptmcp:datasource:delete')) {
    if (active && current === generation) actionLoading.value = false;
    return;
  }
  try {
    await deleteDatasource(targetId, etag.value, deleteKey);
    if (!active || current !== generation) return;
    ElMessage.success('数据源已删除');
    await router.replace('/admin/datasources');
  } catch (cause) {
    if (!active || current !== generation) return;
    if (cause instanceof DataRequestError && isVersionConflict(cause)) {
      form.credential = '';
      conflict.value = null;
      conflictRequired.value = true;
      await readConflict();
    } else recordFailure(cause);
  } finally {
    if (active && current === generation) actionLoading.value = false;
  }
}
function openMetadata(): void {
  if (datasource.value?.activeSnapshotId && session.can('ptmcp:metadata:list'))
    void router.push({ path: '/admin/metadata', query: { datasourceId: id.value } });
}
watch(
  form,
  () => {
    if (!saving.value && !actionLoading.value) {
      saveKey = newUuid();
      testKey = newUuid();
    }
  },
  { deep: true },
);
watch(id, () => {
  clearSensitive();
  void load();
});
onMounted(() => {
  window.addEventListener('ptmcp:sensitive-clear', clearSensitive);
  void load();
});
onBeforeUnmount(() => {
  active = false;
  clearSensitive();
  window.removeEventListener('ptmcp:sensitive-clear', clearSensitive);
});
</script>

<template>
  <section class="page-shell" aria-labelledby="detail-title">
    <PageHeader title-id="detail-title" :title="datasource?.name || '数据源详情'" :description="datasource?.code || '加载中…'">
      <template #before><ElButton link class="back-link" @click="router.push('/admin/datasources')">← 返回数据源</ElButton></template>
      <template #actions>
        <div v-if="datasource" class="heading-actions">
          <ElButton
            v-if="session.can('ptmcp:datasource:test')"
            :loading="actionLoading"
            :disabled="saving || conflictRequired"
            @click="testConnection"
            >测试连接</ElButton
          >
          <ElButton
            v-if="session.can('ptmcp:datasource:sync')"
            type="primary"
            :loading="actionLoading"
            :disabled="datasource.status !== 'ACTIVE' || saving || conflictRequired"
            @click="syncMetadata"
            >同步元数据</ElButton
          >
        </div>
      </template>
    </PageHeader>

    <ElAlert v-if="error" type="error" show-icon :closable="false" class="state-alert"
      ><span
        >{{ error }}<span v-if="requestId">（请求 ID：{{ requestId }}）</span></span
      ></ElAlert
    >
    <VersionConflictPanel
      v-if="conflictRequired || conflict"
      :rows="conflictRows"
      :loading="conflictLoading || saving || actionLoading"
      :ready="!!conflict"
      :resolved="conflict?.resolved ?? false"
      :versions="conflict ? `配置版本 ${conflict.base.configVersion} → ${conflict.latest.configVersion}` : ''"
      credential-cleared
      @refresh="readConflict"
      @latest="resolveConflict(false)"
      @draft="resolveConflict(true)"
    />
    <ElSkeleton v-if="loading" :rows="8" animated />
    <template v-else-if="datasource">
      <div class="status-strip">
        <span class="status-label">当前状态</span><ElTag :type="statusType(datasource.status)">{{ statusLabel(datasource.status) }}</ElTag>
        <span>健康：{{ datasource.health || 'UNKNOWN' }}</span
        ><span>凭据：{{ datasource.credentialConfigured ? '已配置' : '未配置' }}</span>
        <ElButton v-if="datasource.activeSnapshotId && session.can('ptmcp:metadata:list')" link type="primary" @click="openMetadata"
          >查看元数据</ElButton
        >
      </div>
      <ElCard shadow="never" class="detail-card">
        <template #header
          ><div class="card-title">
            <span>连接配置</span><span class="version">版本 {{ datasource.configVersion }}</span>
          </div></template
        >
        <ElForm
          ref="formRef"
          :model="form"
          :rules="rules"
          :disabled="!session.can('ptmcp:datasource:update') || saving || actionLoading || conflictRequired"
          label-position="top"
          class="datasource-form"
        >
          <div class="form-grid">
            <ElFormItem label="编码"><ElInput v-model="form.code" disabled /></ElFormItem>
            <ElFormItem label="名称" prop="name"><ElInput v-model="form.name" maxlength="128" /></ElFormItem>
            <ElFormItem label="引擎"
              ><ElSelect v-model="form.engine" disabled
                ><ElOption label="MySQL" value="MYSQL" /><ElOption label="PostgreSQL" value="POSTGRESQL" /></ElSelect
            ></ElFormItem>
            <ElFormItem label="状态"
              ><ElSelect v-model="form.status"
                ><ElOption label="草稿" value="DRAFT" /><ElOption label="运行中" value="ACTIVE" /><ElOption
                  label="已停用"
                  value="DISABLED" /></ElSelect
            ></ElFormItem>
            <ElFormItem label="主机" prop="host"><ElInput v-model="form.host" /></ElFormItem>
            <ElFormItem label="端口" prop="port"><ElInputNumber v-model="form.port" :min="1" :max="65535" class="full-width" /></ElFormItem>
            <ElFormItem label="数据库名" prop="databaseName"><ElInput v-model="form.databaseName" /></ElFormItem>
            <ElFormItem label="用户名" prop="username"><ElInput v-model="form.username" /></ElFormItem>
            <ElFormItem label="替换凭据"
              ><ElInput v-model="form.credential" type="password" show-password autocomplete="new-password" placeholder="留空保持已配置凭据"
            /></ElFormItem>
            <ElFormItem label="连接池上限" prop="poolMax"><ElInputNumber v-model="form.poolMax" :min="1" :max="8" class="full-width" /></ElFormItem>
          </div>
          <ElAlert type="info" :closable="false">TLS 固定为 VERIFY_FULL。凭据不回显，留空表示保持现有值。</ElAlert>
        </ElForm>
        <div class="form-actions">
          <ElButton
            v-if="session.can('ptmcp:datasource:update')"
            type="primary"
            :loading="saving"
            :disabled="actionLoading || conflictRequired"
            @click="save"
            >保存更改</ElButton
          >
          <ElButton
            v-if="session.can('ptmcp:datasource:delete')"
            type="danger"
            plain
            :loading="actionLoading"
            :disabled="saving || conflictRequired"
            @click="removeDatasource"
            >删除数据源</ElButton
          >
        </div>
      </ElCard>
      <ElCard shadow="never" class="meta-card">
        <template #header
          ><div class="card-title"><span>同步信息</span></div></template
        >
        <dl class="facts">
          <div>
            <dt>最近同步</dt>
            <dd>{{ formatDate(datasource.lastSyncAt) }}</dd>
          </div>
          <div>
            <dt>活动快照</dt>
            <dd>{{ datasource.activeSnapshotId || '待同步' }}</dd>
          </div>
          <div>
            <dt>资源版本</dt>
            <dd>{{ datasource.resourceVersion }}</dd>
          </div>
          <div>
            <dt>更新时间</dt>
            <dd>{{ formatDate(datasource.updatedAt) }}</dd>
          </div>
        </dl>
      </ElCard>
    </template>
    <ElEmpty v-else description="数据源不存在或你已失去访问权限"
      ><ElButton type="primary" @click="router.push('/admin/datasources')">返回列表</ElButton></ElEmpty
    >
  </section>
</template>

<style scoped>
.back-link {
  margin-bottom: 16px;
  padding: 0;
  color: var(--ptmcp-text-muted);
}
.heading-actions {
  display: flex;
  gap: 10px;
}
.status-strip {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 14px;
  margin: 16px 0;
  padding: 12px 16px;
  border: 1px solid var(--ptmcp-border);
  border-radius: 8px;
  background: var(--ptmcp-chrome);
  color: var(--ptmcp-text-muted);
  font-size: 13px;
}
.status-label {
  color: var(--ptmcp-text-list);
  font-weight: 700;
}
.state-alert {
  margin-bottom: 16px;
}
.detail-card,
.meta-card {
  margin-top: 16px;
  border-color: var(--ptmcp-border);
}
.card-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--ptmcp-heading);
  font-weight: 700;
}
.version {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  font-weight: 400;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}
.full-width,
.datasource-form :deep(.el-select) {
  width: 100%;
}
.form-actions {
  display: flex;
  justify-content: space-between;
  margin-top: 22px;
}
.facts {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18px;
  margin: 0;
}
.facts div {
  min-width: 0;
}
dt {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
}
dd {
  margin: 5px 0 0;
  color: var(--ptmcp-text);
  font-weight: 600;
  overflow-wrap: anywhere;
}
@media (max-width: 760px) {
  .heading-actions {
    width: 100%;
  }
  .heading-actions .el-button {
    flex: 1;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .facts {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
