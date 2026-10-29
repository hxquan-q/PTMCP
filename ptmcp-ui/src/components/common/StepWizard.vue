<script setup lang="ts">
import { computed } from 'vue';

const props = withDefaults(
  defineProps<{
    steps: string[];
    modelValue: number;
    nextLabel?: string;
    finishLabel?: string;
    canNext?: boolean;
  }>(),
  { nextLabel: '下一步', finishLabel: '完成', canNext: true },
);

const emit = defineEmits<{
  'update:modelValue': [value: number];
  next: [];
  prev: [];
  finish: [];
}>();

const isFirst = computed(() => props.modelValue <= 0);
const isLast = computed(() => props.modelValue >= props.steps.length - 1);
const currentSlot = computed(() => `step-${props.modelValue}`);

function stepState(index: number): 'done' | 'active' | 'todo' {
  if (index < props.modelValue) return 'done';
  if (index === props.modelValue) return 'active';
  return 'todo';
}

function onPrev() {
  if (isFirst.value) return;
  emit('update:modelValue', props.modelValue - 1);
  emit('prev');
}

function onNext() {
  if (!props.canNext) return;
  if (isLast.value) {
    emit('finish');
    return;
  }
  emit('update:modelValue', props.modelValue + 1);
  emit('next');
}
</script>

<template>
  <section class="step-wizard">
    <ol class="step-wizard__rail" role="list">
      <template v-for="(title, i) in steps" :key="i">
        <li class="step-wizard__step" :data-state="stepState(i)" :aria-current="stepState(i) === 'active' ? 'step' : undefined">
          <span class="step-wizard__node" aria-hidden="true">
            <IconEpCheck v-if="stepState(i) === 'done'" class="step-wizard__check" />
            <span v-else>{{ i + 1 }}</span>
          </span>
          <span class="step-wizard__title">{{ title }}</span>
        </li>
        <li v-if="i < steps.length - 1" class="step-wizard__link" :data-state="stepState(i) === 'done' ? 'done' : 'todo'" aria-hidden="true" />
      </template>
    </ol>

    <div class="step-wizard__body">
      <slot :name="currentSlot" :index="modelValue" />
    </div>

    <footer class="step-wizard__bar">
      <div class="step-wizard__bar-left">
        <el-button v-if="!isFirst" class="step-wizard__btn step-wizard__btn--ghost" @click="onPrev">上一步</el-button>
      </div>
      <div class="step-wizard__bar-right">
        <el-button type="primary" class="step-wizard__btn step-wizard__btn--primary" :disabled="!canNext" @click="onNext">{{
          isLast ? finishLabel : nextLabel
        }}</el-button>
      </div>
    </footer>
  </section>
</template>

<style scoped>
.step-wizard {
  display: flex;
  flex-direction: column;
  background: var(--ptmcp-surface);
  border: 1px solid var(--ptmcp-border-row);
  border-radius: 12px;
  box-shadow: var(--ptmcp-shadow);
  overflow: hidden;
}

/* ── 步骤条 ── */
.step-wizard__rail {
  display: flex;
  align-items: flex-start;
  margin: 0;
  padding: 20px 24px 16px;
  list-style: none;
  overflow-x: auto;
  scrollbar-width: thin;
}
.step-wizard__step {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  flex: 0 0 auto;
  min-width: 72px;
}
.step-wizard__node {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  font-size: 14px;
  font-weight: 600;
  line-height: 1;
  transition:
    background-color 0.2s ease,
    color 0.2s ease;
}
.step-wizard__step[data-state='done'] .step-wizard__node,
.step-wizard__step[data-state='active'] .step-wizard__node {
  background: var(--ptmcp-blue);
  color: #fff;
}
.step-wizard__step[data-state='todo'] .step-wizard__node {
  background: var(--ptmcp-bg-neutral);
  color: var(--ptmcp-text-muted);
}
.step-wizard__check {
  width: 16px;
  height: 16px;
}
.step-wizard__title {
  font-size: 12px;
  line-height: 1.3;
  text-align: center;
  color: var(--ptmcp-text-muted);
  max-width: 120px;
  overflow-wrap: anywhere;
}
.step-wizard__step[data-state='active'] .step-wizard__title {
  color: var(--ptmcp-heading);
  font-weight: 600;
}
.step-wizard__link {
  flex: 1 0 32px;
  height: 2px;
  min-width: 32px;
  margin-top: 15px; /* node 32px 中心对齐 */
  background: var(--ptmcp-border);
}
.step-wizard__link[data-state='done'] {
  background: var(--ptmcp-blue);
}

/* ── 内容区 ── */
.step-wizard__body {
  flex: 1 1 auto;
  min-height: 0;
  padding: 8px 24px 24px;
}

/* ── 底部操作条 ── */
.step-wizard__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 24px;
  border-top: 1px solid var(--ptmcp-border-row);
  background: var(--ptmcp-surface);
}
.step-wizard__bar-right {
  display: flex;
  gap: 8px;
}
.step-wizard__btn {
  border-radius: var(--ptmcp-radius-control);
  font-weight: 600;
  padding: 8px 20px;
  height: auto;
}
.step-wizard__btn--primary:not(.is-disabled) {
  background: var(--ptmcp-blue);
  border-color: var(--ptmcp-blue);
}
.step-wizard__btn--primary:not(.is-disabled):hover {
  background: var(--ptmcp-blue-dark);
  border-color: var(--ptmcp-blue-dark);
}
.step-wizard__btn--ghost {
  color: var(--ptmcp-text);
  border-color: var(--ptmcp-border-strong);
  background: var(--ptmcp-surface);
}
.step-wizard__btn--ghost:hover {
  color: var(--ptmcp-blue);
  border-color: var(--ptmcp-blue);
  background: var(--ptmcp-blue-soft);
}

@media (max-width: 640px) {
  .step-wizard__rail {
    padding: 16px 16px 12px;
  }
  .step-wizard__body {
    padding: 8px 16px 20px;
  }
  .step-wizard__bar {
    padding: 12px 16px;
  }
}
</style>
