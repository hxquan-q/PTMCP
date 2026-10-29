<script setup lang="ts">
import CursorFooter from '../../components/common/CursorFooter.vue';
import PageHeader from '../../components/common/PageHeader.vue';
import StatusBadge from '../../components/common/StatusBadge.vue';
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { mePortalApi, type AuthorizationView } from '../../services/mePortalApi';
import { errorText, formatDate, isAccessError, usePortalTask } from '../portalUtils';

const router = useRouter();
const items = ref<AuthorizationView[]>([]);
const loading = ref(false);
const loadError = ref('');
const nextCursor = ref<string | null>(null);
const revoking = ref(false);
let active = true;
const listTask = usePortalTask(() => {
  active = false;
  items.value = [];
  nextCursor.value = null;
});
async function load(append = false): Promise<void> {
  if (loading.value || (append && !nextCursor.value)) return;
  if (!append) nextCursor.value = null;
  loading.value = true;
  loadError.value = '';
  try {
    const page = await listTask.run((signal) => mePortalApi.authorizations.list(append ? (nextCursor.value ?? undefined) : undefined, signal));
    if (!page) return;
    items.value = append ? [...items.value, ...page.items] : page.items;
    nextCursor.value = page.nextCursor;
  } catch (error) {
    if (isAccessError(error)) items.value = [];
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}
async function revoke(item: AuthorizationView): Promise<void> {
  if (revoking.value || !active) return;
  revoking.value = true;
  try {
    await ElMessageBox.confirm(`将撤销“${item.clientName}”的整组授权，所有使用该授权族的客户端会话都将受影响。`, '撤销 OAuth 授权', {
      type: 'warning',
    });
    if (!active) return;
    await mePortalApi.authorizations.revoke(item.id);
    ElMessage.success('授权已撤销');
    await load();
  } catch (error) {
    if (error === 'cancel' || error === 'close') return;
    ElMessage.error(errorText(error));
  } finally {
    revoking.value = false;
  }
}
onMounted(load);
</script>

<template>
  <section class="portal-page fill-page" aria-labelledby="authorizations-title">
    <PageHeader title-id="authorizations-title" title="我的授权" description="查看和撤销外部客户端的 OAuth 授权。退出控制台不会自动撤销这些授权。" />
    <ElAlert v-if="loadError" type="error" :closable="false" class="mb-4" :title="loadError"
      ><ElButton @click="load()">重新读取授权</ElButton></ElAlert
    ><ElCard shadow="never" class="portal-card ptmcp-table-section ptmcp-fill-card"
      ><div class="ptmcp-table-heading">
        <h2 class="ptmcp-section-title">OAuth 授权</h2>
        <span class="ptmcp-meta-note">共 {{ items.length }} 条</span>
      </div>
      <ElSkeleton v-if="loading" :rows="6" animated /><PageState v-else-if="!items.length && !loadError" description="尚无 OAuth 授权"
        ><ElButton type="primary" plain @click="router.push('/me/integrations')">查看接入说明</ElButton></PageState
      >
      <div v-else-if="items.length" class="ptmcp-table-wrap">
        <ElTable :data="items" class="ptmcp-data-table" size="small" height="100%" row-key="id" aria-label="OAuth 授权列表"
          ><ElTableColumn label="客户端" min-width="200"
            ><template #default="{ row }"
              ><div class="ptmcp-cell-lead">
                <span class="ptmcp-cell-icon"><IconEpLink aria-hidden="true" /></span
                ><span class="ptmcp-cell-stack"
                  ><strong>{{ row.clientName }}</strong
                  ><span>ID {{ row.id }}</span></span
                >
              </div></template
            ></ElTableColumn
          ><ElTableColumn prop="platformId" label="平台 ID" width="120" /><ElTableColumn label="授权时间" min-width="180"
            ><template #default="{ row }">{{ formatDate(row.issuedAt) }}</template></ElTableColumn
          ><ElTableColumn label="最近使用" min-width="180"
            ><template #default="{ row }">{{ formatDate(row.lastUsedAt) }}</template></ElTableColumn
          ><ElTableColumn label="到期时间" min-width="180"
            ><template #default="{ row }">{{ formatDate(row.absoluteExpiresAt) }}</template></ElTableColumn
          ><ElTableColumn label="状态" width="110"
            ><template #default="{ row }"><StatusBadge domain="pat" :value="row.status" /></template></ElTableColumn
          ><ElTableColumn label="操作" width="110" align="right" fixed="right"
            ><template #default="{ row }"
              ><div class="ptmcp-row-actions">
                <ElButton v-if="row.status === 'ACTIVE'" link type="danger" :disabled="revoking" @click="revoke(row as AuthorizationView)"
                  >撤销</ElButton
                ><ElButton v-else link type="primary" @click="router.push('/me/integrations')">重新接入</ElButton>
              </div></template
            ></ElTableColumn
          ></ElTable
        >
      </div>
      <CursorFooter :count="items.length" :has-more="!!nextCursor" :loading="loading" @more="load(true)"
    /></ElCard>
  </section>
</template>

<style scoped>
.portal-card {
  border-color: var(--ptmcp-border);
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
