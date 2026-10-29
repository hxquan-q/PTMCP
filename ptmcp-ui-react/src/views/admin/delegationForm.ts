/** 委派表单纯校验 — 与页面解耦，供测试覆盖。 */
export interface DelegationFormValues {
  userId: string;
  selfUserId: string;
  departmentRootId: string;
  resourceId: string;
  allowedActions: string[];
  validFrom: string;
  validTo: string;
}

/** datetime-local 值（YYYY-MM-DDTHH:mm）格式校验。 */
export function isValidLocalDateTime(value: string): boolean {
  return /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value);
}

/** 返回第一条错误文案；合法返回 null。规则与 Vue DelegationsView 对齐。 */
export function validateDelegationForm(values: DelegationFormValues): string | null {
  if (!values.userId) return '请选择受委派的管理员';
  if (values.userId === values.selfUserId) return '不能把管理范围委派给自己';
  if (!values.departmentRootId) return '请选择部门根';
  if (!values.resourceId) return '请选择资源';
  if (values.allowedActions.length === 0) return '至少选择一个允许的操作';
  if (!isValidLocalDateTime(values.validFrom)) return '请选择生效开始时间';
  if (!isValidLocalDateTime(values.validTo)) return '请选择生效结束时间';
  if (values.validTo <= values.validFrom) return '结束时间必须晚于开始时间';
  return null;
}
