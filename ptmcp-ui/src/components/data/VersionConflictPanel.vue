<script setup lang="ts">
import type { ConflictDifference } from '../../adapters/edit-conflict';
defineProps<{ rows: ConflictDifference[]; loading: boolean; ready: boolean; resolved: boolean; versions: string; credentialCleared?: boolean }>();
defineEmits<{ refresh: []; latest: []; draft: [] }>();
</script>

<template>
  <ElCard shadow="never" class="conflict-panel" role="region" aria-label="版本冲突差异">
    <h2>服务端资源已变化</h2>
    <p v-if="resolved">已选择保留草稿并采用最新保存版本。请对照差异手工合并，完成后再保存。</p>
    <p v-else>当前编辑已保留。请先对照最新数据，再选择载入最新版本或保留草稿继续编辑。</p>
    <p v-if="versions" class="versions">{{ versions }}</p>
    <p v-if="credentialCleared">临时连接凭据已清除，不参与差异比较；如需替换，请在合并后重新输入。</p>
    <ElSkeleton v-if="loading" :rows="3" animated />
    <template v-else-if="ready">
      <div v-if="rows.length" class="table-scroll">
        <ElTable :data="rows" row-key="key" size="small" max-height="420">
          <ElTableColumn prop="label" label="字段" min-width="180" />
          <ElTableColumn prop="base" label="原读取值" min-width="200" />
          <ElTableColumn label="最新服务端" min-width="200"
            ><template #default="scope"
              ><span :class="{ changed: scope.row.serverChanged }">{{ scope.row.latest }}</span></template
            ></ElTableColumn
          >
          <ElTableColumn label="本地草稿" min-width="200"
            ><template #default="scope"
              ><span :class="{ changed: scope.row.draftChanged }">{{ scope.row.draft }}</span></template
            ></ElTableColumn
          >
        </ElTable>
      </div>
      <p v-else>当前可见字段未发现内容差异。请确认最新版本后继续。</p>
    </template>
    <p v-else>暂未读到最新数据。原草稿仍在，读取差异成功前暂停保存。</p>
    <div class="actions">
      <ElButton v-if="!resolved" :loading="loading" @click="$emit('refresh')">重新读取差异</ElButton>
      <ElButton :disabled="!ready || loading" @click="$emit('latest')">载入最新版本（放弃草稿）</ElButton>
      <ElButton v-if="!resolved" type="primary" :disabled="!ready || loading" @click="$emit('draft')">保留草稿，继续编辑</ElButton>
    </div>
  </ElCard>
</template>

<style scoped>
.conflict-panel {
  margin-bottom: 16px;
  border-color: var(--el-color-warning-light-5);
}
h2 {
  margin: 0;
  color: var(--ptmcp-heading);
  font-size: 17px;
}
p {
  color: var(--ptmcp-text-muted);
  line-height: 1.6;
  font-size: 13px;
}
.versions {
  font-variant-numeric: tabular-nums;
}
.table-scroll {
  overflow-x: auto;
}
.table-scroll :deep(.cell) {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.changed {
  color: var(--ptmcp-orange);
  font-weight: 600;
}
.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}
.actions :deep(.el-button) {
  margin-left: 0;
}
</style>
