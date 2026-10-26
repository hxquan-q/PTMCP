<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch, type Component } from 'vue';
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router';
import { useSessionStore } from '../../stores/session';
import { errorMessage } from '../../services/http';

export interface NavigationItem {
  label: string;
  to: string;
  icon: Component;
  permission?: string;
  group?: string;
}
const props = defineProps<{ mode: 'admin' | 'personal'; items: NavigationItem[]; title: string; subtitle: string }>();
const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const menuOpen = ref(false);
const overlayNavigation = ref(false);
const sidebar = ref<HTMLElement | null>(null);
const menuButton = ref<HTMLButtonElement | null>(null);
const content = ref<HTMLElement | null>(null);
const loggingOut = ref(false);
const logoutError = ref('');
const visibleItems = computed(() => props.items.filter((item) => !item.permission || session.can(item.permission)));
const navigationGroups = computed(() => {
  const groups = new Map<string, NavigationItem[]>();
  for (const item of visibleItems.value) {
    const group = item.group ?? (props.mode === 'admin' ? '管理工作台' : '个人门户');
    groups.set(group, [...(groups.get(group) ?? []), item]);
  }
  return Array.from(groups, ([title, items]) => ({ title, items }));
});
const firstAdminRoute = computed(() => {
  const candidates = [
    '/admin/datasources',
    '/admin/metadata',
    '/admin/departments',
    '/admin/users',
    '/admin/roles',
    '/admin/permissions',
    '/admin/delegations',
    '/admin/platforms',
    '/admin/audit',
  ];
  return candidates.find((path) => {
    const record = router.getRoutes().find((candidate) => candidate.path === path);
    return typeof record?.meta.permission === 'string' && session.can(record.meta.permission);
  });
});
const displayName = computed(() => session.user?.displayName || session.user?.username || '当前用户');
const initials = computed(() => displayName.value.trim().slice(0, 1).toUpperCase());
const isActive = (to: string) => route.path === to || route.path.startsWith(`${to}/`);
let media: MediaQueryList | undefined;
let previousOverflow = '';
function closeMenu(restoreFocus = true): void {
  if (!menuOpen.value) return;
  menuOpen.value = false;
  if (restoreFocus) nextTick(() => menuButton.value?.focus()).catch(() => undefined);
}
async function closeForNavigation(): Promise<void> {
  if (!overlayNavigation.value) return;
  closeMenu(false);
  await nextTick();
  content.value?.focus();
}
function updateOverlay(): void {
  overlayNavigation.value = media?.matches ?? false;
  if (!overlayNavigation.value) closeMenu(false);
}
function handleNavigationKey(event: KeyboardEvent): void {
  if (!menuOpen.value || !overlayNavigation.value) return;
  if (event.key === 'Escape') {
    event.preventDefault();
    closeMenu();
    return;
  }
  if (event.key !== 'Tab') return;
  const controls = Array.from(sidebar.value?.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), [tabindex="0"]') ?? []).filter(
    (element) => element.getClientRects().length,
  );
  const first = controls[0];
  const last = controls.at(-1);
  if (!first || !last) return;
  if (event.shiftKey && (document.activeElement === first || !sidebar.value?.contains(document.activeElement))) {
    event.preventDefault();
    last.focus();
  } else if (!event.shiftKey && (document.activeElement === last || !sidebar.value?.contains(document.activeElement))) {
    event.preventDefault();
    first.focus();
  }
}
watch(menuOpen, async (open) => {
  if (open && overlayNavigation.value) {
    previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    await nextTick();
    sidebar.value?.querySelector<HTMLElement>('button, a[href]')?.focus();
  } else document.body.style.overflow = previousOverflow;
});
watch(
  () => route.fullPath,
  async () => {
    if (!menuOpen.value) return;
    closeMenu(false);
    await nextTick();
    content.value?.focus();
  },
);
onMounted(() => {
  media = window.matchMedia('(max-width: 900px)');
  updateOverlay();
  media.addEventListener('change', updateOverlay);
  document.addEventListener('keydown', handleNavigationKey);
});
onUnmounted(() => {
  media?.removeEventListener('change', updateOverlay);
  document.removeEventListener('keydown', handleNavigationKey);
  if (menuOpen.value) document.body.style.overflow = previousOverflow;
});
async function logout(): Promise<void> {
  if (loggingOut.value) return;
  loggingOut.value = true;
  logoutError.value = '';
  try {
    await session.logout();
    await router.replace({ name: 'login', query: { returnTo: route.fullPath } });
  } catch (error) {
    logoutError.value = errorMessage(error);
  } finally {
    loggingOut.value = false;
  }
}
</script>
<template>
  <div class="workspace-shell">
    <header class="workspace-topbar" :inert="overlayNavigation && menuOpen">
      <button
        ref="menuButton"
        class="workspace-menu"
        type="button"
        :aria-expanded="menuOpen"
        aria-controls="workspace-navigation"
        :aria-label="menuOpen ? '关闭导航' : '打开导航'"
        @click="menuOpen ? closeMenu() : (menuOpen = true)"
      >
        <IconEpExpand />
      </button>
      <RouterLink class="workspace-brand" to="/" aria-label="PTMCP 工作台"><strong>PTMCP</strong><span>数据访问管理平台</span></RouterLink>
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
              ><ElDropdownItem divided :disabled="loggingOut" @click="logout">{{
                loggingOut ? '正在退出…' : '退出登录'
              }}</ElDropdownItem></ElDropdownMenu
            ></template
          ></ElDropdown
        >
      </div>
    </header>
    <div class="workspace-viewbar" :inert="overlayNavigation && menuOpen">
      <RouterLink
        v-if="firstAdminRoute"
        :to="firstAdminRoute"
        :class="{ 'is-current': mode === 'admin' }"
        :aria-current="mode === 'admin' ? 'page' : undefined"
        ><IconEpGrid />管理控制台</RouterLink
      >
      <RouterLink to="/me/dictionary" :class="{ 'is-current': mode === 'personal' }" :aria-current="mode === 'personal' ? 'page' : undefined"
        >个人门户</RouterLink
      >
      <span><IconEpLock />权限管控型 MCP 网关</span>
    </div>
    <aside
      id="workspace-navigation"
      ref="sidebar"
      class="workspace-sidebar"
      :class="{ 'is-open': menuOpen }"
      :inert="overlayNavigation && !menuOpen"
      :aria-hidden="overlayNavigation && !menuOpen ? true : undefined"
      :role="overlayNavigation && menuOpen ? 'dialog' : undefined"
      :aria-modal="overlayNavigation && menuOpen ? true : undefined"
      :aria-label="`${title}导航`"
    >
      <div class="workspace-sidebar__mobile-title">
        <strong>{{ title }}</strong
        ><button type="button" aria-label="关闭导航" @click="closeMenu()"><IconEpClose /></button>
      </div>
      <nav class="workspace-nav" :aria-label="mode === 'admin' ? '管理导航' : '个人导航'">
        <section v-for="group in navigationGroups" :key="group.title" class="workspace-nav__group">
          <h2>{{ group.title }}</h2>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            :class="{ 'is-active': isActive(item.to) }"
            :aria-current="isActive(item.to) ? 'page' : undefined"
            @click="closeForNavigation"
          >
            <component :is="item.icon" aria-hidden="true" /><span>{{ item.label }}</span>
          </RouterLink>
        </section>
      </nav>
      <div class="workspace-sidebar__footer">
        <strong>PTMCP {{ mode === 'admin' ? '管理工作区' : '个人门户' }}</strong
        ><span>{{ subtitle }}</span>
      </div>
    </aside>
    <div v-if="menuOpen" class="workspace-scrim" aria-hidden="true" @click="closeMenu()" />
    <div class="workspace-main" :inert="overlayNavigation && menuOpen">
      <main ref="content" class="workspace-content" tabindex="-1">
        <div class="workspace-breadcrumb">
          <span>{{ mode === 'admin' ? '管理控制台' : '个人门户' }}</span
          ><IconEpArrowRight /><strong>{{ String(route.meta.title ?? '概览') }}</strong>
        </div>
        <ElAlert
          v-if="logoutError"
          class="workspace-session-error"
          title="退出未完成"
          :description="logoutError"
          type="error"
          show-icon
          closable
          @close="logoutError = ''"
        />
        <RouterView />
      </main>
    </div>
  </div>
</template>
