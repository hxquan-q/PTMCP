<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import FullScreenIcon from '~icons/ep/full-screen';
import CloseIcon from '~icons/ep/close';
import ArrowUpIcon from '~icons/ep/arrow-up';
import ArrowDownIcon from '~icons/ep/arrow-down';
import UserIcon from '~icons/ep/user';
import CoinIcon from '~icons/ep/coin';
import KeyIcon from '~icons/ep/key';
import CheckIcon from '~icons/ep/check';
import PageHeader from '../../../components/common/PageHeader.vue';
import StatCard from '../../../components/common/StatCard.vue';
import { useAccessWorkspace } from './useAccessWorkspace';
import { findResourceSelection } from './resource-tree';
import type { Grant } from '../../../services/access';
import EffectiveAccessPane from './EffectiveAccessPane.vue';
import EvidenceFields from '../../../components/common/EvidenceFields.vue';
import { formatDate } from '../../portalUtils';

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
const subjectSearch = ref('');
const filteredSubjects = computed(() => {
  const query = subjectSearch.value.trim().toLocaleLowerCase();
  return subjectOptions[subjectType.value].filter((item) => !query || `${item.label} ${item.id}`.toLocaleLowerCase().includes(query));
});
watch(subjectType, () => {
  subjectSearch.value = '';
});
const resourceLabels = { DATASOURCE: '数据源', NAMESPACE: '命名空间', OBJECT: '对象' };
function activateTreeNode(event: KeyboardEvent): void {
  if (event.key !== 'Enter' && event.key !== ' ') return;
  const node = (event.target as HTMLElement).closest<HTMLElement>('[role="treeitem"][data-key]');
  const key = node?.dataset.key;
  if (!key) return;
  const resource = findResourceSelection(resourceTree.value, key);
  if (!resource) return;
  event.preventDefault();
  selectResource(resource);
}
function date(value: string | null) {
  return value ? formatDate(value) : '无结束时间';
}

/* ── 工作台布局：视口锁死三栏 + 可拖分栏 + 专注模式 + 底部预览折叠 ── */
const focusMode = ref(false);
const previewOpen = ref(false);
/* 三栏宽度（%），主体/资源由 colWidths 驱动，规则栏吃剩余 */
const colWidths = ref<[number, number]>([25, 25]);
const subjectColStyle = computed(() => (focusMode.value ? { display: 'none' } : { flex: `0 0 ${colWidths.value[0]}%` }));
const resourceColStyle = computed(() => (focusMode.value ? { display: 'none' } : { flex: `0 0 ${colWidths.value[1]}%` }));
const rulesColStyle = computed(() => (focusMode.value ? { flex: '1 1 0%' } : { flex: '1 1 0%' }));

const MIN_COL = 14; // %
const MAX_COL = 46;
const workspaceEl = ref<HTMLElement | null>(null);
const dragging = ref<0 | 1 | null>(null); // 0=主体|资源 1=资源|规则

function onResizerDown(which: 0 | 1, event: PointerEvent): void {
  event.preventDefault();
  (event.target as HTMLElement).setPointerCapture?.(event.pointerId);
  dragging.value = which;
  window.addEventListener('pointermove', onResizerMove);
  window.addEventListener('pointerup', onResizerUp);
}
function onResizerMove(event: PointerEvent): void {
  const which = dragging.value;
  const host = workspaceEl.value;
  if (which === null || !host) return;
  const rect = host.getBoundingClientRect();
  if (rect.width <= 0) return;
  const xPct = ((event.clientX - rect.left) / rect.width) * 100;
  const [a, b] = colWidths.value;
  if (which === 0) {
    const next = Math.min(Math.max(xPct, MIN_COL), Math.min(MAX_COL, 100 - MIN_COL - b));
    colWidths.value = [next, b];
  } else {
    const next = Math.min(Math.max(xPct - a, MIN_COL), MAX_COL);
    colWidths.value = [a, next];
  }
}
function onResizerUp(): void {
  dragging.value = null;
  window.removeEventListener('pointermove', onResizerMove);
  window.removeEventListener('pointerup', onResizerUp);
}
onBeforeUnmount(() => {
  if (typeof window === 'undefined') return;
  window.removeEventListener('pointermove', onResizerMove);
  window.removeEventListener('pointerup', onResizerUp);
});
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
    <ElAlert v-if="pageError" :title="pageError" type="error" :closable="false" show-icon
      ><ElButton :disabled="!subjectId" @click="loadGrants">重新读取规则</ElButton></ElAlert
    >
    <div class="stat-card-row">
      <StatCard label="授权主体" :value="filteredSubjects.length" hint="当前主体类型下可管理" :icon="UserIcon" />
      <StatCard label="资源范围" :value="selectedResource ? 1 : 0" :hint="selectedResource ? '已选资源' : '未选择资源'" :icon="CoinIcon" />
      <StatCard label="授权规则" :value="grants.length" hint="当前主体已授权规则" :icon="KeyIcon" />
      <StatCard label="有效权限" :value="preview?.decisions?.length ?? 0" hint="平台范围内计算结果" :icon="CheckIcon" tone="success" />
    </div>
    <div ref="workspaceEl" class="access-workspace" :class="{ 'is-focus': focusMode }">
      <aside v-if="!focusMode" class="pane subject-pane" :style="subjectColStyle" aria-label="授权主体">
        <header class="pane-head">
          <h2>授权主体</h2>
          <ElTabs v-model="subjectType" class="subject-tabs" aria-label="主体类型">
            <ElTabPane label="用户" name="USER" :disabled="saving" />
            <ElTabPane label="角色" name="ROLE" :disabled="saving" />
            <ElTabPane label="部门" name="DEPT" :disabled="saving" />
          </ElTabs>
          <ElInput
            v-model="subjectSearch"
            class="subject-search"
            clearable
            placeholder="搜索主体名称或 ID"
            aria-label="搜索授权主体"
            :disabled="saving"
          />
        </header>
        <div class="pane-body subject-body">
          <div class="subject-list" aria-label="可管理主体">
            <button
              v-for="item in filteredSubjects"
              :key="item.id"
              type="button"
              :aria-pressed="subjectId === item.id"
              :disabled="saving"
              @click="subjectId = item.id"
            >
              {{ item.label }}<small>ID {{ item.id }}</small>
            </button>
          </div>
          <p v-if="!filteredSubjects.length" class="help-text">
            {{ subjectSearch ? '没有匹配的主体。' : '没有可选主体，或缺少对应目录的读取权限。' }}
          </p>
        </div>
        <footer class="pane-foot">
          <span>共 {{ filteredSubjects.length }} 个主体</span>
          <ElButton v-if="subjectId" link type="primary" :disabled="saving" @click="subjectId = ''">清除</ElButton>
        </footer>
      </aside>
      <div
        v-if="!focusMode"
        class="col-resizer"
        role="separator"
        aria-orientation="vertical"
        aria-label="拖动调整主体与资源栏宽"
        :class="{ 'is-drag': dragging === 0 }"
        @pointerdown="onResizerDown(0, $event)"
      ></div>
      <aside v-if="!focusMode" class="pane resource-pane" :style="resourceColStyle" aria-label="资源范围">
        <header class="pane-head">
          <h2>资源范围</h2>
          <ElSelect v-model="sourceId" class="source-select" filterable clearable placeholder="选择数据源" :disabled="saving">
            <ElOption v-for="source in sources" :key="source.id" :label="source.name" :value="source.id" />
          </ElSelect>
          <ElButton v-if="sourceCursor" link type="primary" :disabled="saving" @click="loadSources(true)">加载更多数据源</ElButton>
        </header>
        <div class="pane-body resource-body">
          <ElAlert v-if="resourceError" :title="resourceError" type="warning" :closable="false" show-icon
            ><ElButton :disabled="!sourceId" @click="loadResources()">重新读取资源</ElButton></ElAlert
          >
          <ElSkeleton v-if="resourceLoading && !resourceTree.length" :rows="4" animated />
          <div v-else-if="resourceTree.length" class="resource-tree" @keydown="activateTreeNode">
            <ElTree
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
                ><span class="tree-label" :title="data.label"
                  ><span>{{ data.label }}</span
                  ><small>{{ data.type === 'GROUP' ? '目录分组' : resourceLabels[data.type as keyof typeof resourceLabels] }}</small></span
                ></template
              >
            </ElTree>
          </div>
          <p v-else-if="!resourceError" class="help-text">先选择数据源以读取当前资源。</p>
          <ElButton v-if="resourceCursor" link type="primary" :loading="resourceLoading" @click="loadResources(true)">加载更多对象</ElButton>
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
        </div>
        <footer class="pane-foot">
          <span>{{ sources.length }} 个数据源 · {{ resourceTree.length }} 根节点</span>
          <span v-if="selectedResource" class="foot-sel">{{ selectedResource.label }}</span>
        </footer>
      </aside>
      <div
        v-if="!focusMode"
        class="col-resizer"
        role="separator"
        aria-orientation="vertical"
        aria-label="拖动调整资源与规则栏宽"
        :class="{ 'is-drag': dragging === 1 }"
        @pointerdown="onResizerDown(1, $event)"
      ></div>
      <section class="pane rules-pane" :style="rulesColStyle" aria-labelledby="rules-title" :aria-busy="loading">
        <header class="pane-head rules-head">
          <div class="rules-title">
            <h2 id="rules-title">授权规则</h2>
            <p>{{ subjectId ? subjectName : '选择左侧主体查看规则' }}</p>
          </div>
          <div class="rules-tools">
            <ElButton v-if="canList" :disabled="!subjectId" :loading="loading" @click="loadGrants">刷新</ElButton>
            <ElTooltip :content="focusMode ? '退出专注模式' : '专注模式：隐藏左侧两栏'" placement="top">
              <ElButton circle :aria-pressed="focusMode" :aria-label="focusMode ? '退出专注模式' : '进入专注模式'" @click="focusMode = !focusMode">
                <component :is="focusMode ? CloseIcon : FullScreenIcon" />
              </ElButton>
            </ElTooltip>
          </div>
        </header>
        <div class="pane-body rules-body">
          <ElSkeleton v-if="loading && !grants.length" :rows="5" animated />
          <PageState v-else-if="!canList" description="缺少授权规则列表权限，可使用下方已获授权的预览功能。" />
          <PageState v-else-if="!subjectId" kind="idle" title="尚未选择授权主体" description="先在左侧选择主体，再读取直接规则。" />
          <PageState v-else-if="!grants.length && !pageError" kind="empty" description="此主体尚无直接授权规则" />
          <ElTable
            v-else-if="grants.length"
            :data="grants"
            class="ptmcp-data-table rules-table"
            size="small"
            row-key="id"
            height="100%"
            table-layout="auto"
            aria-label="主体授权规则"
          >
            <ElTableColumn label="资源" min-width="150"
              ><template #default="{ row }"
                ><div class="ptmcp-cell-lead">
                  <span class="ptmcp-cell-icon">
                    <IconEpKey aria-hidden="true" />
                  </span>
                  <span class="ptmcp-cell-stack">
                    <strong>{{ resourceLabels[row.resourceType as keyof typeof resourceLabels] }}</strong>
                    <span>ID {{ row.resourceId }} · generation {{ row.resourceGeneration }}</span>
                  </span>
                </div></template
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
            <ElTableColumn label="操作" width="110" align="right" fixed="right"
              ><template #default="{ row }"
                ><div class="ptmcp-row-actions">
                  <ElButton
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
                  >
                </div></template
              ></ElTableColumn
            >
          </ElTable>
        </div>
        <footer class="pane-foot rules-foot">
          <span>共 {{ grants.length }} 条规则</span>
          <span class="foot-note">直接规则 · READ ALLOW 隐含 META，META DENY 阻断 READ</span>
        </footer>
      </section>
    </div>
    <section v-if="canPreview" class="preview-strip" :class="{ 'is-open': previewOpen }" aria-labelledby="preview-title">
      <button type="button" class="preview-strip-head" :aria-expanded="previewOpen" aria-controls="preview-body" @click="previewOpen = !previewOpen">
        <span class="preview-strip-title">
          <strong id="preview-title">有效权限预览</strong>
          <span class="preview-strip-sub">由服务端计算当前选中资源，不执行查询</span>
        </span>
        <span v-if="preview" class="preview-strip-meta">{{ preview.decisions.length }} 项决策 · {{ date(preview.evaluatedAt) }}</span>
        <span class="preview-strip-toggle">{{ previewOpen ? '收起' : '展开' }}<component :is="previewOpen ? ArrowUpIcon : ArrowDownIcon" /></span>
      </button>
      <div v-show="previewOpen" id="preview-body" class="preview-strip-body" :aria-busy="previewLoading">
        <div class="permission-explanation" role="note" aria-label="有效权限判定公式">
          <div class="permission-equation">
            <span>平台 Scope</span><strong>∩</strong><span>用户规则（含继承）</span><strong>=</strong><span>最终权限</span>
          </div>
          <p>由服务端按有效期计算；DENY 优先，META DENY 阻断 READ。预览不代替执行时授权。</p>
        </div>
        <ElForm label-position="top" class="preview-form">
          <ElFormItem v-if="subjectType !== 'USER'" label="预览用户"
            ><ElSelect v-model="previewUserId" filterable clearable placeholder="指定实际用户"
              ><ElOption v-for="user in subjectOptions.USER" :key="user.id" :label="user.label" :value="user.id" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="平台"
            ><ElSelect v-model="platformId" placeholder="选择当前可见平台"
              ><ElOption v-for="platform in platforms" :key="platform.id" :label="platform.name" :value="platform.id" /></ElSelect
            ><ElButton v-if="platformCursor" link type="primary" @click="loadPlatforms(true)">加载更多</ElButton></ElFormItem
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
      </div>
    </section>
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
/* ── 页面：视口锁死 —— 三栏不随内容撑高，下方预览折叠栏与有效资源表固定在可视范围 ── */
.access-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-width: 1600px;
  margin: 0 auto;
  min-height: 0;
}
.access-page > .el-alert {
  flex: none;
}
h2 {
  font-size: 15px;
  margin: 0;
}

/* ── 工作台：flex 三栏 + 两条可拖分隔线，吃满剩余高度并锁死 ── */
.access-workspace {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: stretch;
  gap: 0;
  margin-top: 16px;
  overflow: hidden;
}
.access-workspace.is-focus {
  gap: 0;
}

/* 每栏 = 卡片：head(固定) + body(flex-1 独立滚动) + foot(固定统计) */
.pane {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  border: 1px solid var(--ptmcp-line);
  border-radius: var(--ptmcp-radius-panel);
  background: var(--ptmcp-panel);
  overflow: hidden;
}
.pane-head {
  flex: none;
  padding: 14px 16px;
  border-bottom: 1px solid var(--ptmcp-line);
}
.pane-head h2 {
  margin-bottom: 12px;
}
.pane-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 16px;
}
.pane-foot {
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  border-top: 1px solid var(--ptmcp-line);
  background: var(--ptmcp-band);
  font-size: 12px;
  color: var(--ptmcp-muted);
  min-height: 40px;
}

/* 可拖分隔线：5px 命中区，hover/拖动时显蓝色细线 */
.col-resizer {
  flex: none;
  width: 9px;
  margin-inline: -2px;
  cursor: col-resize;
  position: relative;
  z-index: 2;
  touch-action: none;
}
.col-resizer::before {
  content: '';
  position: absolute;
  inset: 0;
  left: 4px;
  width: 1px;
  background: var(--ptmcp-line);
  transition:
    background 0.15s,
    width 0.15s;
}
.col-resizer:hover::before,
.col-resizer.is-drag::before {
  background: var(--ptmcp-blue);
  width: 3px;
  left: 3px;
}
.col-resizer:focus-visible::before {
  background: var(--ptmcp-blue);
  width: 3px;
}

/* ── 授权主体栏 ── */
.subject-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}
.subject-tabs :deep(.el-tabs__item) {
  padding-inline: 8px;
  font-size: 13px;
}
.subject-search {
  margin-top: 12px;
}
.subject-body {
  display: flex;
  flex-direction: column;
}
.subject-list {
  display: grid;
  gap: 8px;
}
.subject-list button {
  display: grid;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid var(--ptmcp-border);
  border-radius: var(--ptmcp-radius-control);
  background: var(--ptmcp-canvas);
  color: var(--ptmcp-text);
  text-align: left;
  overflow-wrap: anywhere;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.subject-list button:hover {
  border-color: var(--ptmcp-blue);
}
.subject-list button[aria-pressed='true'] {
  background: var(--ptmcp-blue-soft);
  border-color: var(--ptmcp-blue);
}
.subject-list small {
  color: var(--ptmcp-text-muted);
}

/* ── 资源范围栏 ── */
.source-select {
  width: 100%;
}
.resource-body {
  display: flex;
  flex-direction: column;
}
.resource-tree {
  flex: none;
}
.resource-pane :deep(.el-tree-node__content) {
  height: auto;
  min-height: 32px;
}
.resource-tree :deep(.el-tree-node:focus-visible > .el-tree-node__content) {
  outline: 2px solid var(--ptmcp-blue);
  outline-offset: -2px;
}
.resource-tree :deep(.el-tree-node.is-current > .el-tree-node__content) {
  background: var(--ptmcp-selected);
}
.tree-label {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
  flex: 1;
}
.tree-label > span {
  white-space: normal;
  overflow-wrap: anywhere;
  padding-block: 6px;
}
.tree-label small {
  color: var(--ptmcp-muted);
  font-size: 10px;
  flex: none;
}
.selection-summary {
  margin-top: 12px;
  border-top: 1px dashed var(--ptmcp-line);
  padding-top: 12px;
}
.foot-sel {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 55%;
}

/* ── 授权规则栏 ── */
.rules-pane {
  flex: 1 1 0%;
}
.rules-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
.rules-title h2 {
  margin: 0;
}
.rules-title p {
  color: var(--ptmcp-muted);
  font-size: 12px;
  line-height: 1.6;
  margin: 4px 0 0;
}
.rules-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}
.rules-body {
  padding: 0;
  display: flex;
  flex-direction: column;
}
.rules-body > .el-skeleton,
.rules-body > :deep(.page-state) {
  margin: 16px;
}
.rules-body > :deep(.page-state) {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding-block: 24px;
}
/* 紧凑规则表：body 滚动区撑满，表头固定由 height=100% 撑住 */
.rules-table {
  flex: 1;
  min-height: 0;
}
.rules-foot {
  justify-content: space-between;
}
.foot-note {
  font-size: 11px;
  opacity: 0.85;
}
.reason {
  white-space: pre-wrap;
  font-size: 12px;
  margin: 4px 0;
}
.muted,
.help-text {
  color: var(--ptmcp-muted);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

/* ── 有效权限预览：底部折叠栏 ── */
.preview-strip {
  flex: none;
  margin-top: 12px;
  border: 1px solid var(--ptmcp-line);
  border-radius: var(--ptmcp-radius-panel);
  background: var(--ptmcp-panel);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.preview-strip-head {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
  padding: 12px 16px;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
  color: inherit;
}
.preview-strip-head:hover {
  background: var(--ptmcp-hover);
}
.preview-strip-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
  flex: 1;
}
.preview-strip-title strong {
  font-size: 14px;
  white-space: nowrap;
}
.preview-strip-sub {
  font-size: 12px;
  color: var(--ptmcp-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.preview-strip-meta {
  font-size: 12px;
  color: var(--ptmcp-muted);
  white-space: nowrap;
}
.preview-strip-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--ptmcp-blue);
  white-space: nowrap;
}
.preview-strip-body {
  border-top: 1px solid var(--ptmcp-line);
  padding: 16px;
  overflow-y: auto;
  max-height: 46vh;
}
.permission-explanation {
  padding: 16px;
  margin-bottom: 16px;
  background: var(--ptmcp-chrome);
}
.permission-equation {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.permission-explanation p {
  margin: 12px 0 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--ptmcp-text-muted);
}
.preview-form,
.form-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 0 16px;
}
.form-grid {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
.preview-form .el-date-editor,
.form-grid .el-date-editor,
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

/* ── 响应式：小屏禁用分栏拖拽与百分比，改纵向堆叠 ── */
@media (max-width: 1100px) {
  .access-page {
    height: auto;
  }
  .access-workspace {
    flex-direction: column;
    overflow: visible;
  }
  .pane {
    flex: none;
    min-height: 0;
  }
  .subject-pane,
  .resource-pane {
    flex: none !important;
  }
  .pane-body {
    max-height: 320px;
  }
  .rules-body {
    max-height: 480px;
  }
  .col-resizer {
    display: none;
  }
  .preview-form {
    grid-template-columns: minmax(0, 1fr);
  }
}
@media (max-width: 760px) {
  .form-grid {
    grid-template-columns: minmax(0, 1fr);
  }
  .preview-strip-title {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }
}
</style>
