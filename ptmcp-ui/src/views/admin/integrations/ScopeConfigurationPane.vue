<script setup lang="ts">
import { computed, ref, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { controlPlane, type ScopeRule, type ScopeRead } from '../../../services/controlPlane';
import { listDatasources, listMetadata, type DatasourceRead } from '../../../services/data';
import { mutationNotice, scopeApiReady } from '../../../services/frontendMode';
import { useSessionStore } from '../../../stores/session';
import { errorText, isConflict, usePortalTask } from '../../portalUtils';
const props = withDefaults(defineProps<{ platformId?: string }>(), { platformId: '' });
const session = useSessionStore();
/** 平台 Scope 后端已实现（T17-api 切片）；预览模式继续用合成数据。 */
const scopeReady = scopeApiReady;
const current = ref<ScopeRead | null>(null);
const draft = ref<ScopeRule[]>([]);
const sources = ref<DatasourceRead[]>([]);
const sourceId = ref('');
const options = ref<{ key: string; label: string; rule: ScopeRule }[]>([]);
const resource = ref('');
const action = ref('META');
const includeDescendants = ref(false);
const loading = ref(false);
const saving = ref(false);
const conflict = ref(false);
const error = ref('');
const sourceCursor = ref<string | null>(null);
const objectCursor = ref<string | null>(null);
let alive = true;
const task = usePortalTask(() => {
  alive = false;
  current.value = null;
  draft.value = [];
  options.value = [];
  sources.value = [];
});
const directoryTask = usePortalTask();
const key = (row: ScopeRule): string => `${row.resourceType}:${row.resourceId}:${row.action}`;
const changes = computed(() => {
  const before = new Map((current.value?.items ?? []).map((row) => [key(row), JSON.stringify(row)]));
  const after = new Map(draft.value.map((row) => [key(row), JSON.stringify(row)]));
  return {
    added: draft.value.filter((row) => !before.has(key(row))),
    removed: (current.value?.items ?? []).filter((row) => !after.has(key(row))),
    modified: draft.value.filter((row) => before.has(key(row)) && before.get(key(row)) !== JSON.stringify(row)),
  };
});
const dirty = computed(() => Object.values(changes.value).some((items) => items.length));
async function load(keepDraft = false): Promise<void> {
  if (!scopeReady) return;
  loading.value = true;
  error.value = '';
  try {
    const data = await task.run((signal) => controlPlane.scopes.get(props.platformId, signal));
    if (!data) return;
    current.value = data;
    if (!keepDraft) draft.value = data.items.map((row) => ({ ...row }));
    conflict.value = false;
  } catch (reason) {
    error.value = errorText(reason);
  } finally {
    loading.value = false;
  }
}
async function loadSources(more = false): Promise<void> {
  try {
    const result = await directoryTask.run((signal) => listDatasources({ signal, cursor: more ? sourceCursor.value : undefined, pageSize: 100 }));
    if (!result) return;
    sources.value = more ? [...sources.value, ...result.data.items] : result.data.items;
    sourceCursor.value = result.data.nextCursor;
  } catch (reason) {
    error.value = errorText(reason);
  }
}
async function loadObjects(more = false): Promise<void> {
  if (!sourceId.value) return;
  if (!more) {
    options.value = [];
    resource.value = '';
    objectCursor.value = null;
  }
  try {
    const selected = sourceId.value;
    const result = await directoryTask.run((signal) =>
      listMetadata({ datasourceId: selected, signal, cursor: more ? objectCursor.value : undefined, pageSize: 100 }),
    );
    if (!result || sourceId.value !== selected) return;
    const objectOptions = result.data.items.map((row) => ({
      key: `OBJECT:${row.id}`,
      label: [row.databaseName, row.schemaName, row.objectName].filter(Boolean).join(' / '),
      rule: { resourceType: 'OBJECT', resourceId: row.id, resourceGeneration: row.generation, action: 'META', includeDescendants: false },
    }));
    const source = sources.value.find((row) => row.id === selected);
    options.value = [
      ...(more
        ? options.value
        : source
          ? [
              {
                key: `DATASOURCE:${source.id}`,
                label: `${source.name}（数据源）`,
                rule: { resourceType: 'DATASOURCE', resourceId: source.id, resourceGeneration: '1', action: 'META', includeDescendants: false },
              },
            ]
          : []),
      ...objectOptions,
    ];
    objectCursor.value = result.data.nextCursor;
  } catch (reason) {
    error.value = errorText(reason);
  }
}
function add(): void {
  const option = options.value.find((row) => row.key === resource.value);
  if (!option || !option.rule.resourceGeneration || draft.value.length >= 100) return;
  const row = { ...option.rule, action: action.value, includeDescendants: option.rule.resourceType === 'OBJECT' ? false : includeDescendants.value };
  const index = draft.value.findIndex((item) => key(item) === key(row));
  if (index >= 0) draft.value[index] = row;
  else draft.value.push(row);
}
async function save(): Promise<void> {
  if (saving.value || !current.value || !dirty.value || conflict.value || !session.can('ptmcp:platform:scope')) return;
  saving.value = true;
  try {
    await ElMessageBox.confirm(
      `新增 ${changes.value.added.length}、修改 ${changes.value.modified.length}、删除 ${changes.value.removed.length} 条。Scope 为空将无法读库。确认替换整组资源范围？`,
      '确认 Scope 差异',
      { type: 'warning' },
    );
    const result = await controlPlane.scopes.save(props.platformId, draft.value, current.value.version);
    if (!alive) return;
    current.value = result;
    draft.value = result.items.map((row) => ({ ...row }));
    ElMessage.success(mutationNotice);
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') {
      conflict.value = isConflict(reason);
      error.value = errorText(reason);
    }
  } finally {
    saving.value = false;
  }
}
onMounted(() => {
  if (scopeReady) {
    void load();
    void loadSources();
  }
});
</script>
<template>
  <section aria-labelledby="scope-title">
    <div class="section-heading">
      <h2 id="scope-title">平台资源范围</h2>
      <ElButton :disabled="!scopeReady || saving" @click="load()">重新读取</ElButton>
    </div>
    <p class="helper">范围与用户有效权限取交集。DISCOVER 不开放列结构，META DENY 优先；空 Scope 不可读库。</p>
    <ElAlert v-if="error" type="error" :title="error" :closable="false" />
    <ElAlert v-if="conflict" type="warning" title="版本冲突，草稿已保留。可重新读取并明确将草稿合并至新版本。" :closable="false"
      ><ElButton @click="load(true)">保留草稿，采用新版本</ElButton><ElButton @click="load()">放弃草稿，重新读取</ElButton></ElAlert
    >
    <ElSkeleton v-if="loading" :rows="4" animated />
    <ElForm v-else label-position="top" class="scope-form" @submit.prevent="add">
      <ElFormItem label="数据源"
        ><ElSelect v-model="sourceId" :disabled="!scopeReady || saving" placeholder="选择数据源" @change="loadObjects()"
          ><ElOption v-for="source in sources" :key="source.id" :value="source.id" :label="source.name" /></ElSelect
        ><ElButton v-if="sourceCursor" link @click="loadSources(true)">更多数据源</ElButton></ElFormItem
      >
      <ElFormItem label="目标资源"
        ><ElSelect v-model="resource" filterable :disabled="!options.length || saving" placeholder="选择当前快照资源"
          ><ElOption v-for="option in options" :key="option.key" :value="option.key" :label="option.label" /></ElSelect
        ><ElButton v-if="objectCursor" link @click="loadObjects(true)">更多对象</ElButton></ElFormItem
      >
      <ElFormItem label="动作"
        ><ElSelect v-model="action" :disabled="saving"
          ><ElOption label="META · 目录信息" value="META" /><ElOption label="READ · 读取数据" value="READ" /></ElSelect
      ></ElFormItem>
      <ElFormItem label="继承"
        ><ElCheckbox v-model="includeDescendants" :disabled="resource.startsWith('OBJECT:') || saving">包含当前及未来子级</ElCheckbox></ElFormItem
      >
      <ElButton native-type="submit" :disabled="!resource || !current || draft.length >= 100 || saving || !session.can('ptmcp:platform:scope')"
        >添加 / 更新规则</ElButton
      >
    </ElForm>
    <p class="helper">PG catalog 仅分组；命名空间缺少可信实时 generation，暂不可选。最多100条，不使用手填 ID 替代当前资源身份。</p>
    <ElTable :data="draft" aria-label="Scope 规则"
      ><ElTableColumn prop="resourceType" label="资源类型" min-width="140" /><ElTableColumn
        prop="resourceId"
        label="资源 ID"
        min-width="140"
      /><ElTableColumn prop="resourceGeneration" label="generation" min-width="130" /><ElTableColumn
        prop="action"
        label="动作"
        width="100"
      /><ElTableColumn label="继承" width="100"
        ><template #default="{ row }">{{ row.includeDescendants ? '包含子级' : '仅当前' }}</template></ElTableColumn
      ><ElTableColumn label="操作" width="90"
        ><template #default="{ $index }"
          ><ElButton v-if="session.can('ptmcp:platform:scope')" link type="danger" :disabled="saving" @click="draft.splice($index, 1)"
            >移除</ElButton
          ></template
        ></ElTableColumn
      ><template #empty>{{ current ? 'Scope 为空：平台不可读库' : 'Scope 尚未读取' }}</template></ElTable
    >
    <div class="scope-footer">
      <span
        >scopeVersion：{{ current?.scopeVersion ?? '—' }} · 新增 {{ changes.added.length }} / 修改 {{ changes.modified.length }} / 删除
        {{ changes.removed.length }}</span
      ><ElButton :disabled="!dirty || saving" @click="draft = (current?.items ?? []).map((row) => ({ ...row }))">撤销本地修改</ElButton
      ><ElButton
        v-if="session.can('ptmcp:platform:scope')"
        type="primary"
        :loading="saving"
        :disabled="!scopeReady || !current || !dirty || conflict"
        @click="save"
        >保存 Scope</ElButton
      >
    </div>
  </section>
</template>
<style scoped>
.section-heading,
.scope-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
h2 {
  margin: 0;
  font-size: 16px;
}
.helper,
.scope-footer span {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.6;
  margin: 14px 0;
}
.scope-form {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin: 20px 0;
}
.scope-form .el-select {
  width: 100%;
}
.scope-footer {
  margin-top: 18px;
}
@media (max-width: 700px) {
  .scope-form {
    grid-template-columns: 1fr;
  }
}
</style>
