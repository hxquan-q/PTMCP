/* 个人门户的入口：实现按页面拆到 ./pages/，这里只做对外再导出。
   拆分前本文件 1138 行、8 个页面组件混在一起；保持 barrel 让 App.tsx 的引用不变。 */
export { DictionaryPage } from './pages/DictionaryPage';
export { TokensPage } from './pages/TokensPage';
export { AuthorizationsPage } from './pages/AuthorizationsPage';
export { HistoryPage } from './pages/HistoryPage';
export { SecurityPage } from './pages/SecurityPage';
export { HistoryDetailPage } from './pages/HistoryDetailPage';
export { DictionaryDetailPage } from './pages/DictionaryDetailPage';
