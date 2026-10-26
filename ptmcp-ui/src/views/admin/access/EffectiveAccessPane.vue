<script setup lang="ts">
import { watch } from 'vue';
import { controlPlane } from '../../../services/controlPlane';
import { controlPlaneAvailable } from '../../../services/frontendMode';
import { useSessionStore } from '../../../stores/session';
import { useCursorPage } from '../../../composables/useCursorPage';
const props = withDefaults(defineProps<{ userId: string; platformId: string; datasourceId: string; ready?: boolean }>(), { ready: true });
const session = useSessionStore();
const { rows, cursor, loading, error, loaded, reset, load, retry } = useCursorPage((cursor, signal) =>
  controlPlane.effective({ userId: props.userId, platformId: props.platformId, datasourceId: props.datasourceId, cursor }, signal)
);
watch(() => [props.userId, props.platformId, props.datasourceId, props.ready], reset, { flush: 'sync' });
</script>
<template>
  <section v-if="session.can('ptmcp:acl:effective')" class="effective-pane">
    <h2>当前有效资源</h2>
    <p>读取当前用户与平台交集及来源，不能代替执行时授权。切换上下文或保存规则后需重新读取。</p>
    <ElAlert v-if="!controlPlaneAvailable" type="info" :closable="false" title="当前有效资源端点待接入；独立开发预览仅展示合成判定。" /><ElButton
      :disabled="!controlPlaneAvailable || !userId || !platformId || !ready"
      :loading="loading"
      @click="load()"
      >读取当前有效资源</ElButton
    ><ElAlert v-if="error" :title="error" type="error" :closable="false"><ElButton @click="retry">重试</ElButton></ElAlert
    ><ElSkeleton v-if="loading" :rows="4" animated /><ElTable v-else-if="loaded" :data="rows" aria-label="当前有效资源"
      ><ElTableColumn prop="resourceType" label="资源类型" min-width="130" /><ElTableColumn
        prop="resourceId"
        label="资源 ID"
        min-width="130"
      /><ElTableColumn label="META / READ" min-width="160"
        ><template #default="{ row }">{{ row.meta ? '允许' : '拒绝' }} / {{ row.read ? '允许' : '拒绝' }}</template></ElTableColumn
      ><ElTableColumn prop="sourceGrantIds" label="来源规则" min-width="150" /><ElTableColumn
        prop="reasonCodes"
        label="原因码"
        min-width="170"
      /><template #empty>当前条件下有效权限为零。</template></ElTable
    ><ElButton v-if="cursor" :loading="loading" @click="load(true)">加载更多</ElButton>
  </section>
</template>
<style scoped>
.effective-pane {
  margin-top: 24px;
  padding: 22px;
  border: 1px solid var(--ptmcp-border);
  border-radius: 8px;
}
.effective-pane h2 {
  font-size: 16px;
  margin: 0;
}
.effective-pane p {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.7;
}
.effective-pane > .el-button {
  margin: 14px 0;
}
</style>
