<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { createDatasource, DataRequestError, listDatasources, type DatasourceRead, type DatasourceWrite } from '../../../services/data';
import { useSessionStore } from '../../../stores/session';

const router = useRouter();
const session = useSessionStore();
const loading = ref(false);
const submitting = ref(false);
const error = ref('');
const createError = ref('');
const requestId = ref<string | undefined>();
const rows = ref<DatasourceRead[]>([]);
const nextCursor = ref<string | null>(null);
const dialogVisible = ref(false);
const formRef = ref<FormInstance>();
const filters = reactive({ keyword: '', engine: '', status: '' });
const form = reactive<DatasourceWrite>(emptyForm());
const createKey = ref(crypto.randomUUID());
let listController: AbortController | null = null;
let generation = 0;
let createGeneration = 0;
let active = true;

const rules: FormRules<DatasourceWrite> = {
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { max: 64, message: '编码最多64字符', trigger: 'blur' }
  ],
  name: [
    { required: true, message: '请输入名称', trigger: 'blur' },
    { max: 128, message: '名称最多128字符', trigger: 'blur' }
  ],
  host: [
    { required: true, message: '请输入主机', trigger: 'blur' },
    { max: 253, message: '主机最多253字符', trigger: 'blur' }
  ],
  databaseName: [
    { required: true, message: '请输入数据库名', trigger: 'blur' },
    { max: 128, message: '数据库名最多128字符', trigger: 'blur' }
  ],
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { max: 128, message: '用户名最多128字符', trigger: 'blur' }
  ],
  credential: [
    { required: true, message: '请输入连接凭据', trigger: 'blur' },
    { max: 1024, message: '连接凭据最多1024字符', trigger: 'blur' }
  ],
  port: [{ required: true, type: 'number', min: 1, max: 65535, message: '端口必须为1～65535', trigger: 'change' }],
  poolMax: [{ required: true, type: 'number', min: 1, max: 8, message: '连接池上限必须为1～8', trigger: 'change' }]
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
    status: 'DRAFT'
  };
}
function formatDate(value: string | null | undefined): string {
  return value ? new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value)) : '—';
}
function statusLabel(status: string): string {
  return { ACTIVE: '运行中', DRAFT: '草稿', DISABLED: '已停用' }[status] ?? status;
}
function statusType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  return status === 'ACTIVE' ? 'success' : status === 'DISABLED' ? 'info' : 'warning';
}
function requestErrorMessage(value: unknown): string {
  if (value instanceof DataRequestError) {
    requestId.value = value.requestId;
    return `${value.message}${value.code ? `（${value.code}）` : ''}`;
  }
  return value instanceof Error ? value.message : '请求失败，请稍后重试';
}
function aborted(value: unknown): boolean {
  return value instanceof DOMException && value.name === 'AbortError';
}
async function load(cursor: string | null = null): Promise<void> {
  if (!session.can('ptmcp:datasource:list')) {
    rows.value = [];
    return;
  }
  listController?.abort();
  const requestGeneration = ++generation;
  listController = new AbortController();
  loading.value = true;
  error.value = '';
  requestId.value = undefined;
  try {
    const result = await listDatasources({ ...filters, cursor, pageSize: 20, signal: listController.signal });
    if (!active || requestGeneration !== generation) return;
    rows.value = result.data.items ?? [];
    nextCursor.value = result.data.nextCursor ?? null;
  } catch (cause) {
    if (!active || requestGeneration !== generation || aborted(cause)) return;
    error.value = requestErrorMessage(cause);
    if (cause instanceof DataRequestError && cause.code === 'CURSOR_STALE' && cursor) {
      nextCursor.value = null;
      await load();
      return;
    }
    if (cause instanceof DataRequestError && [401, 403, 404].includes(cause.status)) {
      rows.value = [];
      nextCursor.value = null;
    }
  } finally {
    if (active && requestGeneration === generation) loading.value = false;
  }
}
function applyFilters(): void {
  nextCursor.value = null;
  void load();
}
function nextPage(): void {
  if (nextCursor.value && !loading.value) void load(nextCursor.value);
}
function clearCredential(): void {
  form.credential = '';
}
function clearSensitive(): void {
  Object.assign(form, emptyForm());
  dialogVisible.value = false;
  rows.value = [];
  listController?.abort();
  generation += 1;
  createGeneration += 1;
  nextCursor.value = null;
  loading.value = false;
  submitting.value = false;
}
function resetForm(): void {
  createGeneration += 1;
  Object.assign(form, emptyForm());
  createKey.value = crypto.randomUUID();
  createError.value = '';
}
function openCreate(): void {
  if (submitting.value || !session.can('ptmcp:datasource:create')) return;
  resetForm();
  dialogVisible.value = true;
}
async function submitCreate(): Promise<void> {
  if (submitting.value || !dialogVisible.value || !session.can('ptmcp:datasource:create')) return;
  const current = createGeneration;
  submitting.value = true;
  createError.value = '';
  try {
    const valid = formRef.value
      ? await formRef.value
          .validate()
          .then(() => true)
          .catch(() => false)
      : false;
    if (!valid || !active || current !== createGeneration || !dialogVisible.value || !session.can('ptmcp:datasource:create')) return;
    const result = await createDatasource({ ...form }, createKey.value);
    if (!active || current !== createGeneration || !dialogVisible.value) return;
    clearCredential();
    dialogVisible.value = false;
    ElMessage.success('数据源已创建');
    await load();
    if (active && current === createGeneration && result.data?.id && session.can('ptmcp:datasource:read'))
      await router.push(`/admin/datasources/${result.data.id}`);
  } catch (cause) {
    if (!active || current !== createGeneration) return;
    if (cause instanceof DataRequestError && (cause.status === 401 || (cause.status === 403 && cause.code !== 'CSRF_INVALID'))) clearSensitive();
    createError.value = requestErrorMessage(cause);
  } finally {
    if (active && current === createGeneration) submitting.value = false;
  }
}
watch(dialogVisible, (visible) => {
  if (!visible) {
    clearCredential();
    createError.value = '';
  }
});
watch(
  form,
  () => {
    if (!submitting.value) createKey.value = crypto.randomUUID();
  },
  { deep: true }
);
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
  <section class="page-shell" aria-labelledby="datasource-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">平台管理 / 连接资源</p>
        <h1 id="datasource-title">数据源</h1>
        <p class="subtitle">管理受控数据连接、健康状态和元数据同步。</p>
      </div>
      <ElButton v-if="session.can('ptmcp:datasource:create')" type="primary" @click="openCreate">
        <IconEpPlus class="button-icon" aria-hidden="true" /> 新建数据源
      </ElButton>
    </div>

    <ElAlert v-if="error" type="error" :closable="false" show-icon class="state-alert">
      <span
        >{{ error }}<span v-if="requestId">（请求 ID：{{ requestId }}）</span></span
      >
    </ElAlert>

    <ElCard class="filter-card" shadow="never">
      <ElForm :inline="true" @submit.prevent="applyFilters">
        <ElFormItem label="关键词">
          <ElInput v-model="filters.keyword" clearable placeholder="名称或编码" @keyup.enter="applyFilters" />
        </ElFormItem>
        <ElFormItem label="引擎">
          <ElSelect v-model="filters.engine" clearable placeholder="全部引擎" @change="applyFilters">
            <ElOption label="MySQL" value="MYSQL" />
            <ElOption label="PostgreSQL" value="POSTGRESQL" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="状态">
          <ElSelect v-model="filters.status" clearable placeholder="全部状态" @change="applyFilters">
            <ElOption label="草稿" value="DRAFT" />
            <ElOption label="运行中" value="ACTIVE" />
            <ElOption label="已停用" value="DISABLED" />
          </ElSelect>
        </ElFormItem>
        <ElButton @click="applyFilters">查询</ElButton>
      </ElForm>
    </ElCard>

    <ElCard class="table-card" shadow="never" :body-style="{ padding: '0' }">
      <ElTable v-loading="loading" :data="rows" row-key="id" table-layout="auto" empty-text="">
        <ElTableColumn prop="name" label="数据源" min-width="210">
          <template #default="scope">
            <button
              class="link-button"
              type="button"
              :disabled="!session.can('ptmcp:datasource:read')"
              @click="router.push(`/admin/datasources/${scope.row.id}`)"
            >
              <strong>{{ scope.row.name }}</strong>
              <small>{{ scope.row.code }} · ID {{ scope.row.id }}</small>
            </button>
          </template>
        </ElTableColumn>
        <ElTableColumn prop="engine" label="引擎" width="130" />
        <ElTableColumn prop="databaseName" label="数据库" min-width="160" show-overflow-tooltip />
        <ElTableColumn label="状态" width="130">
          <template #default="scope">
            <ElTag :type="statusType(scope.row.status)" effect="light">{{ statusLabel(scope.row.status) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="健康" width="120">
          <template #default="scope"
            ><span class="health-dot" :class="scope.row.health?.toLowerCase()">{{ scope.row.health || 'UNKNOWN' }}</span></template
          >
        </ElTableColumn>
        <ElTableColumn label="凭据" width="120">
          <template #default="scope">{{ scope.row.credentialConfigured ? '已配置' : '未配置' }}</template>
        </ElTableColumn>
        <ElTableColumn label="最近同步" min-width="170">
          <template #default="scope">{{ formatDate(scope.row.lastSyncAt) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="poolMax" label="连接池上限" width="110" />
        <ElTableColumn label="活动快照" min-width="130"
          ><template #default="scope">{{ scope.row.activeSnapshotId || '待同步' }}</template></ElTableColumn
        >
        <ElTableColumn label="操作" width="110" fixed="right">
          <template #default="scope">
            <ElButton v-if="session.can('ptmcp:datasource:read')" link type="primary" @click="router.push(`/admin/datasources/${scope.row.id}`)"
              >查看</ElButton
            >
          </template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty :description="loading ? '正在加载数据源…' : '暂无符合条件的数据源'">
            <ElButton v-if="!loading && session.can('ptmcp:datasource:create')" type="primary" @click="openCreate">新建数据源</ElButton>
          </ElEmpty>
        </template>
      </ElTable>
      <div class="table-footer">
        <span>已加载 {{ rows.length }} 条</span>
        <ElButton :disabled="!nextCursor || loading" @click="nextPage">加载下一页</ElButton>
      </div>
    </ElCard>

    <ElDialog
      v-model="dialogVisible"
      title="新建数据源"
      width="min(620px, calc(100vw - 24px))"
      destroy-on-close
      :close-on-click-modal="!submitting"
      :close-on-press-escape="!submitting"
      :show-close="!submitting"
    >
      <ElAlert v-if="createError" type="error" :closable="false" show-icon class="state-alert">{{ createError }}</ElAlert>
      <ElForm ref="formRef" :model="form" :rules="rules" :disabled="submitting" label-position="top" class="datasource-form">
        <div class="form-grid">
          <ElFormItem label="编码" prop="code" required><ElInput v-model="form.code" maxlength="64" /></ElFormItem>
          <ElFormItem label="名称" prop="name" required><ElInput v-model="form.name" maxlength="128" /></ElFormItem>
          <ElFormItem label="引擎" prop="engine" required
            ><ElSelect v-model="form.engine"><ElOption label="MySQL" value="MYSQL" /><ElOption label="PostgreSQL" value="POSTGRESQL" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="端口" prop="port" required><ElInputNumber v-model="form.port" :min="1" :max="65535" class="full-width" /></ElFormItem>
          <ElFormItem label="主机" prop="host" required><ElInput v-model="form.host" placeholder="主机名或受控地址" /></ElFormItem>
          <ElFormItem label="数据库名" prop="databaseName" required><ElInput v-model="form.databaseName" /></ElFormItem>
          <ElFormItem label="用户名" prop="username" required><ElInput v-model="form.username" /></ElFormItem>
          <ElFormItem label="凭据" prop="credential" required
            ><ElInput v-model="form.credential" type="password" show-password autocomplete="new-password"
          /></ElFormItem>
          <ElFormItem label="连接池上限" prop="poolMax" required
            ><ElInputNumber v-model="form.poolMax" :min="1" :max="8" class="full-width"
          /></ElFormItem>
          <ElFormItem label="状态" prop="status"
            ><ElSelect v-model="form.status"
              ><ElOption label="草稿" value="DRAFT" /><ElOption label="运行中" value="ACTIVE" /><ElOption label="已停用" value="DISABLED" /></ElSelect
          ></ElFormItem>
        </div>
        <ElAlert type="info" :closable="false">连接凭据只在提交时发送，之后仅显示“已配置”。TLS 固定为 VERIFY_FULL。</ElAlert>
      </ElForm>
      <template #footer
        ><ElButton :disabled="submitting" @click="dialogVisible = false">取消</ElButton
        ><ElButton type="primary" :loading="submitting" @click="submitCreate">创建</ElButton></template
      >
    </ElDialog>
  </section>
</template>

<style scoped>
.page-shell {
  max-width: 1440px;
  margin: 0 auto;
  padding: 28px clamp(16px, 3vw, 40px) 44px;
}
.page-heading {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-start;
  margin-bottom: 24px;
}
.eyebrow {
  margin: 0 0 7px;
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
  letter-spacing: -0.02em;
}
.subtitle {
  margin: 8px 0 0;
  color: #627080;
}
.button-icon {
  margin-right: 5px;
}
.filter-card {
  margin-bottom: 16px;
  background: #f8fafb;
  border-color: #dce5e9;
}
.filter-card :deep(.el-form-item) {
  margin-bottom: 0;
}
.table-card {
  border-color: #dce5e9;
  overflow: hidden;
}
.table-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 13px 16px;
  color: #6b7785;
  font-size: 13px;
  border-top: 1px solid #edf1f3;
}
.link-button {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  border: 0;
  padding: 0;
  background: transparent;
  color: #126c72;
  cursor: pointer;
  text-align: left;
}
.link-button:hover strong,
.link-button:focus-visible strong {
  text-decoration: underline;
}
.link-button small {
  color: #778492;
  font-size: 12px;
  font-weight: 400;
}
.health-dot {
  color: #677584;
  font-size: 12px;
}
.health-dot.ok,
.health-dot.healthy {
  color: #188453;
}
.state-alert {
  margin-bottom: 16px;
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.full-width,
.datasource-form :deep(.el-select) {
  width: 100%;
}
@media (max-width: 680px) {
  .page-heading {
    flex-direction: column;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
  .page-shell {
    padding-inline: 12px;
  }
}
</style>
