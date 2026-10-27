<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{ value: unknown }>();
const entries = computed(() => {
  if (!props.value || typeof props.value !== 'object') return [];
  return Object.entries(props.value);
});
const scalar = computed(() => {
  if (props.value === null || props.value === undefined) return '未提供';
  if (typeof props.value === 'boolean') return props.value ? '是' : '否';
  return String(props.value);
});
const labels: Record<string, string> = {
  objectId: '对象 ID',
  datasourceId: '数据源 ID',
  resourceId: '资源 ID',
  resourceType: '资源类型',
  resourceGeneration: '资源代次',
  securityVersion: '安全版本',
  scopeVersion: '平台范围版本',
  aclVersion: '授权版本',
  resourceVersion: '资源版本',
  snapshotId: '快照 ID',
  generation: '代次',
};
function label(key: string): string {
  return Object.hasOwn(labels, key) ? labels[key]! : key;
}
</script>

<template>
  <ol v-if="Array.isArray(value) && entries.length" class="evidence-list">
    <li v-for="[key, entry] in entries" :key="key"><EvidenceFields :value="entry" /></li>
  </ol>
  <dl v-else-if="entries.length" class="evidence-fields">
    <div v-for="[key, entry] in entries" :key="key" class="evidence-fields__row">
      <dt>{{ label(key) }}</dt>
      <dd><EvidenceFields :value="entry" /></dd>
    </div>
  </dl>
  <span v-else-if="value && typeof value === 'object'" class="evidence-empty">无记录</span>
  <span v-else>{{ scalar }}</span>
</template>

<style scoped>
.evidence-fields {
  margin: 0;
}
.evidence-fields__row {
  display: grid;
  grid-template-columns: minmax(100px, 0.4fr) minmax(0, 1fr);
  gap: 8px 16px;
  padding: 10px 0;
  border-bottom: 1px solid var(--ptmcp-border);
  overflow-wrap: anywhere;
}
dt,
.evidence-empty {
  color: var(--ptmcp-text-muted);
}
dd {
  min-width: 0;
  margin: 0;
}
.evidence-list {
  margin: 0;
  padding-left: 24px;
}
.evidence-list > li {
  padding: 6px 0;
}
@media (max-width: 520px) {
  .evidence-fields__row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
