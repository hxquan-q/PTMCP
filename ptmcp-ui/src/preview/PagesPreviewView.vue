<script setup lang="ts">
const groups = [
  {
    title: '数据管理',
    pages: [
      ['数据源列表', '/admin/datasources'],
      ['数据源详情', '/admin/datasources/1'],
      ['同步任务', '/admin/jobs/1'],
      ['元数据目录', '/admin/metadata'],
      ['业务字典编辑', '/admin/metadata/1?datasourceId=1'],
    ],
  },
  {
    title: '组织与权限',
    pages: [
      ['部门', '/admin/departments'],
      ['用户', '/admin/users'],
      ['用户详情', '/admin/users/2'],
      ['角色', '/admin/roles'],
      ['角色详情', '/admin/roles/2'],
      ['委派', '/admin/delegations'],
      ['授权矩阵', '/admin/permissions'],
      ['权限预览', '/admin/permissions/preview'],
    ],
  },
  {
    title: '平台与审计',
    pages: [
      ['平台列表', '/admin/platforms'],
      ['平台 / Scope / 客户端', '/admin/platforms/1'],
      ['全局审计', '/admin/audit'],
      ['审计详情 / 限时解密', '/admin/audit/preview-request-1'],
    ],
  },
  {
    title: '个人门户',
    pages: [
      ['数据字典', '/me/dictionary'],
      ['字典详情', '/me/dictionary/1?platformId=1'],
      ['接入配置', '/me/integrations'],
      ['个人令牌', '/me/tokens'],
      ['我的授权', '/me/authorizations'],
      ['查询历史', '/me/history'],
      ['历史详情', '/me/history/preview-request-1'],
      ['账号安全', '/me/security'],
    ],
  },
  {
    title: '认证与系统状态',
    pages: [
      ['控制台登录', '/__preview/login'],
      ['AS 登录 / 授权同意', '/__preview/authorization'],
      ['无权限', '/403'],
      ['页面不存在', '/404'],
    ],
  },
];
</script>
<template>
  <main class="preview-pages">
    <h1>前端页面工作台</h1>
    <p>所有记录均为合成数据，操作只保留在当前浏览器内存，刷新后重置，不连接业务后端。上方可切换空、错误、冲突与慢速状态。</p>
    <section v-for="group in groups" :key="group.title">
      <h2>{{ group.title }}</h2>
      <ul>
        <li v-for="[title, path] in group.pages" :key="path">
          <RouterLink :to="path">{{ title }}</RouterLink
          ><code>{{ path }}</code>
        </li>
      </ul>
    </section>
  </main>
</template>
<style scoped>
.preview-pages {
  max-width: 1100px;
  margin: 0 auto;
  padding: 84px 24px 60px;
}
.preview-pages h1 {
  font-size: 30px;
  font-weight: 600;
  margin: 0 0 12px;
  color: var(--ptmcp-heading);
}
.preview-pages > p {
  color: var(--ptmcp-text-muted);
  line-height: 1.8;
}
.preview-pages section {
  margin-top: 32px;
}
.preview-pages h2 {
  font-size: 18px;
}
.preview-pages ul {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  list-style: none;
  padding: 0;
  gap: 0 30px;
}
.preview-pages li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  border-bottom: 1px solid var(--ptmcp-border);
  padding: 16px 0;
}
.preview-pages a {
  color: var(--ptmcp-blue);
  font-size: 14px;
}
.preview-pages code {
  font-size: 11px;
  overflow-wrap: anywhere;
  color: var(--ptmcp-text-muted);
}
@media (max-width: 700px) {
  .preview-pages {
    padding-top: 104px;
  }
  .preview-pages ul {
    grid-template-columns: 1fr;
  }
  .preview-pages li {
    align-items: flex-start;
    flex-direction: column;
    gap: 5px;
  }
}
</style>
