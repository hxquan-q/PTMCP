<script setup lang="ts">
withDefaults(defineProps<{ count: number; hasMore?: boolean; loading?: boolean; disabled?: boolean }>(), {
  hasMore: false,
  loading: false,
  disabled: false,
});
const emit = defineEmits<{ more: [] }>();
</script>

<template>
  <footer class="ptmcp-table-footer" :aria-busy="loading">
    <span role="status" aria-atomic="true">已加载 {{ count }} 条</span>
    <ElButton v-if="hasMore" :loading="loading" :disabled="loading || disabled" @click="emit('more')">加载更多</ElButton>
  </footer>
</template>

<style scoped>
.ptmcp-table-footer {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 16px 0;
  border-top: 1px solid var(--ptmcp-border);
  color: var(--ptmcp-text-muted);
  font-size: 13px;
}
</style>
