<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import OnceSecretDialog from '../../../components/common/OnceSecretDialog.vue';
import PlatformBasicsForm from './PlatformBasicsForm.vue';
import ClientConfigurationForm from './ClientConfigurationForm.vue';
import ScopeConfigurationPane from './ScopeConfigurationPane.vue';
import { useSessionStore } from '../../../stores/session';
import { controlPlane, type Platform, type PlatformWrite, type Client, type ClientWrite } from '../../../services/controlPlane';
import { controlPlaneAvailable, mutationNotice } from '../../../services/frontendMode';
import { useCursorPage } from '../../../composables/useCursorPage';
import { errorText, isConflict, usePortalTask } from '../../portalUtils';
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const activeId = computed(() => String(route.params.id ?? ''));
const activeTab = ref('overview');
const basicsVisible = ref(false);
const clientVisible = ref(false);
const clientStatus = ref('');
const clientType = ref('');
const item = ref<Platform | null>(null);
const loading = ref(false);
const error = ref('');
const editingClient = ref<Client | null>(null);
const secret = ref('');
const conflict = ref(false);
const removing = ref(false);
let detailVersion = 0;
let clientIntent = 0;
let alive = true;
function clearForms(): void {
  clientIntent++;
  basicsVisible.value = false;
  clientVisible.value = false;
  secret.value = '';
  editingClient.value = null;
}
const task = usePortalTask(() => {
  item.value = null;
  clearForms();
  detailVersion++;
});
const {
  rows: clients,
  cursor,
  loading: clientsLoading,
  error: clientsError,
  loaded: clientsLoaded,
  reset: resetClients,
  load: loadClients,
  retry: retryClients
} = useCursorPage((cursor, signal) =>
  controlPlane.clients.list(activeId.value, { cursor, type: clientType.value, status: clientStatus.value }, signal)
);
async function load(): Promise<void> {
  if (!controlPlaneAvailable) return;
  const own = ++detailVersion;
  loading.value = true;
  error.value = '';
  item.value = null;
  try {
    const result = await task.run((signal) => controlPlane.platforms.get(activeId.value, signal));
    if (result && own === detailVersion) item.value = result;
  } catch (reason) {
    if (own === detailVersion) error.value = errorText(reason);
  } finally {
    if (own === detailVersion) loading.value = false;
  }
}
async function savePlatform(body: PlatformWrite): Promise<void> {
  if (!item.value) return;
  const editingPlatform = item.value;
  const own = detailVersion;
  if (body.tier !== item.value.tier || body.proofMode !== item.value.proofMode) {
    try {
      await ElMessageBox.confirm(
        `信任配置由 Tier ${item.value.tier} / ${item.value.proofMode} 变为 Tier ${body.tier} / ${body.proofMode}。确认已重新批准；现有客户端可能需重新授权。`,
        '确认信任变更',
        { type: 'warning' }
      );
    } catch {
      return;
    }
  }
  if (own !== detailVersion || !alive || !basicsVisible.value) return;
  try {
    const updated = await controlPlane.platforms.save(body, editingPlatform);
    if (own !== detailVersion || !alive) return;
    item.value = updated;
    basicsVisible.value = false;
    ElMessage.success(mutationNotice);
  } catch (reason) {
    if (isConflict(reason)) conflict.value = true;
    throw reason;
  }
}
function openClient(id?: string): void {
  clientIntent++;
  editingClient.value = clients.value.find((row) => row.id === id) ?? null;
  clientVisible.value = true;
}
async function saveClient(body: ClientWrite): Promise<void> {
  const own = detailVersion;
  const intent = clientIntent;
  const platformId = activeId.value;
  const row = editingClient.value ?? undefined;
  if (editingClient.value) {
    try {
      await ElMessageBox.confirm('变更回调或认证方式会撤销关联授权族，客户端需重新授权。继续保存？', '更新客户端', { type: 'warning' });
    } catch {
      return;
    }
  }
  if (!alive || own !== detailVersion || intent !== clientIntent || !clientVisible.value) return;
  const result: { client: Client; clientSecret?: string } = await controlPlane.clients.save(platformId, body, row);
  if (!alive || own !== detailVersion || intent !== clientIntent || !clientVisible.value) return;
  clientVisible.value = false;
  secret.value = result.clientSecret ?? '';
  ElMessage.success(mutationNotice);
  await loadClients();
}
async function removeClient(id: string): Promise<void> {
  const row = clients.value.find((item) => item.id === id);
  if (!row) return;
  if (removing.value || !session.can('ptmcp:client:delete')) return;
  removing.value = true;
  try {
    await ElMessageBox.confirm(`删除“${row.name}”将撤销其全部关联授权族。`, '删除客户端', { type: 'warning' });
    await controlPlane.clients.remove(row);
    ElMessage.success(mutationNotice);
    await loadClients();
  } catch (reason) {
    if (reason !== 'cancel' && reason !== 'close') ElMessage.error(errorText(reason));
  } finally {
    removing.value = false;
  }
}
watch([clientStatus, clientType], resetClients, { flush: 'sync' });
watch(activeTab, (tab) => {
  if (tab === 'clients' && controlPlaneAvailable && item.value) void loadClients();
});
watch(activeId, () => {
  clearForms();
  activeTab.value = 'overview';
  clientStatus.value = '';
  clientType.value = '';
  resetClients();
  conflict.value = false;
  void load();
});
onMounted(() => {
  window.addEventListener('ptmcp:sensitive-clear', clearForms);
  void load();
});
onBeforeUnmount(() => {
  alive = false;
  window.removeEventListener('ptmcp:sensitive-clear', clearForms);
});
</script>
<template>
  <div class="admin-page">
    <PageHeader title="平台详情" description="管理平台基础信息、资源 Scope 与 OAuth 客户端。"
      ><template #actions><ElButton @click="router.push('/admin/platforms')">返回平台</ElButton></template></PageHeader
    >
    <ElAlert v-if="!controlPlaneAvailable" type="warning" :closable="false" title="平台管理服务待接入，当前可检查表单，保存暂不可用。" />
    <ElAlert v-if="error" type="error" :title="error" :closable="false"><ElButton @click="load">重新读取</ElButton></ElAlert>
    <ElAlert v-if="conflict" type="warning" title="版本已变化，草稿尚未覆盖服务端。关闭编辑并重新读取后再合并。" :closable="false"
      ><ElButton
        @click="
          basicsVisible = false;
          conflict = false;
          load();
        "
        >放弃编辑并重新读取</ElButton
      ></ElAlert
    >
    <ElSkeleton v-if="loading" :rows="6" animated />
    <ElCard v-else shadow="never"
      ><div class="section-heading">
        <h2>{{ item?.name ?? activeId }}</h2>
        <ElTag>{{ item?.status ?? '尚未读取' }}</ElTag>
      </div>
      <p class="helper">平台 ID：{{ activeId }}{{ item ? '' : '（尚未确认此平台存在）' }}</p></ElCard
    >
    <ElTabs v-model="activeTab" aria-label="平台详情页签">
      <ElTabPane label="基础信息" name="overview"
        ><ElCard shadow="never">
          <div class="section-heading">
            <h2>平台配置</h2>
            <ElButton v-if="session.can('ptmcp:platform:update')" :disabled="controlPlaneAvailable && !item" @click="basicsVisible = true"
              >编辑平台</ElButton
            >
          </div>
          <ElDescriptions :column="1" border class="descriptions">
            <ElDescriptionsItem label="Code / 名称">{{ item ? `${item.code} / ${item.name}` : '尚未读取' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="Tier / 目录模式">{{ item ? `${item.tier} / ${item.catalogMode}` : '尚未读取' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="逐调用证明">{{
              item ? `${item.proofMode} / ${item.proofConfigured ? '已配置' : '未配置'}` : '尚未读取'
            }}</ElDescriptionsItem>
            <ElDescriptionsItem label="securityVersion / scopeVersion">{{
              item ? `${item.securityVersion} / ${item.scopeVersion}` : '尚未读取'
            }}</ElDescriptionsItem>
          </ElDescriptions>
          <p class="helper">DISCOVER 仅影响目录发现，不开放列结构；META DENY 优先。Tier 1 须提供逐调用证明，private_key_jwt 不替代该证明。</p>
        </ElCard></ElTabPane
      >
      <ElTabPane label="Scope" name="scope"
        ><ElCard shadow="never"><ScopeConfigurationPane :key="activeId" :platform-id="activeId" /></ElCard
      ></ElTabPane>
      <ElTabPane label="客户端" name="clients"
        ><ElCard shadow="never">
          <div class="section-heading">
            <h2>OAuth 客户端</h2>
            <ElButton v-if="session.can('ptmcp:client:create')" type="primary" :disabled="controlPlaneAvailable && !item" @click="openClient()"
              >新建客户端</ElButton
            >
          </div>
          <div class="client-filters">
            <ElSelect v-model="clientType" clearable placeholder="全部类型" aria-label="客户端类型"
              ><ElOption label="PUBLIC" value="PUBLIC" /><ElOption label="CONFIDENTIAL" value="CONFIDENTIAL" /></ElSelect
            ><ElSelect v-model="clientStatus" clearable placeholder="全部状态" aria-label="客户端状态"
              ><ElOption label="启用" value="ACTIVE" /><ElOption label="停用" value="DISABLED" /></ElSelect
            ><ElButton :loading="clientsLoading" :disabled="!controlPlaneAvailable || !item" @click="loadClients()">查询</ElButton>
          </div>
          <ElAlert v-if="clientsError" :title="clientsError" type="error" :closable="false"><ElButton @click="retryClients">重试</ElButton></ElAlert>
          <ElTable :data="clients" aria-label="客户端列表"
            ><ElTableColumn label="名称 / Client ID" min-width="220"
              ><template #default="{ row }">{{ row.name }} / {{ row.clientId }}</template></ElTableColumn
            ><ElTableColumn prop="type" label="类型" width="160" /><ElTableColumn prop="authMethods" label="认证方式" min-width="170" /><ElTableColumn
              prop="allowedScopes"
              label="Scope"
              min-width="170" /><ElTableColumn label="secret" width="110"
              ><template #default="{ row }">{{ row.secretConfigured ? '已配置' : '无' }}</template></ElTableColumn
            ><ElTableColumn prop="status" label="状态" width="100" /><ElTableColumn label="操作" min-width="160"
              ><template #default="{ row }"
                ><ElButton v-if="session.can('ptmcp:client:update')" link type="primary" @click="openClient(String(row.id))">编辑</ElButton
                ><ElButton v-if="session.can('ptmcp:client:delete')" link type="danger" :disabled="removing" @click="removeClient(String(row.id))"
                  >删除</ElButton
                ></template
              ></ElTableColumn
            ><template #empty
              ><PageState
                :title="clientsLoaded ? '没有符合条件的客户端' : '客户端尚未读取'"
                description="可调整筛选或新建客户端；列表不展示 secret。" /></template
          ></ElTable>
          <ElButton v-if="cursor" :loading="clientsLoading" @click="loadClients(true)">加载更多</ElButton>
        </ElCard></ElTabPane
      >
    </ElTabs>
    <ElDialog v-model="basicsVisible" title="编辑平台" width="min(760px, calc(100vw - 28px))" destroy-on-close
      ><PlatformBasicsForm v-if="basicsVisible" editing :initial="item" :submit="conflict ? undefined : savePlatform"
    /></ElDialog>
    <ElDialog
      v-model="clientVisible"
      :title="editingClient ? '编辑客户端' : '新建客户端'"
      width="min(760px, calc(100vw - 28px))"
      destroy-on-close
      @close="clientIntent++"
      ><ClientConfigurationForm v-if="clientVisible" :initial="editingClient" :submit="saveClient"
    /></ElDialog>
    <OnceSecretDialog v-model="secret" title="客户端 secret · 一次展示" />
  </div>
</template>
<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 14px;
}
h2 {
  margin: 0;
  font-size: 16px;
  overflow-wrap: anywhere;
}
.helper {
  margin: 14px 0 0;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
.descriptions {
  margin-top: 18px;
}
.client-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin: 18px 0;
}
.client-filters .el-select {
  width: 180px;
}
@media (max-width: 600px) {
  .section-heading {
    flex-direction: column;
  }
  .client-filters .el-select {
    width: 100%;
  }
}
</style>
