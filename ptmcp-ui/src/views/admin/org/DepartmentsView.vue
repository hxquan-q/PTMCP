<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useSessionStore } from '../../../stores/session';
import { orgPortalApi, type Department } from '../../../services/orgPortalApi';
import { errorText, isConflict } from '../../portalUtils';

type DepartmentNode = Department & { children: DepartmentNode[] };

const loading = ref(false);
const session = useSessionStore();
const saving = ref(false);
const loadError = ref('');
const departments = ref<Department[]>([]);
const dialogVisible = ref(false);
const editing = ref<Department | null>(null);
const form = reactive({ parentId: '', name: '', enabled: true });

const treeData = computed<DepartmentNode[]>(() => {
  const nodes = new Map<string, DepartmentNode>();
  departments.value.forEach((item) => nodes.set(item.id, { ...item, children: [] }));
  const roots: DepartmentNode[] = [];
  nodes.forEach((node) => {
    if (node.parentId && nodes.has(node.parentId)) nodes.get(node.parentId)?.children.push(node);
    else roots.push(node);
  });
  const sort = (items: DepartmentNode[]): void => {
    items.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN'));
    items.forEach((item) => sort(item.children));
  };
  sort(roots);
  return roots;
});

const parentOptions = computed(() => departments.value.filter((item) => item.id !== editing.value?.id));

function resetForm(): void {
  form.parentId = '';
  form.name = '';
  form.enabled = true;
}

function openCreate(): void {
  editing.value = null;
  resetForm();
  dialogVisible.value = true;
}

function openEdit(item: Department): void {
  editing.value = item;
  form.parentId = item.parentId ?? '';
  form.name = item.name;
  form.enabled = item.enabled;
  dialogVisible.value = true;
}

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = '';
  try {
    departments.value = await orgPortalApi.departments.list();
  } catch (error) {
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}

async function save(): Promise<void> {
  if (!session.can(editing.value ? 'ptmcp:department:update' : 'ptmcp:department:create')) return;
  if (!form.name.trim()) {
    ElMessage.warning('请输入部门名称');
    return;
  }
  saving.value = true;
  try {
    const body = { parentId: form.parentId || null, name: form.name.trim(), enabled: form.enabled };
    if (editing.value) await orgPortalApi.departments.update(editing.value.id, body, editing.value.version);
    else await orgPortalApi.departments.create(body);
    dialogVisible.value = false;
    ElMessage.success(editing.value ? '部门已更新' : '部门已创建');
    await load();
  } catch (error) {
    ElMessage.error(isConflict(error) ? '部门已被其他人修改，请刷新后重试' : errorText(error));
  } finally {
    saving.value = false;
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
    ElMessage.error(isConflict(error) ? '部门版本已变化，请刷新后重试' : errorText(error));
  }
}

onMounted(load);
</script>

<template>
  <section class="portal-page" aria-labelledby="departments-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">组织与范围</p>
        <h1 id="departments-title">部门</h1>
        <p class="page-description">维护部门树和管理范围。移动部门由服务端重算祖先关系。</p>
      </div>
      <ElButton v-if="session.can('ptmcp:department:create')" type="primary" @click="openCreate">新建部门</ElButton>
    </div>

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
      <ElTree v-else :data="treeData" node-key="id" default-expand-all aria-label="部门树">
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
    </ElCard>

    <ElDialog v-model="dialogVisible" :title="editing ? '编辑部门' : '新建部门'" width="min(520px, calc(100vw - 32px))">
      <ElForm label-position="top" @submit.prevent="save">
        <ElFormItem label="部门名称" required><ElInput v-model="form.name" maxlength="100" show-word-limit /></ElFormItem>
        <ElFormItem label="上级部门">
          <ElSelect v-model="form.parentId" clearable placeholder="顶级部门">
            <ElOption v-for="item in parentOptions" :key="item.id" :label="item.name" :value="item.id" />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="状态"><ElSwitch v-model="form.enabled" active-text="启用" inactive-text="停用" /></ElFormItem>
      </ElForm>
      <template #footer
        ><ElButton @click="dialogVisible = false">取消</ElButton><ElButton type="primary" :loading="saving" @click="save">保存</ElButton></template
      >
    </ElDialog>
  </section>
</template>

<style scoped>
.page-heading,
.card-header,
.tree-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.page-heading {
  margin-bottom: 24px;
}
.eyebrow {
  margin: 0 0 6px;
  color: var(--ptmcp-accent, #0070c9);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
h1 {
  margin: 0;
  color: var(--ptmcp-ink, #1d2939);
  font-size: 28px;
}
.page-description,
.muted {
  color: var(--ptmcp-muted, #667085);
  font-size: 14px;
}
.page-description {
  margin: 8px 0 0;
}
.portal-card {
  border-color: var(--ptmcp-border, #d9e2ec);
}
.tree-row {
  width: 100%;
  min-height: 34px;
}
.tree-name {
  min-width: 160px;
  color: var(--ptmcp-ink, #1d2939);
  font-weight: 600;
}
.tree-actions {
  margin-left: auto;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
