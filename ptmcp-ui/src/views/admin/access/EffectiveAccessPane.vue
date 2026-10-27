<script setup lang="ts">
import { watch } from 'vue';
import { controlPlane } from '../../../services/controlPlane';
import { controlPlaneAvailable, effectiveApiReady } from '../../../services/frontendMode';
import { useSessionStore } from '../../../stores/session';
import { useCursorPage } from '../../../composables/useCursorPage';
import PageState from '../../../components/common/PageState.vue';
const props = withDefaults(defineProps<{ userId: string; platformId: string; datasourceId: string; ready?: boolean }>(), { ready: true });
const session = useSessionStore();
/** 当前有效资源端点已实现（T17-api 切片）；预览模式继续用合成判定。 */
const effectiveReady = effectiveApiReady || controlPlaneAvailable;
const { rows, cursor, loading, error, loaded, reset, load, retry } = useCursorPage((cursor, signal) =>
  controlPlane.effective({ userId: props.userId, platformId: props.platformId, datasourceId: props.datasourceId, cursor }, signal),
);
watch(() => [props.userId, props.platformId, props.datasourceId, props.ready], reset, { flush: 'sync' });
</script>
<template>
  <section v-if="session.can('ptmcp:acl:effective')" class="effective-pane">
    <h2>当前有效资源</h2>
    <p>
      读取当前用户与平台交集及来源，不能代替执行时授权。切换上下文或保存规则后需重新读取。 原因码：GRANT_META/GRANT_READ 表示命中授予（READ 蕴含
      META），NO_EFFECTIVE_GRANT 表示无命中授权，UNKNOWN_RESOURCE 表示资源不在当前目录。
    </p>
    <ElButton :disabled="!effectiveReady || !userId || !platformId || !ready" :loading="loading" @click="load()">读取当前有效资源</ElButton
    ><ElAlert v-if="error" :title="error" type="error" :closable="false"><ElButton @click="retry">重试</ElButton></ElAlert
    ><ElSkeleton v-if="loading" :rows="4" animated /><ElTable v-else-if="loaded" :data="rows" aria-label="当前有效资源"
      ><ElTableColumn prop="resourceType" label="资源类型" min-width="130" /><ElTableColumn
        prop="resourceId"
        label="资源 ID"
        min-width="130" /><ElTableColumn label="META / READ" min-width="160"
        ><template #default="{ row }">{{ row.meta ? '允许' : '拒绝' }} / {{ row.read ? '允许' : '拒绝' }}</template></ElTableColumn
      ><ElTableColumn label="来源规则" min-width="150"
        ><template #default="{ row }"
          ><ul v-if="row.sourceGrantIds?.length" class="evidence-items">
            <li v-for="id in row.sourceGrantIds" :key="id">{{ id }}</li>
          </ul>
          <span v-else>无来源规则</span></template
        ></ElTableColumn
      ><ElTableColumn label="原因码" min-width="170"
        ><template #default="{ row }"
          ><ul v-if="row.reasonCodes?.length" class="evidence-items">
            <li v-for="code in row.reasonCodes" :key="code">{{ code }}</li>
          </ul>
          <span v-else>未提供</span></template
        ></ElTableColumn
      ><template #empty>
        <PageState
          title="当前条件下有效权限为零"
          description="主体暂无命中授权或平台范围未覆盖该数据源；授予 META/READ 后重读即可看到变化。" /></template></ElTable
    ><ElButton v-if="cursor" :loading="loading" @click="load(true)">加载更多</ElButton>
  </section>
</template>
<style scoped>
.evidence-items {
  margin: 0;
  padding-left: 18px;
  overflow-wrap: anywhere;
}
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
