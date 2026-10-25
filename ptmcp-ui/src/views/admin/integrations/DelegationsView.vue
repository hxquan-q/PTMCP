<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElCheckbox,
  ElCheckboxGroup,
  ElDialog,
  ElForm,
  ElFormItem,
  ElInput,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag
} from 'element-plus';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import { localOnlyReason, validateRequired, validateWindow } from './adminSurface';
import { useSessionStore } from '../../../stores/session';

const dialogVisible = ref(false);
const session = useSessionStore();
const validationMessage = ref('');
const errors = ref<Record<string, string>>({});
const filter = reactive({ keyword: '', status: '', resourceType: '' });
const draft = reactive({
  userId: '',
  departmentRootId: '',
  resourceType: 'DATASOURCE',
  resourceId: '',
  allowedActions: [] as string[],
  validFrom: '',
  validTo: ''
});
const actionOptions = computed(() => session.user?.permissions.filter((code) => /^ptmcp:(user|department|role|acl):/u.test(code)) ?? []);
const filteredHint = computed(() => {
  const values = [filter.keyword, filter.status, filter.resourceType].filter(Boolean);
  return values.length ? `已保留 ${values.length} 个筛选条件；接口接入后将随请求发送。` : '未设置筛选条件。';
});

function resetDraft(): void {
  Object.assign(draft, {
    userId: '',
    departmentRootId: '',
    resourceType: 'DATASOURCE',
    resourceId: '',
    allowedActions: [],
    validFrom: '',
    validTo: ''
  });
  validationMessage.value = '';
  errors.value = {};
}
function openCreate(): void {
  resetDraft();
  dialogVisible.value = true;
}
function validateDraft(): void {
  errors.value = validateRequired(
    {
      userId: draft.userId,
      departmentRootId: draft.departmentRootId,
      resourceId: draft.resourceId,
      validFrom: draft.validFrom,
      validTo: draft.validTo
    },
    { userId: '被委派管理员', departmentRootId: '部门根', resourceId: '资源', validFrom: '开始时间', validTo: '结束时间' }
  );
  const windowError = validateWindow(draft.validFrom, draft.validTo);
  if (windowError) errors.value.validTo = windowError;
  if (!draft.allowedActions.length) errors.value.allowedActions = '至少选择一个动作；真实可委派子集仍需服务端核验。';
  if (draft.userId === session.user?.userId) errors.value.userId = '不能为自己创建或撤销委派。';
  validationMessage.value = Object.values(errors.value).join('；') || '本地字段校验通过；真实边界仍需服务端核验，尚未提交。';
}
function clearPreview(): void {
  dialogVisible.value = false;
  resetDraft();
}
onMounted(() => window.addEventListener('ptmcp:sensitive-clear', clearPreview));
onBeforeUnmount(() => window.removeEventListener('ptmcp:sensitive-clear', clearPreview));
</script>

<template>
  <div class="admin-page">
    <PageHeader title="管理委派" description="把管理员能力限定在部门子树、资源、动作和服务器有效期的交集内。">
      <template #actions><ElButton v-if="session.can('ptmcp:delegation:create')" type="primary" @click="openCreate">新建委派</ElButton></template>
    </PageHeader>

    <ElAlert type="warning" show-icon :closable="false" title="委派接口待接入" :description="localOnlyReason('管理委派')" />

    <ElCard class="filter-card" shadow="never">
      <div class="filter-grid" aria-label="委派筛选">
        <ElInput v-model="filter.keyword" clearable placeholder="筛选管理员 ID（本地预览）" aria-label="管理员 ID" />
        <ElSelect v-model="filter.status" clearable placeholder="全部状态" aria-label="状态">
          <ElOption label="生效中" value="ACTIVE" /><ElOption label="已到期" value="EXPIRED" /><ElOption label="已撤销" value="REVOKED" />
        </ElSelect>
        <ElSelect v-model="filter.resourceType" clearable placeholder="全部资源类型" aria-label="资源类型">
          <ElOption label="数据源" value="DATASOURCE" /><ElOption label="命名空间" value="NAMESPACE" /><ElOption label="对象" value="OBJECT" />
        </ElSelect>
        <span class="filter-hint">{{ filteredHint }}</span>
      </div>
    </ElCard>

    <ElCard class="table-card" shadow="never">
      <div class="section-heading">
        <div>
          <h2>委派记录</h2>
          <p>记录由服务端返回后才会出现在此处。</p>
        </div>
        <ElTag type="info" effect="plain">未连接</ElTag>
      </div>
      <ElTable :data="[]" class="empty-table" aria-label="委派记录">
        <ElTableColumn prop="userId" label="管理员" min-width="150" />
        <ElTableColumn prop="departmentRootId" label="部门根" min-width="130" />
        <ElTableColumn prop="resource" label="资源范围" min-width="180" />
        <ElTableColumn prop="allowedActions" label="动作" min-width="150" />
        <ElTableColumn prop="validTo" label="有效期" min-width="170" />
        <ElTableColumn prop="status" label="状态" width="100" />
        <template #empty><PageState title="委派记录尚未读取" description="管理委派服务尚未接入，暂时无法读取记录或确认有效委派范围。" /></template>
      </ElTable>
    </ElCard>

    <ElCard class="boundary-card" shadow="never">
      <h2>边界说明</h2>
      <div class="boundary-grid">
        <div><strong>部门子树</strong><span>只能从可管理根节点向下展开。</span></div>
        <div><strong>资源范围</strong><span>资源身份和 generation 由服务端确认。</span></div>
        <div><strong>动作集合</strong><span>不得超过操作者可委派的动作子集。</span></div>
        <div><strong>有效期</strong><span>服务器时间决定到期，不能由浏览器延长。</span></div>
      </div>
    </ElCard>

    <ElDialog v-model="dialogVisible" title="新建委派 · 本地预览" width="min(680px, calc(100vw - 28px))" destroy-on-close @closed="resetDraft">
      <ElAlert
        type="info"
        show-icon
        :closable="false"
        title="仅本地预览"
        description="委派服务尚未接入。管理员、部门根和资源须从服务端受控范围选择，当前不可选；可以检查动作与日期字段。"
      />
      <ElForm label-position="top" class="dialog-form" @submit.prevent>
        <div class="two-col">
          <ElFormItem label="被委派管理员" required :error="errors.userId"
            ><ElSelect v-model="draft.userId" disabled placeholder="管理员目录尚未接入"
          /></ElFormItem>
          <ElFormItem label="部门根" required :error="errors.departmentRootId"
            ><ElSelect v-model="draft.departmentRootId" disabled placeholder="可管理部门范围尚未接入"
          /></ElFormItem>
        </div>
        <div class="two-col">
          <ElFormItem label="资源类型" required
            ><ElSelect v-model="draft.resourceType"
              ><ElOption label="数据源" value="DATASOURCE" /><ElOption label="命名空间" value="NAMESPACE" /><ElOption
                label="对象"
                value="OBJECT" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="目标资源" required :error="errors.resourceId"
            ><ElSelect v-model="draft.resourceId" disabled placeholder="受控资源目录尚未接入"
          /></ElFormItem>
        </div>
        <ElFormItem label="动作预览" required :error="errors.allowedActions"
          ><ElCheckboxGroup v-model="draft.allowedActions"
            ><ElCheckbox v-for="action in actionOptions" :key="action" :value="action">{{ action }}</ElCheckbox></ElCheckboxGroup
          ></ElFormItem
        >
        <p class="filter-hint">仅列出当前会话的组织/ACL功能码供预览，不把它视为服务端已确认的可委派子集。</p>
        <div class="two-col">
          <ElFormItem label="开始时间（浏览器本地时区）" required :error="errors.validFrom"
            ><ElInput v-model="draft.validFrom" type="datetime-local" /></ElFormItem
          ><ElFormItem label="结束时间（浏览器本地时区）" required :error="errors.validTo"
            ><ElInput v-model="draft.validTo" type="datetime-local"
          /></ElFormItem>
        </div>
        <p v-if="validationMessage" class="validation-message" role="status">{{ validationMessage }}</p>
      </ElForm>
      <template #footer
        ><ElButton @click="dialogVisible = false">关闭</ElButton><ElButton @click="validateDraft">校验预览</ElButton
        ><ElButton type="primary" disabled title="后端 delegation:create 尚未提供">提交委派</ElButton></template
      >
    </ElDialog>
  </div>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}
.filter-card,
.table-card,
.boundary-card {
  border-radius: 8px;
}
.filter-grid {
  display: grid;
  grid-template-columns: minmax(180px, 1.5fr) repeat(2, minmax(150px, 1fr)) minmax(180px, 1fr);
  gap: 10px;
  align-items: center;
}
.filter-hint,
.section-heading p,
.boundary-grid span {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
h2 {
  margin: 0;
  color: var(--ptmcp-text);
  font-size: 16px;
}
.section-heading p {
  margin: 6px 0 0;
}
.empty-table {
  margin-top: 16px;
}
.boundary-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-top: 14px;
}
.boundary-grid div {
  display: grid;
  gap: 6px;
  padding: 14px;
  border: 1px solid var(--ptmcp-border);
  border-radius: 6px;
  background: #f8fafc;
}
.boundary-grid strong {
  color: var(--ptmcp-text);
  font-size: 13px;
}
.dialog-form {
  margin-top: 18px;
}
.dialog-form .el-select {
  width: 100%;
}
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.validation-message {
  margin: 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: #f5f8fb;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
}
@media (max-width: 760px) {
  .filter-grid,
  .boundary-grid,
  .two-col {
    grid-template-columns: 1fr;
  }
  .filter-hint {
    grid-column: auto;
  }
}
</style>
