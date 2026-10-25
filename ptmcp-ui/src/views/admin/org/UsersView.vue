<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { orgPortalApi, type Department, type Role, type User } from '../../../services/orgPortalApi';
import { errorText, isConflict } from '../../portalUtils';

const loading = ref(false);
const saving = ref(false);
const loadError = ref('');
const users = ref<User[]>([]);
const departments = ref<Department[]>([]);
const roles = ref<Role[]>([]);
const keyword = ref('');
const departmentFilter = ref('');
const dialogVisible = ref(false);
const editing = ref<User | null>(null);
const form = reactive({ username: '', displayName: '', deptId: '', roleIds: [] as string[], enabled: true });

const canEdit = computed(() => roles.value.length > 0 || !editing.value);

function resetForm(): void {
  form.username = '';
  form.displayName = '';
  form.deptId = departments.value[0]?.id ?? '';
  form.roleIds = [];
  form.enabled = true;
}

function openCreate(): void {
  editing.value = null;
  resetForm();
  dialogVisible.value = true;
}

function openEdit(user: User): void {
  editing.value = user;
  form.username = user.username;
  form.displayName = user.displayName;
  form.deptId = user.deptId;
  form.roleIds = [...user.roleIds];
  form.enabled = user.enabled;
  dialogVisible.value = true;
}

async function loadLookups(): Promise<void> {
  const [departmentResult, roleResult] = await Promise.allSettled([orgPortalApi.departments.list(), orgPortalApi.roles.list()]);
  if (departmentResult.status === 'fulfilled') departments.value = departmentResult.value;
  if (roleResult.status === 'fulfilled') roles.value = roleResult.value;
  // A failed role request must not replace an existing role list with an empty array.
  if (roleResult.status === 'rejected' && roles.value.length === 0) ElMessage.warning('角色列表暂时不可用，角色选择已禁用');
}

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = '';
  try {
    await loadLookups();
    users.value = await orgPortalApi.users.list({ departmentId: departmentFilter.value || undefined, keyword: keyword.value.trim() || undefined });
  } catch (error) {
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}

async function save(): Promise<void> {
  if (!form.username.trim() || !form.displayName.trim() || !form.deptId) {
    ElMessage.warning('请填写用户名、显示名称和部门');
    return;
  }
  if (!canEdit.value) {
    ElMessage.warning('角色信息加载失败，无法安全保存');
    return;
  }
  saving.value = true;
  try {
    const body = {
      username: form.username.trim(),
      displayName: form.displayName.trim(),
      deptId: form.deptId,
      roleIds: [...form.roleIds],
      enabled: form.enabled
    };
    if (editing.value) await orgPortalApi.users.update(editing.value.id, body, editing.value.version);
    else await orgPortalApi.users.create(body);
    dialogVisible.value = false;
    ElMessage.success(editing.value ? '用户已更新' : '用户已创建');
    await load();
  } catch (error) {
    ElMessage.error(isConflict(error) ? '用户已被其他人修改，请刷新后重试' : errorText(error));
  } finally {
    saving.value = false;
  }
}

async function toggleStatus(user: User): Promise<void> {
  const next = user.enabled ? 'DISABLED' : 'ACTIVE';
  try {
    await ElMessageBox.confirm(`确定${user.enabled ? '停用' : '启用'}“${user.displayName}”吗？`, '更新用户状态', {
      type: user.enabled ? 'warning' : 'info'
    });
    await orgPortalApi.users.setStatus(user.id, next, user.version);
    ElMessage.success('状态已更新');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(isConflict(error) ? '用户版本已变化，请刷新后重试' : errorText(error));
  }
}

function roleNames(ids: string[]): string {
  return ids.map((id) => roles.value.find((role) => role.id === id)?.name ?? `角色 ${id}`).join('、') || '未分配角色';
}

function departmentName(id: string): string {
  return departments.value.find((department) => department.id === id)?.name ?? `部门 ${id}`;
}

onMounted(load);
</script>

<template>
  <section class="portal-page" aria-labelledby="users-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">组织与范围</p>
        <h1 id="users-title">用户</h1>
        <p class="page-description">管理用户状态、部门归属和角色。每项变更都携带资源版本。</p>
      </div>
      <ElButton type="primary" @click="openCreate">新建用户</ElButton>
    </div>
    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError"
      ><template #default><ElButton link type="danger" @click="load">重新加载</ElButton></template></ElAlert
    >
    <ElCard shadow="never" class="portal-card">
      <div class="filters" role="search">
        <ElInput v-model="keyword" clearable placeholder="搜索用户名或显示名称" aria-label="搜索用户名或显示名称" @keyup.enter="load" />
        <ElSelect v-model="departmentFilter" clearable placeholder="全部部门" aria-label="部门筛选" @change="load"
          ><ElOption v-for="item in departments" :key="item.id" :label="item.name" :value="item.id"
        /></ElSelect>
        <ElButton type="primary" plain @click="load">筛选</ElButton>
      </div>
      <ElSkeleton v-if="loading" :rows="5" animated />
      <ElEmpty v-else-if="!users.length" description="没有符合条件的用户" />
      <ElTable v-else :data="users" stripe row-key="id" aria-label="用户列表">
        <ElTableColumn prop="username" label="用户名" min-width="160" />
        <ElTableColumn prop="displayName" label="显示名称" min-width="140" />
        <ElTableColumn label="部门" min-width="140"
          ><template #default="{ row }">{{ departmentName(row.deptId) }}</template></ElTableColumn
        >
        <ElTableColumn label="角色" min-width="180"
          ><template #default="{ row }"
            ><span class="muted">{{ roleNames(row.roleIds) }}</span></template
          ></ElTableColumn
        >
        <ElTableColumn label="状态" width="100"
          ><template #default="{ row }"
            ><ElTag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</ElTag></template
          ></ElTableColumn
        >
        <ElTableColumn label="操作" fixed="right" width="180"
          ><template #default="{ row }"
            ><ElButton link type="primary" @click="openEdit(row)">编辑</ElButton
            ><ElButton link :type="row.enabled ? 'danger' : 'success'" @click="toggleStatus(row)">{{
              row.enabled ? '停用' : '启用'
            }}</ElButton></template
          ></ElTableColumn
        >
      </ElTable>
    </ElCard>
    <ElDialog v-model="dialogVisible" :title="editing ? '编辑用户' : '新建用户'" width="min(600px, calc(100vw - 32px))">
      <ElForm label-position="top">
        <div class="form-grid">
          <ElFormItem label="用户名" required><ElInput v-model="form.username" :disabled="Boolean(editing)" maxlength="100" /></ElFormItem
          ><ElFormItem label="显示名称" required><ElInput v-model="form.displayName" maxlength="100" /></ElFormItem>
        </div>
        <ElFormItem label="部门" required
          ><ElSelect v-model="form.deptId" class="wide-select"
            ><ElOption v-for="item in departments" :key="item.id" :label="item.name" :value="item.id" /></ElSelect
        ></ElFormItem>
        <ElFormItem label="角色"
          ><ElSelect v-model="form.roleIds" multiple collapse-tags :disabled="roles.length === 0" class="wide-select"
            ><ElOption v-for="item in roles" :key="item.id" :label="item.name" :value="item.id" /></ElSelect
          ><span v-if="roles.length === 0" class="helper">角色列表不可用时不会覆盖现有角色</span></ElFormItem
        >
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
.filters,
.form-grid {
  display: flex;
  align-items: center;
  gap: 16px;
}
.page-heading {
  justify-content: space-between;
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
.muted,
.helper {
  color: var(--ptmcp-muted, #667085);
  font-size: 14px;
}
.page-description {
  margin: 8px 0 0;
}
.portal-card {
  border-color: var(--ptmcp-border, #d9e2ec);
}
.filters {
  margin-bottom: 18px;
  flex-wrap: wrap;
}
.filters .el-input {
  width: min(320px, 100%);
}
.filters .el-select {
  width: 220px;
}
.form-grid > * {
  flex: 1;
}
.wide-select {
  width: 100%;
}
.helper {
  display: block;
  margin-top: 4px;
  font-size: 12px;
}
.mb-4 {
  margin-bottom: 16px;
}
@media (max-width: 640px) {
  .page-heading {
    align-items: flex-start;
    flex-direction: column;
  }
  .filters .el-input,
  .filters .el-select {
    width: 100%;
  }
  .form-grid {
    align-items: stretch;
    flex-direction: column;
    gap: 0;
  }
}
</style>
