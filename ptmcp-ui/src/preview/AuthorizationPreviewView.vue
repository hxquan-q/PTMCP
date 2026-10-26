<script setup lang="ts">
import { ref, reactive, onBeforeUnmount } from 'vue';
const step = ref('login');
const result = ref('');
const form = reactive({ username: '', password: '' });
const selected = ref(['mcp:catalog']);
onBeforeUnmount(() => {
  form.password = '';
});
function login(): void {
  if (!form.username || !form.password) {
    result.value = '请输入演示用户名和密码（不发送到后端）。';
    return;
  }
  form.password = '';
  result.value = '';
  step.value = 'consent';
}
function decide(allowed: boolean): void {
  result.value = allowed ? '前端演示：已确认所选 Scope；未签发授权码或令牌。' : '前端演示：已拒绝（access_denied）；未创建任何授权。';
  step.value = 'result';
  form.password = '';
}
</script>
<template>
  <main class="authorization-preview">
    <ElCard shadow="never"
      ><h1>{{ step === 'login' ? '登录授权服务' : step === 'consent' ? '确认客户端授权' : '授权演示结果' }}</h1>
      <ElAlert type="info" :closable="false" title="独立 AS 页面前端预览。真实登录/授权由授权服务承载，此页不发送 OAuth 请求。" /><ElForm
        v-if="step === 'login'"
        label-position="top"
        class="form"
        @submit.prevent="login"
        ><ElFormItem label="用户名"><ElInput v-model="form.username" autocomplete="off" /></ElFormItem
        ><ElFormItem label="演示密码"><ElInput v-model="form.password" type="password" autocomplete="off" /></ElFormItem
        ><ElButton native-type="submit" type="primary">进入同意页预览</ElButton></ElForm
      ><template v-else-if="step === 'consent'"
        ><ElDescriptions :column="1" border class="form"
          ><ElDescriptionsItem label="客户端">合成公开客户端 / preview-public</ElDescriptionsItem
          ><ElDescriptionsItem label="平台">合成桌面平台 · Tier 3</ElDescriptionsItem
          ><ElDescriptionsItem label="精确回调">http://127.0.0.1:3000/callback</ElDescriptionsItem
          ><ElDescriptionsItem label="有效期">由授权服务策略决定，当前未签发</ElDescriptionsItem></ElDescriptions
        >
        <h2>请求权限</h2>
        <ElCheckboxGroup v-model="selected"
          ><ElCheckbox value="mcp:catalog">mcp:catalog · 发现目录</ElCheckbox><ElCheckbox value="mcp:schema">mcp:schema · 读取已授权结构</ElCheckbox
          ><ElCheckbox value="mcp:query">mcp:query · 只读查询已授权对象</ElCheckbox></ElCheckboxGroup
        >
        <p>Scope 不扩大平台或用户权限。拒绝会返回 access_denied，不创建授权。</p>
        <div class="actions">
          <ElButton @click="decide(false)">拒绝</ElButton
          ><ElButton type="primary" :disabled="!selected.length" @click="decide(true)">允许所选权限</ElButton>
        </div></template
      ><ElAlert v-if="result" :title="result" :closable="false" /><RouterLink to="/__preview/pages">返回全部页面</RouterLink></ElCard
    >
  </main>
</template>
<style scoped>
.authorization-preview {
  max-width: 680px;
  margin: 0 auto;
  padding: 96px 20px 60px;
}
h1 {
  font-size: 24px;
  margin: 0 0 20px;
  color: var(--ptmcp-heading);
}
h2 {
  font-size: 16px;
  margin-top: 24px;
}
.form {
  margin: 22px 0;
}
.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin: 20px 0;
}
p {
  font-size: 13px;
  line-height: 1.7;
  color: var(--ptmcp-text-muted);
}
a {
  display: inline-block;
  color: var(--ptmcp-blue);
  margin-top: 24px;
}
.el-checkbox-group {
  display: grid;
}
@media (max-width: 600px) {
  .authorization-preview {
    padding-top: 104px;
  }
}
</style>
