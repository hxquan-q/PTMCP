<script setup lang="ts">
import WorkspaceLayout, { type NavigationItem } from '../components/common/WorkspaceLayout.vue';
import CoinIcon from '~icons/ep/coin';
import CollectionIcon from '~icons/ep/collection';
import UserIcon from '~icons/ep/user-filled';
import AvatarIcon from '~icons/ep/avatar';
import SetUpIcon from '~icons/ep/set-up';
import PlatformIcon from '~icons/ep/platform';
import DocumentIcon from '~icons/ep/document';
const items: NavigationItem[] = [
  { label: '数据源', to: '/admin/datasources', icon: CoinIcon, permission: 'ptmcp:datasource:list' },
  { label: '元数据', to: '/admin/metadata', icon: CollectionIcon, permission: 'ptmcp:metadata:list' },
  { label: '部门', to: '/admin/departments', icon: UserIcon, permission: 'ptmcp:department:list' },
  { label: '用户', to: '/admin/users', icon: UserIcon, permission: 'ptmcp:user:list' },
  { label: '角色', to: '/admin/roles', icon: AvatarIcon, permission: 'ptmcp:role:list' },
  { label: '委派与权限', to: '/admin/permissions', icon: SetUpIcon, permission: 'ptmcp:acl:list' },
  { label: '平台与客户端', to: '/admin/platforms', icon: PlatformIcon, permission: 'ptmcp:platform:list' },
  { label: '审计记录', to: '/admin/audit', icon: DocumentIcon, permission: 'ptmcp:audit:list' }
];
</script>
<template><WorkspaceLayout mode="admin" title="控制面" subtitle="权限、资源与运行状态" :items="items" /></template>
