<script setup lang="ts">
import PageHeader from '../../../components/common/PageHeader.vue';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useSessionStore } from '../../../stores/session';
import { orgPortalApi, type Role } from '../../../services/orgPortalApi';
import { errorText, isAccessError, isConflict, usePortalTask } from '../../portalUtils';
import { roleWriteGate } from '../integrations/adminSurface';

const roles = ref<Role[]>([]);
const session = useSessionStore();
const route = useRoute();
const roleGate = computed(() => roleWriteGate(session));
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
// Two full codes fit the flexible column without chopping values; the rest stay in the popover.
const INLINE_CODE_LIMIT = 2;
function permissionList(role: Role): string[] {
  return role.permissionCodes ?? [];
}
function inlineCodes(role: Role): string[] {
  return permissionList(role).slice(0, INLINE_CODE_LIMIT);
}
function hiddenCodeCount(role: Role): number {
  return Math.max(0, permissionList(role).length - INLINE_CODE_LIMIT);
}
/** The ptmcp: prefix repeats on every row; dropping it keeps the distinguishing resource/action visible. */
function codeLabel(code: string): string {
  return code.startsWith('ptmcp:') ? code.slice(6) : code;
}
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
  if (!roleGate.value.canWrite || !session.can(editing.value ? 'ptmcp:role:update' : 'ptmcp:role:create')) return;
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
  if (!roleGate.value.canWrite || !session.can('ptmcp:role:delete')) return;
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
  <section class="portal-page fill-page" aria-labelledby="roles-title">
    <PageHeader title-id="roles-title" title="角色" description="角色只管理功能码；数据权限在授权矩阵中单独维护。">
      <template #actions
        ><ElButton v-if="roleGate.canWrite && session.can('ptmcp:role:create')" type="primary" @click="openCreate">新建角色</ElButton></template
      >
    </PageHeader>
    <ElAlert v-if="roleGate.hint" type="info" :closable="false" :title="roleGate.hint" class="mb-4" />
    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError"
      ><template #default><ElButton link type="danger" @click="load">重新加载</ElButton></template></ElAlert
    >
    <section class="roles-table-section ptmcp-table-region" aria-labelledby="roles-table-title">
      <div class="roles-table-heading ptmcp-table-heading">
        <h2 id="roles-table-title" class="ptmcp-section-title">角色列表</h2>
        <p class="ptmcp-meta-note">成员数与委派部门的服务端数据暂不可用。</p>
      </div>
      <p class="ptmcp-scroll-hint">左右滑动表格查看完整列。</p>
      <ElSkeleton v-if="loading" :rows="5" animated />
      <PageState v-else-if="!roles.length && !loadError" kind="empty" description="尚未创建角色" />
      <ElTable
        v-else
        class="roles-table ptmcp-data-table"
        :data="roles"
        row-key="id"
        size="small"
        height="100%"
        table-layout="fixed"
        scrollbar-tabindex="0"
        aria-label="角色列表"
      >
        <ElTableColumn prop="key" label="Key" width="160"
          ><template #default="{ row }"
            ><span class="ptmcp-cell-truncate" :title="row.key">{{ row.key }}</span></template
          ></ElTableColumn
        ><ElTableColumn prop="name" label="名称" width="160"
          ><template #default="{ row }"
            ><span class="ptmcp-cell-truncate" :title="row.name">{{ row.name }}</span></template
          ></ElTableColumn
        ><ElTableColumn label="状态" width="90"
          ><template #default="{ row }"
            ><ElTag class="ptmcp-table-tag" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</ElTag></template
          ></ElTableColumn
        ><ElTableColumn label="功能码" min-width="320"
          ><template #default="{ row }"
            ><div class="code-row">
              <ElTag v-for="code in inlineCodes(row as Role)" :key="code" class="code-tag" :title="code">{{ codeLabel(code) }}</ElTag
              ><ElPopover v-if="hiddenCodeCount(row as Role)" trigger="click" placement="bottom-start" :width="340" popper-class="role-codes-popover">
                <template #reference
                  ><button type="button" class="more-codes" :aria-label="`查看 ${row.name} 的全部 ${permissionList(row as Role).length} 个功能码`">
                    +{{ hiddenCodeCount(row as Role) }} 项
                  </button></template
                >
                <div class="codes-popover">
                  <p class="codes-popover__title">全部 {{ permissionList(row as Role).length }} 个功能码</p>
                  <div class="codes-popover__list">
                    <ElTag v-for="code in permissionList(row as Role)" :key="code" class="code-tag">{{ code }}</ElTag>
                  </div>
                </div>
              </ElPopover>
              <span v-if="!permissionList(row as Role).length" class="ptmcp-cell-meta">—</span>
            </div></template
          ></ElTableColumn
        ><ElTableColumn prop="memberCount" label="成员数" width="120"
          ><template #default="{ row }"
            ><span class="ptmcp-cell-meta">{{ row.memberCount ?? '—' }}</span></template
          ></ElTableColumn
        ><ElTableColumn label="管理部门" width="120"
          ><template #default="{ row }"
            ><span class="ptmcp-cell-truncate ptmcp-cell-meta" :title="row.managedDepartmentIds?.join('、')">{{
              row.managedDepartmentIds?.length ? row.managedDepartmentIds.join('、') : '—'
            }}</span></template
          ></ElTableColumn
        ><ElTableColumn label="操作" width="110" align="right" fixed="right"
          ><template #default="{ row }"
            ><div class="ptmcp-row-actions">
              <ElButton
                v-if="roleGate.canWrite && session.can('ptmcp:role:update')"
                link
                type="primary"
                :aria-label="`编辑角色：${row.name}`"
                @click="openEdit(row as Role)"
                >编辑</ElButton
              >
              ><ElButton
                v-if="roleGate.canWrite && session.can('ptmcp:role:delete')"
                link
                type="danger"
                :aria-label="`删除角色：${row.name}`"
                @click="remove(row as Role)"
                >删除</ElButton
              >
            </div></template
          ></ElTableColumn
        >
      </ElTable>
    </section>
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
.helper {
  display: block;
  margin-top: 4px;
  color: var(--ptmcp-muted);
  font-size: 12px;
}
.code-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.roles-table-section {
  margin-top: 24px;
}
.roles-table {
  width: 100%;
}
.code-row {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow: hidden;
}
.code-row :deep(.code-tag),
.codes-popover :deep(.code-tag) {
  flex: 0 1 auto;
  min-width: 0;
  max-width: 190px;
  overflow: hidden;
  height: 24px;
  padding: 2px 8px;
  border: none;
  border-radius: 4px;
  background: var(--ptmcp-wash);
  color: var(--ptmcp-blue-dark);
  font-size: 14px;
  line-height: 20px;
}
.code-row :deep(.code-tag .el-tag__content),
.codes-popover :deep(.code-tag .el-tag__content) {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.more-codes {
  flex: none;
  min-height: 24px;
  padding: 2px 6px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--ptmcp-blue);
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
}
.more-codes:hover {
  background: var(--ptmcp-wash);
}
.codes-popover__title {
  margin: 0 0 10px;
  color: var(--ptmcp-heading);
  font-size: 14px;
  font-weight: 500;
}
.codes-popover__list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 280px;
  overflow: auto;
}
.codes-popover :deep(.code-tag) {
  max-width: 100%;
}
.wide {
  width: 100%;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
