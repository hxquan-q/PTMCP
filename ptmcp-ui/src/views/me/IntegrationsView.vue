<script setup lang="ts">
import { onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { mePortalApi, type PlatformItem, type McpConfig } from '../../services/mePortalApi';
import { errorText } from '../portalUtils';

const platforms = ref<PlatformItem[]>([]);
const platformId = ref('');
const clientType = ref('GENERIC');
const config = ref<McpConfig | null>(null);
const loading = ref(false);
const error = ref('');
const clientTypes = [
  { label: '通用客户端', value: 'GENERIC' },
  { label: 'WorkBuddy', value: 'WORKBUDDY' },
  { label: 'Cursor', value: 'CURSOR' },
  { label: 'Claude Code', value: 'CLAUDE_CODE' },
  { label: 'VS Code', value: 'VSCODE' }
];

async function loadPlatforms(): Promise<void> {
  try {
    platforms.value = (await mePortalApi.accessContext.platforms()).items;
    if (!platformId.value && platforms.value[0]) platformId.value = platforms.value[0].id;
  } catch (reason) {
    error.value = errorText(reason);
  }
}
async function loadConfig(): Promise<void> {
  config.value = null;
  error.value = '';
  if (!platformId.value) return;
  loading.value = true;
  try {
    config.value = await mePortalApi.mcpConfig(platformId.value, clientType.value);
  } catch (reason) {
    error.value = errorText(reason);
  } finally {
    loading.value = false;
  }
}
async function copyConfig(): Promise<void> {
  if (!config.value) return;
  try {
    await navigator.clipboard.writeText(config.value.configTemplate);
    ElMessage.success('配置模板已复制');
  } catch {
    ElMessage.warning('浏览器未允许自动复制，请手动选择文本');
  }
}
watch([platformId, clientType], () => {
  void loadConfig();
});
onMounted(loadPlatforms);
</script>

<template>
  <section class="portal-page" aria-labelledby="integrations-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">个人接入</p>
        <h1 id="integrations-title">MCP 接入配置</h1>
        <p class="page-description">模板只提供人工配置步骤，不会自动连接客户端或执行查询。</p>
      </div>
    </div>
    <ElAlert v-if="error" type="error" :closable="false" :title="error" class="mb-4" />
    <ElCard shadow="never" class="portal-card"
      ><div class="filters">
        <ElFormItem label="平台"
          ><ElSelect v-model="platformId" placeholder="选择平台" class="select"
            ><ElOption v-for="item in platforms" :key="item.id" :label="`${item.name} · Tier ${item.tier}`" :value="item.id" /></ElSelect></ElFormItem
        ><ElFormItem label="客户端"
          ><ElSelect v-model="clientType" class="select"
            ><ElOption v-for="item in clientTypes" :key="item.value" :label="item.label" :value="item.value" /></ElSelect
        ></ElFormItem></div
    ></ElCard>
    <ElCard shadow="never" class="portal-card"
      ><ElSkeleton v-if="loading" :rows="8" animated /><ElEmpty v-else-if="!config" description="选择平台后加载配置" /><template v-else
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
        <div class="code-heading"><span>配置模板</span><ElButton size="small" @click="copyConfig">复制模板</ElButton></div>
        <pre class="config-block"><code>{{ config.configTemplate }}</code></pre>
        <p class="muted">PAT 仅作为占位符使用。请通过“我的 PAT”单独创建并安全保存凭证。</p></template
      ></ElCard
    >
  </section>
</template>

<style scoped>
.page-heading {
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
.muted {
  color: var(--ptmcp-muted, #667085);
  font-size: 14px;
}
.page-description {
  margin: 8px 0 0;
}
.portal-card {
  margin-bottom: 16px;
  border-color: var(--ptmcp-border, #d9e2ec);
}
.filters {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
}
.filters :deep(.el-form-item) {
  margin: 0;
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
  color: var(--ptmcp-muted, #667085);
  font-size: 12px;
  margin-bottom: 4px;
}
.status-row strong {
  color: var(--ptmcp-ink, #1d2939);
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
  border: 1px solid var(--ptmcp-border, #d9e2ec);
  background: #f7f9fb;
  color: #27364b;
  white-space: pre-wrap;
  word-break: break-word;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
