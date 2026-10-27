<script setup lang="ts">
import PageHeader from '../../../components/common/PageHeader.vue';
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import {
  DataRequestError,
  getMetadata,
  updateDictionary,
  type DictionaryWrite,
  type MetadataColumn,
  type MetadataDetail,
} from '../../../services/data';
import { newUuid } from '../../../services/uuid';
import { useSessionStore } from '../../../stores/session';
import { dictionaryDifferences, dictionaryDraft, isVersionConflict, rebaseDictionaryDraft } from '../../../adapters/edit-conflict';
import VersionConflictPanel from '../../../components/data/VersionConflictPanel.vue';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const id = computed(() => String(route.params.objectId));
const detail = ref<MetadataDetail | null>(null);
const loading = ref(true);
const saving = ref(false);
const error = ref('');
const requestId = ref<string>();
const etag = ref('');
const columns = ref<MetadataColumn[]>([]);
const formRef = ref<FormInstance>();
const form = reactive<DictionaryWrite>({ title: '', description: '', ownerUserId: null, columns: [] });
const conflict = ref<{ base: MetadataDetail; latest: MetadataDetail; etag: string; resolved: boolean } | null>(null);
const conflictRequired = ref(false);
const conflictLoading = ref(false);
const removedDraft = ref<DictionaryWrite['columns']>([]);
const conflictRows = computed(() => (conflict.value ? dictionaryDifferences(conflict.value.base, conflict.value.latest, form) : []));
let controller: AbortController | null = null;
let generation = 0;
let active = true;
let saveKey = newUuid();
const rules: FormRules<DictionaryWrite> = {
  title: [
    { required: true, message: '请输入业务标题', trigger: 'blur' },
    { max: 128, message: '业务标题最多128字符', trigger: 'blur' },
    { pattern: /^[^<>]*$/, message: '业务标题仅支持纯文本', trigger: 'blur' },
  ],
  description: [
    { max: 8000, message: '业务说明最多8000字符', trigger: 'blur' },
    { pattern: /^[^<>]*$/, message: '业务说明仅支持纯文本', trigger: 'blur' },
  ],
  ownerUserId: [
    {
      validator: (_rule, value, callback) => {
        if (value && !/^[1-9]\d*$/.test(String(value))) callback(new Error('负责人 ID 必须为正整数字符串'));
        else callback();
      },
      trigger: 'blur',
    },
  ],
};
function errorMessage(value: unknown): string {
  if (value instanceof DataRequestError) {
    requestId.value = value.requestId;
    return `${value.message}${value.code ? `（${value.code}）` : ''}`;
  }
  return value instanceof Error ? value.message : '请求失败，请稍后重试';
}
function copyDictionary(value: MetadataDetail): void {
  columns.value = value.columns;
  Object.assign(form, dictionaryDraft(value));
}
function clearSensitive(): void {
  controller?.abort();
  generation += 1;
  detail.value = null;
  columns.value = [];
  form.title = '';
  form.description = '';
  form.ownerUserId = null;
  form.columns = [];
  etag.value = '';
  conflict.value = null;
  conflictRequired.value = false;
  conflictLoading.value = false;
  removedDraft.value = [];
  saving.value = false;
}
function recordFailure(cause: unknown): void {
  const message = errorMessage(cause);
  if (cause instanceof DataRequestError && (cause.status === 401 || cause.status === 404 || (cause.status === 403 && cause.code !== 'CSRF_INVALID')))
    clearSensitive();
  error.value = message;
}
async function readConflict(): Promise<void> {
  if (conflictLoading.value || !detail.value || !session.can('ptmcp:metadata:read')) return;
  const current = generation;
  const base = conflict.value?.base ?? detail.value;
  const targetId = id.value;
  conflictLoading.value = true;
  error.value = '';
  try {
    const result = await getMetadata(targetId, controller?.signal);
    if (!active || current !== generation) return;
    conflict.value = { base, latest: result.data, etag: result.etag ?? `"${result.data.dictionary.version}"`, resolved: false };
  } catch (cause) {
    if (active && current === generation && !(cause instanceof DOMException && cause.name === 'AbortError')) recordFailure(cause);
  } finally {
    if (active && current === generation) conflictLoading.value = false;
  }
}
function resolveConflict(keepDraft: boolean): void {
  if (!conflict.value || conflictLoading.value || saving.value || !session.can('ptmcp:metadata:read')) return;
  const latest = conflict.value.latest;
  etag.value = conflict.value.etag;
  detail.value = latest;
  if (keepDraft) {
    const rebased = rebaseDictionaryDraft(form, latest);
    Object.assign(form, rebased.draft);
    columns.value = latest.columns;
    removedDraft.value = rebased.removed;
    conflict.value.resolved = true;
  } else {
    copyDictionary(latest);
    conflict.value = null;
    removedDraft.value = [];
  }
  conflictRequired.value = false;
  error.value = '';
  saveKey = newUuid();
}
async function load(): Promise<void> {
  controller?.abort();
  const current = ++generation;
  controller = new AbortController();
  detail.value = null;
  columns.value = [];
  etag.value = '';
  conflict.value = null;
  conflictRequired.value = false;
  conflictLoading.value = false;
  removedDraft.value = [];
  loading.value = true;
  error.value = '';
  if (!session.can('ptmcp:metadata:read')) {
    loading.value = false;
    return;
  }
  try {
    const result = await getMetadata(id.value, controller.signal);
    if (!active || current !== generation) return;
    detail.value = result.data;
    etag.value = result.etag ?? `"${result.data.dictionary.version}"`;
    copyDictionary(result.data);
    saveKey = newUuid();
  } catch (cause) {
    if (active && current === generation && !(cause instanceof DOMException && cause.name === 'AbortError')) error.value = errorMessage(cause);
  } finally {
    if (active && current === generation) loading.value = false;
  }
}
async function save(): Promise<void> {
  if (
    !detail.value ||
    saving.value ||
    conflictRequired.value ||
    removedDraft.value.length ||
    !etag.value ||
    !session.can('ptmcp:metadata:dictionary')
  )
    return;
  const current = generation;
  const targetId = id.value;
  saving.value = true;
  error.value = '';
  try {
    const valid = formRef.value
      ? await formRef.value
          .validate()
          .then(() => true)
          .catch(() => false)
      : false;
    if (!valid || !active || current !== generation || !session.can('ptmcp:metadata:dictionary')) return;
    const changedColumns = form.columns.filter((item) => item.title.trim() || item.description.trim());
    if (changedColumns.length > 500 || changedColumns.some((item) => !item.title.trim() || /[<>]/.test(item.title + item.description))) {
      error.value = '每个提交列都需要纯文本业务标题，且最多提交500列。';
      return;
    }
    const result = await updateDictionary(
      targetId,
      { ...form, ownerUserId: form.ownerUserId || null, columns: changedColumns.map((item) => ({ ...item })) },
      etag.value,
      saveKey,
    );
    if (!active || current !== generation) return;
    detail.value = result.data;
    etag.value = result.etag ?? `"${result.data.dictionary.version}"`;
    copyDictionary(result.data);
    conflict.value = null;
    saveKey = newUuid();
    ElMessage.success('业务字典已保存');
  } catch (cause) {
    if (!active || current !== generation) return;
    if (cause instanceof DataRequestError && isVersionConflict(cause)) {
      conflict.value = null;
      conflictRequired.value = true;
      await readConflict();
    } else recordFailure(cause);
  } finally {
    if (active && current === generation) saving.value = false;
  }
}
function dictionaryForColumn(name: string): { title: string; description: string } {
  const item = form.columns.find((value) => value.columnName.toLowerCase() === name.toLowerCase());
  return item ?? { title: '', description: '' };
}
watch(
  form,
  () => {
    if (!saving.value) saveKey = newUuid();
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
  <section class="page-shell" aria-labelledby="metadata-detail-title">
    <PageHeader
      title-id="metadata-detail-title"
      :title="detail?.objectName || '对象详情'"
      :description="detail ? `${detail.databaseName}${detail.schemaName ? ` · ${detail.schemaName}` : ''} · ${detail.objectType}` : '加载中…'"
    >
      <template #before><ElButton link class="back-link" @click="router.back()">← 返回元数据</ElButton></template>
      <template #status
        ><ElTag v-if="detail" :type="detail.objectType === 'TABLE' ? 'success' : 'info'">{{
          detail.objectType === 'TABLE' ? '表' : '视图'
        }}</ElTag></template
      >
    </PageHeader>
    <ElAlert v-if="error" type="error" show-icon :closable="false" class="state-alert"
      ><span
        >{{ error }}<span v-if="requestId">（请求 ID：{{ requestId }}）</span></span
      ></ElAlert
    >
    <VersionConflictPanel
      v-if="conflictRequired || conflict"
      :rows="conflictRows"
      :loading="conflictLoading || saving"
      :ready="!!conflict"
      :resolved="conflict?.resolved ?? false"
      :versions="
        conflict
          ? `字典版本 ${conflict.base.dictionary.version} → ${conflict.latest.dictionary.version}；快照版本 ${conflict.base.snapshotVersion} → ${conflict.latest.snapshotVersion}`
          : ''
      "
      @refresh="readConflict"
      @latest="resolveConflict(false)"
      @draft="resolveConflict(true)"
    />
    <ElSkeleton v-if="loading" :rows="10" animated />
    <template v-else-if="detail">
      <ElAlert v-if="detail.objectType === 'VIEW'" type="warning" show-icon :closable="false" class="state-alert">
        视图默认不可执行。当前接口未提供依赖及定义核验状态；只有视图和全部底层关系具备 READ 权限，且专项验证通过后才能开放。
      </ElAlert>
      <ElCard shadow="never" class="summary-card"
        ><dl class="facts">
          <div>
            <dt>物理标识</dt>
            <dd>{{ detail.physicalIdentity }}</dd>
          </div>
          <div>
            <dt>数据库注释</dt>
            <dd>{{ detail.tableComment || '—' }}</dd>
          </div>
          <div>
            <dt>快照版本</dt>
            <dd>{{ detail.snapshotVersion }}</dd>
          </div>
          <div>
            <dt>资源代次</dt>
            <dd>{{ detail.generation }}</dd>
          </div>
        </dl></ElCard
      >
      <ElCard shadow="never" class="structure-card"
        ><template #header
          ><div class="card-title">
            <strong>字段结构</strong><span>{{ columns.length }} 列 · 只读</span>
          </div></template
        >
        <div class="table-scroll">
          <ElTable :data="columns" row-key="name" size="small"
            ><ElTableColumn prop="ordinal" label="#" width="65" /><ElTableColumn prop="name" label="列名" min-width="170" /><ElTableColumn
              prop="databaseType"
              label="类型"
              min-width="150" /><ElTableColumn label="可空" width="80"
              ><template #default="scope">{{ scope.row.nullable ? '是' : '否' }}</template></ElTableColumn
            ><ElTableColumn label="主键" width="80"
              ><template #default="scope">{{ scope.row.primaryKey ? '是' : '—' }}</template></ElTableColumn
            ><ElTableColumn prop="precision" label="精度" width="80" /><ElTableColumn prop="scale" label="小数位" width="80" /><ElTableColumn
              prop="maxLength"
              label="最大长度"
              width="110" />
            <ElTableColumn label="外键信息" min-width="200"
              ><template #default="scope">{{ scope.row.foreignKey ? JSON.stringify(scope.row.foreignKey) : '—' }}</template></ElTableColumn
            >
            <ElTableColumn prop="comment" label="数据库注释" min-width="210" show-overflow-tooltip
          /></ElTable></div
      ></ElCard>
      <ElCard shadow="never" class="dictionary-card"
        ><template #header
          ><div class="card-title">
            <strong>业务字典</strong><span>版本 {{ detail.dictionary.version }} · 纯文本</span>
          </div></template
        ><ElForm
          ref="formRef"
          :model="form"
          :rules="rules"
          :disabled="!session.can('ptmcp:metadata:dictionary') || saving || conflictRequired"
          label-position="top"
          ><ElFormItem label="业务标题" prop="title" required><ElInput v-model="form.title" maxlength="128" show-word-limit /></ElFormItem
          ><ElFormItem label="业务说明" prop="description"
            ><ElInput v-model="form.description" type="textarea" :rows="4" maxlength="8000" show-word-limit /></ElFormItem
          ><ElFormItem label="负责人 ID" prop="ownerUserId"
            ><ElInput v-model="form.ownerUserId" inputmode="numeric" placeholder="可留空"
          /></ElFormItem>
          <div class="column-heading"><span>列级说明</span><small>列名来自当前快照，不能新增或执行 DDL。</small></div>
          <div class="column-dictionary">
            <div v-for="column in columns" :key="column.name" class="column-row">
              <div class="column-name">
                <strong>{{ column.name }}</strong
                ><small>{{ column.databaseType }}</small>
              </div>
              <div><ElInput v-model="dictionaryForColumn(column.name).title" maxlength="128" placeholder="列业务标题" /></div>
              <div><ElInput v-model="dictionaryForColumn(column.name).description" maxlength="8000" placeholder="列业务说明" /></div>
            </div>
          </div>
          <div v-if="removedDraft.length" class="removed-draft" role="region" aria-label="最新结构已移除列的草稿">
            <ElAlert type="warning" :closable="false"
              >以下列已不在最新结构中，草稿暂存于本页，不会提交。请先将需要的说明复制到保留字段，处理后再保存。</ElAlert
            >
            <ElTable :data="removedDraft" row-key="columnName" size="small"
              ><ElTableColumn prop="columnName" label="失效列" min-width="140" /><ElTableColumn
                prop="title"
                label="草稿标题"
                min-width="180" /><ElTableColumn prop="description" label="草稿说明" min-width="220"
            /></ElTable>
            <ElButton :disabled="saving" @click="removedDraft = []">我已处理，放弃这些失效列草稿</ElButton>
          </div>
          <div class="form-actions">
            <span class="version-note">保存将生成新字典版本并使用 If-Match 防止覆盖他人修改。</span
            ><ElButton
              v-if="session.can('ptmcp:metadata:dictionary')"
              type="primary"
              :loading="saving"
              :disabled="conflictRequired || !!removedDraft.length"
              @click="save"
              >保存业务字典</ElButton
            >
          </div></ElForm
        ></ElCard
      >
    </template>
    <ElEmpty v-else description="对象不存在、快照已失效或你已失去访问权限"
      ><ElButton type="primary" @click="router.push('/admin/metadata')">返回元数据</ElButton></ElEmpty
    >
  </section>
</template>

<style scoped>
.back-link {
  display: block;
  margin-bottom: 14px;
  padding: 0;
  color: var(--ptmcp-text-muted);
}
.state-alert {
  margin-bottom: 16px;
}
.summary-card,
.structure-card,
.dictionary-card {
  border-color: var(--ptmcp-border);
}
.structure-card,
.dictionary-card {
  margin-top: 16px;
}
.facts {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18px;
  margin: 0;
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
.card-title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  color: var(--ptmcp-heading);
}
.card-title span {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  font-weight: 400;
}
.table-scroll {
  overflow-x: auto;
}
.column-heading {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  margin: 22px 0 9px;
  color: var(--ptmcp-heading);
  font-weight: 700;
}
.column-heading small {
  color: var(--ptmcp-text-muted);
  font-weight: 400;
}
.column-dictionary {
  border: 1px solid var(--ptmcp-border);
  border-radius: 6px;
  overflow: hidden;
}
.column-row {
  display: grid;
  grid-template-columns: minmax(160px, 0.8fr) minmax(180px, 1fr) minmax(230px, 1.5fr);
  gap: 12px;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid var(--ptmcp-border);
}
.column-row:last-child {
  border-bottom: 0;
}
.column-name {
  display: flex;
  flex-direction: column;
  gap: 3px;
  overflow-wrap: anywhere;
}
.column-name small {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  font-weight: 400;
}
.form-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-top: 22px;
}
.version-note {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
}
.removed-draft {
  margin-top: 18px;
  overflow-x: auto;
}
.removed-draft :deep(.el-button) {
  margin-top: 12px;
}
@media (max-width: 760px) {
  .facts {
    grid-template-columns: repeat(2, 1fr);
  }
  .column-row {
    grid-template-columns: 1fr;
  }
  .form-actions {
    align-items: flex-start;
    flex-direction: column;
  }
  .form-actions .el-button {
    width: 100%;
  }
}
</style>
