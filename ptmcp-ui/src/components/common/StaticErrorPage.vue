<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router';
import { computed } from 'vue';
import PageState from './PageState.vue';
const route = useRoute();
const router = useRouter();
const forbidden = computed(() => route.name === 'forbidden');
</script>
<template>
  <main class="static-error-page">
    <RouterLink to="/" class="static-error-page__brand">PTMCP</RouterLink>
    <PageState
      :kind="forbidden ? 'forbidden' : 'not-found'"
      :title="forbidden ? '无法访问此页面' : '页面不存在'"
      :description="
        forbidden
          ? '当前账号或管理范围没有访问此页面的权限。你可以返回个人门户，或联系管理员确认授权。'
          : '页面可能已移动，资源也可能不存在或当前不可见。'
      "
      action-label="前往个人门户"
      @action="router.replace('/me/dictionary')"
    />
    <div class="static-error-page__links"><RouterLink to="/login">重新登录</RouterLink></div>
  </main>
</template>
<style scoped>
.static-error-page {
  display: grid;
  align-content: center;
  min-height: 100vh;
  padding: 32px;
  background: var(--ptmcp-canvas);
}
.static-error-page__brand {
  margin: auto;
  color: var(--ptmcp-blue-dark);
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 0.06em;
  text-decoration: none;
}
.static-error-page__links {
  text-align: center;
  font-size: 13px;
}
.static-error-page__links a {
  color: var(--ptmcp-blue);
}
</style>
