<script setup lang="ts">
withDefaults(defineProps<{ title: string; description?: string; titleId?: string }>(), { description: undefined, titleId: undefined });
</script>
<template>
  <header class="page-header">
    <div class="page-header__main">
      <slot name="before" />
      <div class="page-header__title">
        <h1 :id="titleId">{{ title }}</h1>
        <slot name="status" />
      </div>
      <p v-if="description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions"><slot name="actions" /></div>
  </header>
</template>
<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}
.page-header__main {
  min-width: 0;
}
.page-header__title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}
.page-header h1 {
  margin: 0;
  color: var(--ptmcp-heading);
  font-size: 20px;
  font-weight: 600;
  line-height: 1.3;
  overflow-wrap: anywhere;
}
.page-header p {
  margin: 4px 0 0;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
  line-height: 1.5;
}
.page-header__actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}
@media (max-width: 640px) {
  .page-header {
    flex-direction: column;
  }
  .page-header__actions {
    justify-content: flex-start;
  }
}
</style>
