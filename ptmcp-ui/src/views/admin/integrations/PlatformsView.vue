<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';
import { ElAlert, ElButton, ElCard, ElDialog, ElInput, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import { localOnlyReason } from './adminSurface';
import { useSessionStore } from '../../../stores/session';
import PlatformBasicsForm from './PlatformBasicsForm.vue';

const filter = reactive({ keyword: '', tier: '', status: '' });
const dialogVisible = ref(false);
const session = useSessionStore();
const filterText = computed(() =>
  [filter.keyword, filter.tier, filter.status].filter(Boolean).length ? '筛选条件已准备，接口接入后将按服务端游标查询。' : '平台目录尚未连接。'
);
function clearPreview(): void {
  dialogVisible.value = false;
}
onMounted(() => window.addEventListener('ptmcp:sensitive-clear', clearPreview));
onBeforeUnmount(() => window.removeEventListener('ptmcp:sensitive-clear', clearPreview));
</script>

<template>
  <div class="admin-page">
    <PageHeader title="平台与客户端" description="维护受信平台、目录 Scope 和 OAuth 客户端，机密值只由服务端一次性返回。">
      <template #actions
        ><ElButton v-if="session.can('ptmcp:platform:create')" type="primary" @click="dialogVisible = true">新建平台</ElButton></template
      >
    </PageHeader>
    <ElAlert type="warning" show-icon :closable="false" title="平台管理接口待接入" :description="localOnlyReason('平台与客户端')" />
    <ElCard class="filter-card" shadow="never">
      <div class="filter-row">
        <ElInput v-model="filter.keyword" clearable placeholder="平台名称或 code" aria-label="平台名称或 code" />
        <ElSelect v-model="filter.tier" clearable placeholder="全部 Tier" aria-label="平台 Tier"
          ><ElOption label="Tier 1" value="1" /><ElOption label="Tier 2" value="2" /><ElOption label="Tier 3" value="3"
        /></ElSelect>
        <ElSelect v-model="filter.status" clearable placeholder="全部状态" aria-label="平台状态"
          ><ElOption label="启用" value="ACTIVE" /><ElOption label="停用" value="DISABLED"
        /></ElSelect>
        <span>{{ filterText }}</span>
      </div>
    </ElCard>
    <ElCard class="table-card" shadow="never">
      <div class="section-heading">
        <div>
          <h2>平台目录</h2>
          <p>平台、Scope 和客户端应由后端授权接口返回。</p>
        </div>
        <ElTag effect="plain" type="info">未连接</ElTag>
      </div>
      <ElTable :data="[]" class="empty-table" aria-label="平台目录">
        <ElTableColumn prop="code" label="Code" min-width="150" />
        <ElTableColumn prop="name" label="名称" min-width="170" />
        <ElTableColumn prop="tier" label="Tier" width="90" />
        <ElTableColumn prop="catalogMode" label="目录模式" min-width="120" />
        <ElTableColumn prop="proofConfigured" label="逐调用证明" min-width="130" />
        <ElTableColumn prop="status" label="状态" width="100" />
        <template #empty><PageState title="平台目录尚未读取" description="平台管理服务尚未接入。可打开新建表单检查字段，保存暂不可用。" /></template>
      </ElTable>
    </ElCard>
    <ElCard class="guide-card" shadow="never">
      <h2>配置原则</h2>
      <div class="guide-grid">
        <div>
          <span class="guide-number">01</span><strong>Scope 先于客户端</strong>
          <p>资源类型只能是数据源、命名空间或对象；PG catalog 只作分组。</p>
        </div>
        <div>
          <span class="guide-number">02</span><strong>证明方式受控</strong>
          <p>Tier 1 不允许 NONE；private_key_jwt 不替代逐调用证明。</p>
        </div>
        <div>
          <span class="guide-number">03</span><strong>回调精确匹配</strong>
          <p>禁止通配符和开放重定向，变更回调会撤销关联授权族。</p>
        </div>
      </div>
    </ElCard>
    <ElDialog v-model="dialogVisible" title="新建平台 · 本地预览" width="min(760px, calc(100vw - 28px))" destroy-on-close
      ><PlatformBasicsForm v-if="dialogVisible"
    /></ElDialog>
  </div>
</template>

<style scoped>
.admin-page {
  display: grid;
  gap: 16px;
}
.filter-card,
.table-card,
.guide-card {
  border-radius: 8px;
}
.filter-row {
  display: grid;
  grid-template-columns: minmax(190px, 1.5fr) repeat(2, minmax(150px, 0.7fr)) minmax(220px, 1fr);
  align-items: center;
  gap: 10px;
}
.filter-row > span,
.section-heading p,
.guide-grid p {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.section-heading {
  display: flex;
  justify-content: space-between;
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
.guide-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-top: 14px;
}
.guide-grid > div {
  position: relative;
  min-height: 116px;
  padding: 16px;
  border: 1px solid var(--ptmcp-border);
  border-radius: 6px;
  background: #f8fafc;
}
.empty-table {
  margin-top: 16px;
}
.guide-grid strong {
  display: block;
  margin-top: 8px;
  color: var(--ptmcp-text);
  font-size: 14px;
}
.guide-grid p {
  margin: 6px 0 0;
}
.guide-number {
  color: var(--ptmcp-blue);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
}
.guide-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--ptmcp-border);
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
}
@media (max-width: 760px) {
  .filter-row,
  .guide-grid {
    grid-template-columns: 1fr;
  }
}
</style>
