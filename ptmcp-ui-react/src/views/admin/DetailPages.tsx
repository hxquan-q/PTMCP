/* 管理端详情页的入口：实现按页面拆到 ./detail/，这里只做对外再导出。
   拆分前本文件 1587 行、8 个页面组件混在一起；保持 barrel 让 App.tsx 的引用不变。 */
export { BackBar } from './detail/shared';
export { JobDetailPage } from './detail/JobDetailPage';
export { AuditDetailPage } from './detail/AuditDetailPage';
export { AuditDecryptDialog } from './detail/AuditDecryptDialog';
export { MetadataDetailPage } from './detail/MetadataDetailPage';
export { DatasourceDetailPage } from './detail/DatasourceDetailPage';
export { UserDetailPage, RoleDetailPage, PlatformDetailPage } from './detail/OrgDetailPages';
