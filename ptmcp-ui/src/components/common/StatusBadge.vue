<script setup lang="ts">
import { computed } from 'vue';
import { ElTag } from 'element-plus';
import { statusPresentation, type StatusDomain } from './statusPresentation';

const props = defineProps<{ domain: StatusDomain; value?: string | null }>();
const status = computed(() => statusPresentation(props.domain, props.value));
</script>

<template>
  <ElTag :type="status.tone" effect="light" class="status-badge">
    <span class="status-badge__icon" aria-hidden="true">
      <IconEpCircleCheck v-if="status.icon === 'check'" />
      <IconEpCircleClose v-else-if="status.icon === 'close'" />
      <IconEpWarning v-else-if="status.icon === 'warning'" />
      <IconEpClock v-else-if="status.icon === 'clock'" />
      <IconEpQuestionFilled v-else />
    </span>
    {{ status.label }}
  </ElTag>
</template>

<style scoped>
.status-badge {
  height: auto;
  min-height: 24px;
  white-space: normal;
  overflow-wrap: anywhere;
}
.status-badge :deep(.el-tag__content) {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.status-badge__icon {
  display: inline-flex;
  flex-shrink: 0;
}
</style>
