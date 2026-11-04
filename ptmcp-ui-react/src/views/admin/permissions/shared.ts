/* 权限矩阵页共用的主体页签与视图模型。 */
export const SUBJECT_TABS = [
  { key: 'USER', label: '用户' },
  { key: 'ROLE', label: '角色' },
  { key: 'DEPT', label: '部门' },
];

export interface SubjectItem {
  id: string;
  name: string;
  sub: string;
}
