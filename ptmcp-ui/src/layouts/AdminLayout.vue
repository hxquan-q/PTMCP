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
  { label: '数据源', to: '/admin/datasources', icon: CoinIcon, permission: 'ptmcp:datasource:list', group: '数据管理' },
  { label: '元数据与字典', to: '/admin/metadata', icon: CollectionIcon, permission: 'ptmcp:metadata:list', group: '数据管理' },
  { label: '授权矩阵', to: '/admin/permissions', icon: SetUpIcon, permission: 'ptmcp:acl:list', group: '访问控制' },
  { label: '管理委派', to: '/admin/delegations', icon: SetUpIcon, permission: 'ptmcp:delegation:list', group: '访问控制' },
  { label: '部门', to: '/admin/departments', icon: UserIcon, permission: 'ptmcp:department:list', group: '组织与接入' },
  { label: '用户', to: '/admin/users', icon: UserIcon, permission: 'ptmcp:user:list', group: '组织与接入' },
  { label: '角色管理', to: '/admin/roles', icon: AvatarIcon, permission: 'ptmcp:role:list', group: '组织与接入' },
  { label: '平台与客户端', to: '/admin/platforms', icon: PlatformIcon, permission: 'ptmcp:platform:list', group: '组织与接入' },
  { label: '审计记录', to: '/admin/audit', icon: DocumentIcon, permission: 'ptmcp:audit:list', group: '安全与追踪' },
  { label: '操作日志', to: '/admin/operations', icon: DocumentIcon, permission: 'ptmcp:audit:list', group: '安全与追踪' },
];
</script>
<template><WorkspaceLayout mode="admin" title="控制面" subtitle="权限、资源与运行状态" :items="items" /></template>
