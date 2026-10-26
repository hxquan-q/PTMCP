<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useSessionStore } from '../../../stores/session';
import { orgPortalApi, type Role } from '../../../services/orgPortalApi';
import { errorText, isAccessError, isConflict, usePortalTask } from '../../portalUtils';

const roles = ref<Role[]>([]);
const session = useSessionStore();
const route = useRoute();
const loading = ref(false);
const loadError = ref('');
const dialogVisible = ref(false);
const editing = ref<Role | null>(null);
const conflict = ref<Role | null>(null);
const conflictDetected = ref(false);
const saving = ref(false);
const form = reactive({ key: '', name: '', enabled: true, permissionCodes: [] as string[] });
const permissionOptions = computed(() => [...new Set(session.user?.permissions ?? [])].sort());
const retainedCodes = computed(() => (editing.value?.permissionCodes ?? []).filter((code) => !permissionOptions.value.includes(code)));
const clearPage = (): void => {
  roles.value = [];
  editing.value = null;
  conflict.value = null;
  conflictDetected.value = false;
  dialogVisible.value = false;
};
const rolesTask = usePortalTask(clearPage);
const detailTask = usePortalTask(clearPage);

function resetForm(): void {
  form.key = '';
  form.name = '';
  form.enabled = true;
  form.permissionCodes = [];
}
function openCreate(): void {
  editing.value = null;
  conflict.value = null;
  conflictDetected.value = false;
  resetForm();
  dialogVisible.value = true;
}
function openEdit(role: Role): void {
  editing.value = role;
  conflict.value = null;
  conflictDetected.value = false;
  form.key = role.key;
  form.name = role.name;
  form.enabled = role.enabled;
  form.permissionCodes = role.permissionCodes.filter((code) => permissionOptions.value.includes(code));
  dialogVisible.value = true;
}
function permissionCodes(): string[] {
  return [...new Set([...retainedCodes.value, ...form.permissionCodes.filter((code) => permissionOptions.value.includes(code))])];
}

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = '';
  try {
    const items = await rolesTask.run((signal) => orgPortalApi.roles.list(signal));
    if (items) roles.value = items;
  } catch (error) {
    if (isAccessError(error)) clearPage();
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}
async function save(): Promise<void> {
  if (saving.value || conflictDetected.value) return;
  if (!session.can(editing.value ? 'ptmcp:role:update' : 'ptmcp:role:create')) return;
  if (!form.name.trim() || form.name.trim().length > 30 || !form.key.trim() || form.key.trim().length > 100) {
    ElMessage.warning('角色 key 需 1～100 个字符，名称需 1～30 个字符');
    return;
  }
  saving.value = true;
  try {
    if (editing.value?.enabled && !form.enabled)
      await ElMessageBox.confirm('停用角色会触发成员有效权限重新计算。继续吗？', '停用角色', { type: 'warning' });
    if (editing.value)
      await orgPortalApi.roles.update(
        editing.value.id,
        { name: form.name.trim(), enabled: form.enabled, permissionCodes: permissionCodes() },
        editing.value.version,
      );
    else await orgPortalApi.roles.create({ key: form.key.trim(), name: form.name.trim(), enabled: form.enabled, permissionCodes: permissionCodes() });
    dialogVisible.value = false;
    ElMessage.success(editing.value ? '角色已更新' : '角色已创建');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    if (isConflict(error) && editing.value) {
      conflictDetected.value = true;
      await refreshConflict();
      ElMessage.warning('角色版本冲突，草稿已保留。请核对差异后继续。');
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
    const item = await detailTask.run((signal) => orgPortalApi.roles.get(id, signal));
    if (item) conflict.value = item;
  } catch (error) {
    ElMessage.error(errorText(error));
  }
}
async function remove(role: Role): Promise<void> {
  if (!session.can('ptmcp:role:delete')) return;
  try {
    await ElMessageBox.confirm(`确定删除“${role.name}”吗？仍被用户或 ACL 使用的角色会被服务端拒绝。`, '删除角色', { type: 'warning' });
    await orgPortalApi.roles.remove(role.id, role.version);
    ElMessage.success('角色已删除');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    if (isConflict(error)) loadError.value = '删除未完成：角色版本已变化，或仍被用户/ACL 使用。请刷新角色并处理关联项后手动重试。';
    else ElMessage.error(errorText(error));
  }
}
async function loadRouteDetail(): Promise<void> {
  detailTask.cancel();
  const id = typeof route.params.id === 'string' ? route.params.id : null;
  if (id) {
    try {
      const item = await detailTask.run((signal) => orgPortalApi.roles.get(id, signal));
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
  () => session.can('ptmcp:role:list'),
  (allowed) => {
    if (!allowed) {
      rolesTask.cancel();
      roles.value = [];
    }
  },
);
onMounted(async () => {
  await load();
  await loadRouteDetail();
});
</script>

<template>
  <section class="portal-page" aria-labelledby="roles-title">
    <div class="page-heading">
      <div>
        <h1 id="roles-title">角色</h1>
        <p class="page-description">角色只管理功能码；数据权限在授权矩阵中单独维护。</p>
        <p class="page-description">成员数量与委派部门信息当前暂不可用。</p>
      </div>
      <ElButton v-if="session.can('ptmcp:role:create')" type="primary" @click="openCreate">新建角色</ElButton>
    </div>
    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError"
      ><template #default><ElButton link type="danger" @click="load">重新加载</ElButton></template></ElAlert
    >
    <ElCard shadow="never" class="portal-card">
      <ElSkeleton v-if="loading" :rows="5" animated />
      <ElEmpty v-else-if="!roles.length" description="尚未创建角色" />
      <ElTable v-else :data="roles" stripe row-key="id" aria-label="角色列表">
        <ElTableColumn prop="key" label="Key" min-width="180" /><ElTableColumn prop="name" label="名称" min-width="160" /><ElTableColumn
          label="状态"
          width="100"
          ><template #default="{ row }"
            ><ElTag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</ElTag></template
          ></ElTableColumn
        ><ElTableColumn label="功能码" min-width="280"
          ><template #default="{ row }"
            ><div class="code-list">
              <ElTag v-for="code in row.permissionCodes" :key="code" size="small" effect="plain">{{ code }}</ElTag
              ><span v-if="!row.permissionCodes.length" class="muted">未配置</span>
            </div></template
          ></ElTableColumn
        ><ElTableColumn prop="memberCount" label="成员数" width="90"
          ><template #default="{ row }">{{ row.memberCount ?? '未提供' }}</template></ElTableColumn
        ><ElTableColumn label="管理部门" min-width="140"
          ><template #default="{ row }">{{ row.managedDepartmentIds?.join('、') ?? '服务端未提供' }}</template></ElTableColumn
        ><ElTableColumn label="操作" fixed="right" width="130"
          ><template #default="{ row }"
            ><ElButton v-if="session.can('ptmcp:role:update')" link type="primary" @click="openEdit(row as Role)">编辑</ElButton
            ><ElButton v-if="session.can('ptmcp:role:delete')" link type="danger" @click="remove(row as Role)">删除</ElButton></template
          ></ElTableColumn
        >
      </ElTable>
    </ElCard>
    <ElDialog v-model="dialogVisible" :title="editing ? '编辑角色' : '新建角色'" width="min(600px, calc(100vw - 32px))"
      ><ElAlert v-if="conflictDetected" type="warning" :closable="false" title="版本冲突：草稿已保留，请先核对服务器当前值。" class="mb-4"
        ><template v-if="conflict"
          ><p>
            服务器：{{ conflict.name }} · {{ conflict.enabled ? '启用' : '停用' }} · {{ conflict.permissionCodes.join('、') }}（版本
            {{ conflict.version }}）
          </p>
          <ElButton size="small" @click="mergeServerVersion">已核对，以新版本继续编辑</ElButton
          ><ElButton size="small" @click="openEdit(conflict)">使用服务器值重置</ElButton></template
        ><ElButton v-else size="small" @click="refreshConflict">读取当前版本并比较</ElButton></ElAlert
      ><ElForm label-position="top" :disabled="saving || (Boolean(editing) && !session.can('ptmcp:role:update'))"
        ><ElFormItem label="角色 Key" required
          ><ElInput v-model="form.key" :disabled="Boolean(editing)" maxlength="100" /><span class="helper">创建后不可修改。</span></ElFormItem
        ><ElFormItem label="角色名称" required><ElInput v-model="form.name" maxlength="30" show-word-limit /></ElFormItem
        ><ElFormItem label="功能码"
          ><ElSelect v-model="form.permissionCodes" multiple filterable class="wide"
            ><ElOption v-for="code in permissionOptions" :key="code" :value="code" :label="code" /></ElSelect
          ><span class="helper">只能选择当前会话获授的功能码；最终可委派范围仍由服务端校验。数据权限在授权矩阵配置。</span></ElFormItem
        ><ElFormItem v-if="retainedCodes.length" label="保留的只读功能码"
          ><div class="code-list">
            <ElTag v-for="code in retainedCodes" :key="code" type="info">{{ code }}</ElTag>
          </div>
          <span class="helper">当前会话不能委派这些已有代码，保存时保持原值。</span></ElFormItem
        ><ElFormItem label="状态"><ElSwitch v-model="form.enabled" active-text="启用" inactive-text="停用" /></ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="dialogVisible = false">取消</ElButton
        ><ElButton
          v-if="session.can(editing ? 'ptmcp:role:update' : 'ptmcp:role:create')"
          type="primary"
          :disabled="conflictDetected"
          :loading="saving"
          @click="save"
          >保存</ElButton
        ></template
      ></ElDialog
    >
  </section>
</template>

<style scoped>
.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
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
.code-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.wide {
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
}
</style>
