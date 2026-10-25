<script setup lang="ts">
import { RouterView } from 'vue-router';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import { onMounted, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
const route = useRoute();
const router = useRouter();
function handleExpired(): void {
  if (route.name !== 'login') void router.replace({ name: 'login', query: { returnTo: route.fullPath } });
}
onMounted(() => window.addEventListener('ptmcp:session-expired', handleExpired));
onUnmounted(() => window.removeEventListener('ptmcp:session-expired', handleExpired));
</script>
<template>
  <ElConfigProvider :locale="zhCn">
    <RouterView />
  </ElConfigProvider>
</template>
