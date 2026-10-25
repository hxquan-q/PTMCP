<script setup lang="ts">
import WorkspaceLayout, { type NavigationItem } from '../components/common/WorkspaceLayout.vue';
const items: NavigationItem[] = [
  { label: '数据源', to: '/admin/datasources', icon: 'IconEpCoin', permission: 'ptmcp:datasource:list' },
  { label: '元数据', to: '/admin/metadata', icon: 'IconEpCollection', permission: 'ptmcp:metadata:list' },
  { label: '组织与用户', to: '/admin/departments', icon: 'IconEpUserFilled', permission: 'ptmcp:department:list' },
  { label: '角色', to: '/admin/roles', icon: 'IconEpAvatar', permission: 'ptmcp:role:list' },
  { label: '委派与权限', to: '/admin/permissions', icon: 'IconEpSetUp', permission: 'ptmcp:acl:list' },
  { label: '平台与客户端', to: '/admin/platforms', icon: 'IconEpPlatform', permission: 'ptmcp:platform:list' },
  { label: '审计记录', to: '/admin/audit', icon: 'IconEpDocument', permission: 'ptmcp:audit:list' }
];
</script>
<template><WorkspaceLayout mode="admin" title="控制面" subtitle="权限、资源与运行状态" :items="items" /></template>
