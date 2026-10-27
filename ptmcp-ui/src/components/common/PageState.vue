<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
const props = withDefaults(
  defineProps<{
    kind?: 'loading' | 'empty' | 'error' | 'forbidden' | 'not-found';
    title?: string;
    description?: string;
    actionLabel?: string;
    headingTag?: 'h1' | 'h2';
    requestId?: string | null;
  }>(),
  { kind: 'empty', title: undefined, description: undefined, actionLabel: undefined, headingTag: 'h2', requestId: null },
);
const emit = defineEmits<{ action: [] }>();
const copying = ref(false);
const copyFeedback = ref('');
let copySequence = 0;
function clearCopy(): void {
  copySequence++;
  copying.value = false;
  copyFeedback.value = '';
}
watch(() => props.requestId, clearCopy, { flush: 'sync' });
onBeforeUnmount(clearCopy);
async function copyRequestId(): Promise<void> {
  if (!props.requestId || copying.value) return;
  const own = ++copySequence;
  copying.value = true;
  try {
    await navigator.clipboard.writeText(props.requestId);
    if (own === copySequence) copyFeedback.value = '请求 ID 已复制';
  } catch {
    if (own === copySequence) copyFeedback.value = '复制失败，请手动选中请求 ID 复制';
  } finally {
    if (own === copySequence) copying.value = false;
  }
}
</script>

<template>
  <section class="page-state" :data-kind="kind" role="status" aria-live="polite">
    <div v-if="kind === 'loading'" class="page-state__spinner" aria-hidden="true"><span /></div>
    <div v-else class="page-state__mark" aria-hidden="true">
      <IconEpCircleCheck v-if="kind === 'empty'" />
      <IconEpWarning v-else-if="kind === 'error'" />
      <IconEpLock v-else-if="kind === 'forbidden'" />
      <IconEpDocumentDelete v-else />
    </div>
    <component :is="headingTag">
      {{
        title ??
        (kind === 'loading'
          ? '正在加载'
          : kind === 'empty'
            ? '暂无记录'
            : kind === 'forbidden'
              ? '无权访问'
              : kind === 'not-found'
                ? '页面不存在'
                : '加载失败')
      }}
    </component>
    <p>
      {{
        description ??
        (kind === 'loading'
          ? '正在获取最新数据，请稍候。'
          : kind === 'empty'
            ? '调整筛选条件，或使用右上角主操作开始。'
            : kind === 'forbidden'
              ? '当前账号或管理范围没有访问此资源的权限。'
              : kind === 'not-found'
                ? '资源不存在或当前不可见。'
                : '请稍后重试；如果问题持续，请联系平台管理员。')
      }}
    </p>
    <div v-if="requestId" class="page-state__request">
      <span
        >请求 ID：<code>{{ requestId }}</code></span
      >
      <ElButton :loading="copying" :disabled="copying" @click="copyRequestId">复制请求 ID</ElButton>
      <span v-if="copyFeedback">{{ copyFeedback }}</span>
    </div>
    <ElButton v-if="actionLabel" type="primary" @click="emit('action')">{{ actionLabel }}</ElButton>
    <slot />
  </section>
</template>

<style scoped>
.page-state__request {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: center;
  gap: 8px 12px;
  max-width: 100%;
  overflow-wrap: anywhere;
}
.page-state {
  display: grid;
  justify-items: center;
  gap: 10px;
  min-height: 260px;
  align-content: center;
  padding: 32px;
  text-align: center;
  color: var(--ptmcp-text-muted);
}
.page-state h1,
.page-state h2 {
  margin: 0;
  color: var(--ptmcp-heading);
  font-size: 18px;
  font-weight: 650;
}
.page-state p {
  max-width: 440px;
  margin: 0 0 8px;
  line-height: 1.65;
}
.page-state__mark {
  display: grid;
  place-items: center;
  width: 46px;
  height: 46px;
  border-radius: 50%;
  background: var(--ptmcp-blue-soft);
  color: var(--ptmcp-blue);
  font-size: 22px;
}
[data-kind='error'] .page-state__mark {
  background: var(--ptmcp-bg-danger);
  color: var(--ptmcp-red);
}
[data-kind='forbidden'] .page-state__mark {
  background: var(--ptmcp-bg-warning);
  color: var(--ptmcp-orange);
}
[data-kind='not-found'] .page-state__mark {
  background: var(--ptmcp-bg-neutral);
  color: var(--ptmcp-text-muted);
}
.page-state__spinner {
  width: 38px;
  height: 38px;
  border: 3px solid var(--ptmcp-bg-accent);
  border-top-color: var(--ptmcp-blue);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}
@media (prefers-reduced-motion: reduce) {
  .page-state__spinner {
    animation: none;
  }
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
