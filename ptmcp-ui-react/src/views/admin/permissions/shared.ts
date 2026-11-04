/* 权限矩阵页共用的主体页签与视图模型。入口默认角色：角色是主授权载体，用户/部门为次级覆盖。 */
export const SUBJECT_TABS = [
  { key: 'ROLE', label: '角色' },
  { key: 'USER', label: '用户' },
  { key: 'DEPT', label: '部门' },
];

export interface SubjectItem {
  id: string;
  name: string;
  sub: string;
}
