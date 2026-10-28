<script setup lang="ts">
import { RouterView, useRoute, useRouter } from 'vue-router';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { onMounted, onUnmounted, ref, defineAsyncComponent } from 'vue';
import { frontendPreview } from './services/frontendMode';
const PreviewToolbar =
  import.meta.env.DEV && import.meta.env.MODE === 'frontend-preview' ? defineAsyncComponent(() => import('./preview/PreviewToolbar.vue')) : null;
const previewKey = ref(0);
const route = useRoute();
const router = useRouter();
function handleExpired(): void {
  if (route.name !== 'login') router.replace({ name: 'login', query: { returnTo: route.fullPath } }).catch(() => undefined);
}
onMounted(() => window.addEventListener('ptmcp:session-expired', handleExpired));
onUnmounted(() => window.removeEventListener('ptmcp:session-expired', handleExpired));
</script>
<template>
  <ElConfigProvider :locale="zhCn">
    <div :class="{ 'frontend-preview': frontendPreview }">
      <PreviewToolbar v-if="PreviewToolbar" @change="previewKey++" /><RouterView :key="previewKey" />
    </div>
  </ElConfigProvider>
</template>
<style>
.frontend-preview .workspace-topbar {
  top: 48px;
}
.frontend-preview .workspace-viewbar {
  top: 108px;
}
.frontend-preview .workspace-sidebar {
  top: 152px;
}
.frontend-preview .workspace-main {
  padding-top: 152px;
}
@media (max-width: 900px) {
  .frontend-preview .workspace-viewbar {
    top: 108px;
  }
  .frontend-preview .workspace-sidebar {
    top: 48px;
  }
  .frontend-preview .workspace-main {
    padding-top: 152px;
  }
}
@media (max-width: 600px) {
  .frontend-preview .workspace-topbar {
    top: 74px;
  }
  .frontend-preview .workspace-viewbar {
    top: 134px;
  }
  .frontend-preview .workspace-sidebar {
    top: 74px;
  }
  .frontend-preview .workspace-main {
    padding-top: 178px;
  }
}
</style>
