export type ValidationErrors = Record<string, string>;

export function splitLines(value: string): string[] {
  return [
    ...new Set(
      value
        .split(/\r?\n/u)
        .map((item) => item.trim())
        .filter(Boolean),
    ),
  ];
}

export function validateRequired(values: Record<string, string>, labels: Record<string, string>): ValidationErrors {
  const errors: ValidationErrors = {};
  for (const [key, value] of Object.entries(values)) {
    if (!value.trim()) errors[key] = `${labels[key] ?? key}不能为空`;
  }
  return errors;
}

export function validateRedirectUris(value: string): string | null {
  const uris = splitLines(value);
  if (!uris.length) return '至少填写一个精确回调 URI。';
  for (const uri of uris) {
    try {
      const parsed = new URL(uri);
      const isWeb = parsed.protocol === 'https:';
      const isLoopback = parsed.protocol === 'http:' && ['127.0.0.1', '[::1]'].includes(parsed.hostname);
      const isCustom = parsed.protocol === 'workbuddy:';
      const nestedRedirect = [...parsed.searchParams.values()].some((part) => /^(?:[a-z][a-z0-9+.-]*:|\/\/)/iu.test(part));
      if (
        !/^[a-z][a-z0-9+.-]*:\/\//iu.test(uri) ||
        /\s/u.test(uri) ||
        (!isWeb && !isLoopback && !isCustom) ||
        parsed.hash ||
        parsed.username ||
        parsed.password ||
        uri.includes('*') ||
        uri.includes('\\') ||
        nestedRedirect
      )
        return '回调 URI 必须精确且符合安全规则：仅 HTTPS、精确 loopback 或已支持的 custom scheme；不能含通配符、嵌套跳转、凭据或片段。';
    } catch {
      return '回调 URI 格式无效，请检查每行地址。';
    }
  }
  return null;
}

export function validateWindow(from: string, to: string): string | null {
  if (!from || !to) return null;
  const start = new Date(from).getTime();
  const end = new Date(to).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end)) return '有效期必须是有效日期。';
  if (end <= start) return '结束时间必须晚于开始时间。';
  return null;
}

export function validateDateRange(from: string, to: string, maxDays = 31): string | null {
  if (!from || !to) return null;
  const start = new Date(from).getTime();
  const end = new Date(to).getTime();
  if (!Number.isFinite(start) || !Number.isFinite(end)) return '日期格式无效。';
  if (end < start) return '结束时间必须晚于开始时间。';
  if (end - start > maxDays * 24 * 60 * 60 * 1000) return `单次查询时间范围不能超过${maxDays}天。`;
  return null;
}

export const allowedClientScopes = ['mcp:catalog', 'mcp:schema', 'mcp:query'] as const;

export function validateAllowedScopes(scopes: string[]): string | null {
  const values = [...new Set(scopes.map((scope) => scope.trim()).filter(Boolean))];
  if (!values.length) return '至少选择一个经审批的 Scope。';
  const invalid = values.find((scope) => !(allowedClientScopes as readonly string[]).includes(scope));
  return invalid ? `Scope 不在服务端白名单中：${invalid}` : null;
}

export function localOnlyReason(resource: string): string {
  return `${resource}管理服务尚未接入。记录暂不可读取，提交暂不可用；可预览页面字段和本地校验。`;
}

/**
 * 委派提交失败的用户可读原因。DUPLICATE_GRANT（409）是"同范围生效中委派已存在"，
 * 不是版本冲突；沿用通用 409 文案会误导管理员去"合并版本"。未识别错误原样透传。
 */
export function delegationSubmitError(reason: unknown, fallback: (error: unknown) => string): string {
  if (reason && typeof reason === 'object' && 'code' in reason && (reason as { code?: unknown }).code === 'DUPLICATE_GRANT')
    return '已存在生效中的同范围委派（DUPLICATE_GRANT）。请调整范围、期限，或先撤销旧委派后再创建。';
  return fallback(reason);
}

/**
 * 角色跨管理范围共享（RoleAdminController 写端点 requirePlatformAdmin）：
 * 委派管理员即使持 ptmcp:role:* 功能码（T16 可委派动作含 role 域），UI 也必须只读并解释，
 * 否则按钮可点而服务端必 403（page-spec P-07）。
 */
export function roleWriteGate(session: { can: (code: string) => boolean; platformAdmin: boolean }): {
  canWrite: boolean;
  hint: string | null;
} {
  const canWrite =
    session.platformAdmin && (session.can('ptmcp:role:update') || session.can('ptmcp:role:delete') || session.can('ptmcp:role:create'));
  return {
    canWrite,
    hint:
      session.platformAdmin || !session.can('ptmcp:role:list') ? null : '角色跨管理范围共享，仅平台管理员可修改；你可查看角色并将其分配给所辖用户。',
  };
}
