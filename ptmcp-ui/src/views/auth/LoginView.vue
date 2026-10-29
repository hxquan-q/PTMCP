<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { useSessionStore } from '../../stores/session';

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const form = reactive({ username: '', password: '' });
const formRef = ref();
const showPassword = ref(false);
const submitting = ref(false);
const submitError = ref('');
let active = true;

const returnTo = computed(() => {
  const candidate = typeof route.query.returnTo === 'string' ? route.query.returnTo : '/';
  return /^\/(?:admin|me)(?:\/|\?|$)/.test(candidate) &&
    !candidate.includes('\\') &&
    [...candidate].every((character) => character.charCodeAt(0) >= 32)
    ? candidate
    : '/';
});

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^[a-zA-Z0-9_.-]{1,30}$/, message: '用户名格式不正确', trigger: 'blur' },
  ],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
};

async function submit() {
  if (submitting.value) return;
  submitting.value = true;
  submitError.value = '';
  try {
    const valid = await formRef.value?.validate().catch(() => false);
    if (!valid || !active) return;
    await session.login(form.username, form.password);
    form.password = '';
    if (active) await router.replace(returnTo.value);
  } catch (error) {
    form.password = '';
    if (active) {
      submitError.value = error instanceof Error ? error.message : '登录失败，请稍后重试';
      ElMessage.error(submitError.value);
    }
  } finally {
    submitting.value = false;
  }
}
onBeforeUnmount(() => {
  active = false;
  form.password = '';
});
</script>

<template>
  <main class="login-shell">
    <section class="login-panel" aria-labelledby="login-title">
      <div class="login-brand">
        <div class="brand-mark" aria-hidden="true">P</div>
        <div>
          <p class="brand-name">权限问数网关</p>
        </div>
      </div>

      <div class="login-copy">
        <h1 id="login-title">登录控制台</h1>
        <p>管理数据源、目录权限与个人接入配置。</p>
      </div>

      <ElAlert
        v-if="submitError || session.statusError"
        class="login-error"
        :title="submitError || session.statusError || ''"
        type="error"
        show-icon
        :closable="false"
      />

      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <ElFormItem label="用户名" prop="username">
          <ElInput v-model="form.username" autocomplete="username" placeholder="例如：platform.admin" size="large" />
        </ElFormItem>
        <ElFormItem label="密码" prop="password">
          <ElInput
            v-model="form.password"
            :type="showPassword ? 'text' : 'password'"
            autocomplete="current-password"
            placeholder="请输入密码"
            size="large"
          >
            <template #suffix>
              <button
                class="password-toggle"
                type="button"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                @click="showPassword = !showPassword"
              >
                <IconEpView v-if="!showPassword" aria-hidden="true" />
                <IconEpHide v-else aria-hidden="true" />
              </button>
            </template>
          </ElInput>
        </ElFormItem>
        <ElButton class="login-submit" type="primary" native-type="submit" size="large" :loading="submitting"> 进入控制台 </ElButton>
      </ElForm>

      <p class="login-footnote">会话由服务端 Cookie 管理，凭证不会保存在浏览器本地。</p>
    </section>
    <aside class="login-aside" aria-label="PTMCP 产品信息">
      <h2>让每一次数据访问都可解释、可撤销、可追溯。</h2>
      <p class="aside-description">从数据源连接、元数据目录到个人接入，PTMCP 把权限边界放在查询发生之前。</p>
      <div class="aside-note"><span class="note-dot"></span><span>当前环境：控制台预览</span></div>
    </aside>
  </main>
</template>

<style scoped>
.login-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(420px, 0.9fr) minmax(460px, 1.1fr);
  background: var(--ptmcp-surface);
  color: var(--ptmcp-ink);
}
.login-panel {
  display: flex;
  width: min(440px, calc(100% - 64px));
  flex-direction: column;
  justify-content: center;
  margin: 0 auto;
  padding: 56px 0;
}
.login-brand {
  display: flex;
  align-items: center;
  gap: 14px;
}
.brand-mark {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 8px;
  background: var(--ptmcp-blue);
  color: white;
  font-size: 20px;
  font-weight: 700;
}
.brand-name {
  margin: 4px 0 0;
  font-size: 14px;
  font-weight: 700;
}
.login-copy {
  margin: 68px 0 28px;
}
.login-copy h1 {
  margin: 0;
  color: var(--ptmcp-heading);
  font-size: clamp(30px, 4vw, 42px);
  letter-spacing: -0.04em;
}
.login-copy p {
  margin: 12px 0 0;
  color: var(--ptmcp-muted);
  font-size: 15px;
}
.login-submit {
  width: 100%;
  margin-top: 8px;
}
.login-error {
  margin-bottom: 16px;
}
.password-toggle {
  display: inline-flex;
  border: 0;
  background: transparent;
  color: var(--ptmcp-muted);
  cursor: pointer;
  padding: 3px;
}
.password-toggle:focus-visible {
  outline: 2px solid var(--ptmcp-blue);
  outline-offset: 2px;
}
.login-footnote {
  margin: 28px 0 0;
  color: var(--ptmcp-muted);
  font-size: 12px;
  line-height: 1.6;
}
.login-aside {
  position: relative;
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  justify-content: flex-end;
  overflow: hidden;
  background: linear-gradient(118deg, var(--ptmcp-navy) 0%, #0837a5 58%, var(--ptmcp-blue) 100%);
  color: #fff;
  padding: 72px clamp(40px, 8vw, 120px);
}
.login-aside h2 {
  max-width: 640px;
  margin: 20px 0 0;
  font-size: clamp(34px, 4.6vw, 68px);
  line-height: 1.04;
  letter-spacing: -0.04em;
}
.aside-description {
  max-width: 520px;
  margin: 28px 0 0;
  color: rgba(255, 255, 255, 0.72);
  font-size: 15px;
  line-height: 1.7;
}
.aside-note {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 80px;
  color: rgba(255, 255, 255, 0.75);
  font-size: 12px;
}
.note-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ptmcp-sky);
  box-shadow: 0 0 0 5px rgba(107, 192, 255, 0.16);
}
@media (max-width: 860px) {
  .login-shell {
    display: block;
  }
  .login-panel {
    width: min(440px, calc(100% - 40px));
    min-height: 100vh;
    padding: 40px 0;
  }
  .login-copy {
    margin-top: 56px;
  }
  .login-aside {
    display: none;
  }
}
</style>
