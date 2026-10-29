<script setup lang="ts">
import PageHeader from '../../components/common/PageHeader.vue';
import StepWizard from '../../components/common/StepWizard.vue';
import { computed, onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { mePortalApi, type PlatformItem, type McpConfig } from '../../services/mePortalApi';
import { copyText } from '../../services/clipboard';
import { errorRequestId, errorText, usePortalTask } from '../portalUtils';

const platforms = ref<PlatformItem[]>([]);
const platformId = ref('');
const clientType = ref('GENERIC');
const config = ref<McpConfig | null>(null);
const loading = ref(false);
const error = ref('');
const requestId = ref<string | null>(null);
const platformLoading = ref(false);
const templateParts = computed(() => config.value?.configTemplate.split(/(\$\{PTMCP_PAT\}|<YOUR_PAT>|<PAT_OR_OAUTH_ACCESS_TOKEN>)/g) ?? []);
const needsPat = computed(() => templateParts.value.length > 1);
const nextPlatformCursor = ref<string | null>(null);
let configSequence = 0;
const platformTask = usePortalTask(() => {
  platforms.value = [];
  platformId.value = '';
  nextPlatformCursor.value = null;
  platformLoading.value = false;
});
const configTask = usePortalTask(() => {
  configSequence++;
  config.value = null;
  loading.value = false;
});
const clientTypes = [
  { label: '通用客户端', value: 'GENERIC' },
  { label: 'WorkBuddy', value: 'WORKBUDDY' },
  { label: 'Cursor', value: 'CURSOR' },
  { label: 'Claude Code', value: 'CLAUDE_CODE' },
  { label: 'VS Code', value: 'VSCODE' },
];
const wizardStep = ref(0);
const wizardCanNext = computed(() => {
  if (wizardStep.value === 0) return !!platformId.value && !platformLoading.value;
  if (wizardStep.value === 1) return !!config.value && !loading.value;
  return true;
});

async function loadPlatforms(append = false): Promise<void> {
  if (platformLoading.value) return;
  platformLoading.value = true;
  error.value = '';
  requestId.value = null;
  try {
    const page = await platformTask.run((signal) =>
      mePortalApi.accessContext.platforms(append ? (nextPlatformCursor.value ?? undefined) : undefined, signal),
    );
    if (!page) return;
    platforms.value = append ? [...platforms.value, ...page.items] : page.items;
    nextPlatformCursor.value = page.nextCursor;
    if (!platformId.value && platforms.value[0]) platformId.value = platforms.value[0].id;
  } catch (reason) {
    error.value = errorText(reason, false);
    requestId.value = errorRequestId(reason);
  } finally {
    platformLoading.value = false;
  }
}
async function loadConfig(): Promise<void> {
  const own = ++configSequence;
  configTask.cancel();
  config.value = null;
  error.value = '';
  requestId.value = null;
  if (!platformId.value) {
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await configTask.run((signal) => mePortalApi.mcpConfig(platformId.value, clientType.value, signal));
    if (result && own === configSequence) config.value = result;
  } catch (reason) {
    if (own === configSequence) {
      error.value = errorText(reason, false);
      requestId.value = errorRequestId(reason);
    }
  } finally {
    if (own === configSequence) loading.value = false;
  }
}
async function copyConfig(): Promise<void> {
  if (!config.value) return;
  try {
    await copyText(config.value.configTemplate);
    ElMessage.success('配置模板已复制');
  } catch {
    ElMessage.warning('浏览器未允许自动复制，请手动选择文本');
  }
}
watch([platformId, clientType], () => {
  void loadConfig();
});
onMounted(() => {
  void loadPlatforms();
});
</script>

<template>
  <section class="portal-page" aria-labelledby="integrations-title">
    <PageHeader title-id="integrations-title" title="MCP 接入配置" description="模板只提供人工配置步骤，不会自动连接客户端或执行查询。" />
    <PageState
      v-if="error"
      kind="error"
      title="接入配置读取失败"
      :description="error"
      :request-id="requestId"
      action-label="重新读取"
      @action="platforms.length ? loadConfig() : loadPlatforms()"
    />
    <StepWizard v-model="wizardStep" :steps="['选择平台与客户端', '配置模板', '接入步骤']" :can-next="wizardCanNext" finish-label="完成接入">
      <template #step-0>
        <div class="ptmcp-filter-strip">
          <ElFormItem label="平台"
            ><ElSelect v-model="platformId" :loading="platformLoading" placeholder="选择平台" class="select"
              ><ElOption v-for="item in platforms" :key="item.id" :label="`${item.name} · Tier ${item.tier}`" :value="item.id" /><template
                v-if="nextPlatformCursor"
                #footer
                ><ElButton link type="primary" @click="loadPlatforms(true)">加载更多平台</ElButton></template
              ></ElSelect
            ></ElFormItem
          >
        </div>
        <h2 class="client-heading">选择客户端</h2>
        <ElRadioGroup v-model="clientType" class="client-options" aria-label="MCP 客户端">
          <ElRadio v-for="client in clientTypes" :key="client.value" :value="client.value" border>
            <span class="client-option"
              ><span aria-hidden="true" class="client-icon"
                ><IconEpConnection v-if="client.value === 'WORKBUDDY'" /><IconEpEditPen v-else-if="client.value === 'CURSOR'" /><IconEpCpu
                  v-else-if="client.value === 'CLAUDE_CODE'" /><IconEpMonitor v-else-if="client.value === 'VSCODE'" /><IconEpSetting v-else /></span
              >{{ client.label }}</span
            >
          </ElRadio>
        </ElRadioGroup>
      </template>
      <template #step-1>
        <ElSkeleton v-if="loading" :rows="8" animated /><PageState v-else-if="!config" description="返回上一步选择平台后加载配置" /><template v-else
          ><div class="status-row">
            <div>
              <span class="label">传输</span><strong>{{ config.transport }}</strong>
            </div>
            <div>
              <span class="label">认证</span><strong>{{ config.authentication.join(' / ') || '待配置' }}</strong>
            </div>
            <ElTag type="warning">{{ config.compatibilityStatus === 'UNVERIFIED' ? '待验证' : config.compatibilityStatus }}</ElTag>
          </div>
          <ElAlert
            v-if="!config.url || !config.validatedVersion"
            type="warning"
            :closable="false"
            title="部署地址或客户端版本尚未验证，请在人工配置前确认环境。"
            class="mb-4"
          />
          <ElAlert v-if="needsPat" type="warning" :closable="false" title="模板包含凭证占位符" class="mb-4"
            ><p>使用 PAT 时，先创建 PAT，再在客户端受控凭证设置中替换高亮占位符；使用 OAuth 时由客户端完成授权。</p>
            <RouterLink to="/me/tokens">前往创建 PAT</RouterLink></ElAlert
          >
          <div class="code-heading"><span>配置模板</span><ElButton size="small" @click="copyConfig">复制模板</ElButton></div>
          <pre
            class="config-block"
          ><code><template v-for="(part, index) in templateParts" :key="index"><mark v-if="index % 2 === 1">{{ part }}</mark><template v-else>{{ part }}</template></template></code></pre>
          <p class="muted">
            验证版本：{{ config.validatedVersion ?? '尚未验证' }}。PAT 仅作为占位符使用。<RouterLink to="/me/tokens">创建和管理 PAT</RouterLink>
          </p></template
        >
      </template>
      <template #step-2>
        <h2 class="client-heading">接入步骤</h2>
        <ol class="muted">
          <li>确认平台、服务地址及客户端版本，手动复制配置模板。</li>
          <li>OAuth：在客户端发起登录并核对授权范围；到期或撤销后重新授权。</li>
          <li>PAT：仅在客户端受控凭证设置中替换占位符，不写入共享配置或提交到仓库。</li>
          <li>在客户端检查连接。此页面不执行连接测试或查询，也不承诺未经实测的兼容性。</li>
        </ol>
      </template>
    </StepWizard>
  </section>
</template>

<style scoped>
.client-heading {
  margin: 16px 0 12px;
  font-size: 14px;
}
.client-options {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 12px;
  width: 100%;
}
.client-options :deep(.el-radio) {
  margin: 0;
  min-width: 0;
  min-height: 52px;
  height: auto;
  padding: 12px;
  background: var(--ptmcp-canvas);
}
.client-options :deep(.el-radio.is-checked) {
  background: var(--ptmcp-blue-soft);
}
.client-option,
.client-icon {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}
.config-block mark {
  background: var(--ptmcp-bg-warning);
  color: var(--ptmcp-orange);
  padding: 2px;
}
.muted {
  color: var(--ptmcp-muted);
  font-size: 14px;
}
.portal-card {
  margin-bottom: 16px;
  border-color: var(--ptmcp-border);
}
.select {
  min-width: 240px;
}
.status-row,
.code-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
.label {
  display: block;
  color: var(--ptmcp-muted);
  font-size: 12px;
  margin-bottom: 4px;
}
.status-row strong {
  color: var(--ptmcp-ink);
}
.code-heading {
  margin: 18px 0 8px;
  font-weight: 600;
}
.config-block {
  margin: 0;
  max-height: 420px;
  overflow: auto;
  padding: 18px;
  border: 1px solid var(--ptmcp-border);
  background: var(--ptmcp-chrome);
  color: var(--ptmcp-text);
  white-space: pre-wrap;
  word-break: break-word;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
