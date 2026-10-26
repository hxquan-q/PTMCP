<script setup lang="ts">
import { onBeforeUnmount, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useSessionStore } from '../../stores/session';
import { errorText, formatDate, usePortalTask } from '../portalUtils';
import { passwordError } from '../../services/formValidation';

const router = useRouter();
const session = useSessionStore();
const saving = ref(false);
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' });
function clearPasswords(): void {
  form.oldPassword = '';
  form.newPassword = '';
  form.confirmPassword = '';
}
usePortalTask(clearPasswords);
async function changePassword(): Promise<void> {
  if (saving.value) return;
  if (!form.oldPassword) {
    ElMessage.warning('请输入当前密码');
    return;
  }
  if (passwordError(form.newPassword)) {
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
    await session.changePassword(form.oldPassword, form.newPassword);
    ElMessage.success('密码已修改，请重新登录');
    router.replace({ name: 'login', query: { returnTo: '/me/security' } });
  } catch (error) {
    ElMessage.error(errorText(error));
  } finally {
    saving.value = false;
  }
}

onBeforeUnmount(() => {
  clearPasswords();
});
</script>

<template>
  <section class="portal-page" aria-labelledby="security-title">
    <div class="page-heading">
      <div>
        <h1 id="security-title">安全设置</h1>
        <p class="page-description">修改密码后，当前账号的其他会话、PAT 和授权会按服务端规则失效。</p>
      </div>
    </div>
    <ElCard shadow="never" class="portal-card account-summary"
      ><template #header>当前账号</template
      ><ElDescriptions :column="1" border
        ><ElDescriptionsItem label="用户名">{{ session.user?.username ?? '—' }}</ElDescriptionsItem
        ><ElDescriptionsItem label="显示名称">{{ session.user?.displayName ?? '—' }}</ElDescriptionsItem
        ><ElDescriptionsItem label="部门">{{ session.user?.department?.name ?? '未提供' }}</ElDescriptionsItem
        ><ElDescriptionsItem label="会话到期（台北）">{{ formatDate(session.user?.expiresAt) }}</ElDescriptionsItem></ElDescriptions
      ></ElCard
    >
    <ElCard shadow="never" class="portal-card"
      ><template #header>修改密码</template
      ><ElForm label-position="top" class="security-form" :disabled="saving" @submit.prevent="changePassword"
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
h1 {
  margin: 0;
  color: var(--ptmcp-heading);
  font-size: 28px;
}
.page-description {
  margin: 8px 0 0;
  color: var(--ptmcp-muted);
  font-size: 14px;
}
.portal-card {
  max-width: 680px;
  border-color: var(--ptmcp-border);
}
.account-summary {
  margin-bottom: 20px;
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
