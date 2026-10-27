<script setup lang="ts">
import PageHeader from '../../../components/common/PageHeader.vue';
import { useAccessWorkspace } from './useAccessWorkspace';
import type { Grant } from '../../../services/access';
import EffectiveAccessPane from './EffectiveAccessPane.vue';
import EvidenceFields from '../../../components/common/EvidenceFields.vue';

const {
  canList,
  canRead,
  canCreate,
  canUpdate,
  canDelete,
  canPreview,
  subjectType,
  subjectId,
  subjectOptions,
  lookupError,
  grants,
  loading,
  pageError,
  sources,
  sourceCursor,
  sourceId,
  resourceCursor,
  resourceLoading,
  resourceError,
  selectedResource,
  resourceTree,
  platforms,
  platformCursor,
  platformId,
  previewUserId,
  previewAt,
  preview,
  previewError,
  previewLoading,
  dialogVisible,
  editing,
  latestConflict,
  saving,
  formError,
  unknownOutcome,
  draft,
  draftTarget,
  pendingDelete,
  subjectName,
  validDraft,
  changed,
  loadGrants,
  loadSources,
  loadResources,
  loadPlatforms,
  selectResource,
  openCreate,
  openEdit,
  save,
  revoke,
  runPreview,
} = useAccessWorkspace();
const resourceLabels = { DATASOURCE: '数据源', NAMESPACE: '命名空间', OBJECT: '对象' };
function date(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN', { timeZone: 'Asia/Taipei', hour12: false }) : '无结束时间';
}
</script>

<template>
  <section class="access-page" aria-labelledby="access-title">
    <PageHeader title-id="access-title" title="权限矩阵" description="选择主体与资源，管理规则并计算平台范围内的有效权限。">
      <template #actions>
        <ElButton
          v-if="canCreate"
          type="primary"
          :disabled="!subjectId || !selectedResource?.generation || !!resourceError || saving"
          @click="openCreate"
          >新增规则</ElButton
        >
      </template>
    </PageHeader>
    <ElAlert v-if="lookupError" :title="lookupError" type="warning" :closable="false" show-icon />
    <ElAlert v-if="pageError" :title="pageError" type="error" :closable="false" show-icon />
    <div class="access-workspace">
      <aside class="selection-panel" aria-label="授权主体与资源">
        <h2>授权主体</h2>
        <ElForm label-position="top" :disabled="saving">
          <ElFormItem label="主体类型"
            ><ElSelect v-model="subjectType"
              ><ElOption label="用户" value="USER" /><ElOption label="角色" value="ROLE" /><ElOption label="部门" value="DEPT" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="选择主体"
            ><ElSelect v-model="subjectId" filterable clearable placeholder="选择有权管理的主体"
              ><ElOption v-for="item in subjectOptions[subjectType]" :key="item.id" :label="item.label" :value="item.id" /></ElSelect
          ></ElFormItem>
          <p v-if="!subjectOptions[subjectType].length" class="help-text">没有可选主体，或缺少对应目录的读取权限。</p>
          <h2 class="resource-title">资源范围</h2>
          <ElFormItem label="数据源"
            ><ElSelect v-model="sourceId" filterable clearable placeholder="选择数据源"
              ><ElOption v-for="source in sources" :key="source.id" :label="source.name" :value="source.id" /></ElSelect
          ></ElFormItem>
        </ElForm>
        <ElButton v-if="sourceCursor" text :disabled="saving" @click="loadSources(true)">加载更多数据源</ElButton>
        <ElAlert v-if="resourceError" :title="resourceError" type="warning" :closable="false" show-icon />
        <ElSkeleton v-if="resourceLoading && !resourceTree.length" :rows="4" animated />
        <ElTree
          v-else-if="resourceTree.length"
          :data="resourceTree"
          node-key="key"
          :props="{ label: 'label', children: 'children' }"
          default-expand-all
          highlight-current
          :current-node-key="selectedResource?.key"
          :expand-on-click-node="false"
          aria-label="授权资源树"
          @node-click="selectResource"
        >
          <template #default="{ data }"
            ><span class="tree-label"
              ><span>{{ data.label }}</span
              ><small>{{ data.type === 'GROUP' ? '目录分组' : resourceLabels[data.type as keyof typeof resourceLabels] }}</small></span
            ></template
          >
        </ElTree>
        <p v-else class="help-text">先选择数据源以读取当前资源。</p>
        <ElButton v-if="resourceCursor" text :loading="resourceLoading" @click="loadResources(true)">加载更多对象</ElButton>
        <p v-if="selectedResource" class="selection-summary">
          当前：{{ selectedResource.label }}<br />{{
            selectedResource.type === 'GROUP' ? '此节点仅分组，不能授权。' : `资源 ID ${selectedResource.id}`
          }}
        </p>
        <ElAlert
          v-if="selectedResource?.type === 'NAMESPACE'"
          title="命名空间缺少实时 generation 接口，当前可预览，暂不能安全新增或编辑规则。"
          type="info"
          :closable="false"
        />
      </aside>
      <div class="rules-column">
        <section class="rules-panel" aria-labelledby="rules-title" :aria-busy="loading">
          <header class="panel-heading">
            <div>
              <h2 id="rules-title">授权规则</h2>
              <p>{{ subjectId ? subjectName : '选择左侧主体查看规则' }}</p>
            </div>
            <ElButton v-if="canList" :disabled="!subjectId" :loading="loading" @click="loadGrants">刷新</ElButton>
          </header>
          <ElSkeleton v-if="loading && !grants.length" :rows="5" animated />
          <PageState v-else-if="!canList" description="缺少授权规则列表权限，可使用下方已获授权的预览功能。" />
          <PageState v-else-if="!subjectId" description="尚未选择授权主体" />
          <PageState v-else-if="!grants.length && !pageError" description="此主体尚无直接授权规则" />
          <ElTable v-else :data="grants" row-key="id" table-layout="auto" aria-label="主体授权规则">
            <ElTableColumn label="资源" min-width="150"
              ><template #default="{ row }"
                ><strong>{{ resourceLabels[row.resourceType as keyof typeof resourceLabels] }}</strong>
                <div class="muted">ID {{ row.resourceId }} · generation {{ row.resourceGeneration }}</div></template
              ></ElTableColumn
            >
            <ElTableColumn label="规则" min-width="150"
              ><template #default="{ row }"
                ><ElTag :type="row.effect === 'DENY' ? 'danger' : 'success'" size="small"
                  >{{ row.effect === 'DENY' ? '拒绝' : '允许' }} {{ row.action }}</ElTag
                >
                <div class="muted">{{ row.includeDescendants ? '包含子级' : '仅此资源' }} · {{ row.subjectType }} 直接规则</div></template
              ></ElTableColumn
            >
            <ElTableColumn label="有效期（台北）" min-width="185"
              ><template #default="{ row }"
                ><div>{{ date(row.validFrom) }}</div>
                <div class="muted">至 {{ date(row.validTo) }}</div></template
              ></ElTableColumn
            >
            <ElTableColumn label="状态 / 原因" min-width="150"
              ><template #default="{ row }"
                ><ElTag :type="row.status === 'ACTIVE' ? 'info' : 'warning'" size="small">{{
                  row.status === 'ACTIVE' ? '有效规则' : '已撤销'
                }}</ElTag>
                <p class="reason">{{ row.reason || '未填写原因' }}</p></template
              ></ElTableColumn
            >
            <ElTableColumn label="操作" min-width="130"
              ><template #default="{ row }"
                ><ElButton
                  v-if="canUpdate && canRead"
                  link
                  type="primary"
                  :disabled="row.status !== 'ACTIVE' || saving"
                  @click="openEdit(row as Grant)"
                  >编辑</ElButton
                ><ElButton
                  v-if="canDelete"
                  link
                  type="danger"
                  :disabled="row.status !== 'ACTIVE' || !!pendingDelete"
                  :loading="pendingDelete === row.id"
                  @click="revoke(row as Grant)"
                  >撤销</ElButton
                ></template
              ></ElTableColumn
            >
          </ElTable>
          <p class="panel-note">规则列表展示直接配置；有效权限还受平台范围、继承、DENY 和有效期约束。READ ALLOW 隐含 META，META DENY 阻断 READ。</p>
        </section>
        <section v-if="canPreview" class="preview-panel" aria-labelledby="preview-title" :aria-busy="previewLoading">
          <header class="panel-heading">
            <div>
              <h2 id="preview-title">有效权限预览</h2>
              <p>由服务端计算当前选中资源，不执行查询。</p>
            </div>
          </header>
          <ElForm label-position="top" class="preview-form">
            <ElFormItem v-if="subjectType !== 'USER'" label="预览用户"
              ><ElSelect v-model="previewUserId" filterable clearable placeholder="指定实际用户"
                ><ElOption v-for="user in subjectOptions.USER" :key="user.id" :label="user.label" :value="user.id" /></ElSelect
            ></ElFormItem>
            <ElFormItem label="平台"
              ><ElSelect v-model="platformId" placeholder="选择当前可见平台"
                ><ElOption v-for="platform in platforms" :key="platform.id" :label="platform.name" :value="platform.id" /></ElSelect
              ><ElButton v-if="platformCursor" text @click="loadPlatforms(true)">加载更多</ElButton></ElFormItem
            >
            <ElFormItem label="计算时点（台北，留空为当前）"
              ><ElDatePicker v-model="previewAt" type="datetime" clearable placeholder="当前时点"
            /></ElFormItem>
          </ElForm>
          <ElButton
            type="primary"
            :loading="previewLoading"
            :disabled="
              saving ||
              !!pendingDelete ||
              unknownOutcome ||
              !platformId ||
              !(subjectType === 'USER' ? subjectId : previewUserId) ||
              !selectedResource ||
              selectedResource.type === 'GROUP'
            "
            @click="runPreview"
            >计算有效权限</ElButton
          >
          <ElAlert
            v-if="previewAt"
            class="preview-alert"
            title="指定时点为模拟计算，不可作为执行授权凭据。"
            type="warning"
            show-icon
            :closable="false"
          />
          <ElAlert v-if="previewError" class="preview-alert" :title="previewError" type="error" show-icon :closable="false" />
          <div v-if="preview" class="preview-result" role="status">
            <div v-for="decision in preview.decisions" :key="decision.resourceId" class="decision">
              <span>资源 {{ selectedResource?.label }}</span
              ><ElTag :type="decision.meta ? 'success' : 'danger'">META {{ decision.meta ? '允许' : '拒绝' }}</ElTag
              ><ElTag :type="decision.read ? 'success' : 'danger'">READ {{ decision.read ? '允许' : '拒绝' }}</ElTag>
              <p>来源规则：{{ decision.sourceGrantIds.join('、') || '无' }}</p>
              <p v-if="decision.reasonCodes?.length">原因码：{{ decision.reasonCodes.join('、') }}</p>
            </div>
            <p class="help-text">
              计算于 {{ date(preview.evaluatedAt) }}<span v-if="preview.nextBoundaryAt"> · 下一时间边界 {{ date(preview.nextBoundaryAt) }}</span>
            </p>
            <section v-if="preview.versions" class="version-evidence" aria-label="使用版本">
              <h3>使用版本</h3>
              <EvidenceFields :value="preview.versions" />
            </section>
            <p v-else class="help-text">当前预览接口尚未返回完整版本证据，待后端补齐。</p>
          </div>
        </section>
      </div>
    </div>
    <EffectiveAccessPane
      :key="grants.map((row) => `${row.id}:${row.version}`).join(',')"
      :user-id="subjectType === 'USER' ? subjectId : previewUserId"
      :platform-id="platformId"
      :datasource-id="sourceId"
      :ready="!saving && !pendingDelete && !unknownOutcome"
    />
    <ElDialog
      v-model="dialogVisible"
      :title="editing ? '编辑授权规则' : '新增授权规则'"
      width="680px"
      :close-on-click-modal="false"
      :close-on-press-escape="!saving"
      :show-close="!saving"
      destroy-on-close
    >
      <p class="dialog-context">{{ subjectName }} → {{ draftTarget?.label }} · generation {{ draftTarget?.generation }}</p>
      <ElAlert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
      <div v-if="latestConflict" class="conflict-panel">
        <h3>服务端规则已变化，草稿已保留</h3>
        <p>
          当前版本 {{ latestConflict.version }}：{{ latestConflict.effect }} {{ latestConflict.action }}，{{
            latestConflict.includeDescendants ? '含子级' : '仅此资源'
          }}；{{ date(latestConflict.validFrom) }} 至 {{ date(latestConflict.validTo) }}
        </p>
        <p>服务端原因：{{ latestConflict.reason || '无' }}</p>
        <ElButton :disabled="saving" @click="openEdit(latestConflict)">放弃当前草稿，读取服务端版本</ElButton>
      </div>
      <ElForm label-position="top" :disabled="saving || unknownOutcome" class="grant-form">
        <div class="form-grid">
          <ElFormItem label="动作"
            ><ElSelect v-model="draft.action"
              ><ElOption label="META · 目录与结构" value="META" /><ElOption label="READ · 查询数据" value="READ" /></ElSelect></ElFormItem
          ><ElFormItem label="效果"
            ><ElSelect v-model="draft.effect"><ElOption label="ALLOW · 允许" value="ALLOW" /><ElOption label="DENY · 拒绝" value="DENY" /></ElSelect
          ></ElFormItem>
        </div>
        <ElFormItem label="继承范围"
          ><ElCheckbox v-model="draft.includeDescendants" :disabled="draftTarget?.type === 'OBJECT'">包含当前与未来子级</ElCheckbox></ElFormItem
        >
        <div class="form-grid">
          <ElFormItem label="生效时间（台北）" required><ElDatePicker v-model="draft.from" type="datetime" :clearable="false" /></ElFormItem
          ><ElFormItem label="结束时间（台北，可留空）"><ElDatePicker v-model="draft.to" type="datetime" clearable /></ElFormItem>
        </div>
        <ElFormItem label="变更原因" required
          ><ElInput v-model="draft.reason" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="说明本次变更的业务原因"
        /></ElFormItem>
        <p v-if="!validDraft" class="validation-text">请填写原因，结束时间须晚于生效时间。</p>
      </ElForm>
      <template #footer
        ><ElButton :disabled="saving" @click="dialogVisible = false">{{ unknownOutcome ? '关闭并核对规则' : '取消' }}</ElButton
        ><ElButton type="primary" :loading="saving" :disabled="!validDraft || !changed || unknownOutcome || !!latestConflict" @click="save">{{
          editing ? '保存修改' : '创建规则'
        }}</ElButton></template
      >
    </ElDialog>
  </section>
</template>

<style scoped>
.access-page {
  max-width: 1600px;
  margin: 0 auto;
}
.panel-heading {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 20px;
}
h2 {
  font-size: 15px;
  margin: 0 0 14px;
}
.panel-heading p {
  color: var(--ptmcp-muted);
  font-size: 13px;
  line-height: 1.6;
  margin: 8px 0 0;
}
.access-workspace {
  display: grid;
  grid-template-columns: 260px minmax(0, 1fr);
  gap: 20px;
  margin-top: 16px;
  align-items: start;
}
.selection-panel,
.rules-panel,
.preview-panel {
  border: 1px solid var(--ptmcp-line);
  border-radius: 8px;
  background: var(--ptmcp-panel);
  padding: 20px;
  min-width: 0;
}
.rules-column {
  min-width: 0;
  display: grid;
  gap: 20px;
}
.resource-title {
  padding-top: 16px;
  border-top: 1px solid var(--ptmcp-line);
}
.help-text,
.panel-note,
.selection-summary,
.muted {
  color: var(--ptmcp-muted);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.panel-heading {
  margin-bottom: 20px;
}
.panel-heading h2 {
  margin: 0;
}
.panel-note {
  border-top: 1px solid var(--ptmcp-line);
  padding-top: 14px;
  margin-bottom: 0;
}
.tree-label {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
}
.tree-label > span {
  overflow: hidden;
  text-overflow: ellipsis;
}
.tree-label small {
  color: var(--ptmcp-muted);
  font-size: 10px;
  flex: none;
}
.reason {
  white-space: pre-wrap;
  font-size: 12px;
  margin: 4px 0;
}
.preview-form,
.form-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 16px;
}
.preview-form .el-date-editor,
.form-grid .el-date-editor,
.selection-panel .el-select,
.preview-form .el-select,
.grant-form .el-select {
  width: 100%;
}
.preview-alert,
.preview-result,
.grant-form {
  margin-top: 16px;
}
.decision {
  padding: 16px;
  background: var(--ptmcp-surface);
  border-radius: 6px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.decision p {
  width: 100%;
  font-size: 12px;
  margin: 0;
}
.dialog-context {
  color: var(--ptmcp-muted);
  margin-top: 0;
  overflow-wrap: anywhere;
}
.conflict-panel {
  padding: 12px 14px;
  background: var(--ptmcp-bg-accent);
  margin-top: 16px;
  font-size: 13px;
}
.conflict-panel h3 {
  margin-top: 0;
  font-size: 14px;
}
.validation-text {
  color: var(--el-color-danger);
  font-size: 12px;
}
@media (max-width: 1100px) {
  .access-workspace {
    grid-template-columns: 230px minmax(0, 1fr);
    gap: 14px;
  }
  .selection-panel,
  .rules-panel,
  .preview-panel {
    padding: 16px;
  }
}
@media (max-width: 760px) {
  .access-workspace {
    grid-template-columns: minmax(0, 1fr);
  }
  .preview-form,
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
