<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, type FormInstance, type FormRules } from 'element-plus';
import {
  DataRequestError,
  getMetadata,
  updateDictionary,
  type DictionaryWrite,
  type MetadataColumn,
  type MetadataDetail
} from '../../../services/data';
import { useSessionStore } from '../../../stores/session';
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
let controller: AbortController | null = null;
let generation = 0;
let active = true;
let saveKey = crypto.randomUUID();
const rules: FormRules<DictionaryWrite> = {
  title: [
    { required: true, message: '请输入业务标题', trigger: 'blur' },
    { max: 128, message: '业务标题最多128字符', trigger: 'blur' },
    { pattern: /^[^<>]*$/, message: '业务标题仅支持纯文本', trigger: 'blur' }
  ],
  description: [
    { max: 8000, message: '业务说明最多8000字符', trigger: 'blur' },
    { pattern: /^[^<>]*$/, message: '业务说明仅支持纯文本', trigger: 'blur' }
  ],
  ownerUserId: [
    {
      validator: (_rule, value, callback) => {
        if (value && !/^[1-9]\d*$/.test(String(value))) callback(new Error('负责人 ID 必须为正整数字符串'));
        else callback();
      },
      trigger: 'blur'
    }
  ]
};
function errorMessage(value: unknown): string {
  if (value instanceof DataRequestError) {
    requestId.value = value.requestId;
    return value.message;
  }
  return value instanceof Error ? value.message : '请求失败，请稍后重试';
}
function copyDictionary(value: MetadataDetail): void {
  columns.value = value.columns ?? [];
  Object.assign(form, {
    title: value.dictionary?.title ?? '',
    description: value.dictionary?.description ?? '',
    ownerUserId: value.dictionary?.ownerUserId ?? null,
    columns: (value.dictionary?.columns ?? []).map((column) => ({
      columnName: column.columnName,
      title: column.title ?? '',
      description: column.description ?? ''
    }))
  });
  for (const column of columns.value)
    if (!form.columns.some((item) => item.columnName.toLowerCase() === column.name.toLowerCase()))
      form.columns.push({ columnName: column.name, title: '', description: '' });
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
}
async function load(): Promise<void> {
  controller?.abort();
  const current = ++generation;
  controller = new AbortController();
  detail.value = null;
  columns.value = [];
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
    saveKey = crypto.randomUUID();
  } catch (cause) {
    if (active && current === generation && !(cause instanceof DOMException && cause.name === 'AbortError')) error.value = errorMessage(cause);
  } finally {
    if (active && current === generation) loading.value = false;
  }
}
async function save(): Promise<void> {
  if (!detail.value || saving.value || !etag.value || !session.can('ptmcp:metadata:dictionary')) return;
  const valid = formRef.value
    ? await formRef.value
        .validate()
        .then(() => true)
        .catch(() => false)
    : false;
  if (!valid) return;
  const changedColumns = form.columns.filter((item) => item.title.trim() || item.description.trim());
  if (changedColumns.length > 500 || changedColumns.some((item) => !item.title.trim() || /[<>]/.test(item.title + item.description))) {
    error.value = '每个提交列都需要纯文本业务标题，且最多提交500列。';
    return;
  }
  const current = generation;
  const targetId = id.value;
  saving.value = true;
  error.value = '';
  try {
    const result = await updateDictionary(
      targetId,
      { ...form, ownerUserId: form.ownerUserId || null, columns: changedColumns.map((item) => ({ ...item })) },
      etag.value,
      saveKey
    );
    if (!active || current !== generation) return;
    detail.value = result.data;
    etag.value = result.etag ?? `"${result.data.dictionary.version}"`;
    copyDictionary(result.data);
    saveKey = crypto.randomUUID();
    ElMessage.success('业务字典已保存');
  } catch (cause) {
    if (active && current === generation) error.value = errorMessage(cause);
  } finally {
    saving.value = false;
  }
}
function dictionaryForColumn(name: string): { title: string; description: string } {
  const item = form.columns.find((value) => value.columnName.toLowerCase() === name.toLowerCase());
  return item ?? { title: '', description: '' };
}
watch(
  form,
  () => {
    if (!saving.value) saveKey = crypto.randomUUID();
  },
  { deep: true }
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
    <div class="page-heading">
      <div>
        <ElButton link class="back-link" @click="router.back()">← 返回元数据</ElButton>
        <p class="eyebrow">元数据 / 业务字典</p>
        <h1 id="metadata-detail-title">{{ detail?.objectName || '对象详情' }}</h1>
        <p class="subtitle">
          {{ detail ? `${detail.databaseName}${detail.schemaName ? ` · ${detail.schemaName}` : ''} · ${detail.objectType}` : '加载中…' }}
        </p>
      </div>
      <ElTag v-if="detail" :type="detail.objectType === 'TABLE' ? 'success' : 'info'">{{ detail.objectType === 'TABLE' ? '表' : '视图' }}</ElTag>
    </div>
    <ElAlert v-if="error" type="error" show-icon :closable="false" class="state-alert"
      ><span
        >{{ error }}<span v-if="requestId">（请求 ID：{{ requestId }}）</span></span
      ></ElAlert
    >
    <ElSkeleton v-if="loading" :rows="10" animated />
    <template v-else-if="detail">
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
            ><ElTableColumn prop="comment" label="数据库注释" min-width="210" show-overflow-tooltip
          /></ElTable></div
      ></ElCard>
      <ElCard shadow="never" class="dictionary-card"
        ><template #header
          ><div class="card-title">
            <strong>业务字典</strong><span>版本 {{ detail.dictionary.version }} · 纯文本</span>
          </div></template
        ><ElForm ref="formRef" :model="form" :rules="rules" :disabled="!session.can('ptmcp:metadata:dictionary') || saving" label-position="top"
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
          <div class="form-actions">
            <span class="version-note">保存将生成新字典版本并使用 If-Match 防止覆盖他人修改。</span
            ><ElButton v-if="session.can('ptmcp:metadata:dictionary')" type="primary" :loading="saving" @click="save">保存业务字典</ElButton>
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
.page-shell {
  max-width: 1240px;
  margin: 0 auto;
  padding: 26px clamp(16px, 3vw, 40px) 44px;
}
.page-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-end;
  gap: 18px;
  margin-bottom: 22px;
}
.back-link {
  display: block;
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
.summary-card,
.structure-card,
.dictionary-card {
  border-color: #dce5e9;
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
  color: #7b8792;
  font-size: 12px;
}
dd {
  margin: 5px 0 0;
  color: #263646;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.card-title {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  color: #253545;
}
.card-title span {
  color: #7b8792;
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
  color: #2e4050;
  font-weight: 700;
}
.column-heading small {
  color: #7b8792;
  font-weight: 400;
}
.column-dictionary {
  border: 1px solid #e4eaed;
  border-radius: 6px;
  overflow: hidden;
}
.column-row {
  display: grid;
  grid-template-columns: minmax(160px, 0.8fr) minmax(180px, 1fr) minmax(230px, 1.5fr);
  gap: 12px;
  align-items: center;
  padding: 10px 12px;
  border-bottom: 1px solid #edf1f3;
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
  color: #7b8792;
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
  color: #7b8792;
  font-size: 12px;
}
@media (max-width: 760px) {
  .page-heading {
    align-items: flex-start;
    flex-direction: column;
  }
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
