export function passwordError(value: string): string | null {
  if ([...value].length < 12 || [...value].length > 64 || new TextEncoder().encode(value).length > 72)
    return '密码需12～64个字符，且 UTF-8 不超过72字节。';
  return null;
}
export function userProfileError(value: { email: string; phoneNumber: string; remark: string }): string | null {
  if (value.email.length > 50 || (value.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value.email))) return '邮箱格式无效或超过50字符。';
  if (value.phoneNumber.length > 11 || (value.phoneNumber && !/^\+?[0-9-]+$/.test(value.phoneNumber)))
    return '电话号码最多11字符，仅允许数字、+ 和短横线。';
  if (value.remark.length > 500) return '备注不能超过500字符。';
  return null;
}
