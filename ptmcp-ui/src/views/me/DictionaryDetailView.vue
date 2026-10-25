<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { mePortalApi, type ObjectDetail } from '../../services/mePortalApi';
import { errorText } from '../portalUtils';

const route = useRoute();
const router = useRouter();
const detail = ref<ObjectDetail | null>(null);
const loading = ref(true);
const loadError = ref('');
const platformId = String(route.query.platformId ?? '');
const objectId = String(route.params.objectId ?? '');

async function load(): Promise<void> {
  if (!platformId || !objectId) {
    loadError.value = '缺少对象上下文';
    loading.value = false;
    return;
  }
  try {
    detail.value = await mePortalApi.dictionary.detail(platformId, objectId);
  } catch (error) {
    loadError.value = errorText(error);
  } finally {
    loading.value = false;
  }
}
onMounted(load);
</script>

<template>
  <section class="portal-page" aria-labelledby="detail-title">
    <ElButton link type="primary" class="back" @click="router.back()">← 返回数据字典</ElButton>
    <ElSkeleton v-if="loading" :rows="8" animated />
    <ElAlert v-else-if="loadError" type="error" :closable="false" :title="loadError"
      ><template #default><ElButton link type="danger" @click="load">重试</ElButton></template></ElAlert
    >
    <template v-else-if="detail">
      <div class="page-heading">
        <div>
          <p class="eyebrow">{{ detail.objectType }}</p>
          <h1 id="detail-title">{{ detail.tableName }}</h1>
          <p class="page-description">
            {{ [detail.databaseName, detail.schemaName].filter(Boolean).join(' / ') }} · 快照 {{ detail.snapshotVersion }}
          </p>
        </div>
        <ElTag :type="detail.authorized ? 'success' : 'warning'">{{ detail.authorized ? '可读取' : '仅目录可见' }}</ElTag>
      </div>
      <ElAlert v-if="!detail.authorized" type="warning" :closable="false" title="当前对象没有 READ 权限，列结构不会展示。" class="mb-4" />
      <ElCard shadow="never" class="portal-card"
        ><template #header><span>业务说明</span></template>
        <p class="plain-text">{{ detail.dictionaryDescription || detail.tableComment || '暂无业务说明' }}</p>
        <p v-if="detail.dictionaryTitle" class="muted">字典标题：{{ detail.dictionaryTitle }}</p></ElCard
      >
      <ElCard v-if="detail.authorized" shadow="never" class="portal-card"
        ><template #header
          ><div class="card-header">
            <span>列结构</span><span class="muted">{{ detail.columns.length }} 列</span>
          </div></template
        ><ElTable :data="detail.columns" stripe row-key="ordinal" aria-label="列结构"
          ><ElTableColumn prop="ordinal" label="#" width="70" /><ElTableColumn prop="name" label="列名" min-width="170" /><ElTableColumn
            prop="databaseType"
            label="类型"
            width="150"
          /><ElTableColumn label="可空" width="80"
            ><template #default="{ row }">{{ row.nullable ? '是' : '否' }}</template></ElTableColumn
          ><ElTableColumn label="主键" width="80"
            ><template #default="{ row }"
              ><ElTag v-if="row.primaryKey" size="small" type="warning">PK</ElTag><span v-else>—</span></template
            ></ElTableColumn
          ><ElTableColumn label="说明" min-width="240"
            ><template #default="{ row }">{{ row.description || row.comment || row.title || '—' }}</template></ElTableColumn
          ></ElTable
        ></ElCard
      >
      <ElAlert v-if="detail.foreignKeysRestricted" type="info" :closable="false" title="部分外键目标不可见，服务端已隐藏受限引用。" />
    </template>
  </section>
</template>

<style scoped>
.back {
  margin-bottom: 18px;
}
.page-heading,
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}
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
.plain-text {
  white-space: pre-wrap;
  line-height: 1.7;
}
.mb-4 {
  margin-bottom: 16px;
}
</style>
