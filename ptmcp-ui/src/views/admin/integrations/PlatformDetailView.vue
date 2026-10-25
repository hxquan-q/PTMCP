<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDescriptions,
  ElDescriptionsItem,
  ElDialog,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTabPane,
  ElTabs,
  ElTag
} from 'element-plus';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import { useSessionStore } from '../../../stores/session';
import PlatformBasicsForm from './PlatformBasicsForm.vue';
import ClientConfigurationForm from './ClientConfigurationForm.vue';
import ScopeConfigurationPane from './ScopeConfigurationPane.vue';

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const activeId = computed(() => String(route.params.id ?? ''));
const activeTab = ref('overview');
const basicsVisible = ref(false);
const clientVisible = ref(false);
const clientStatus = ref('');
const clientType = ref('');
function clearForms(): void {
  basicsVisible.value = false;
  clientVisible.value = false;
}
watch(activeId, () => {
  clearForms();
  activeTab.value = 'overview';
  clientStatus.value = '';
  clientType.value = '';
});
onMounted(() => window.addEventListener('ptmcp:sensitive-clear', clearForms));
onBeforeUnmount(() => window.removeEventListener('ptmcp:sensitive-clear', clearForms));
</script>

<template>
  <div class="admin-page">
    <PageHeader title="平台详情" description="平台基本信息、资源 Scope 和 OAuth 客户端按各自权限管理。"
      ><template #actions><ElButton @click="router.push('/admin/platforms')">返回平台</ElButton></template></PageHeader
    >
    <ElAlert
      type="warning"
      show-icon
      :closable="false"
      title="平台管理服务待接入"
      description="当前无法读取平台、Scope 或客户端记录。此处保留详情结构与表单预览；所有保存、删除和停用操作暂不可用。"
    />
    <ElCard shadow="never"
      ><div class="identity">
        <div>
          <span>PLATFORM ID</span>
          <h2>{{ activeId }}</h2>
        </div>
        <ElTag type="info" effect="plain">尚未读取</ElTag>
      </div>
      <p class="helper">此 ID 来自路由，仅作为查询上下文；未确认对应平台存在。</p></ElCard
    >
    <ElTabs v-model="activeTab" aria-label="平台详情页签">
      <ElTabPane label="基础信息" name="overview"
        ><ElCard shadow="never"
          ><div class="section-heading">
            <h2>平台配置</h2>
            <ElButton v-if="session.can('ptmcp:platform:update')" @click="basicsVisible = true">预览编辑表单</ElButton>
          </div>
          <ElDescriptions class="descriptions" :column="1" border
            ><ElDescriptionsItem label="Code / 名称">尚未读取</ElDescriptionsItem
            ><ElDescriptionsItem label="Tier / 目录模式">尚未读取</ElDescriptionsItem
            ><ElDescriptionsItem label="逐调用证明">尚未读取（只展示已配置状态）</ElDescriptionsItem
            ><ElDescriptionsItem label="状态 / securityVersion">尚未读取</ElDescriptionsItem></ElDescriptions
          >
          <div class="boundary">
            <strong>目录可见不等于可查询</strong>
            <p>DISCOVER 不开放列结构；META DENY 优先。Tier 1 必须提供逐调用证明，private_key_jwt 不能替代该证明。</p>
          </div></ElCard
        ></ElTabPane
      >
      <ElTabPane label="Scope" name="scope"
        ><ElCard shadow="never"><ScopeConfigurationPane /></ElCard
      ></ElTabPane>
      <ElTabPane label="客户端" name="clients"
        ><ElCard shadow="never"
          ><div class="section-heading">
            <div>
              <h2>OAuth 客户端</h2>
              <p class="helper">仅显示服务端返回的客户端，secret 不进入列表。</p>
            </div>
            <ElButton v-if="session.can('ptmcp:client:create')" type="primary" @click="clientVisible = true">新建客户端</ElButton>
          </div>
          <div class="client-filters">
            <ElSelect v-model="clientType" clearable placeholder="全部类型" aria-label="客户端类型"
              ><ElOption label="PUBLIC" value="PUBLIC" /><ElOption label="CONFIDENTIAL" value="CONFIDENTIAL" /></ElSelect
            ><ElSelect v-model="clientStatus" clearable placeholder="全部状态" aria-label="客户端状态"
              ><ElOption label="启用" value="ACTIVE" /><ElOption label="停用" value="DISABLED"
            /></ElSelect>
          </div>
          <ElTable :data="[]" aria-label="客户端列表"
            ><ElTableColumn label="名称 / Client ID" min-width="220" /><ElTableColumn label="类型" min-width="150" /><ElTableColumn
              label="认证方式"
              min-width="180" /><ElTableColumn label="允许 Scope" min-width="180" /><ElTableColumn label="状态" width="100" /><template #empty
              ><PageState title="客户端尚未读取" description="客户端列表接口尚未接入，不能据此判断平台没有客户端。" /></template></ElTable></ElCard
      ></ElTabPane>
    </ElTabs>
    <ElDialog v-model="basicsVisible" title="平台编辑表单预览" width="min(760px, calc(100vw - 28px))" destroy-on-close
      ><PlatformBasicsForm v-if="basicsVisible" editing
    /></ElDialog>
    <ElDialog v-model="clientVisible" title="新建客户端 · 本地预览" width="min(760px, calc(100vw - 28px))" destroy-on-close
      ><ClientConfigurationForm v-if="clientVisible"
    /></ElDialog>
  </div>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}
.identity,
.section-heading {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 14px;
}
.identity span {
  color: var(--ptmcp-blue);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
.identity h2 {
  margin-top: 8px;
  overflow-wrap: anywhere;
  font-family: ui-monospace, Consolas, monospace;
}
h2 {
  margin: 0;
  font-size: 16px;
}
.helper,
.boundary p {
  margin: 8px 0 0;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
.descriptions {
  margin-top: 18px;
}
.boundary {
  margin-top: 20px;
  padding: 16px;
  background: #f5f8fb;
  border-radius: 6px;
}
.boundary strong {
  font-size: 13px;
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
