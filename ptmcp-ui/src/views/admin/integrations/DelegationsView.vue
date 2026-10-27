<script setup lang="ts">
import { computed, reactive, ref, watch, onMounted } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import CursorFooter from '../../../components/common/CursorFooter.vue';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import StatusBadge from '../../../components/common/StatusBadge.vue';
import { useSessionStore } from '../../../stores/session';
import { controlPlane } from '../../../services/controlPlane';
import { controlPlaneAvailable, delegationApiReady, mutationNotice } from '../../../services/frontendMode';
import { orgPortalApi, type User, type Department } from '../../../services/orgPortalApi';
import { listDatasources, listMetadata } from '../../../services/data';
import { useCursorPage } from '../../../composables/useCursorPage';
import { errorText, formatDate, usePortalTask } from '../../portalUtils';
import { delegationSubmitError, validateRequired, validateWindow } from './adminSurface';
const session = useSessionStore();
/** 委派后端已实现（T17-api 切片）；预览模式继续用合成数据走完整交互。 */
const delegationReady = delegationApiReady || controlPlaneAvailable;
const filter = reactive({ userId: '', status: '', resourceType: '' });
const { rows, cursor, loading, error, requestId, loaded, reset, load, retry } = useCursorPage((cursor, signal) =>
  controlPlane.delegations.list({ ...filter, cursor }, signal),
);
const dialogVisible = ref(false);
const users = ref<User[]>([]);
const departments = ref<Department[]>([]);
const resources = ref<{ id: string; label: string; type: string }[]>([]);
const saving = ref(false);
const directoryError = ref('');
const validation = ref('');
const errors = ref<Record<string, string>>({});
const draft = reactive({
  userId: '',
  departmentRootId: '',
  resourceType: 'DATASOURCE',
  resourceId: '',
  allowedActions: [] as string[],
  validFrom: '',
  validTo: '',
});
let alive = true;
let editorVersion = 0;
const actionOptions = computed(() => session.user?.permissions.filter((code) => /^ptmcp:(user|department|role|acl):/u.test(code)) ?? []);
function clear(): void {
  editorVersion++;
  dialogVisible.value = false;
  Object.assign(draft, {
    userId: '',
    departmentRootId: '',
    resourceType: 'DATASOURCE',
    resourceId: '',
    allowedActions: [],
    validFrom: '',
    validTo: '',
  });
  validation.value = '';
  errors.value = {};
}
const directoryTask = usePortalTask(() => {
  alive = false;
  clear();
  users.value = [];
  departments.value = [];
  resources.value = [];
});
async function loadDirectories(): Promise<void> {
  if (!delegationReady) return;
  directoryError.value = '';
  try {
    const result = await directoryTask.run(async (signal) => {
      const [people, depts, sources] = await Promise.all([
        orgPortalApi.users.list({}, signal),
        orgPortalApi.departments.list(signal),
        listDatasources({ signal, pageSize: 100 }),
      ]);
      // Sources without a published snapshot return SCHEMA_STALE; the datasource node itself
      // stays delegable, so per-source object reads fail soft instead of blocking the directory.
      const objects = await Promise.all(
        sources.data.items.map((source) =>
          listMetadata({ datasourceId: source.id, signal, pageSize: 100 }).catch(() => ({ data: { items: [], nextCursor: null } })),
        ),
      );
      return { people, depts, sources: sources.data.items, objects };
    });
    if (!result) return;
    users.value = result.people.filter((user) => user.id !== session.user?.userId);
    departments.value = result.depts;
    resources.value = [
      ...result.sources.map((row) => ({ id: row.id, label: row.name, type: 'DATASOURCE' })),
      ...result.objects.flatMap((page) => page.data.items.map((row) => ({ id: row.id, label: row.objectName, type: 'OBJECT' }))),
    ];
  } catch (reason) {
    directoryError.value = errorText(reason);
  }
}
function openCreate(): void {
  clear();
  dialogVisible.value = true;
}
function validate(): boolean {
  errors.value = validateRequired(
    {
      userId: draft.userId,
      departmentRootId: draft.departmentRootId,
      resourceId: draft.resourceId,
      validFrom: draft.validFrom,
      validTo: draft.validTo,
    },
    { userId: '管理员', departmentRootId: '部门根', resourceId: '资源', validFrom: '开始时间', validTo: '结束时间' },
  );
  const windowError = validateWindow(draft.validFrom, draft.validTo);
  if (windowError) errors.value.validTo = windowError;
  if (!draft.allowedActions.length) errors.value.allowedActions = '至少选择一个动作。';
  if (!users.value.some((row) => row.id === draft.userId) || draft.userId === session.user?.userId)
    errors.value.userId = '请选择其他受控管理员，不能自我委派。';
  if (!departments.value.some((row) => row.id === draft.departmentRootId)) errors.value.departmentRootId = '请选择可管理的部门根。';
  if (!resources.value.some((row) => row.id === draft.resourceId && row.type === draft.resourceType))
    errors.value.resourceId = '请选择当前目录资源。';
  validation.value = Object.keys(errors.value).length ? '请检查标出的字段。' : '字段校验通过，服务端仍须核验管理范围和期限。';
  return !Object.keys(errors.value).length;
}
async function submit(): Promise<void> {
  if (saving.value || !validate() || !delegationReady || directoryError.value || !session.can('ptmcp:delegation:create')) return;
  saving.value = true;
  const own = editorVersion;
  try {
    await ElMessageBox.confirm(
      `管理员 ${draft.userId}：部门子树 ${draft.departmentRootId} ∩ ${draft.resourceType}:${draft.resourceId} ∩ ${draft.allowedActions.length} 个动作；有效至 ${formatDate(new Date(draft.validTo).toISOString())}（Asia/Taipei）。`,
      '确认委派边界',
      { type: 'warning' },
    );
    if (own !== editorVersion || !alive) return;
    await controlPlane.delegations.create({
      ...draft,
      allowedActions: [...draft.allowedActions],
      validFrom: new Date(draft.validFrom).toISOString(),
      validTo: new Date(draft.validTo).toISOString(),
    });
    if (own !== editorVersion || !alive) return;
    clear();
    ElMessage.success(mutationNotice);
    await load();
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close' && own === editorVersion) validation.value = delegationSubmitError(reason, errorText);
  } finally {
    saving.value = false;
  }
}
async function revoke(id: string): Promise<void> {
  const row = rows.value.find((item) => item.id === id);
  if (!row) return;
  if (saving.value || row.userId === session.user?.userId || !session.can('ptmcp:delegation:delete')) return;
  saving.value = true;
  try {
    await ElMessageBox.confirm(`撤销后，管理员 ${row.userId} 将失去此委派范围。变更范围需撤销后新建。`, '撤销委派', { type: 'warning' });
    await controlPlane.delegations.revoke(row);
    ElMessage.success(mutationNotice);
    await load();
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') {
      ElMessage.error(errorText(reason));
      await load();
    }
  } finally {
    saving.value = false;
  }
}
watch(filter, reset, { flush: 'sync' });
onMounted(() => {
  if (delegationReady) {
    void load();
    void loadDirectories();
  }
});
</script>
<template>
  <div class="admin-page">
    <PageHeader title="管理委派" description="部门子树 ∩ 资源范围 ∩ 动作 ∩ 服务器有效期。"
      ><template #actions
        ><ElButton v-if="session.can('ptmcp:delegation:create')" type="primary" @click="openCreate">新建委派</ElButton></template
      ></PageHeader
    >
    <ElAlert v-if="directoryError" type="error" :title="directoryError" :closable="false"
      ><ElButton @click="loadDirectories">重新加载目录</ElButton></ElAlert
    >
    <ElCard shadow="never" class="ptmcp-filter-bar"
      ><ElForm class="filters" label-position="top" @submit.prevent="load()"
        ><ElFormItem label="管理员"><ElInput v-model="filter.userId" clearable placeholder="管理员 ID" /></ElFormItem
        ><ElFormItem label="状态"
          ><ElSelect v-model="filter.status" clearable placeholder="全部状态"
            ><ElOption label="生效中" value="ACTIVE" /><ElOption label="已到期" value="EXPIRED" /><ElOption
              label="已撤销"
              value="REVOKED" /></ElSelect></ElFormItem
        ><ElFormItem label="资源类型"
          ><ElSelect v-model="filter.resourceType" clearable placeholder="全部类型"
            ><ElOption label="数据源" value="DATASOURCE" /><ElOption label="命名空间" value="NAMESPACE" /><ElOption
              label="对象"
              value="OBJECT" /></ElSelect></ElFormItem
        ><ElButton native-type="submit" type="primary" :disabled="!delegationReady" :loading="loading">查询</ElButton
        ><ElButton
          @click="
            Object.assign(filter, { userId: '', status: '', resourceType: '' });
            delegationReady && load();
          "
          >重置</ElButton
        ></ElForm
      ></ElCard
    >
    <ElCard shadow="never" class="ptmcp-table-section"
      ><h2 class="ptmcp-section-title">委派记录</h2>
      <PageState
        v-if="error"
        kind="error"
        title="委派读取失败"
        :description="error"
        :request-id="requestId"
        action-label="重试"
        @action="retry" /><ElSkeleton v-if="loading" :rows="5" animated /><ElTable
        v-else-if="!error || rows.length"
        :data="rows"
        aria-label="委派记录"
        ><ElTableColumn prop="userId" label="管理员" min-width="120" /><ElTableColumn
          prop="departmentRootId"
          label="部门根"
          min-width="120" /><ElTableColumn label="资源范围" min-width="170"
          ><template #default="{ row }">{{ row.resourceType }}:{{ row.resourceId }}</template></ElTableColumn
        ><ElTableColumn prop="allowedActions" label="动作" min-width="200" /><ElTableColumn label="有效期（台北）" min-width="260"
          ><template #default="{ row }">{{ formatDate(row.validFrom) }} 至 {{ formatDate(row.validTo) }}</template></ElTableColumn
        ><ElTableColumn prop="status" label="状态" width="100"
          ><template #default="{ row }"><StatusBadge domain="delegation" :value="row.status" /></template></ElTableColumn
        ><ElTableColumn prop="version" label="版本" width="80" /><ElTableColumn label="操作" width="90"
          ><template #default="{ row }"
            ><ElButton
              v-if="session.can('ptmcp:delegation:delete') && row.status === 'ACTIVE'"
              link
              type="danger"
              :disabled="saving || row.userId === session.user?.userId"
              @click="revoke(String(row.id))"
              >撤销</ElButton
            ></template
          ></ElTableColumn
        ><template #empty
          ><PageState
            :title="loaded ? '没有符合条件的委派' : '委派尚未读取'"
            description="可调整条件或新建委派；未读取不代表没有委派。" /></template></ElTable
      ><CursorFooter v-if="loaded || rows.length" :count="rows.length" :has-more="!!cursor" :loading="loading" @more="load(true)"
    /></ElCard>
    <ElAlert
      type="info"
      :closable="false"
      title="委派不支持直接编辑"
      description="变更管理员、资源或期限时，先撤销旧委派再创建。撤销和到期会使对应管理权失效；禁止自我操作，服务端仍需核验能力和有效期。"
    />
    <ElDialog v-model="dialogVisible" title="新建委派" width="min(680px, calc(100vw - 28px))" destroy-on-close @close="editorVersion++">
      <ElForm label-position="top" @submit.prevent="submit"
        ><div class="two-col">
          <ElFormItem label="管理员" required :error="errors.userId"
            ><ElSelect v-model="draft.userId" filterable :disabled="!delegationReady || saving" placeholder="选择其他管理员"
              ><ElOption v-for="user in users" :key="user.id" :value="user.id" :label="user.displayName" /></ElSelect></ElFormItem
          ><ElFormItem label="部门根" required :error="errors.departmentRootId"
            ><ElSelect v-model="draft.departmentRootId" filterable :disabled="!delegationReady || saving"
              ><ElOption v-for="dept in departments" :key="dept.id" :value="dept.id" :label="dept.name" /></ElSelect></ElFormItem
          ><ElFormItem label="资源类型"
            ><ElSelect v-model="draft.resourceType" :disabled="saving" @change="draft.resourceId = ''">
              <ElOption label="数据源" value="DATASOURCE" /><ElOption label="命名空间（可信目录待接入）" value="NAMESPACE" disabled /><ElOption
                label="对象"
                value="OBJECT" /></ElSelect></ElFormItem
          ><ElFormItem label="目标资源" required :error="errors.resourceId"
            ><ElSelect v-model="draft.resourceId" filterable :disabled="!delegationReady || saving"
              ><ElOption
                v-for="resource in resources.filter((row) => row.type === draft.resourceType)"
                :key="resource.id"
                :label="resource.label"
                :value="resource.id" /></ElSelect
          ></ElFormItem>
        </div>
        <ElFormItem label="动作" required :error="errors.allowedActions"
          ><ElCheckboxGroup v-model="draft.allowedActions" :disabled="saving"
            ><ElCheckbox v-for="action in actionOptions" :key="action" :value="action">{{ action }}</ElCheckbox></ElCheckboxGroup
          ></ElFormItem
        >
        <p class="helper">当前会话功能码仅作预选，最终可委派子集须由服务端确认。</p>
        <div class="two-col">
          <ElFormItem label="开始时间（本机时区）" required :error="errors.validFrom"
            ><ElInput v-model="draft.validFrom" type="datetime-local" :disabled="saving" /></ElFormItem
          ><ElFormItem label="结束时间（本机时区）" required :error="errors.validTo"
            ><ElInput v-model="draft.validTo" type="datetime-local" :disabled="saving"
          /></ElFormItem>
        </div>
        <p v-if="validation" role="status">{{ validation }}</p></ElForm
      >
      <template #footer
        ><ElButton @click="clear">关闭</ElButton><ElButton :disabled="saving" @click="validate">校验字段</ElButton
        ><ElButton type="primary" :disabled="!delegationReady || !!directoryError" :loading="saving" @click="submit">提交委派</ElButton></template
      >
    </ElDialog>
  </div>
</template>
<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}
.filters {
  display: flex;
  align-items: flex-end;
  flex-wrap: wrap;
  gap: 12px;
}
.filters .el-form-item {
  margin: 0;
  flex: 1 1 180px;
  min-width: 0;
}
.el-select {
  width: 100%;
}
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.helper {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
@media (max-width: 700px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
