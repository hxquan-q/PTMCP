<script setup lang="ts">
import { computed } from 'vue';
import { statusPresentation, type StatusDomain } from './statusPresentation';

const props = defineProps<{ domain: StatusDomain; value?: string | null }>();
const status = computed(() => statusPresentation(props.domain, props.value));
</script>

<template>
  <span class="status-badge" :data-tone="status.tone">
    <span class="status-badge__dot" aria-hidden="true" />
    <span class="status-badge__label">{{ status.label }}</span>
    <span class="status-badge__icon" aria-hidden="true">
      <IconEpCircleCheck v-if="status.icon === 'check'" />
      <IconEpCircleClose v-else-if="status.icon === 'close'" />
      <IconEpWarning v-else-if="status.icon === 'warning'" />
      <IconEpClock v-else-if="status.icon === 'clock'" />
      <IconEpQuestionFilled v-else />
    </span>
  </span>
</template>

<style scoped>
.status-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  line-height: 20px;
  white-space: normal;
  overflow-wrap: anywhere;
}
.status-badge__dot {
  flex-shrink: 0;
  width: 7px;
  height: 7px;
  border-radius: 50%;
}
.status-badge[data-tone='success'] {
  color: var(--ptmcp-green);
}
.status-badge[data-tone='success'] .status-badge__dot {
  background: var(--ptmcp-green);
}
.status-badge[data-tone='warning'] {
  color: var(--ptmcp-orange);
}
.status-badge[data-tone='warning'] .status-badge__dot {
  background: var(--ptmcp-orange);
}
.status-badge[data-tone='danger'] {
  color: var(--ptmcp-red);
}
.status-badge[data-tone='danger'] .status-badge__dot {
  background: var(--ptmcp-red);
}
.status-badge[data-tone='info'] {
  color: var(--ptmcp-text-muted);
}
.status-badge[data-tone='info'] .status-badge__dot {
  background: var(--ptmcp-text-faint);
}
.status-badge__icon {
  display: inline-flex;
  flex-shrink: 0;
  margin-left: 1px;
  font-size: 13px;
}
</style>
