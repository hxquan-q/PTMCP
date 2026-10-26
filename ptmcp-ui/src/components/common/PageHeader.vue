<script setup lang="ts">
withDefaults(defineProps<{ title: string; description?: string }>(), { description: undefined });
</script>
<template>
  <header class="page-header">
    <div>
      <h1>{{ title }}</h1>
      <p v-if="description">{{ description }}</p>
    </div>
    <div class="page-header__actions"><slot name="actions" /></div>
  </header>
</template>
<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
}
.page-header h1 {
  margin: 0;
  color: var(--ptmcp-heading);
  font-size: 24px;
  line-height: 1.2;
  letter-spacing: -0.02em;
}
.page-header p {
  margin: 8px 0 0;
  color: var(--ptmcp-text-muted);
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
