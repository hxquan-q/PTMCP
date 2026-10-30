/** 角色表单纯校验 — 与页面解耦，供测试覆盖。 */
export interface RoleFormValues {
  key: string;
  name: string;
  enabled: boolean;
  permissionCodes: string[];
}

const KEY_PATTERN = /^[a-z][a-z0-9_]{0,99}$/;

/** 返回第一条错误文案；合法返回 null。isCreate=true 时校验编码。 */
export function validateRoleForm(values: RoleFormValues, isCreate: boolean): string | null {
  if (isCreate) {
    const key = values.key.trim();
    if (!key) return '请输入角色编码';
    if (!KEY_PATTERN.test(key)) return '角色编码须以小写字母开头，仅含小写字母、数字与下划线，长度 1–100';
  }
  const name = values.name.trim();
  if (name.length < 2 || name.length > 30) return '角色名称长度须为 2–30 个字符';
  return null;
}
