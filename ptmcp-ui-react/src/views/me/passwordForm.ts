/** 密码强度校验 — 与 Vue formValidation.passwordError 同源规则。 */
export function passwordError(value: string): string | null {
  const chars = [...value].length;
  const bytes = new TextEncoder().encode(value).length;
  if (chars < 12 || chars > 64 || bytes > 72) return '密码需12～64个字符，且 UTF-8 不超过72字节。';
  return null;
}

export interface PasswordFormValues {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}

/** 返回第一条错误；一致且通过强度校验返回 null。 */
export function validatePasswordForm(values: PasswordFormValues): string | null {
  if (!values.oldPassword) return '请输入当前密码';
  const strength = passwordError(values.newPassword);
  if (strength) return strength;
  if (values.newPassword !== values.confirmPassword) return '两次输入的新密码不一致';
  return null;
}
