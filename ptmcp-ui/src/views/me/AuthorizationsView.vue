<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { mePortalApi, type AuthorizationView } from '../../services/mePortalApi';
import { errorText, formatDate } from '../portalUtils';

const router = useRouter();
const items = ref<AuthorizationView[]>([]);
const loading = ref(false);
const loadError = ref('');
const nextCursor = ref<string | null>(null);
async function load(append = false): Promise<void> {
  loading.value = true;
  loadError.value = '';
  try {
    const page = await mePortalApi.authorizations.list(append ? (nextCursor.value ?? undefined) : undefined);
    items.value = append ? [...items.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
  } catch (error) {
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}
async function revoke(item: AuthorizationView): Promise<void> {
  try {
    await ElMessageBox.confirm(`将撤销“${item.clientName}”的整组授权，所有使用该授权族的客户端会话都将受影响。`, '撤销 OAuth 授权', {
      type: 'warning'
    });
    await mePortalApi.authorizations.revoke(item.id);
    ElMessage.success('授权已撤销');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(errorText(error));
  }
}
onMounted(load);
</script>

<template>
  <section class="portal-page" aria-labelledby="authorizations-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">个人接入</p>
        <h1 id="authorizations-title">我的授权</h1>
        <p class="page-description">查看和撤销外部客户端的 OAuth 授权。退出控制台不会自动撤销这些授权。</p>
      </div>
    </div>
    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError" /><ElCard shadow="never" class="portal-card"
      ><ElSkeleton v-if="loading" :rows="6" animated /><ElEmpty v-else-if="!items.length" description="尚无 OAuth 授权"
        ><ElButton type="primary" plain @click="router.push('/me/integrations')">查看接入说明</ElButton></ElEmpty
      ><ElTable v-else :data="items" stripe row-key="id" aria-label="OAuth 授权列表"
        ><ElTableColumn prop="clientName" label="客户端" min-width="180" /><ElTableColumn
          prop="platformId"
          label="平台 ID"
          width="120"
        /><ElTableColumn label="授权时间" min-width="180"
          ><template #default="{ row }">{{ formatDate(row.issuedAt) }}</template></ElTableColumn
        ><ElTableColumn label="最近使用" min-width="180"
          ><template #default="{ row }">{{ formatDate(row.lastUsedAt) }}</template></ElTableColumn
        ><ElTableColumn label="到期时间" min-width="180"
          ><template #default="{ row }">{{ formatDate(row.absoluteExpiresAt) }}</template></ElTableColumn
        ><ElTableColumn label="状态" width="110"
          ><template #default="{ row }"
            ><ElTag :type="row.status === 'ACTIVE' ? 'success' : 'info'">{{ row.status }}</ElTag></template
          ></ElTableColumn
        ><ElTableColumn label="操作" width="100"
          ><template #default="{ row }"
            ><ElButton v-if="row.status === 'ACTIVE'" link type="danger" @click="revoke(row)">撤销</ElButton></template
          ></ElTableColumn
        ></ElTable
      >
      <div v-if="nextCursor" class="load-more"><ElButton link type="primary" @click="load(true)">加载更多</ElButton></div></ElCard
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
.page-description {
  margin: 8px 0 0;
  color: var(--ptmcp-muted, #667085);
  font-size: 14px;
}
.portal-card {
  border-color: var(--ptmcp-border, #d9e2ec);
}
.load-more {
  padding-top: 16px;
  text-align: center;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
