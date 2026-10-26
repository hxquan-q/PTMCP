<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { request } from '../../../services/http';
import { controlPlaneAvailable, mutationNotice } from '../../../services/frontendMode';
import { passwordError } from '../../../services/formValidation';
import { errorText, usePortalTask } from '../../portalUtils';
import { ElMessage } from 'element-plus';
const props = defineProps<{ modelValue: boolean; userId: string; version: number; username: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; saved: [] }>();
const open = computed({ get: () => props.modelValue, set: (value) => emit('update:modelValue', value) });
const password = ref('');
const confirm = ref('');
const reason = ref('');
const error = ref('');
const saving = ref(false);
let sequence = 0;
function clear(): void {
  sequence++;
  password.value = '';
  confirm.value = '';
  reason.value = '';
  error.value = '';
}
usePortalTask(() => {
  clear();
  open.value = false;
});
watch(() => [props.modelValue, props.userId], clear, { flush: 'sync' });
async function save(): Promise<void> {
  if (saving.value || !controlPlaneAvailable) return;
  error.value =
    passwordError(password.value) ??
    (password.value !== confirm.value
      ? '两次密码不一致。'
      : reason.value.trim().length < 10 || reason.value.length > 500
        ? '请填写10～500字符的重置理由。'
        : '');
  if (error.value) return;
  const own = sequence;
  saving.value = true;
  try {
    await request(`/api/v1/admin/users/${encodeURIComponent(props.userId)}/password`, {
      method: 'PUT',
      headers: { 'If-Match': `"${props.version}"` },
      body: { newPassword: password.value, reason: reason.value.trim() }
    });
    if (own !== sequence) return;
    clear();
    open.value = false;
    ElMessage.success(mutationNotice);
    emit('saved');
  } catch (cause) {
    if (own === sequence) error.value = errorText(cause);
  } finally {
    password.value = '';
    confirm.value = '';
    saving.value = false;
  }
}
</script>
<template>
  <ElDialog
    v-model="open"
    :title="`重置密码 · ${username}`"
    width="min(560px, calc(100vw - 28px))"
    destroy-on-close
    :close-on-click-modal="false"
    @close="clear"
    ><ElAlert
      type="warning"
      :closable="false"
      :title="controlPlaneAvailable ? '该操作将使用户的会话、OAuth 和 PAT 凭证失效。' : '密码管理接口待接入，正常模式禁止提交。'"
    /><ElForm label-position="top" class="password-form" :disabled="saving" @submit.prevent="save"
      ><ElFormItem label="新密码" required><ElInput v-model="password" type="password" autocomplete="new-password" /></ElFormItem
      ><ElFormItem label="确认密码" required><ElInput v-model="confirm" type="password" autocomplete="new-password" /></ElFormItem
      ><ElFormItem label="重置理由（10～500字符）" required
        ><ElInput v-model="reason" type="textarea" maxlength="500" :rows="3" /></ElFormItem></ElForm
    ><ElAlert v-if="error" type="error" :title="error" :closable="false" /><template #footer
      ><ElButton @click="open = false">取消</ElButton
      ><ElButton type="danger" :disabled="!controlPlaneAvailable" :loading="saving" @click="save">确认重置并使凭证失效</ElButton></template
    ></ElDialog
  >
</template>
<style scoped>
.password-form {
  margin-top: 20px;
}
</style>
