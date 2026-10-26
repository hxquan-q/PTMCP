<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { mePortalApi, type PlatformItem, type TokenView } from '../../services/mePortalApi';
import { copyText } from '../../services/clipboard';
import { PortalWriteIntent } from '../../services/portalMutations';
import { errorText, formatDate, isAccessError, usePortalTask } from '../portalUtils';

const tokens = ref<TokenView[]>([]);
const platforms = ref<PlatformItem[]>([]);
const status = ref('');
const loading = ref(false);
const loadError = ref('');
const nextCursor = ref<string | null>(null);
const dialogVisible = ref(false);
const secretVisible = ref(false);
const tokenPlaintext = ref('');
const saving = ref(false);
const platformCursor = ref<string | null>(null);
const createIntent = new PortalWriteIntent();
const form = reactive({ name: '', platformId: '', scopes: ['mcp:catalog'], expiresAt: new Date() as Date | null });
const tier3Platforms = computed(() => platforms.value.filter((item) => item.tier === 3));
const scopes = ['mcp:catalog', 'mcp:schema', 'mcp:query'];

function defaultExpiry(): Date {
  const date = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000);
  date.setSeconds(0, 0);
  return date;
}
function resetForm(): void {
  form.name = '';
  form.platformId = tier3Platforms.value[0]?.id ?? '';
  form.scopes = ['mcp:catalog'];
  form.expiresAt = defaultExpiry();
}
function openCreate(): void {
  resetForm();
  dialogVisible.value = true;
}
function closeSecret(): void {
  secretVisible.value = false;
  tokenPlaintext.value = '';
}
function closeCreate(done: () => void): void {
  if (saving.value) {
    ElMessage.info('创建请求正在处理中，请等待返回后关闭');
    return;
  }
  done();
}

function invalidateSecrets(): void {
  createIntent.dispose();
  closeSecret();
  dialogVisible.value = false;
  tokens.value = [];
  platforms.value = [];
}

const tokenTask = usePortalTask(invalidateSecrets);
const platformTask = usePortalTask(() => {
  platforms.value = [];
});

onBeforeUnmount(() => {
  closeSecret();
  form.name = '';
  createIntent.dispose();
});

async function loadPlatforms(append = false): Promise<void> {
  try {
    const page = await platformTask.run((signal) =>
      mePortalApi.accessContext.platforms(append ? (platformCursor.value ?? undefined) : undefined, signal),
    );
    if (page) {
      platforms.value = append ? [...platforms.value, ...page.items] : page.items;
      platformCursor.value = page.nextCursor;
    }
  } catch (error) {
    ElMessage.error(errorText(error));
  }
}
async function load(append = false): Promise<void> {
  if (!append) nextCursor.value = null;
  loading.value = true;
  loadError.value = '';
  try {
    const page = await tokenTask.run((signal) =>
      mePortalApi.tokens.list(status.value || undefined, append ? (nextCursor.value ?? undefined) : undefined, signal),
    );
    if (!page) return;
    tokens.value = append ? [...tokens.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
  } catch (error) {
    loadError.value = errorText(error);
    if (!append || isAccessError(error)) tokens.value = [];
  } finally {
    loading.value = false;
  }
}
async function create(): Promise<void> {
  if (saving.value) return;
  if (form.name.trim().length < 1 || !form.platformId || !form.scopes.length) {
    ElMessage.warning('请填写名称、Tier3 平台并至少选择一个 Scope');
    return;
  }
  if (!form.expiresAt || form.expiresAt.getTime() <= Date.now() || form.expiresAt.getTime() > Date.now() + 90 * 86400000) {
    ElMessage.warning('到期时间必须在未来 90 天内');
    return;
  }
  const intent = createIntent.begin();
  if (intent === null) return;
  saving.value = true;
  try {
    const result = await mePortalApi.tokens.create({
      name: form.name.trim(),
      platformId: form.platformId,
      scopes: [...form.scopes],
      expiresAt: form.expiresAt.toISOString(),
    });
    if (!createIntent.accepts(intent)) return;
    dialogVisible.value = false;
    tokenPlaintext.value = result.token;
    secretVisible.value = true;
    ElMessage.success('PAT 已创建，请立即安全保存');
    await load();
  } catch (error) {
    ElMessage.error(errorText(error));
  } finally {
    createIntent.finish(intent);
    saving.value = false;
  }
}
async function revoke(token: TokenView): Promise<void> {
  try {
    await ElMessageBox.confirm(`撤销后使用“${token.name}”的客户端必须更换凭证。继续吗？`, '撤销 PAT', { type: 'warning' });
    await mePortalApi.tokens.revoke(token.id);
    ElMessage.success('PAT 已撤销');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(errorText(error));
  }
}
async function copyToken(): Promise<void> {
  try {
    await copyText(tokenPlaintext.value);
    ElMessage.success('PAT 已复制，请确认已安全保存');
  } catch {
    ElMessage.warning('浏览器未允许自动复制，请手动选择文本');
  }
}
function platformName(id: string | null): string {
  return id ? (platforms.value.find((item) => item.id === id)?.name ?? id) : '—';
}
onMounted(async () => {
  await loadPlatforms();
  await load();
});
</script>

<template>
  <section class="portal-page" aria-labelledby="tokens-title">
    <div class="page-heading">
      <div>
        <h1 id="tokens-title">我的 PAT</h1>
        <p class="page-description">PAT 明文只在创建响应中出现一次，关闭提示后无法找回。</p>
      </div>
      <ElButton type="primary" @click="openCreate">创建 PAT</ElButton>
    </div>
    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError" />
    <ElCard shadow="never" class="portal-card"
      ><div class="filters">
        <ElSelect v-model="status" clearable placeholder="全部状态" @change="load(false)"
          ><ElOption label="活跃" value="ACTIVE" /><ElOption label="已撤销" value="REVOKED" /><ElOption label="已过期" value="EXPIRED"
        /></ElSelect>
      </div>
      <ElSkeleton v-if="loading" :rows="6" animated /><ElEmpty v-else-if="!tokens.length" description="尚无 PAT，可创建一个用于 MCP 接入"
        ><ElButton type="primary" plain @click="openCreate">创建 PAT</ElButton></ElEmpty
      ><ElTable v-else :data="tokens" stripe row-key="id" aria-label="PAT 列表"
        ><ElTableColumn prop="name" label="名称" min-width="160" /><ElTableColumn label="前缀" width="150"
          ><template #default="{ row }"
            ><code>{{ row.prefix }}</code></template
          ></ElTableColumn
        ><ElTableColumn label="平台" min-width="140"
          ><template #default="{ row }">{{ platformName(row.platformId) }}</template></ElTableColumn
        ><ElTableColumn label="Scope" min-width="240"
          ><template #default="{ row }"
            ><ElTag v-for="scope in row.scopes" :key="scope" size="small" effect="plain" class="scope-tag">{{ scope }}</ElTag></template
          ></ElTableColumn
        ><ElTableColumn label="到期（台北）" min-width="170"
          ><template #default="{ row }">{{ formatDate(row.expiresAt) }}</template></ElTableColumn
        ><ElTableColumn label="最近使用" min-width="170"
          ><template #default="{ row }">{{ formatDate(row.lastUsedAt) }}</template></ElTableColumn
        ><ElTableColumn label="状态" width="100"
          ><template #default="{ row }"
            ><ElTag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</ElTag></template
          ></ElTableColumn
        ><ElTableColumn label="操作" width="100"
          ><template #default="{ row }"
            ><ElButton v-if="row.status === 'ACTIVE'" link type="danger" @click="revoke(row as TokenView)">撤销</ElButton></template
          ></ElTableColumn
        ></ElTable
      >
      <div v-if="nextCursor" class="load-more"><ElButton link type="primary" @click="load(true)">加载更多</ElButton></div></ElCard
    >
    <ElDialog v-model="dialogVisible" title="创建 PAT" :before-close="closeCreate" width="min(520px, calc(100vw - 32px))"
      ><ElForm label-position="top" :disabled="saving"
        ><ElFormItem label="名称" required
          ><ElInput v-model="form.name" maxlength="64" show-word-limit placeholder="例如：本地开发客户端" /></ElFormItem
        ><ElFormItem label="Tier3 平台" required
          ><ElSelect v-model="form.platformId" class="wide"
            ><ElOption v-for="item in tier3Platforms" :key="item.id" :label="item.name" :value="item.id" /><template v-if="platformCursor" #footer
              ><ElButton link type="primary" @click="loadPlatforms(true)">加载更多平台</ElButton></template
            ></ElSelect
          >
          <p v-if="!tier3Platforms.length" class="helper">
            当前没有对你可见的 Tier3 平台：平台目录可见性由管理员授予（平台 Scope + 你的授权），可见后即可创建 PAT。
          </p></ElFormItem
        ><ElFormItem label="Scope" required
          ><ElCheckboxGroup v-model="form.scopes"
            ><ElCheckbox v-for="scope in scopes" :key="scope" :label="scope" :value="scope" /></ElCheckboxGroup></ElFormItem
        ><ElFormItem label="到期时间（本机时区）" required
          ><ElDatePicker
            v-model="form.expiresAt"
            type="datetime"
            :disabled-date="(date: Date) => date.getTime() < Date.now()"
            class="wide" /></ElFormItem></ElForm
      ><ElAlert type="info" :closable="false" title="默认有效期 30 天，最长 90 天。服务端会再次校验平台等级与期限。" /><template #footer
        ><ElButton :disabled="saving" @click="dialogVisible = false">取消</ElButton
        ><ElButton type="primary" :loading="saving" @click="create">创建</ElButton></template
      ></ElDialog
    >
    <ElDialog v-model="secretVisible" title="请立即保存 PAT" width="min(620px, calc(100vw - 32px))" :before-close="closeSecret" @closed="closeSecret"
      ><ElAlert type="warning" :closable="false" title="这是唯一一次显示完整 PAT。关闭后无法找回，遗失时请撤销并重新创建。" />
      <div class="secret-block">
        <code>{{ tokenPlaintext }}</code
        ><ElButton type="primary" plain @click="copyToken">复制 PAT</ElButton>
      </div>
      <template #footer><ElButton type="primary" @click="closeSecret">我已安全保存，关闭</ElButton></template></ElDialog
    >
  </section>
</template>

<style scoped>
.helper {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.6;
  margin: 4px 0 0;
}
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
.muted {
  color: var(--ptmcp-muted);
  font-size: 14px;
}
.page-description {
  margin: 8px 0 0;
}
.portal-card {
  border-color: var(--ptmcp-border);
}
.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 18px;
}
.scope-tag {
  margin-right: 4px;
}
.load-more {
  padding-top: 16px;
  text-align: center;
}
.wide {
  width: 100%;
}
.secret-block {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 18px;
  padding: 16px;
  border: 1px solid var(--el-color-warning-light-5);
  background: var(--ptmcp-bg-warning);
  overflow: auto;
}
.secret-block code {
  flex: 1;
  word-break: break-all;
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
