<!--
  GlobalSearch — 顶栏全局搜索框（⌘K / Ctrl+K 聚焦）

  接入方式：调用方注入 `search` prop 返回分组结果，组件负责防抖/快捷键/键盘导航/渲染。
    <GlobalSearch :search="searchAll" @select="onSelect" />
    async function searchAll(q: string): Promise<SearchResultGroup[]> {
      return [{ label: '数据源', items: [{ id: 'ds1', title: 'core-mysql', subtitle: 'MySQL' }] }];
    }

  快捷键：⌘K / Ctrl+K 全局聚焦输入框；Esc 关闭面板（焦点在输入框时同时失焦）；
  ↑/↓ 在结果行间移动高亮，Enter 触发 select。输入清空时面板关闭。
-->
<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch, type Component } from 'vue';

export interface SearchResultItem {
  id: string;
  title: string;
  subtitle?: string;
  icon?: Component;
}
export interface SearchResultGroup {
  label: string;
  items: SearchResultItem[];
}

const props = withDefaults(
  defineProps<{
    search: (query: string) => Promise<SearchResultGroup[]>;
    placeholder?: string;
    maxHeight?: string;
  }>(),
  { placeholder: '搜索功能、资源或关键词', maxHeight: '360px' },
);
const emit = defineEmits<{ select: [item: SearchResultItem] }>();

const query = ref('');
const groups = ref<SearchResultGroup[]>([]);
const open = ref(false);
const loading = ref(false);
const activeIndex = ref(-1);
const inputRef = ref<HTMLInputElement | null>(null);
const panelRef = ref<HTMLElement | null>(null);
let debounceTimer: ReturnType<typeof setTimeout> | undefined;
let requestSeq = 0;

interface FlatEntry {
  item: SearchResultItem;
  groupIndex: number;
  itemIndex: number;
}
const flatItems = computed<FlatEntry[]>(() =>
  groups.value.flatMap((group, groupIndex) => group.items.map((item, itemIndex) => ({ item, groupIndex, itemIndex }))),
);
const isMac = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform);

watch(query, (value) => {
  if (debounceTimer) clearTimeout(debounceTimer);
  const keyword = value.trim();
  if (!keyword) {
    requestSeq += 1;
    groups.value = [];
    open.value = false;
    loading.value = false;
    return;
  }
  debounceTimer = setTimeout(() => runSearch(keyword), 300);
});

async function runSearch(keyword: string): Promise<void> {
  const seq = ++requestSeq;
  loading.value = true;
  open.value = true;
  activeIndex.value = -1;
  try {
    const result = await props.search(keyword);
    if (seq !== requestSeq) return;
    groups.value = result ?? [];
  } catch {
    if (seq === requestSeq) groups.value = [];
  } finally {
    if (seq === requestSeq) loading.value = false;
  }
}

function focusInput(): void {
  nextTick(() => {
    inputRef.value?.focus();
    inputRef.value?.select();
  });
}
function closePanel(): void {
  open.value = false;
  activeIndex.value = -1;
}
function isEditableTarget(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null;
  if (!el) return false;
  return el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.isContentEditable;
}
function handleGlobalKeydown(event: KeyboardEvent): void {
  if ((event.metaKey || event.ctrlKey) && (event.key === 'k' || event.key === 'K')) {
    event.preventDefault();
    focusInput();
  }
}
function handleInputKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault();
    closePanel();
    inputRef.value?.blur();
    return;
  }
  if (!open.value) return;
  const total = flatItems.value.length;
  if (event.key === 'ArrowDown') {
    event.preventDefault();
    if (total) activeIndex.value = (activeIndex.value + 1) % total;
  } else if (event.key === 'ArrowUp') {
    event.preventDefault();
    if (total) activeIndex.value = (activeIndex.value - 1 + total) % total;
  } else if (event.key === 'Enter') {
    event.preventDefault();
    const entry = activeIndex.value >= 0 ? flatItems.value[activeIndex.value] : flatItems.value[0];
    if (entry) selectItem(entry.item);
  }
}
function handleDocumentPointerdown(event: PointerEvent): void {
  if (!open.value) return;
  const target = event.target as Node | null;
  if (panelRef.value?.contains(target) || inputRef.value?.contains(target)) return;
  closePanel();
}
function selectItem(item: SearchResultItem): void {
  emit('select', item);
  closePanel();
}
function handleFocus(): void {
  if (groups.value.length && query.value.trim()) open.value = true;
}
function flatIndexOf(groupIndex: number, itemIndex: number): number {
  let index = 0;
  for (let g = 0; g < groupIndex; g += 1) index += groups.value[g]?.items.length ?? 0;
  return index + itemIndex;
}

onMounted(() => {
  window.addEventListener('keydown', handleGlobalKeydown);
  document.addEventListener('pointerdown', handleDocumentPointerdown);
});
onUnmounted(() => {
  window.removeEventListener('keydown', handleGlobalKeydown);
  document.removeEventListener('pointerdown', handleDocumentPointerdown);
  if (debounceTimer) clearTimeout(debounceTimer);
});
</script>
<template>
  <div class="global-search">
    <div class="global-search__box" :class="{ 'global-search__box--open': open }">
      <IconEpSearch class="global-search__icon" aria-hidden="true" />
      <input
        ref="inputRef"
        v-model="query"
        class="global-search__input"
        type="text"
        :placeholder="placeholder"
        role="combobox"
        :aria-expanded="open ? 'true' : 'false'"
        aria-autocomplete="list"
        @keydown="handleInputKeydown"
        @focus="handleFocus"
      />
      <kbd class="global-search__kbd" aria-hidden="true">{{ isMac ? '⌘K' : 'Ctrl K' }}</kbd>
    </div>
    <transition name="global-search-pop">
      <div v-if="open" ref="panelRef" class="global-search__panel" :style="{ maxHeight }" role="listbox">
        <p v-if="loading" class="global-search__hint">搜索中…</p>
        <p v-else-if="!flatItems.length" class="global-search__hint">无匹配结果</p>
        <template v-else>
          <section v-for="(group, groupIndex) in groups" :key="group.label" class="global-search__group">
            <p class="global-search__group-label">{{ group.label }}</p>
            <button
              v-for="(item, itemIndex) in group.items"
              :key="item.id"
              class="global-search__row"
              :class="{ 'global-search__row--active': flatIndexOf(groupIndex, itemIndex) === activeIndex }"
              type="button"
              role="option"
              :aria-selected="flatIndexOf(groupIndex, itemIndex) === activeIndex"
              @mouseenter="activeIndex = flatIndexOf(groupIndex, itemIndex)"
              @click="selectItem(item)"
            >
              <span class="global-search__row-icon" aria-hidden="true">
                <component :is="item.icon" v-if="item.icon" />
                <IconEpDocument v-else />
              </span>
              <span class="global-search__row-text">
                <span class="global-search__row-title">{{ item.title }}</span>
                <span v-if="item.subtitle" class="global-search__row-subtitle">{{ item.subtitle }}</span>
              </span>
            </button>
          </section>
        </template>
      </div>
    </transition>
  </div>
</template>
<style scoped>
.global-search {
  position: relative;
  width: min(480px, 100%);
  flex: none;
  margin-inline: auto;
}
.global-search__box {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 10px 0 12px;
  border: 1px solid var(--ptmcp-border);
  border-radius: var(--ptmcp-radius-control);
  background: var(--ptmcp-surface);
  transition:
    border-color 0.15s ease,
    box-shadow 0.15s ease;
}
.global-search__box:focus-within,
.global-search__box--open {
  border-color: var(--ptmcp-blue);
  box-shadow: 0 0 0 3px var(--ptmcp-selected);
}
.global-search__icon {
  flex: none;
  color: var(--ptmcp-text-faint);
  font-size: 15px;
}
.global-search__input {
  min-width: 0;
  flex: 1;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--ptmcp-text);
  font: inherit;
  font-size: 13.5px;
}
.global-search__input::placeholder {
  color: var(--ptmcp-text-faint);
}
.global-search__kbd {
  flex: none;
  padding: 2px 6px;
  border: 1px solid var(--ptmcp-border);
  border-radius: 6px;
  background: var(--ptmcp-canvas);
  color: var(--ptmcp-text-muted);
  font-family: inherit;
  font-size: 11px;
  line-height: 1.4;
}
.global-search__panel {
  position: absolute;
  z-index: 100;
  top: calc(100% + 6px);
  right: 0;
  left: 0;
  overflow-y: auto;
  padding: 6px;
  border: 1px solid var(--ptmcp-border-row);
  border-radius: var(--ptmcp-radius-panel);
  background: var(--ptmcp-surface);
  box-shadow: var(--ptmcp-shadow);
}
.global-search__hint {
  margin: 0;
  padding: 20px 12px;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
  text-align: center;
}
.global-search__group + .global-search__group {
  margin-top: 4px;
}
.global-search__group-label {
  margin: 0;
  padding: 6px 10px 4px;
  color: var(--ptmcp-text-faint);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  text-transform: uppercase;
}
.global-search__row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--ptmcp-radius-control);
  background: transparent;
  color: var(--ptmcp-text);
  cursor: pointer;
  font: inherit;
  text-align: left;
}
.global-search__row:hover {
  background: var(--ptmcp-hover);
}
.global-search__row--active,
.global-search__row--active:hover {
  background: var(--ptmcp-selected);
}
.global-search__row-icon {
  display: grid;
  width: 28px;
  height: 28px;
  flex: none;
  place-items: center;
  border-radius: var(--ptmcp-radius-control);
  background: var(--ptmcp-canvas);
  color: var(--ptmcp-text-muted);
  font-size: 14px;
}
.global-search__row--active .global-search__row-icon {
  background: var(--ptmcp-blue-soft);
  color: var(--ptmcp-blue);
}
.global-search__row-text {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 1px;
}
.global-search__row-title {
  color: var(--ptmcp-text-strong);
  font-size: 13.5px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.global-search__row-subtitle {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.global-search-pop-enter-active,
.global-search-pop-leave-active {
  transition:
    opacity 0.12s ease,
    transform 0.12s ease;
}
.global-search-pop-enter-from,
.global-search-pop-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
