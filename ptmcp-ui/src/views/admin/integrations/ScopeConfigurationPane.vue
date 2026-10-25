<script setup lang="ts">
import { ref } from 'vue';
import { ElAlert, ElButton, ElCheckbox, ElForm, ElFormItem, ElOption, ElSelect, ElTable, ElTableColumn, ElTag } from 'element-plus';
const resource = ref('');
const action = ref('META');
const includeDescendants = ref(false);
</script>

<template>
  <section aria-labelledby="scope-title">
    <div class="section-heading">
      <div>
        <h2 id="scope-title">平台资源范围</h2>
        <p>资源范围与用户有效权限取交集。尚未读取 Scope，不能据此判断平台可读哪些数据。</p>
      </div>
      <ElTag type="info" effect="plain">尚未读取</ElTag>
    </div>
    <ElAlert
      class="notice"
      type="warning"
      show-icon
      :closable="false"
      title="Scope 读取与保存接口待接入"
      description="资源 generation 必须由当前快照返回。不会用手填 ID 或旧资源身份替代受控选择。"
    />
    <div class="scope-layout">
      <aside class="resource-panel">
        <h3>资源树</h3>
        <p>数据源 → catalog（分组） → 命名空间 → 对象</p>
        <div class="resource-empty">资源目录尚未接入</div>
        <p>PG catalog 仅分组，无授权复选框。</p>
      </aside>
      <ElForm class="scope-form" label-position="top" @submit.prevent
        ><ElFormItem label="目标资源" required><ElSelect v-model="resource" disabled placeholder="等待受控资源目录" /></ElFormItem
        ><ElFormItem label="动作"
          ><ElSelect v-model="action"
            ><ElOption label="META · 目录信息" value="META" /><ElOption label="READ · 读取数据" value="READ" /></ElSelect></ElFormItem
        ><ElFormItem label="继承范围"><ElCheckbox v-model="includeDescendants">包含当前及未来子级</ElCheckbox></ElFormItem>
        <p>只能提交 DATASOURCE、NAMESPACE 或 OBJECT。每次整组最多100条；保存前应显示新增、修改、删除差异。</p>
        <ElButton disabled>添加到待提交差异（资源待接入）</ElButton></ElForm
      >
    </div>
    <ElTable :data="[]" aria-label="Scope 规则"
      ><ElTableColumn label="资源" min-width="220" /><ElTableColumn label="generation" min-width="140" /><ElTableColumn
        label="动作"
        width="100"
      /><ElTableColumn label="继承" width="100" /><template #empty
        ><div class="empty-message">Scope 尚未读取，不代表 Scope 为空。</div></template
      ></ElTable
    >
    <div class="scope-footer">
      <span>scopeVersion / securityVersion 尚未返回</span><ElButton type="primary" disabled>保存 Scope（待接入）</ElButton>
    </div>
  </section>
</template>

<style scoped>
.section-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}
h2 {
  margin: 0;
  font-size: 16px;
}
h3 {
  margin: 0;
  font-size: 14px;
}
.section-heading p,
.resource-panel p,
.scope-form p,
.scope-footer span {
  margin: 8px 0 0;
  color: var(--ptmcp-text-muted);
  font-size: 12px;
  line-height: 1.6;
}
.notice {
  margin: 18px 0;
}
.scope-layout {
  display: grid;
  grid-template-columns: minmax(200px, 1fr) minmax(240px, 1.4fr);
  gap: 24px;
  margin-bottom: 24px;
}
.resource-panel {
  padding: 16px;
  border: 1px solid var(--ptmcp-border);
  border-radius: 6px;
  background: #f8fafc;
}
.resource-empty {
  display: grid;
  place-items: center;
  min-height: 170px;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
}
.scope-form .el-select {
  width: 100%;
}
.scope-footer {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 18px;
}
.empty-message {
  padding: 35px 16px;
  color: var(--ptmcp-text-muted);
  font-size: 13px;
}
@media (max-width: 700px) {
  .scope-layout {
    grid-template-columns: 1fr;
  }
  .resource-empty {
    min-height: 90px;
  }
}
</style>
