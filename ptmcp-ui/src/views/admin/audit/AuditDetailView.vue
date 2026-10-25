<script setup lang="ts">
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElAlert, ElButton, ElCard, ElDescriptions, ElDescriptionsItem, ElTag } from 'element-plus';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import { localOnlyReason } from '../integrations/adminSurface';

const route = useRoute();
const router = useRouter();
const requestId = computed(() => String(route.params.requestId ?? ''));
</script>

<template>
  <div class="admin-page">
    <PageHeader title="审计详情" description="详情只显示服务端授权范围内的脱敏上下文、版本和终态信息。">
      <template #actions><ElButton @click="router.push('/admin/audit')">返回审计</ElButton></template>
    </PageHeader>
    <ElAlert type="warning" show-icon :closable="false" title="审计详情接口待接入" :description="localOnlyReason('审计详情')" />
    <ElCard class="identity-card" shadow="never">
      <div class="identity-heading">
        <div>
          <span class="eyebrow">REQUEST ID</span>
          <h2>{{ requestId }}</h2>
        </div>
        <ElTag type="info" effect="plain">未读取</ElTag>
      </div>
      <p>此 ID 来自安全路由参数，仅作为导航上下文；在详情端点返回前，不将它解释为存在的审计记录。</p>
    </ElCard>
    <ElCard class="detail-card" shadow="never">
      <h2>调用上下文</h2>
      <ElDescriptions :column="2" border class="descriptions">
        <ElDescriptionsItem label="员工">待服务端返回</ElDescriptionsItem><ElDescriptionsItem label="平台">待服务端返回</ElDescriptionsItem>
        <ElDescriptionsItem label="工具">待服务端返回</ElDescriptionsItem><ElDescriptionsItem label="目标">待服务端返回</ElDescriptionsItem>
        <ElDescriptionsItem label="结果">待服务端返回</ElDescriptionsItem><ElDescriptionsItem label="判定">待服务端返回</ElDescriptionsItem>
      </ElDescriptions>
      <PageState kind="empty" title="没有已加载的详情" description="当前后端尚未提供 audit:read；不会从缓存、日志或业务库猜测审计内容。" />
    </ElCard>
    <ElCard class="security-card" shadow="never">
      <h2>敏感内容边界</h2>
      <ul>
        <li>仅展示服务端返回的 sqlMasked、指纹、版本和阶段耗时。</li>
        <li>当前没有 audit:decrypt 端点，不提供原 SQL输入框、解密按钮或临时明文区域。</li>
        <li>UNKNOWN 终态必须保留为未知，并显示服务端给出的 requestId 排障线索。</li>
      </ul>
    </ElCard>
  </div>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}
.identity-card,
.detail-card,
.security-card {
  border-radius: 8px;
}
.identity-heading {
  display: flex;
  justify-content: space-between;
  gap: 14px;
  align-items: flex-start;
}
.eyebrow {
  color: var(--ptmcp-blue);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
h2 {
  margin: 7px 0 0;
  color: var(--ptmcp-text);
  font-size: 16px;
}
.identity-heading h2 {
  overflow-wrap: anywhere;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 15px;
  font-weight: 600;
}
.identity-card p {
  margin: 14px 0 0;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
  line-height: 1.65;
}
.descriptions {
  margin-top: 16px;
}
.security-card ul {
  display: grid;
  gap: 9px;
  margin: 14px 0 0;
  padding-left: 20px;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
  line-height: 1.6;
}
</style>
