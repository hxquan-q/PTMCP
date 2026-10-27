<script setup lang="ts">
import PageHeader from '../../../components/common/PageHeader.vue';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useSessionStore } from '../../../stores/session';
import { orgPortalApi, type Department } from '../../../services/orgPortalApi';
import { departmentDescendants } from '../../../services/portalMutations';
import { errorText, isAccessError, isConflict, usePortalTask } from '../../portalUtils';
import { frontendPreview } from '../../../services/frontendMode';

type DepartmentNode = Department & { children: DepartmentNode[] };

const loading = ref(false);
const session = useSessionStore();
const saving = ref(false);
const loadError = ref('');
const departments = ref<Department[]>([]);
const dialogVisible = ref(false);
const editing = ref<Department | null>(null);
const conflict = ref<Department | null>(null);
const conflictDetected = ref(false);
const form = reactive({ parentId: '', name: '', enabled: true, orderNum: 0 });
const selected = ref<Department | null>(null);
const clearPage = (): void => {
  departments.value = [];
  editing.value = null;
  conflict.value = null;
  conflictDetected.value = false;
  dialogVisible.value = false;
  selected.value = null;
};
const departmentsTask = usePortalTask(clearPage);
const conflictTask = usePortalTask(clearPage);

const treeData = computed<DepartmentNode[]>(() => {
  const nodes = new Map<string, DepartmentNode>();
  departments.value.forEach((item) => nodes.set(item.id, { ...item, children: [] }));
  const roots: DepartmentNode[] = [];
  nodes.forEach((node) => {
    if (node.parentId && nodes.has(node.parentId)) nodes.get(node.parentId)?.children.push(node);
    else roots.push(node);
  });
  const sort = (items: DepartmentNode[]): void => {
    items.sort((a, b) => (a.orderNum ?? 0) - (b.orderNum ?? 0) || a.name.localeCompare(b.name, 'zh-CN'));
    items.forEach((item) => sort(item.children));
  };
  sort(roots);
  return roots;
});

function descendantIds(id: string): Set<string> {
  return departmentDescendants(departments.value, id);
}
const parentOptions = computed(() => {
  const excluded = editing.value ? descendantIds(editing.value.id) : new Set<string>();
  return departments.value.filter((item) => !excluded.has(item.id));
});
function pathLabel(id: string | null): string {
  const labels: string[] = [];
  const visited = new Set<string>();
  let current = id;
  while (current && !visited.has(current)) {
    visited.add(current);
    const department = departments.value.find((item) => item.id === current);
    labels.unshift(department?.name ?? current);
    current = department?.parentId ?? null;
  }
  return labels.length ? labels.join(' / ') : '根节点';
}

function resetForm(): void {
  form.parentId = '';
  form.name = '';
  form.enabled = true;
  form.orderNum = 0;
}

function openCreate(): void {
  editing.value = null;
  conflict.value = null;
  conflictDetected.value = false;
  resetForm();
  dialogVisible.value = true;
}

function openEdit(item: Department): void {
  editing.value = item;
  conflict.value = null;
  conflictDetected.value = false;
  form.parentId = item.parentId ?? '';
  form.name = item.name;
  form.enabled = item.enabled;
  form.orderNum = item.orderNum ?? 0;
  dialogVisible.value = true;
}

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = '';
  try {
    const items = await departmentsTask.run((signal) => orgPortalApi.departments.list(signal));
    if (items) departments.value = items;
  } catch (error) {
    if (isAccessError(error)) clearPage();
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}

async function save(): Promise<void> {
  if (saving.value || conflictDetected.value) return;
  if (!session.can(editing.value ? 'ptmcp:department:update' : 'ptmcp:department:create')) return;
  if (!form.name.trim() || form.name.trim().length > 30) {
    ElMessage.warning('部门名称需 1～30 个字符');
    return;
  }
  saving.value = true;
  try {
    const body = {
      parentId: form.parentId || null,
      name: form.name.trim(),
      enabled: form.enabled,
      ...(frontendPreview ? { orderNum: form.orderNum } : {}),
    };
    if (editing.value && body.parentId !== editing.value.parentId) {
      if (body.parentId && descendantIds(editing.value.id).has(body.parentId)) throw new Error('不能移动到当前部门或其子部门');
      await ElMessageBox.confirm(
        `将“${pathLabel(editing.value.id)}”移动到“${pathLabel(body.parentId)} / ${form.name}”。影响子树共 ${descendantIds(editing.value.id).size} 个部门，成员授权将重新计算。`,
        '确认移动部门',
        { type: 'warning' },
      );
    }
    if (editing.value) await orgPortalApi.departments.update(editing.value.id, body, editing.value.version);
    else await orgPortalApi.departments.create(body);
    dialogVisible.value = false;
    ElMessage.success(editing.value ? '部门已更新' : '部门已创建');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    if (isConflict(error) && editing.value) {
      conflictDetected.value = true;
      await refreshConflict();
      ElMessage.warning('部门版本冲突，草稿已保留。请核对后继续。');
    } else ElMessage.error(errorText(error));
  } finally {
    saving.value = false;
  }
}

function mergeServerVersion(): void {
  if (!conflict.value) return;
  editing.value = conflict.value;
  conflict.value = null;
  conflictDetected.value = false;
}
async function refreshConflict(): Promise<void> {
  if (!editing.value) return;
  const id = editing.value.id;
  try {
    const item = await conflictTask.run((signal) => orgPortalApi.departments.get(id, signal));
    if (item) conflict.value = item;
  } catch (error) {
    ElMessage.error(errorText(error));
  }
}

async function remove(item: Department): Promise<void> {
  if (!session.can('ptmcp:department:delete')) return;
  try {
    await ElMessageBox.confirm(`确定删除“${item.name}”吗？部门下仍有成员或子部门时服务端会拒绝。`, '删除部门', { type: 'warning' });
    await orgPortalApi.departments.remove(item.id, item.version);
    ElMessage.success('部门已删除');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    if (isConflict(error)) loadError.value = '删除未完成：部门版本已变化，或仍存在子部门/用户。请刷新树并处理关联项后手动重试。';
    else ElMessage.error(errorText(error));
  }
}

watch(
  () => session.can('ptmcp:department:list'),
  (allowed) => {
    if (!allowed) {
      departmentsTask.cancel();
      clearPage();
    }
  },
);
onMounted(load);
</script>

<template>
  <section class="portal-page" aria-labelledby="departments-title">
    <PageHeader title-id="departments-title" title="部门" description="维护部门树和管理范围。移动部门由服务端重算祖先关系。">
      <template #actions><ElButton v-if="session.can('ptmcp:department:create')" type="primary" @click="openCreate">新建部门</ElButton></template>
    </PageHeader>
    <p v-if="!frontendPreview" class="page-description">部门排序保存接口待接入；子部门数量按当前已读取树计算。</p>

    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError">
      <template #default><ElButton link type="danger" @click="load">重新加载</ElButton></template>
    </ElAlert>

    <ElCard shadow="never" class="portal-card">
      <template #header>
        <div class="card-header">
          <span>部门层级</span><span class="muted">{{ departments.length }} 个部门</span>
        </div>
      </template>
      <ElSkeleton v-if="loading" :rows="5" animated />
      <ElEmpty v-else-if="!departments.length" description="尚未创建部门" />
      <ElTree v-else :data="treeData" node-key="id" default-expand-all aria-label="部门树" @node-click="selected = $event">
        <template #default="{ data }">
          <div class="tree-row">
            <span class="tree-name">{{ data.name }}</span>
            <ElTag size="small" :type="data.enabled ? 'success' : 'info'">{{ data.enabled ? '启用' : '停用' }}</ElTag>
            <span class="tree-actions">
              <ElButton v-if="session.can('ptmcp:department:update')" link type="primary" @click.stop="openEdit(data)">编辑</ElButton>
              <ElButton v-if="session.can('ptmcp:department:delete')" link type="danger" @click.stop="remove(data)">删除</ElButton>
            </span>
          </div>
        </template>
      </ElTree>
      <ElDescriptions v-if="selected" :column="1" border class="department-detail"
        ><ElDescriptionsItem label="部门 ID">{{ selected.id }}</ElDescriptionsItem
        ><ElDescriptionsItem label="路径">{{ pathLabel(selected.id) }}</ElDescriptionsItem
        ><ElDescriptionsItem label="上级 ID">{{ selected.parentId ?? '根节点' }}</ElDescriptionsItem
        ><ElDescriptionsItem label="排序">{{ selected.orderNum ?? '服务端未提供' }}</ElDescriptionsItem
        ><ElDescriptionsItem label="直接子部门">{{ departments.filter((row) => row.parentId === selected?.id).length }}</ElDescriptionsItem
        ><ElDescriptionsItem label="版本">{{ selected.version }}</ElDescriptionsItem></ElDescriptions
      >
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="editing ? '编辑部门' : '新建部门'" width="min(520px, calc(100vw - 32px))">
      <ElAlert v-if="conflictDetected" type="warning" :closable="false" title="服务器版本已变化，当前草稿已保留。" class="mb-4"
        ><template v-if="conflict"
          ><p>
            服务器：{{ conflict.name }} · 上级 {{ pathLabel(conflict.parentId) }} · {{ conflict.enabled ? '启用' : '停用' }}（版本
            {{ conflict.version }}）
          </p>
          <ElButton size="small" @click="mergeServerVersion">已核对，以新版本继续编辑</ElButton
          ><ElButton size="small" @click="openEdit(conflict)">使用服务器值重置</ElButton></template
        ><ElButton v-else size="small" @click="refreshConflict">读取当前版本并比较</ElButton></ElAlert
      >
      <ElForm label-position="top" :disabled="saving" @submit.prevent="save">
        <ElFormItem label="部门名称" required><ElInput v-model="form.name" maxlength="30" show-word-limit /></ElFormItem>
        <ElFormItem label="上级部门">
          <ElSelect v-model="form.parentId" clearable placeholder="顶级部门">
            <ElOption v-for="item in parentOptions" :key="item.id" :label="item.name" :value="item.id" />
          </ElSelect>
          <p class="helper">顶级部门（不选上级）仅平台管理员可创建；委派管理员请选择委派范围内的上级部门。</p>
        </ElFormItem>
        <ElFormItem label="状态"><ElSwitch v-model="form.enabled" active-text="启用" inactive-text="停用" /></ElFormItem>
        <ElFormItem label="排序号"
          ><ElInputNumber v-model="form.orderNum" :min="0" :max="9999" :disabled="!frontendPreview" /><span v-if="!frontendPreview" class="helper"
            >接口待接入</span
          ></ElFormItem
        >
      </ElForm>
      <template #footer
        ><ElButton @click="dialogVisible = false">取消</ElButton
        ><ElButton type="primary" :disabled="conflictDetected" :loading="saving" @click="save">保存</ElButton></template
      >
    </ElDialog>
  </section>
</template>

<style scoped>
.helper {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.6;
  margin: 4px 0 0;
}
.department-detail {
  margin-top: 24px;
}
.card-header,
.tree-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.page-description,
.muted {
  color: var(--ptmcp-muted);
  font-size: 14px;
}
.page-description {
  margin: 8px 0 0;
}
.portal-card {
  border-color: var(--ptmcp-border);
}
.tree-row {
  width: 100%;
  min-height: 34px;
}
.tree-name {
  min-width: 160px;
  color: var(--ptmcp-ink);
  font-weight: 600;
}
.tree-actions {
  margin-left: auto;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
