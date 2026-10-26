<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useSessionStore } from '../../../stores/session';
import { orgPortalApi, type Department, type Role, type User } from '../../../services/orgPortalApi';
import { userChangePlan } from '../../../services/portalMutations';
import { errorText, isAccessError, isConflict, usePortalTask } from '../../portalUtils';
import { frontendPreview, mutationNotice, passwordApiReady } from '../../../services/frontendMode';
import { request } from '../../../services/http';
import { passwordError, userProfileError } from '../../../services/formValidation';
import UserPasswordDialog from './UserPasswordDialog.vue';

const loading = ref(false);
const session = useSessionStore();
const route = useRoute();
const saving = ref(false);
const loadError = ref('');
const users = ref<User[]>([]);
const departments = ref<Department[]>([]);
const roles = ref<Role[]>([]);
const keyword = ref('');
const departmentFilter = ref('');
const dialogVisible = ref(false);
const editing = ref<User | null>(null);
const conflict = ref<User | null>(null);
const conflictDetected = ref(false);
const roleOptionsLoaded = ref(false);
const passwordVisible = ref(false);
const passwordUser = ref<User | null>(null);
const profile = reactive({ email: '', phoneNumber: '', remark: '', initialPassword: '' });
function clearProfile(): void {
  Object.assign(profile, { email: '', phoneNumber: '', remark: '', initialPassword: '' });
}
const form = reactive({ username: '', displayName: '', deptId: '', roleIds: [] as string[], enabled: true });
const clearPage = (): void => {
  users.value = [];
  departments.value = [];
  roles.value = [];
  editing.value = null;
  conflict.value = null;
  conflictDetected.value = false;
  dialogVisible.value = false;
  roleOptionsLoaded.value = false;
  passwordVisible.value = false;
  passwordUser.value = null;
  clearProfile();
};
const usersTask = usePortalTask(clearPage);
const detailTask = usePortalTask(clearPage);

const canEdit = computed(
  () => !editing.value || ['ptmcp:user:update', 'ptmcp:user:move', 'ptmcp:user:assign-role', 'ptmcp:user:status'].some(session.can),
);
const canMove = computed(() => !editing.value || session.can('ptmcp:user:move'));
const canAssignRoles = computed(() => session.can('ptmcp:user:assign-role') && roleOptionsLoaded.value);

function resetForm(): void {
  clearProfile();
  form.username = '';
  form.displayName = '';
  form.deptId = departments.value[0]?.id ?? '';
  form.roleIds = [];
  form.enabled = true;
}

function openCreate(): void {
  editing.value = null;
  conflict.value = null;
  conflictDetected.value = false;
  resetForm();
  dialogVisible.value = true;
}

function openEdit(user: User): void {
  clearProfile();
  Object.assign(profile, { email: user.email ?? '', phoneNumber: user.phoneNumber ?? '', remark: user.remark ?? '' });
  editing.value = user;
  conflict.value = null;
  conflictDetected.value = false;
  form.username = user.username;
  form.displayName = user.displayName;
  form.deptId = user.deptId;
  form.roleIds = [...user.roleIds];
  form.enabled = user.enabled;
  dialogVisible.value = true;
}

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = '';
  try {
    const result = await usersTask.run(async (signal) => {
      const [departmentResult, roleResult] = await Promise.allSettled([orgPortalApi.departments.list(signal), orgPortalApi.roles.list(signal)]);
      const items = await orgPortalApi.users.list(
        { departmentId: departmentFilter.value || undefined, keyword: keyword.value.trim() || undefined },
        signal,
      );
      return { departmentResult, roleResult, items };
    });
    if (!result) return;
    if (result.departmentResult.status === 'fulfilled') departments.value = result.departmentResult.value;
    if (result.roleResult.status === 'fulfilled') {
      roles.value = result.roleResult.value;
      roleOptionsLoaded.value = true;
    } else {
      roleOptionsLoaded.value = false;
      ElMessage.warning('角色列表暂时不可用，角色选择已禁用');
    }
    users.value = result.items;
  } catch (error) {
    if (isAccessError(error)) clearPage();
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}

async function save(): Promise<void> {
  if (saving.value || !canEdit.value || conflictDetected.value) return;
  if (!editing.value && !session.can('ptmcp:user:create')) return;
  if (!form.username.trim() || !form.displayName.trim() || !form.deptId) {
    ElMessage.warning('请填写用户名、显示名称和部门');
    return;
  }
  if (frontendPreview) {
    const issue = userProfileError(profile) ?? (!editing.value ? passwordError(profile.initialPassword) : null);
    if (issue) {
      ElMessage.warning(issue);
      return;
    }
  }
  saving.value = true;
  let completed = 0;
  try {
    const body = {
      username: form.username.trim(),
      displayName: form.displayName.trim(),
      deptId: form.deptId,
      roleIds: canAssignRoles.value ? [...form.roleIds] : [],
      enabled: session.can('ptmcp:user:status') ? form.enabled : true,
    };
    if (editing.value) {
      let current = editing.value;
      const plan = userChangePlan(current, form, session.can, roleOptionsLoaded.value);
      if (plan.includes('department')) {
        await ElMessageBox.confirm(
          `将“${current.displayName}”从“${departmentName(current.deptId)}”移动到“${departmentName(form.deptId)}”。服务端将重新计算该用户的有效授权。`,
          '确认迁移用户部门',
          { type: 'warning' },
        );
      }
      if (plan.includes('status') && !form.enabled) {
        await ElMessageBox.confirm(`停用“${current.displayName}”后，该用户的后续请求可能失效。继续吗？`, '确认停用用户', { type: 'warning' });
      }
      if (plan.includes('profile')) {
        current = await orgPortalApi.users.update(
          current.id,
          {
            displayName: form.displayName.trim(),
            deptId: current.deptId,
            enabled: current.enabled,
            ...(frontendPreview ? { email: profile.email, phoneNumber: profile.phoneNumber, remark: profile.remark } : {}),
          },
          current.version,
        );
        editing.value = current;
        completed += 1;
      }
      if (plan.includes('department')) {
        current = await orgPortalApi.users.setDepartment(current.id, form.deptId, current.version);
        editing.value = current;
        completed += 1;
      }
      if (plan.includes('roles')) {
        current = await orgPortalApi.users.setRoles(current.id, [...form.roleIds], current.version);
        editing.value = current;
        completed += 1;
      }
      if (plan.includes('status')) {
        current = await orgPortalApi.users.setStatus(current.id, form.enabled ? 'ACTIVE' : 'DISABLED', current.version);
        editing.value = current;
        completed += 1;
      }
      if (frontendPreview && !plan.includes('profile'))
        await orgPortalApi.users.update(
          current.id,
          {
            displayName: current.displayName,
            deptId: current.deptId,
            enabled: current.enabled,
            ...{ email: profile.email, phoneNumber: profile.phoneNumber, remark: profile.remark },
          },
          current.version,
        );
    } else await orgPortalApi.users.create({ ...body, ...(frontendPreview ? { ...profile } : {}) });
    profile.initialPassword = '';
    dialogVisible.value = false;
    ElMessage.success(frontendPreview ? mutationNotice : editing.value ? '用户已更新' : '用户已创建');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    if (completed) loadError.value = `已完成 ${completed} 项独立变更，后续操作未完成。请核对保留的草稿和服务端状态后继续。`;
    if (isConflict(error) && editing.value) {
      conflictDetected.value = true;
      await refreshConflict();
      ElMessage.warning('检测到版本冲突，草稿已保留。请核对服务器版本后继续。');
    } else ElMessage.error(errorText(error));
  } finally {
    saving.value = false;
  }
}
function openPassword(id: string): void {
  passwordUser.value = users.value.find((row) => row.id === id) ?? null;
  passwordVisible.value = !!passwordUser.value;
}
async function removeUser(id: string): Promise<void> {
  const row = users.value.find((item) => item.id === id);
  if (!row || saving.value || !passwordApiReady || !session.can('ptmcp:user:delete')) return;
  saving.value = true;
  try {
    await ElMessageBox.confirm(`删除“${row.displayName}”将使其全部凭证失效。需先停用并处理关联依赖。`, '删除用户', { type: 'warning' });
    await request(`/api/v1/admin/users/${encodeURIComponent(id)}`, { method: 'DELETE', headers: { 'If-Match': `"${row.version}"` } });
    ElMessage.success(mutationNotice);
    await load();
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorText(reason));
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
    const item = await detailTask.run((signal) => orgPortalApi.users.get(id, signal));
    if (item) conflict.value = item;
  } catch (error) {
    ElMessage.error(errorText(error));
  }
}

async function toggleStatus(user: User): Promise<void> {
  if (!session.can('ptmcp:user:status')) return;
  const next = user.enabled ? 'DISABLED' : 'ACTIVE';
  try {
    await ElMessageBox.confirm(`确定${user.enabled ? '停用' : '启用'}“${user.displayName}”吗？`, '更新用户状态', {
      type: user.enabled ? 'warning' : 'info',
    });
    await orgPortalApi.users.setStatus(user.id, next, user.version);
    ElMessage.success('状态已更新');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    if (isConflict(error)) {
      loadError.value = '用户版本已变化，状态操作尚未完成。请重新加载并核对当前状态。';
    } else ElMessage.error(errorText(error));
  }
}

function roleNames(ids: string[]): string {
  return ids.map((id) => roles.value.find((role) => role.id === id)?.name ?? `角色 ${id}`).join('、') || '未分配角色';
}

function departmentName(id: string): string {
  return departments.value.find((department) => department.id === id)?.name ?? `部门 ${id}`;
}

async function loadRouteDetail(): Promise<void> {
  detailTask.cancel();
  const id = typeof route.params.id === 'string' ? route.params.id : null;
  if (id) {
    try {
      const item = await detailTask.run((signal) => orgPortalApi.users.get(id, signal));
      if (item) openEdit(item);
    } catch (error) {
      if (isAccessError(error)) {
        editing.value = null;
        dialogVisible.value = false;
      }
      ElMessage.error(errorText(error));
    }
  }
}
watch(
  () => route.params.id,
  () => {
    void loadRouteDetail();
  },
);
watch(
  () => session.can('ptmcp:user:list'),
  (allowed) => {
    if (!allowed) {
      usersTask.cancel();
      users.value = [];
    }
  },
);
onMounted(async () => {
  await load();
  await loadRouteDetail();
});
</script>

<template>
  <section class="portal-page" aria-labelledby="users-title">
    <div class="page-heading">
      <div>
        <h1 id="users-title">用户</h1>
        <p class="page-description">管理用户状态、部门归属和角色。每项变更都携带资源版本。</p>
      </div>
      <ElButton v-if="session.can('ptmcp:user:create')" type="primary" @click="openCreate">新建用户</ElButton>
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
        <ElTableColumn label="操作" fixed="right" width="280"
          ><template #default="{ row }"
            ><ElButton
              v-if="['ptmcp:user:update', 'ptmcp:user:move', 'ptmcp:user:assign-role'].some(session.can)"
              link
              type="primary"
              @click="openEdit(row as User)"
              >编辑</ElButton
            ><ElButton v-if="session.can('ptmcp:user:status')" link :type="row.enabled ? 'danger' : 'success'" @click="toggleStatus(row as User)">{{
              row.enabled ? '停用' : '启用'
            }}</ElButton
            ><ElButton v-if="session.can('ptmcp:user:reset-password')" link @click="openPassword(String(row.id))">重置密码</ElButton
            ><ElButton
              v-if="session.can('ptmcp:user:delete')"
              link
              type="danger"
              :disabled="!passwordApiReady || saving || row.enabled"
              @click="removeUser(String(row.id))"
              >删除</ElButton
            ></template
          ></ElTableColumn
        >
      </ElTable>
    </ElCard>
    <ElDialog
      v-model="dialogVisible"
      :title="editing ? '编辑用户' : '新建用户'"
      width="min(600px, calc(100vw - 32px))"
      @close="profile.initialPassword = ''"
    >
      <ElAlert
        v-if="conflictDetected"
        type="warning"
        :closable="false"
        title="服务器版本已变化，当前草稿已保留。请核对后显式选择继续或重置。"
        class="mb-4"
      >
        <template v-if="conflict"
          ><p>
            服务器：{{ conflict.displayName }} · {{ departmentName(conflict.deptId) }} · {{ roleNames(conflict.roleIds) }} ·
            {{ conflict.enabled ? '启用' : '停用' }}（版本 {{ conflict.version }}）
          </p>
          <ElButton size="small" @click="mergeServerVersion">已核对，以新版本继续编辑</ElButton
          ><ElButton size="small" @click="openEdit(conflict)">使用服务器值重置</ElButton></template
        ><ElButton v-else size="small" @click="refreshConflict">读取当前版本并比较</ElButton>
      </ElAlert>
      <ElAlert
        v-if="!editing && !frontendPreview"
        type="info"
        :closable="false"
        title="当前创建接口只创建外部身份记录，不设置初始密码。创建成功不代表用户已能通过密码登录。"
        class="mb-4"
      />
      <ElForm label-position="top" :disabled="saving">
        <div class="form-grid">
          <ElFormItem label="用户名" required><ElInput v-model="form.username" :disabled="Boolean(editing)" maxlength="100" /></ElFormItem
          ><ElFormItem label="显示名称" required
            ><ElInput v-model="form.displayName" :disabled="Boolean(editing) && !session.can('ptmcp:user:update')" maxlength="100"
          /></ElFormItem>
        </div>
        <ElFormItem label="部门" required
          ><ElSelect v-model="form.deptId" :disabled="!canMove" class="wide-select"
            ><ElOption v-for="item in departments" :key="item.id" :label="item.name" :value="item.id" /></ElSelect
        ></ElFormItem>
        <ElFormItem label="角色"
          ><ElSelect v-model="form.roleIds" multiple collapse-tags :disabled="!canAssignRoles" class="wide-select"
            ><ElOption v-for="item in roles" :key="item.id" :label="item.name" :value="item.id" /></ElSelect
          ><span v-if="!roleOptionsLoaded" class="helper">角色列表不可用时不会覆盖现有角色</span></ElFormItem
        >
        <ElFormItem label="状态"
          ><ElSwitch v-model="form.enabled" :disabled="!session.can('ptmcp:user:status')" active-text="启用" inactive-text="停用"
        /></ElFormItem>
        <ElFormItem label="邮箱（最多50字符）"
          ><ElInput v-model="profile.email" :disabled="!frontendPreview" maxlength="50" type="email"
        /></ElFormItem>
        <ElFormItem label="电话（最多11字符）"><ElInput v-model="profile.phoneNumber" :disabled="!frontendPreview" maxlength="11" /></ElFormItem>
        <ElFormItem label="备注（最多500字符）"
          ><ElInput v-model="profile.remark" :disabled="!frontendPreview" type="textarea" maxlength="500" :rows="3"
        /></ElFormItem>
        <ElFormItem v-if="!editing" label="初始密码（12～64字符 / UTF-8≤72字节）" :required="frontendPreview"
          ><ElInput v-model="profile.initialPassword" type="password" autocomplete="new-password" :disabled="!frontendPreview"
        /></ElFormItem>
        <p v-if="!frontendPreview" class="helper">邮箱、电话、备注及初始密码接口字段待接入；当前只保存已支持的组织档案。</p>
      </ElForm>
      <template #footer
        ><ElButton @click="dialogVisible = false">取消</ElButton
        ><ElButton type="primary" :disabled="conflictDetected || !canEdit" :loading="saving" @click="save">保存</ElButton></template
      >
    </ElDialog>
    <UserPasswordDialog
      v-if="passwordUser"
      v-model="passwordVisible"
      :user-id="passwordUser.id"
      :version="passwordUser.version"
      :username="passwordUser.username"
      @saved="load"
    />
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
h1 {
  margin: 0;
  color: var(--ptmcp-heading);
  font-size: 28px;
}
.page-description,
.muted,
.helper {
  color: var(--ptmcp-muted);
  font-size: 14px;
}
.page-description {
  margin: 8px 0 0;
}
.portal-card {
  border-color: var(--ptmcp-border);
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
