<script setup lang="ts">
import { ref, computed, watch, onBeforeUnmount } from 'vue';
import { controlPlane, type DecryptedAudit } from '../../../services/controlPlane';
import { errorText } from '../../portalUtils';
const props = defineProps<{ modelValue: boolean; requestId: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; revealed: [] }>();
const open = computed({
  get: () => props.modelValue,
  set: (value) => {
    if (!value) clear();
    emit('update:modelValue', value);
  },
});
const reason = ref('');
const fields = ref(['sqlRaw']);
const value = ref<DecryptedAudit | null>(null);
const loading = ref(false);
const error = ref('');
const remaining = ref(0);
let controller: AbortController | null = null;
let timer: ReturnType<typeof setInterval> | undefined;
let sequence = 0;
const valid = computed(() => reason.value.trim().length >= 10 && reason.value.length <= 500 && fields.value.length > 0);
function clear(): void {
  sequence++;
  controller?.abort();
  controller = null;
  if (timer) clearInterval(timer);
  timer = undefined;
  value.value = null;
  reason.value = '';
  fields.value = ['sqlRaw'];
  loading.value = false;
  error.value = '';
  remaining.value = 0;
}
async function reveal(): Promise<void> {
  if (!valid.value || loading.value || !open.value) return;
  const own = ++sequence;
  controller = new AbortController();
  loading.value = true;
  error.value = '';
  value.value = null;
  try {
    const result = await controlPlane.audit.decrypt(props.requestId, reason.value.trim(), [...fields.value], controller.signal);
    if (own !== sequence || !open.value) return;
    emit('revealed');
    const expiry = new Date(result.expiresAt).getTime();
    if (!Number.isFinite(expiry) || expiry <= Date.now()) {
      error.value = '明文已过期，请重新申请。';
      return;
    }
    value.value = result;
    const tick = (): void => {
      remaining.value = Math.max(0, Math.ceil((expiry - Date.now()) / 1000));
      if (!remaining.value) {
        value.value = null;
        if (timer) clearInterval(timer);
        timer = undefined;
        error.value = '明文展示已到期并清除。';
      }
    };
    tick();
    timer = setInterval(tick, 250);
  } catch (cause) {
    if (own === sequence) error.value = errorText(cause);
  } finally {
    if (own === sequence) loading.value = false;
  }
}
const onClear = (): void => {
  clear();
  emit('update:modelValue', false);
};
watch(() => [props.modelValue, props.requestId], clear, { flush: 'sync' });
window.addEventListener('ptmcp:sensitive-clear', onClear);
onBeforeUnmount(() => {
  clear();
  window.removeEventListener('ptmcp:sensitive-clear', onClear);
});
</script>
<template>
  <ElDialog
    v-model="open"
    title="申请临时查看原始 SQL"
    width="min(720px, calc(100vw - 28px))"
    destroy-on-close
    :close-on-click-modal="false"
    @close="clear"
  >
    <ElAlert type="warning" :closable="false" title="敏感访问会单独审计。明文不下载、不缓存，关闭、过期、离页或会话失效立即清除。" />
    <ElForm v-if="!value" label-position="top" class="decrypt-form" @submit.prevent="reveal"
      ><ElFormItem label="排查理由（10～500字符）" required
        ><ElInput v-model="reason" type="textarea" :rows="3" maxlength="500" show-word-limit :disabled="loading" /></ElFormItem
      ><ElFormItem label="申请字段" required
        ><ElCheckboxGroup v-model="fields" :disabled="loading"
          ><ElCheckbox value="sqlRaw">sqlRaw · 原始请求</ElCheckbox><ElCheckbox value="sqlFinal">sqlFinal · 执行语句</ElCheckbox></ElCheckboxGroup
        ></ElFormItem
      ><ElButton type="primary" native-type="submit" :disabled="!valid" :loading="loading">提交理由并申请</ElButton></ElForm
    >
    <ElAlert v-if="error" type="error" :title="error" :closable="false" />
    <section v-if="value" class="plaintext">
      <p role="status" aria-live="polite">明文已解锁。剩余 {{ remaining }} 秒，到期自动清除（expiresAt：{{ value.expiresAt }}）。</p>
      <template v-if="value.sqlRaw"
        ><h3>sqlRaw</h3>
        <pre>{{ value.sqlRaw }}</pre></template
      ><template v-if="value.sqlFinal"
        ><h3>sqlFinal</h3>
        <pre>{{ value.sqlFinal }}</pre>
      </template>
    </section>
    <template #footer><ElButton @click="open = false">关闭并清除</ElButton></template>
  </ElDialog>
</template>
<style scoped>
.decrypt-form {
  margin-top: 20px;
}
.plaintext {
  margin-top: 16px;
}
.plaintext pre {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  padding: 16px;
  background: var(--ptmcp-blue-soft);
}
.plaintext p {
  font-size: 13px;
  color: var(--ptmcp-text-muted);
}
</style>
