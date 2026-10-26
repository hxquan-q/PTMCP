<script setup lang="ts">
import { computed, ref, onBeforeUnmount } from 'vue';
const props = defineProps<{ modelValue: string; title: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const message = ref('');
const visible = computed({
  get: () => !!props.modelValue,
  set: (value) => {
    if (!value) emit('update:modelValue', '');
  }
});
async function copy(): Promise<void> {
  try {
    await navigator.clipboard.writeText(props.modelValue);
    message.value = '已复制，请妥善保存。';
  } catch {
    message.value = '复制失败，请手动选择并复制。';
  }
}
onBeforeUnmount(() => emit('update:modelValue', ''));
</script>
<template>
  <ElDialog v-model="visible" :title="title" width="min(600px, calc(100vw - 28px))" destroy-on-close :close-on-click-modal="false">
    <ElAlert type="warning" :closable="false" title="仅展示本次，关闭后无法找回。请保存到受控凭证库。" />
    <pre v-if="modelValue" class="once-secret">{{ modelValue }}</pre>
    <p v-if="message" role="status">{{ message }}</p>
    <template #footer><ElButton @click="copy">复制</ElButton><ElButton type="primary" @click="visible = false">已安全保存，关闭</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.once-secret {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 16px;
  background: var(--ptmcp-blue-soft);
  user-select: all;
}
</style>
