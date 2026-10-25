<script setup lang="ts">
import WorkspaceLayout, { type NavigationItem } from '../components/common/WorkspaceLayout.vue';
import CollectionIcon from '~icons/ep/collection';
import ConnectionIcon from '~icons/ep/connection';
import KeyIcon from '~icons/ep/key';
import LockIcon from '~icons/ep/lock';
import TicketsIcon from '~icons/ep/tickets';
import SettingIcon from '~icons/ep/setting';
const items: NavigationItem[] = [
  { label: '我的数据字典', to: '/me/dictionary', icon: CollectionIcon },
  { label: '接入配置', to: '/me/integrations', icon: ConnectionIcon },
  { label: '个人令牌', to: '/me/tokens', icon: KeyIcon },
  { label: '授权管理', to: '/me/authorizations', icon: LockIcon },
  { label: '查询历史', to: '/me/history', icon: TicketsIcon },
  { label: '账号安全', to: '/me/security', icon: SettingIcon }
];
</script>
<template><WorkspaceLayout mode="personal" title="数据门户" subtitle="查看已授权的数据资产" :items="items" /></template>
