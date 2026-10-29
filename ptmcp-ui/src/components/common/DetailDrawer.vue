<!--
  DetailDrawer — 右侧详情抽屉（Fiori 密度 + Apple 圆角）

  基本用法：
    <DetailDrawer v-model="open" title="数据源详情" subtitle="MySQL · 生产" :tabs="['概览','审计']" v-model:activeTab="tab">
      <template #badge><StatusBadge status="success" /></template>
      <dl class="drawer-fields">
        <dt>名称</dt><dd>core-mysql</dd>
        <dt>负责人</dt><dd>张三</dd>
      </dl>
      <template #footer>
        <el-button>取消</el-button>
        <el-button type="primary">保存</el-button>
      </template>
    </DetailDrawer>

  内置 :deep 样式 `drawer-fields`：抽屉主体内写 <dl class="drawer-fields">，
  即得 "label 左(120px) value 右" 两列字段列表（dt 灰小字 12px / dd 深色 13.5px / 行距 12px）。
  若需 "label 上 value 下" 竖排，加修饰类 <dl class="drawer-fields drawer-fields--stacked">。

  插槽：
    default   主体滚动区
    icon      标题左侧图标块（默认：浅蓝圆角方块 + IconEpDocument）
    badge     副标题旁状态徽标
    footer    底部固定操作区（hairline 上边框、右对齐）
-->
<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    modelValue: boolean;
    title: string;
    subtitle?: string;
    tabs?: string[];
    activeTab?: string;
    width?: string;
  }>(),
  { subtitle: undefined, tabs: undefined, activeTab: undefined, width: '420px' },
);
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  'update:activeTab': [value: string];
  close: [];
}>();

const open = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
});
const tab = computed({
  get: () => props.activeTab ?? props.tabs?.[0] ?? '',
  set: (value: string) => emit('update:activeTab', value),
});

function handleClose(): void {
  open.value = false;
  emit('close');
}
</script>
<template>
  <el-drawer v-model="open" :size="width" :with-header="false" class="detail-drawer" destroy-on-close @close="handleClose">
    <div class="detail-drawer__shell">
      <header class="detail-drawer__header">
        <slot name="icon">
          <span class="detail-drawer__icon" aria-hidden="true"><IconEpDocument /></span>
        </slot>
        <div class="detail-drawer__heading">
          <h2 class="detail-drawer__title">{{ title }}</h2>
          <div v-if="subtitle || $slots.badge" class="detail-drawer__meta">
            <span v-if="subtitle" class="detail-drawer__subtitle">{{ subtitle }}</span>
            <slot name="badge" />
          </div>
        </div>
        <button class="detail-drawer__close" type="button" aria-label="关闭" @click="handleClose">
          <IconEpClose aria-hidden="true" />
        </button>
      </header>
      <el-tabs v-if="tabs?.length" v-model="tab" class="detail-drawer__tabs">
        <el-tab-pane v-for="name in tabs" :key="name" :label="name" :name="name" />
      </el-tabs>
      <div class="detail-drawer__body"><slot /></div>
      <footer v-if="$slots.footer" class="detail-drawer__footer"><slot name="footer" /></footer>
    </div>
  </el-drawer>
</template>
<style scoped>
.detail-drawer__shell {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--ptmcp-surface);
}
.detail-drawer__header {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 20px 20px 16px;
  border-bottom: 1px solid var(--ptmcp-border-row);
}
.detail-drawer__icon {
  display: grid;
  width: 40px;
  height: 40px;
  flex: none;
  place-items: center;
  border-radius: var(--ptmcp-radius-panel);
  background: var(--ptmcp-blue-soft);
  color: var(--ptmcp-blue);
  font-size: 20px;
}
.detail-drawer__heading {
  min-width: 0;
  flex: 1;
}
.detail-drawer__title {
  margin: 2px 0 0;
  color: var(--ptmcp-heading);
  font-size: 17px;
  font-weight: 600;
  line-height: 1.3;
  overflow-wrap: anywhere;
}
.detail-drawer__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}
.detail-drawer__subtitle {
  color: var(--ptmcp-text-muted);
  font-size: 12.5px;
  line-height: 1.4;
}
.detail-drawer__close {
  display: grid;
  width: 28px;
  height: 28px;
  flex: none;
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: var(--ptmcp-radius-control);
  background: transparent;
  color: var(--ptmcp-text-faint);
  cursor: pointer;
  transition:
    background 0.15s ease,
    color 0.15s ease;
}
.detail-drawer__close:hover {
  background: var(--ptmcp-hover);
  color: var(--ptmcp-text);
}
.detail-drawer__tabs {
  flex: none;
  padding: 0 20px;
  border-bottom: 1px solid var(--ptmcp-border-row);
}
.detail-drawer__tabs :deep(.el-tabs__header) {
  margin: 0;
}
.detail-drawer__tabs :deep(.el-tabs__nav-wrap::after) {
  display: none;
}
.detail-drawer__body {
  flex: 1;
  min-height: 0;
  padding: 20px;
  overflow-y: auto;
}
.detail-drawer__footer {
  display: flex;
  flex: none;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--ptmcp-border-row);
  background: var(--ptmcp-band);
}
/* 语义化字段列表：<dl class="drawer-fields"><dt>…</dt><dd>…</dd></dl> */
.detail-drawer__body :deep(.drawer-fields) {
  display: grid;
  grid-template-columns: 120px 1fr;
  row-gap: 12px;
  column-gap: 16px;
  margin: 0;
}
.detail-drawer__body :deep(.drawer-fields dt) {
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.5;
}
.detail-drawer__body :deep(.drawer-fields dd) {
  margin: 0;
  color: var(--ptmcp-text-strong);
  font-size: 13.5px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}
.detail-drawer__body :deep(.drawer-fields--stacked) {
  grid-template-columns: 1fr;
  row-gap: 2px;
}
.detail-drawer__body :deep(.drawer-fields--stacked dd + dt) {
  margin-top: 10px;
}
</style>
