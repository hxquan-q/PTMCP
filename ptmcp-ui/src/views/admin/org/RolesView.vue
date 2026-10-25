<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useSessionStore } from '../../../stores/session';
import { orgPortalApi, type Role } from '../../../services/orgPortalApi';
import { errorText, isConflict } from '../../portalUtils';

const roles = ref<Role[]>([]);
const session = useSessionStore();
const route = useRoute();
const loading = ref(false);
const loadError = ref('');
const dialogVisible = ref(false);
const editing = ref<Role | null>(null);
const saving = ref(false);
const form = reactive({ key: '', name: '', enabled: true, permissionCodesText: '' });

function resetForm(): void {
  form.key = '';
  form.name = '';
  form.enabled = true;
  form.permissionCodesText = '';
}
function openCreate(): void {
  editing.value = null;
  resetForm();
  dialogVisible.value = true;
}
function openEdit(role: Role): void {
  editing.value = role;
  form.key = role.key;
  form.name = role.name;
  form.enabled = role.enabled;
  form.permissionCodesText = role.permissionCodes.join('\n');
  dialogVisible.value = true;
}
function permissionCodes(): string[] {
  return [
    ...new Set(
      form.permissionCodesText
        .split(/[\n,]/)
        .map((item) => item.trim())
        .filter(Boolean)
    )
  ];
}

async function load(): Promise<void> {
  loading.value = true;
  loadError.value = '';
  try {
    roles.value = await orgPortalApi.roles.list();
  } catch (error) {
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}
async function save(): Promise<void> {
  if (!session.can(editing.value ? 'ptmcp:role:update' : 'ptmcp:role:create')) return;
  if (!form.name.trim() || (!editing.value && !form.key.trim())) {
    ElMessage.warning('请填写角色 key 和名称');
    return;
  }
  saving.value = true;
  try {
    if (editing.value)
      await orgPortalApi.roles.update(
        editing.value.id,
        { name: form.name.trim(), enabled: form.enabled, permissionCodes: permissionCodes() },
        editing.value.version
      );
    else await orgPortalApi.roles.create({ key: form.key.trim(), name: form.name.trim(), enabled: form.enabled, permissionCodes: permissionCodes() });
    dialogVisible.value = false;
    ElMessage.success(editing.value ? '角色已更新' : '角色已创建');
    await load();
  } catch (error) {
    ElMessage.error(isConflict(error) ? '角色已被其他人修改，请刷新后重试' : errorText(error));
  } finally {
    saving.value = false;
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
    ElMessage.error(isConflict(error) ? '角色版本已变化，请刷新后重试' : errorText(error));
  }
}
onMounted(async () => {
  await load();
  const id = typeof route.params.id === 'string' ? route.params.id : null;
  if (id) {
    try {
      openEdit(await orgPortalApi.roles.get(id));
    } catch (error) {
      ElMessage.error(errorText(error));
    }
  }
});
</script>

<template>
  <section class="portal-page" aria-labelledby="roles-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">组织与范围</p>
        <h1 id="roles-title">角色</h1>
        <p class="page-description">角色只管理功能码；数据权限在授权矩阵中单独维护。</p>
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
        ><ElTableColumn label="操作" fixed="right" width="130"
          ><template #default="{ row }"
            ><ElButton v-if="session.can('ptmcp:role:update')" link type="primary" @click="openEdit(row as Role)">编辑</ElButton
            ><ElButton v-if="session.can('ptmcp:role:delete')" link type="danger" @click="remove(row as Role)">删除</ElButton></template
          ></ElTableColumn
        >
      </ElTable>
    </ElCard>
    <ElDialog v-model="dialogVisible" :title="editing ? '编辑角色' : '新建角色'" width="min(600px, calc(100vw - 32px))"
      ><ElForm label-position="top"
        ><ElFormItem label="角色 Key" required
          ><ElInput v-model="form.key" :disabled="Boolean(editing)" maxlength="100" /><span class="helper">创建后不可修改。</span></ElFormItem
        ><ElFormItem label="角色名称" required><ElInput v-model="form.name" maxlength="30" show-word-limit /></ElFormItem
        ><ElFormItem label="功能码"
          ><ElInput v-model="form.permissionCodesText" type="textarea" :rows="5" placeholder="每行一个功能码，例如 ptmcp:user:list" /><span
            class="helper"
            >仅填写调用者获授且允许委派的功能码，数据权限不在这里配置。</span
          ></ElFormItem
        ><ElFormItem label="状态"><ElSwitch v-model="form.enabled" active-text="启用" inactive-text="停用" /></ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="dialogVisible = false">取消</ElButton><ElButton type="primary" :loading="saving" @click="save">保存</ElButton></template
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
.code-list {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
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
