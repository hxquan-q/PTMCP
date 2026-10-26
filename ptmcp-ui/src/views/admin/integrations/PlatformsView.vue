<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import {
  ElAlert,
  ElButton,
  ElCard,
  ElDialog,
  ElInput,
  ElMessage,
  ElMessageBox,
  ElOption,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
} from 'element-plus';
import PageHeader from '../../../components/common/PageHeader.vue';
import PageState from '../../../components/common/PageState.vue';
import { useSessionStore } from '../../../stores/session';
import PlatformBasicsForm from './PlatformBasicsForm.vue';
import { controlPlane, type PlatformWrite } from '../../../services/controlPlane';
import { mutationNotice, platformApiReady } from '../../../services/frontendMode';
import { useCursorPage } from '../../../composables/useCursorPage';
import { errorText, isConflict } from '../../portalUtils';

const filter = reactive({ keyword: '', tier: '', status: '' });
const dialogVisible = ref(false);
const session = useSessionStore();
/** 平台 CRUD 后端已实现（T17-api 切片）；预览模式继续用合成数据。 */
const platformReady = platformApiReady;
const router = useRouter();
const { rows, cursor, loading, error, loaded, reset, load, retry } = useCursorPage((cursor, signal) =>
  controlPlane.platforms.list({ ...filter, cursor }, signal),
);
const removing = ref(false);
async function save(body: PlatformWrite): Promise<void> {
  const row = await controlPlane.platforms.save(body);
  dialogVisible.value = false;
  ElMessage.success(mutationNotice);
  await router.push(`/admin/platforms/${encodeURIComponent(row.id)}`);
}
async function remove(id: string): Promise<void> {
  const row = rows.value.find((item) => item.id === id);
  if (!row) return;
  if (removing.value || !session.can('ptmcp:platform:delete')) return;
  try {
    await ElMessageBox.confirm(`删除“${row.name}”将影响关联客户端和授权。平台须先停用并处理依赖。`, '删除平台', { type: 'warning' });
    removing.value = true;
    await controlPlane.platforms.remove(row);
    ElMessage.success(mutationNotice);
    await load();
  } catch (reason) {
    if (reason === 'cancel' || reason === 'close') return;
    ElMessage.error(errorText(reason));
    if (isConflict(reason)) await load();
  } finally {
    removing.value = false;
  }
}
watch(filter, reset, { flush: 'sync' });
onMounted(() => {
  if (platformReady) void load();
});
const filterText = computed(() =>
  [filter.keyword, filter.tier, filter.status].filter(Boolean).length ? '筛选条件已准备，接口接入后将按服务端游标查询。' : '平台目录尚未连接。',
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
    <ElCard class="filter-card" shadow="never">
      <div class="filter-row">
        <ElInput v-model="filter.keyword" clearable placeholder="平台名称或 code" aria-label="平台名称或 code" />
        <ElSelect v-model="filter.tier" clearable placeholder="全部 Tier" aria-label="平台 Tier"
          ><ElOption label="Tier 1" value="1" /><ElOption label="Tier 2" value="2" /><ElOption label="Tier 3" value="3"
        /></ElSelect>
        <ElSelect v-model="filter.status" clearable placeholder="全部状态" aria-label="平台状态"
          ><ElOption label="启用" value="ACTIVE" /><ElOption label="停用" value="DISABLED"
        /></ElSelect>
        <div>
          <ElButton type="primary" :loading="loading" :disabled="!platformReady" @click="load()">查询</ElButton
          ><ElButton
            @click="
              Object.assign(filter, { keyword: '', tier: '', status: '' });
              platformReady && load();
            "
            >重置</ElButton
          >
        </div>
      </div>
    </ElCard>
    <ElCard class="table-card" shadow="never">
      <div class="section-heading">
        <div>
          <h2>平台目录</h2>
          <p>平台、Scope 和客户端应由后端授权接口返回。</p>
        </div>
        <ElTag effect="plain" type="info">{{ platformReady ? '已接入' : '未连接' }}</ElTag>
      </div>
      <ElAlert v-if="error" type="error" :title="error" :closable="false"><ElButton @click="retry">重试</ElButton></ElAlert>
      <ElSkeleton v-if="loading && !rows.length" :rows="5" animated />
      <ElTable v-else :data="rows" class="empty-table" aria-label="平台目录">
        <ElTableColumn prop="code" label="Code" min-width="150" />
        <ElTableColumn prop="name" label="名称" min-width="170" />
        <ElTableColumn prop="tier" label="Tier" width="90" />
        <ElTableColumn prop="catalogMode" label="目录模式" min-width="120" />
        <ElTableColumn label="逐调用证明" min-width="130"
          ><template #default="{ row }">{{ row.proofMode }} · {{ row.proofConfigured ? '已配置' : '未配置' }}</template></ElTableColumn
        >
        <ElTableColumn prop="status" label="状态" width="100" />
        <ElTableColumn label="操作" min-width="150"
          ><template #default="{ row }"
            ><ElButton
              v-if="session.can('ptmcp:platform:read')"
              link
              type="primary"
              @click="router.push(`/admin/platforms/${encodeURIComponent(row.id)}`)"
              >详情</ElButton
            ><ElButton
              v-if="session.can('ptmcp:platform:delete')"
              link
              type="danger"
              :disabled="removing || row.status !== 'DISABLED'"
              @click="remove(String(row.id))"
              >删除</ElButton
            ></template
          ></ElTableColumn
        >
        <template #empty
          ><PageState
            :title="error ? '平台读取失败' : loaded ? '没有符合条件的平台' : '平台目录尚未读取'"
            :description="platformReady ? '可调整筛选、查询或创建平台。' : filterText"
        /></template>
      </ElTable>
      <ElButton v-if="cursor" :loading="loading" @click="load(true)">加载更多</ElButton>
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
      ><PlatformBasicsForm v-if="dialogVisible" :submit="save"
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
  color: var(--ptmcp-heading);
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
  background: var(--ptmcp-chrome);
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
