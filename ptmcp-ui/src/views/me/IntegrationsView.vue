<script setup lang="ts">
import PageHeader from '../../components/common/PageHeader.vue';
import { onMounted, ref, watch } from 'vue';
import { ElMessage } from 'element-plus';
import { mePortalApi, type PlatformItem, type McpConfig } from '../../services/mePortalApi';
import { copyText } from '../../services/clipboard';
import { errorText, usePortalTask } from '../portalUtils';

const platforms = ref<PlatformItem[]>([]);
const platformId = ref('');
const clientType = ref('GENERIC');
const config = ref<McpConfig | null>(null);
const loading = ref(false);
const error = ref('');
const nextPlatformCursor = ref<string | null>(null);
let configSequence = 0;
const platformTask = usePortalTask(() => {
  platforms.value = [];
  platformId.value = '';
  nextPlatformCursor.value = null;
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

async function loadPlatforms(append = false): Promise<void> {
  try {
    const page = await platformTask.run((signal) =>
      mePortalApi.accessContext.platforms(append ? (nextPlatformCursor.value ?? undefined) : undefined, signal),
    );
    if (!page) return;
    platforms.value = append ? [...platforms.value, ...page.items] : page.items;
    nextPlatformCursor.value = page.nextCursor;
    if (!platformId.value && platforms.value[0]) platformId.value = platforms.value[0].id;
  } catch (reason) {
    error.value = errorText(reason);
  }
}
async function loadConfig(): Promise<void> {
  const own = ++configSequence;
  configTask.cancel();
  config.value = null;
  error.value = '';
  if (!platformId.value) {
    loading.value = false;
    return;
  }
  loading.value = true;
  try {
    const result = await configTask.run((signal) => mePortalApi.mcpConfig(platformId.value, clientType.value, signal));
    if (result && own === configSequence) config.value = result;
  } catch (reason) {
    if (own === configSequence) error.value = errorText(reason);
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
    <ElAlert v-if="error" type="error" :closable="false" :title="error" class="mb-4"
      ><ElButton @click="platforms.length ? loadConfig() : loadPlatforms()">重新读取</ElButton></ElAlert
    >
    <ElCard shadow="never" class="portal-card"
      ><div class="filters">
        <ElFormItem label="平台"
          ><ElSelect v-model="platformId" placeholder="选择平台" class="select"
            ><ElOption v-for="item in platforms" :key="item.id" :label="`${item.name} · Tier ${item.tier}`" :value="item.id" /><template
              v-if="nextPlatformCursor"
              #footer
              ><ElButton link type="primary" @click="loadPlatforms(true)">加载更多平台</ElButton></template
            ></ElSelect
          ></ElFormItem
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
        <p class="muted">
          验证版本：{{ config.validatedVersion ?? '尚未验证' }}。PAT 仅作为占位符使用。<RouterLink to="/me/tokens">创建和管理 PAT</RouterLink>
        </p>
        <h2>接入步骤</h2>
        <ol class="muted">
          <li>确认平台、服务地址及客户端版本，手动复制配置模板。</li>
          <li>OAuth：在客户端发起登录并核对授权范围；到期或撤销后重新授权。</li>
          <li>PAT：仅在客户端受控凭证设置中替换占位符，不写入共享配置或提交到仓库。</li>
          <li>在客户端检查连接。此页面不执行连接测试或查询，也不承诺未经实测的兼容性。</li>
        </ol></template
      ></ElCard
    >
  </section>
</template>

<style scoped>
.muted {
  color: var(--ptmcp-muted);
  font-size: 14px;
}
.portal-card {
  margin-bottom: 16px;
  border-color: var(--ptmcp-border);
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
