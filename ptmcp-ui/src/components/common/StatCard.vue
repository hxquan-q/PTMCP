<script setup lang="ts">
import type { Component } from 'vue';

withDefaults(
  defineProps<{
    icon?: Component;
    value: string | number;
    label: string;
    hint?: string;
    tone?: 'default' | 'success' | 'warning' | 'danger';
  }>(),
  { icon: undefined, hint: undefined, tone: 'default' },
);
</script>

<template>
  <!--
    一行多卡用法：给父容器加全局类 .stat-card-row（见下方 :global 样式），
    例：<div class="stat-card-row"><StatCard .../><StatCard .../></div>
  -->
  <div class="stat-card" :data-tone="tone">
    <div v-if="icon" class="stat-card__icon" aria-hidden="true">
      <component :is="icon" />
    </div>
    <div class="stat-card__text">
      <div class="stat-card__value">{{ value }}</div>
      <div class="stat-card__label">{{ label }}</div>
      <div v-if="hint" class="stat-card__hint">{{ hint }}</div>
    </div>
  </div>
</template>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: var(--ptmcp-surface);
  border: 1px solid var(--ptmcp-border-row);
  border-radius: 12px;
  box-shadow: var(--ptmcp-shadow);
  min-width: 0;
}
.stat-card__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  font-size: 20px;
  background: var(--ptmcp-blue-soft);
  color: var(--ptmcp-blue);
}
.stat-card[data-tone='success'] .stat-card__icon {
  background: var(--ptmcp-bg-success);
  color: var(--ptmcp-green);
}
.stat-card[data-tone='warning'] .stat-card__icon {
  background: var(--ptmcp-bg-warning);
  color: var(--ptmcp-orange);
}
.stat-card[data-tone='danger'] .stat-card__icon {
  background: var(--ptmcp-bg-danger);
  color: var(--ptmcp-red);
}
.stat-card__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.stat-card__value {
  font-size: 20px;
  font-weight: 600;
  line-height: 1.2;
  color: var(--ptmcp-heading);
  overflow-wrap: anywhere;
}
.stat-card[data-tone='success'] .stat-card__value {
  color: var(--ptmcp-green);
}
.stat-card[data-tone='warning'] .stat-card__value {
  color: var(--ptmcp-orange);
}
.stat-card[data-tone='danger'] .stat-card__value {
  color: var(--ptmcp-red);
}
.stat-card__label {
  font-size: 13px;
  line-height: 1.4;
  color: var(--ptmcp-text);
}
.stat-card__hint {
  font-size: 12px;
  line-height: 1.4;
  color: var(--ptmcp-text-muted);
}
</style>

<!-- 全局容器类：一排 StatCard 的自适应栅格（父容器加 class="stat-card-row"） -->
<style>
.stat-card-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 16px;
}
</style>
