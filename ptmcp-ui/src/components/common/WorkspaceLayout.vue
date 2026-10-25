<script setup lang="ts">
import { computed, ref, type Component } from 'vue';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import { useSessionStore } from '../../stores/session';

export interface NavigationItem {
  label: string;
  to: string;
  icon: Component;
  permission?: string;
}
const props = defineProps<{ mode: 'admin' | 'personal'; items: NavigationItem[]; title: string; subtitle: string }>();
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const menuOpen = ref(false);
const visibleItems = computed(() => props.items.filter((item) => !item.permission || session.can(item.permission)));
const displayName = computed(() => session.user?.displayName || session.user?.username || '当前用户');
const initials = computed(() => displayName.value.trim().slice(0, 1).toUpperCase());
const isActive = (to: string) => route.path === to || route.path.startsWith(`${to}/`);
async function logout(): Promise<void> {
  await session.logout();
  await router.replace({ name: 'login', query: { returnTo: route.fullPath } });
}
</script>
<template>
  <div class="workspace-shell">
    <aside class="workspace-sidebar" :class="{ 'is-open': menuOpen }">
      <div class="workspace-brand">
        <div class="workspace-brand__mark">P</div>
        <div><strong>PTMCP</strong><span>Data access control</span></div>
      </div>
      <div class="workspace-context">
        <span>{{ mode === 'admin' ? '管理工作台' : '个人门户' }}</span
        ><strong>{{ title }}</strong
        ><small>{{ subtitle }}</small>
      </div>
      <nav class="workspace-nav" :aria-label="mode === 'admin' ? '管理导航' : '个人导航'">
        <RouterLink v-for="item in visibleItems" :key="item.to" :to="item.to" :class="{ 'is-active': isActive(item.to) }" @click="menuOpen = false">
          <component :is="item.icon" aria-hidden="true" /><span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="workspace-sidebar__footer">
        <RouterLink :to="mode === 'admin' ? '/me/dictionary' : '/admin/datasources'" class="workspace-switch"
          ><IconEpSwitch />{{ mode === 'admin' ? '切换到个人门户' : '切换到管理工作台' }}</RouterLink
        >
      </div>
    </aside>
    <div v-if="menuOpen" class="workspace-scrim" aria-hidden="true" @click="menuOpen = false" />
    <div class="workspace-main">
      <header class="workspace-topbar">
        <button class="workspace-menu" type="button" aria-label="打开导航" @click="menuOpen = !menuOpen"><IconEpExpand /></button>
        <div class="workspace-breadcrumb">
          <span>{{ mode === 'admin' ? '管理工作台' : '个人门户' }}</span
          ><IconEpArrowRight /><strong>{{ String(route.meta.title ?? '概览') }}</strong>
        </div>
        <div class="workspace-user">
          <div class="workspace-avatar" aria-hidden="true">{{ initials }}</div>
          <div class="workspace-user__text">
            <strong>{{ displayName }}</strong
            ><span>{{ session.user?.department?.name ?? 'PTMCP 用户' }}</span>
          </div>
          <ElDropdown trigger="click"
            ><button class="workspace-user__button" aria-label="用户菜单"><IconEpMoreFilled /></button
            ><template #dropdown
              ><ElDropdownMenu
                ><ElDropdownItem @click="router.push('/me/security')">账号安全</ElDropdownItem
                ><ElDropdownItem divided @click="logout">退出登录</ElDropdownItem></ElDropdownMenu
              ></template
            ></ElDropdown
          >
        </div>
      </header>
      <main class="workspace-content"><RouterView /></main>
    </div>
  </div>
</template>
