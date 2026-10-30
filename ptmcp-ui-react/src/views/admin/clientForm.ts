import type { ClientWrite } from '../../services/access';

/** 与服务端 ClientAdminService 对齐的客户端表单规则（纯函数，便于契约测试）。 */
export const CLIENT_SCOPES = ['mcp:catalog', 'mcp:schema', 'mcp:query'] as const;
export const CLIENT_AUTH_METHODS = ['client_secret_basic', 'client_secret_post', 'private_key_jwt', 'none'] as const;
const SECRET_METHODS = ['client_secret_basic', 'client_secret_post'];
const MAX_REDIRECT_URIS = 10;
const MAX_JWKS_URI = 2048;

export interface ClientFormValues {
  name: string;
  type: string;
  status: string;
  authMethods: string[];
  redirectUris: string[];
  allowedScopes: string[];
  jwksUri: string;
}

/** 文本域一行一个回调地址；忽略空行。 */
export function parseRedirectUris(text: string): string[] {
  return text
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line.length > 0);
}

function validRedirectUri(value: string): boolean {
  // 与后端一致：通配符与 ".." 在原始串上就拒绝（URL 规范化后 ".." 会被吞掉）。
  if (value.includes('*') || value.includes('..')) return false;
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    return false;
  }
  if (url.username || url.password) return false;
  if (url.hash) return false;
  const https = url.protocol === 'https:';
  const loopbackHttp = url.protocol === 'http:' && (url.hostname === 'localhost' || url.hostname.startsWith('127.'));
  if (!https && !loopbackHttp) return false;
  // 后端要求 rawPath 以 "/" 开头：只有 scheme+host、没有路径的地址不合法。
  const afterScheme = value.slice(value.indexOf('://') + 3);
  return afterScheme.includes('/') && url.pathname.startsWith('/');
}

/** 返回首条错误文案；空串表示通过。 */
export function validateClientForm(values: ClientFormValues): string {
  const name = values.name.trim();
  if (name.length < 1 || name.length > 200) return '客户端名称长度须为 1–200 个字符';
  if (values.type !== 'PUBLIC' && values.type !== 'CONFIDENTIAL') return '请选择客户端类型';
  if (values.status !== 'ACTIVE' && values.status !== 'DISABLED') return '请选择状态';
  if (values.authMethods.length === 0) return '请至少选择一种认证方式';
  if (values.authMethods.some((method) => !CLIENT_AUTH_METHODS.includes(method as (typeof CLIENT_AUTH_METHODS)[number]))) {
    return '认证方式不在服务端支持范围内';
  }
  const confidential = values.type === 'CONFIDENTIAL';
  if (!confidential && !(values.authMethods.length === 1 && values.authMethods[0] === 'none')) {
    return 'PUBLIC 客户端只能使用 none 认证';
  }
  if (confidential && values.authMethods.includes('none')) {
    return 'CONFIDENTIAL 客户端不能使用 none 认证';
  }
  if (values.allowedScopes.length === 0) return '请至少选择一个 Scope';
  if (values.allowedScopes.some((scope) => !CLIENT_SCOPES.includes(scope as (typeof CLIENT_SCOPES)[number]))) {
    return 'Scope 不在服务端支持范围内';
  }
  if (values.redirectUris.length > MAX_REDIRECT_URIS) return `回调地址最多 ${MAX_REDIRECT_URIS} 个`;
  const badRedirect = values.redirectUris.find((uri) => !validRedirectUri(uri));
  if (badRedirect) return `回调地址无效：${badRedirect}（须为 https，或 http 回环地址，且不含通配符/片段）`;
  const jwksUri = values.jwksUri.trim();
  if (jwksUri.length > MAX_JWKS_URI) return `JWKS 地址长度不能超过 ${MAX_JWKS_URI}`;
  if (jwksUri) {
    let parsed: URL;
    try {
      parsed = new URL(jwksUri);
    } catch {
      return 'JWKS 地址必须是合法 https URL';
    }
    if (parsed.protocol !== 'https:') return 'JWKS 地址必须是 https';
  }
  const usesSecret = values.authMethods.some((method) => SECRET_METHODS.includes(method));
  if (confidential && !usesSecret && !jwksUri) return '仅使用 private_key_jwt 时，JWKS 地址必填';
  return '';
}

export function toClientWrite(values: ClientFormValues): ClientWrite {
  return {
    name: values.name.trim(),
    type: values.type,
    authMethods: [...values.authMethods],
    redirectUris: [...values.redirectUris],
    allowedScopes: [...values.allowedScopes],
    jwksUri: values.jwksUri.trim() ? values.jwksUri.trim() : null,
    status: values.status,
  };
}
