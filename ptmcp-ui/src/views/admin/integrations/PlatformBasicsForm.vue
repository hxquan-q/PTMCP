<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue';
import { ElAlert, ElButton, ElForm, ElFormItem, ElInput, ElOption, ElSelect } from 'element-plus';
import { validateRequired } from './adminSurface';

const props = withDefaults(defineProps<{ editing?: boolean }>(), { editing: false });
const form = reactive({ code: '', name: '', tier: '3', catalogMode: 'HIDE', proofMode: 'NONE', status: 'DISABLED' });
const errors = ref<Record<string, string>>({});
const message = ref('');
const proofHint = computed(() =>
  form.proofMode === 'MTLS'
    ? '由服务端登记证书指纹与 TLS 约束。'
    : form.proofMode === 'PROXY_SECRET'
      ? '由服务端登记代理网络约束与只写 secret。'
      : '没有逐调用证明；Tier 1 不允许使用。'
);
watch(form, () => {
  errors.value = {};
  message.value = '';
});
function validate(): void {
  const values: Record<string, string> = props.editing ? { name: form.name } : { code: form.code, name: form.name };
  errors.value = validateRequired(values, { code: '平台 code', name: '平台名称' });
  if (form.tier === '1' && form.proofMode === 'NONE') errors.value.proofMode = 'Tier 1 必须配置逐调用证明。';
  message.value = Object.keys(errors.value).length ? '请检查标出的字段。' : '基础字段校验完成。证明配置需服务端核验，当前尚未保存。';
}
</script>

<template>
  <ElForm label-position="top" @submit.prevent="validate">
    <ElAlert
      class="form-notice"
      type="info"
      show-icon
      :closable="false"
      title="本地表单预览"
      description="平台管理服务尚未接入，输入只在当前表单保留，保存按钮暂不可用。"
    />
    <div class="two-col">
      <ElFormItem label="平台 code" required :error="errors.code"
        ><ElInput
          v-model="form.code"
          :disabled="editing"
          maxlength="64"
          :placeholder="editing ? '现有 code 尚未读取，创建后不可修改' : '平台唯一标识'"
      /></ElFormItem>
      <ElFormItem label="平台名称" required :error="errors.name"
        ><ElInput v-model="form.name" maxlength="128" placeholder="输入平台名称"
      /></ElFormItem>
    </div>
    <div class="three-col">
      <ElFormItem label="信任等级"
        ><ElSelect v-model="form.tier"
          ><ElOption label="Tier 1 · 受信后端" value="1" /><ElOption label="Tier 2 · 受控客户端" value="2" /><ElOption
            label="Tier 3 · 公开客户端"
            value="3" /></ElSelect
      ></ElFormItem>
      <ElFormItem label="目录模式"
        ><ElSelect v-model="form.catalogMode"
          ><ElOption label="HIDE · 仅可读目录" value="HIDE" /><ElOption label="DISCOVER · 可发现目录" value="DISCOVER" /></ElSelect
      ></ElFormItem>
      <ElFormItem label="状态"
        ><ElSelect v-model="form.status"><ElOption label="启用" value="ACTIVE" /><ElOption label="停用" value="DISABLED" /></ElSelect
      ></ElFormItem>
    </div>
    <p class="helper">DISCOVER 只影响目录发现，不授予列结构或查询权限；显式 META DENY 仍优先。</p>
    <ElFormItem label="逐调用证明方式" :error="errors.proofMode"
      ><ElSelect v-model="form.proofMode"
        ><ElOption label="MTLS · 双向 TLS" value="MTLS" /><ElOption label="PROXY_SECRET · 受控代理" value="PROXY_SECRET" /><ElOption
          label="NONE · 无证明"
          value="NONE" /></ElSelect
    ></ElFormItem>
    <ElFormItem label="证明配置"
      ><ElInput disabled :placeholder="proofHint" />
      <p class="helper">证明配置接口接入后仅展示“已配置”。本表单不收集 secret。</p></ElFormItem
    >
    <ElAlert
      type="warning"
      :closable="false"
      title="信任方式变更需要重新批准"
      description="private_key_jwt 仅证明 token 端点身份，不能替代每次 MCP 调用的证明。"
    />
    <p v-if="message" class="form-result" role="status">{{ message }}</p>
    <div class="form-actions"><ElButton native-type="submit">校验字段</ElButton><ElButton type="primary" disabled>保存平台（待接入）</ElButton></div>
  </ElForm>
</template>

<style scoped>
.two-col,
.three-col {
  display: grid;
  gap: 14px;
}
.two-col {
  grid-template-columns: 1fr 1fr;
}
.three-col {
  grid-template-columns: repeat(3, 1fr);
}
.form-notice {
  margin-bottom: 18px;
}
.helper {
  margin: 6px 0 14px;
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
  .two-col,
  .three-col {
    grid-template-columns: 1fr;
  }
}
</style>
