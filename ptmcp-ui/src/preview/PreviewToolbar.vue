<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { setPreviewScenario, type PreviewScenario } from './server';
const emit = defineEmits<{ change: [] }>();
const router = useRouter();
const scenario = ref<PreviewScenario>('normal');
function change(): void {
  setPreviewScenario(scenario.value);
  emit('change');
}
</script>
<template>
  <div class="preview-banner" role="region" aria-label="前端开发预览工具">
    <strong>前端预览 · 合成数据 / 仅内存</strong>
    <div class="preview-controls">
      <RouterLink to="/__preview/pages">全部页面</RouterLink
      ><select v-model="scenario" aria-label="预览状态" @change="change">
        <option value="normal">正常数据</option>
        <option value="empty">空状态</option>
        <option value="error">服务错误</option>
        <option value="conflict">写入冲突</option>
        <option value="slow">慢速加载</option></select
      ><button type="button" @click="router.go(0)">重置演示数据</button>
    </div>
  </div>
</template>
<style scoped>
.preview-banner {
  position: fixed;
  z-index: 100;
  inset: 0 0 auto;
  min-height: 42px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 8px 18px;
  background: var(--ptmcp-bg-warning);
  color: var(--ptmcp-orange);
  font-size: 12px;
  line-height: 1.5;
}
.preview-controls {
  display: flex;
  align-items: center;
  gap: 12px;
}
.preview-controls a {
  color: inherit;
}
.preview-controls select,
.preview-controls button {
  font: inherit;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 4px;
  background: #fffdf7;
  color: var(--ptmcp-orange);
  padding: 3px 6px;
  cursor: pointer;
}
@media (max-width: 600px) {
  .preview-banner {
    min-height: 74px;
    flex-direction: column;
    justify-content: center;
    gap: 4px;
    padding: 6px 10px;
  }
  .preview-controls {
    gap: 8px;
  }
}
</style>
