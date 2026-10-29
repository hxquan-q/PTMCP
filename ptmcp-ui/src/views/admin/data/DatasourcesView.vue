<script setup lang="ts">
import CursorFooter from '../../../components/common/CursorFooter.vue';
import DetailDrawer from '../../../components/common/DetailDrawer.vue';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import StatusBadge from '../../../components/common/StatusBadge.vue';
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import { createDatasource, DataRequestError, listDatasources, type DatasourceRead, type DatasourceWrite } from '../../../services/data';
import { newUuid } from '../../../services/uuid';
import { useCursorPage } from '../../../composables/useCursorPage';
import { useSessionStore } from '../../../stores/session';
import { formatDate } from '../../portalUtils';

const router = useRouter();
const session = useSessionStore();
const submitting = ref(false);
const createError = ref('');
const dialogVisible = ref(false);
const detailRow = ref<DatasourceRead | null>(null);
const detailOpen = ref(false);
const createStep = ref(0);
const formRef = ref<FormInstance>();
const filters = reactive({ keyword: '', engine: '', status: '' });
const form = reactive<DatasourceWrite>(emptyForm());
const createKey = ref(newUuid());
let createGeneration = 0;
let active = true;
function toPageError(cause: unknown): Error {
  if (cause instanceof DataRequestError) {
    const error = new Error(`${cause.message}${cause.code ? `（${cause.code}）` : ''}`);
    return Object.assign(error, { requestId: cause.requestId, code: cause.code, status: cause.status });
  }
  return cause instanceof Error ? cause : new Error('请求失败，请稍后重试');
}
const {
  rows,
  cursor: nextCursor,
  loading,
  loaded,
  error,
  requestId,
  reset: resetList,
  load: loadPage,
} = useCursorPage<DatasourceRead>(async (cursor, signal) => {
  if (!session.can('ptmcp:datasource:list')) return { items: [], nextCursor: null };
  try {
    const result = await listDatasources({ ...filters, cursor, pageSize: 20, signal });
    return { items: result.data.items ?? [], nextCursor: result.data.nextCursor ?? null };
  } catch (cause) {
    throw toPageError(cause);
  }
});

const rules: FormRules<DatasourceWrite> = {
  code: [
    { required: true, message: '请输入编码', trigger: 'blur' },
    { max: 64, message: '编码最多64字符', trigger: 'blur' },
  ],
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
  credential: [
    { required: true, message: '请输入连接凭据', trigger: 'blur' },
    { max: 1024, message: '连接凭据最多1024字符', trigger: 'blur' },
  ],
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
function requestErrorMessage(value: unknown): string {
  return value instanceof Error ? value.message : '请求失败，请稍后重试';
}
async function load(): Promise<void> {
  await loadPage();
}
function applyFilters(): void {
  resetList();
  void load();
}
function nextPage(): void {
  if (nextCursor.value && !loading.value) void loadPage(true);
}
function clearCredential(): void {
  form.credential = '';
}
function clearSensitive(): void {
  Object.assign(form, emptyForm());
  dialogVisible.value = false;
  createGeneration += 1;
  resetList();
  submitting.value = false;
}
function resetForm(): void {
  createGeneration += 1;
  Object.assign(form, emptyForm());
  createKey.value = newUuid();
  createError.value = '';
  createStep.value = 0;
}
async function validateStep(fields: (keyof DatasourceWrite)[]): Promise<boolean> {
  if (!formRef.value) return false;
  try {
    await formRef.value.validateField(fields);
    return true;
  } catch {
    return false;
  }
}
async function nextCreateStep(): Promise<void> {
  // step 0 = 连接信息;step 1 = 归属与资源
  if (createStep.value === 0) {
    if (!(await validateStep(['engine', 'host', 'port', 'databaseName', 'username', 'credential']))) return;
    createStep.value = 1;
  } else {
    await submitCreate();
  }
}
function openCreate(): void {
  if (submitting.value || !session.can('ptmcp:datasource:create')) return;
  resetForm();
  dialogVisible.value = true;
}
function openDetail(row: DatasourceRead): void {
  if (!session.can('ptmcp:datasource:read')) return;
  detailRow.value = row;
  detailOpen.value = true;
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
    if (!submitting.value) createKey.value = newUuid();
  },
  { deep: true },
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
  <section class="page-shell fill-page" aria-labelledby="datasource-title">
    <PageHeader title-id="datasource-title" title="数据源" description="管理受控数据连接、健康状态和元数据同步。">
      <template #actions>
        <ElButton v-if="session.can('ptmcp:datasource:create')" type="primary" @click="openCreate">
          <IconEpPlus class="button-icon" aria-hidden="true" /> 新建数据源
        </ElButton>
      </template>
    </PageHeader>

    <PageState
      v-if="error"
      kind="error"
      title="数据源读取失败"
      :description="error"
      :request-id="requestId"
      action-label="重新读取"
      @action="load()"
    />

    <div class="ptmcp-filter-strip">
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
        <ElButton type="primary" plain @click="applyFilters">查询</ElButton>
      </ElForm>
    </div>

    <ElCard class="table-card ptmcp-table-section ptmcp-fill-card" shadow="never" :body-style="{ padding: '0' }">
      <div class="ptmcp-table-heading">
        <h2 class="ptmcp-section-title">数据源列表</h2>
        <span class="ptmcp-meta-note">共 {{ rows.length }} 条</span>
      </div>
      <div class="ptmcp-table-wrap">
        <ElSkeleton v-if="loading && !rows.length" :rows="5" animated />
        <ElTable
          v-else-if="rows.length || (loaded && !error)"
          v-loading="loading"
          :data="rows"
          class="ptmcp-data-table"
          size="small"
          height="100%"
          row-key="id"
          table-layout="auto"
          empty-text=""
        >
          <ElTableColumn prop="name" label="数据源" min-width="230">
            <template #default="scope">
              <button
                class="link-button ptmcp-cell-lead"
                type="button"
                :disabled="!session.can('ptmcp:datasource:read')"
                @click="router.push(`/admin/datasources/${scope.row.id}`)"
              >
                <span class="ptmcp-cell-icon" :data-engine="scope.row.engine"><IconEpCoin aria-hidden="true" /></span>
                <span class="ptmcp-cell-stack">
                  <strong>{{ scope.row.name }}</strong>
                  <span>{{ scope.row.code }} · ID {{ scope.row.id }}</span>
                </span>
              </button>
            </template>
          </ElTableColumn>
          <ElTableColumn prop="engine" label="引擎" width="130" />
          <ElTableColumn prop="databaseName" label="数据库" min-width="160" show-overflow-tooltip />
          <ElTableColumn label="状态" width="130">
            <template #default="scope">
              <StatusBadge domain="lifecycle" :value="scope.row.status" />
            </template>
          </ElTableColumn>
          <ElTableColumn label="健康" width="120">
            <template #default="scope"><StatusBadge domain="connection" :value="scope.row.health" /></template>
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
          <ElTableColumn label="操作" width="130" align="right" fixed="right">
            <template #default="scope">
              <div class="ptmcp-row-actions">
                <ElButton v-if="session.can('ptmcp:datasource:read')" link type="primary" @click="openDetail(scope.row as DatasourceRead)"
                  >查看</ElButton
                >
                <ElButton v-if="session.can('ptmcp:datasource:read')" link @click="router.push(`/admin/datasources/${scope.row.id}`)">管理</ElButton>
              </div>
            </template>
          </ElTableColumn>
          <template #empty>
            <PageState kind="empty" :description="loaded ? '暂无符合条件的数据源' : '数据源尚未读取'">
              <ElButton v-if="!loading && session.can('ptmcp:datasource:create')" type="primary" @click="openCreate">新建数据源</ElButton>
            </PageState>
          </template>
        </ElTable>
      </div>
      <CursorFooter v-if="loaded || rows.length" :count="rows.length" :has-more="!!nextCursor" :loading="loading" @more="nextPage" />
    </ElCard>

    <DetailDrawer v-model="detailOpen" :title="detailRow?.name ?? ''" :subtitle="`${detailRow?.engine ?? ''} · ${detailRow?.databaseName ?? ''}`">
      <template #icon>
        <span class="ptmcp-cell-icon ptmcp-cell-icon--lg" :data-engine="detailRow?.engine"><IconEpCoin aria-hidden="true" /></span>
      </template>
      <dl class="drawer-fields">
        <dt>编码</dt>
        <dd>{{ detailRow?.code }}</dd>
        <dt>引擎</dt>
        <dd>{{ detailRow?.engine }}</dd>
        <dt>数据库</dt>
        <dd>{{ detailRow?.databaseName }}</dd>
        <dt>主机</dt>
        <dd>{{ detailRow?.host }}:{{ detailRow?.port }}</dd>
        <dt>生命周期</dt>
        <dd><StatusBadge domain="lifecycle" :value="detailRow?.status" /></dd>
        <dt>连接健康</dt>
        <dd><StatusBadge domain="connection" :value="detailRow?.health" /></dd>
        <dt>凭据</dt>
        <dd>{{ detailRow?.credentialConfigured ? '已配置' : '未配置' }}</dd>
        <dt>最近同步</dt>
        <dd>{{ formatDate(detailRow?.lastSyncAt) }}</dd>
        <dt>连接池上限</dt>
        <dd>{{ detailRow?.poolMax }}</dd>
        <dt>活动快照</dt>
        <dd>{{ detailRow?.activeSnapshotId || '待同步' }}</dd>
      </dl>
      <template #footer>
        <ElButton @click="detailOpen = false">关闭</ElButton>
        <ElButton type="primary" @click="router.push(`/admin/datasources/${detailRow?.id}`)">进入管理</ElButton>
      </template>
    </DetailDrawer>

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
      <ol class="create-steps" aria-hidden="true">
        <li :data-state="createStep === 0 ? 'active' : 'done'">
          <span class="create-steps__node"><IconEpCheck v-if="createStep > 0" /><span v-else>1</span></span
          >连接信息
        </li>
        <li class="create-steps__link" :data-state="createStep > 0 ? 'done' : 'todo'" />
        <li :data-state="createStep === 1 ? 'active' : 'todo'"><span class="create-steps__node">2</span>归属与资源</li>
      </ol>
      <ElForm ref="formRef" :model="form" :rules="rules" :disabled="submitting" label-position="top" class="datasource-form">
        <div v-show="createStep === 0" class="form-grid">
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
        </div>
        <div v-show="createStep === 1" class="form-grid">
          <ElFormItem label="编码" prop="code" required><ElInput v-model="form.code" maxlength="64" /></ElFormItem>
          <ElFormItem label="名称" prop="name" required><ElInput v-model="form.name" maxlength="128" /></ElFormItem>
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
        ><ElButton v-if="createStep === 1" :disabled="submitting" @click="createStep = 0">上一步</ElButton
        ><ElButton :disabled="submitting" @click="dialogVisible = false">取消</ElButton
        ><ElButton type="primary" :loading="submitting" @click="nextCreateStep">{{ createStep === 0 ? '下一步' : '创建' }}</ElButton></template
      >
    </ElDialog>
  </section>
</template>

<style scoped>
.button-icon {
  margin-right: 5px;
}
.filter-card {
  margin-bottom: 16px;
  background: var(--ptmcp-chrome);
  border-color: var(--ptmcp-border);
}
.filter-card :deep(.el-form-item) {
  margin-bottom: 0;
}
.filter-card :deep(.el-select) {
  width: 160px;
}
.table-card {
  border-color: var(--ptmcp-border);
  overflow: hidden;
}
.link-button {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--ptmcp-blue);
  cursor: pointer;
  text-align: left;
}
.link-button:hover strong,
.link-button:focus-visible strong {
  text-decoration: underline;
}
.link-button small {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  font-weight: 400;
}
/* 名称列:icon + 双行文字横排,覆盖 link-button 的纵向堆叠 */
.link-button.ptmcp-cell-lead {
  flex-direction: row;
  align-items: center;
}
.state-alert {
  margin-bottom: 16px;
}
/* 新建向导的两步指示条(与 StepWizard 同视觉:32px 圆点 + 连接线) */
.create-steps {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 18px;
  padding: 0;
  list-style: none;
  color: var(--ptmcp-text-muted);
  font-size: 12.5px;
}
.create-steps > li[data-state] {
  display: flex;
  align-items: center;
  gap: 8px;
}
.create-steps__node {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--ptmcp-wash);
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  font-weight: 600;
}
.create-steps > li[data-state='active'] {
  color: var(--ptmcp-heading);
  font-weight: 600;
}
.create-steps > li[data-state='active'] .create-steps__node,
.create-steps > li[data-state='done'] .create-steps__node {
  background: var(--ptmcp-blue);
  color: #fff;
}
.create-steps__link {
  flex: 1;
  min-width: 24px;
  height: 2px;
  background: var(--ptmcp-border);
}
.create-steps__link[data-state='done'] {
  background: var(--ptmcp-blue);
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
  .filter-card :deep(.el-form--inline) {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: 12px;
  }
  .filter-card :deep(.el-form-item) {
    width: 100%;
    margin-right: 0;
  }
  .filter-card :deep(.el-form-item__content) {
    min-width: 0;
  }
  .filter-card :deep(.el-select) {
    width: 100%;
  }
  .form-grid {
    grid-template-columns: 1fr;
  }
}
</style>
