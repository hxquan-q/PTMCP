<script setup lang="ts">
import { reactive, ref, watch } from 'vue';
import { ElAlert, ElButton, ElCheckbox, ElCheckboxGroup, ElForm, ElFormItem, ElInput, ElOption, ElSelect } from 'element-plus';
import { allowedClientScopes, validateAllowedScopes, validateRedirectUris, validateRequired } from './adminSurface';

const form = reactive({
  name: '',
  type: 'PUBLIC',
  authMethods: ['none'] as string[],
  redirectUris: '',
  allowedScopes: ['mcp:catalog'] as string[],
  jwksUri: '',
  status: 'DISABLED'
});
const errors = ref<Record<string, string>>({});
const message = ref('');
watch(
  () => form.type,
  (type) => {
    form.authMethods = type === 'PUBLIC' ? ['none'] : ['private_key_jwt'];
    form.jwksUri = '';
  }
);
watch(form, () => {
  errors.value = {};
  message.value = '';
});
function validate(): void {
  errors.value = validateRequired({ name: form.name }, { name: '客户端名称' });
  const uriError = validateRedirectUris(form.redirectUris);
  if (uriError) errors.value.redirectUris = uriError;
  const scopeError = validateAllowedScopes(form.allowedScopes);
  if (scopeError) errors.value.allowedScopes = scopeError;
  if (!form.authMethods.length) errors.value.authMethods = '至少选择一种认证方式。';
  if (form.type === 'CONFIDENTIAL' && form.authMethods.includes('private_key_jwt')) {
    try {
      const uri = new URL(form.jwksUri);
      if (uri.protocol !== 'https:' || (uri.port && uri.port !== '443') || uri.username || uri.password || uri.hash) throw new Error();
    } catch {
      errors.value.jwksUri = 'private_key_jwt 需要有效的 HTTPS/443 JWKS URI，且不能含凭据或片段。';
    }
  }
  message.value = Object.keys(errors.value).length
    ? '请检查标出的字段。'
    : '客户端字段校验完成。回调 ownership、Scope 审批及 JWKS 网络策略仍需服务端核验，尚未保存。';
}
</script>

<template>
  <ElForm label-position="top" @submit.prevent="validate">
    <ElAlert
      class="form-notice"
      type="info"
      show-icon
      :closable="false"
      title="客户端管理服务待接入"
      description="可检查配置字段。secret 不在当前表单中输入；机密客户端创建后仅由服务端一次性返回。"
    />
    <div class="two-col">
      <ElFormItem label="客户端名称" required :error="errors.name"><ElInput v-model="form.name" maxlength="128" /></ElFormItem
      ><ElFormItem label="客户端类型"
        ><ElSelect v-model="form.type"
          ><ElOption label="PUBLIC · 公开客户端" value="PUBLIC" /><ElOption label="CONFIDENTIAL · 机密客户端" value="CONFIDENTIAL" /></ElSelect
      ></ElFormItem>
    </div>
    <ElFormItem label="认证方式" required :error="errors.authMethods"
      ><ElCheckboxGroup v-model="form.authMethods"
        ><ElCheckbox v-if="form.type === 'PUBLIC'" value="none" disabled>none · 配合 PKCE S256</ElCheckbox
        ><template v-else
          ><ElCheckbox value="private_key_jwt">private_key_jwt</ElCheckbox
          ><ElCheckbox value="client_secret_basic">client_secret_basic（需审核）</ElCheckbox
          ><ElCheckbox value="tls_client_auth">mTLS</ElCheckbox></template
        ></ElCheckboxGroup
      ></ElFormItem
    >
    <ElFormItem label="精确回调 URI" required :error="errors.redirectUris"
      ><ElInput v-model="form.redirectUris" type="textarea" :rows="4" maxlength="8192" placeholder="每行一个完整 URI，不使用通配符" />
      <p class="helper">
        Web 回调使用 HTTPS；loopback 仅 127.0.0.1/[::1]。当前 custom scheme 只校验已知 workbuddy，其他客户端需完成兼容验证。
      </p></ElFormItem
    >
    <ElFormItem label="允许 Scope" required :error="errors.allowedScopes"
      ><ElCheckboxGroup v-model="form.allowedScopes"
        ><ElCheckbox v-for="scope in allowedClientScopes" :key="scope" :value="scope">{{ scope }}</ElCheckbox></ElCheckboxGroup
      >
      <p class="helper">默认仅 mcp:catalog。选择更多 Scope 需服务端审批，不会增加平台资源范围。</p></ElFormItem
    >
    <ElFormItem v-if="form.type === 'CONFIDENTIAL' && form.authMethods.includes('private_key_jwt')" label="JWKS URI" required :error="errors.jwksUri"
      ><ElInput v-model="form.jwksUri" placeholder="https://受批准公网域名/jwks.json" />
      <p class="helper">服务端还须校验批准域名、全部 DNS 解析地址、TLS 与响应预算。</p></ElFormItem
    >
    <ElFormItem label="状态"
      ><ElSelect v-model="form.status"><ElOption label="启用" value="ACTIVE" /><ElOption label="停用" value="DISABLED" /></ElSelect
    ></ElFormItem>
    <ElAlert
      type="warning"
      :closable="false"
      title="配置变更影响现有授权"
      description="变更回调、认证方式或删除客户端会撤销关联授权族，接入端需重新授权。"
    />
    <p v-if="message" class="form-result" role="status">{{ message }}</p>
    <div class="form-actions">
      <ElButton native-type="submit">校验字段</ElButton><ElButton type="primary" disabled>保存客户端（待接入）</ElButton>
    </div>
  </ElForm>
</template>

<style scoped>
.two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.form-notice {
  margin-bottom: 18px;
}
.helper {
  margin: 6px 0 0;
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
.form-result {
  padding: 12px;
  background: var(--ptmcp-blue-soft);
  color: var(--ptmcp-text);
  font-size: 13px;
  line-height: 1.6;
}
.form-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 18px;
}
.el-select {
  width: 100%;
}
@media (max-width: 700px) {
  .two-col {
    grid-template-columns: 1fr;
  }
}
</style>
