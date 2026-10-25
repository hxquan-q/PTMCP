<script setup lang="ts">
import { reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { mePortalApi } from '../../services/mePortalApi';
import { errorText } from '../portalUtils';

const router = useRouter();
const saving = ref(false);
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });
function byteLength(value: string): number {
  return new TextEncoder().encode(value).length;
}
async function changePassword(): Promise<void> {
  if (form.newPassword.length < 12 || form.newPassword.length > 64 || byteLength(form.newPassword) > 72) {
    ElMessage.warning('新密码需 12～64 个字符且 UTF-8 不超过 72 字节');
    return;
  }
  if (form.newPassword !== form.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致');
    return;
  }
  if (form.oldPassword === form.newPassword) {
    ElMessage.warning('新密码不能与旧密码相同');
    return;
  }
  saving.value = true;
  try {
    await mePortalApi.changePassword(form.oldPassword, form.newPassword);
    ElMessage.success('密码已修改，请重新登录');
    router.replace({ name: 'login', query: { returnTo: '/me/security' } });
  } catch (error) {
    ElMessage.error(errorText(error));
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <section class="portal-page" aria-labelledby="security-title">
    <div class="page-heading">
      <div>
        <p class="eyebrow">账号安全</p>
        <h1 id="security-title">安全设置</h1>
        <p class="page-description">修改密码后，当前账号的其他会话、PAT 和授权会按服务端规则失效。</p>
      </div>
    </div>
    <ElCard shadow="never" class="portal-card"
      ><template #header>修改密码</template
      ><ElForm label-position="top" class="security-form" @submit.prevent="changePassword"
        ><ElFormItem label="当前密码" required
          ><ElInput v-model="form.oldPassword" type="password" show-password autocomplete="current-password" /></ElFormItem
        ><ElFormItem label="新密码" required
          ><ElInput v-model="form.newPassword" type="password" show-password autocomplete="new-password" /></ElFormItem
        ><ElFormItem label="确认新密码" required
          ><ElInput v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" /></ElFormItem
        ><ElAlert type="info" :closable="false" title="密码长度 12～64 个字符，UTF-8 不超过 72 字节。密码只在当前页面内存中使用。" />
        <div class="form-actions"><ElButton type="primary" :loading="saving" @click="changePassword">修改密码</ElButton></div></ElForm
      ></ElCard
    >
  </section>
</template>

<style scoped>
.page-heading {
  margin-bottom: 24px;
}
.eyebrow {
  margin: 0 0 6px;
  color: var(--ptmcp-accent, #0070c9);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}
h1 {
  margin: 0;
  color: var(--ptmcp-ink, #1d2939);
  font-size: 28px;
}
.page-description {
  margin: 8px 0 0;
  color: var(--ptmcp-muted, #667085);
  font-size: 14px;
}
.portal-card {
  max-width: 680px;
  border-color: var(--ptmcp-border, #d9e2ec);
}
.security-form {
  max-width: 520px;
}
.form-actions {
  display: flex;
  justify-content: flex-end;
  margin-top: 22px;
}
</style>
