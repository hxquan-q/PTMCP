/**
 * OAuth 登录/授权页的纯数据模型。
 *
 * 页面与授权服务同源，所有展示值都来自 `/oauth2/ui/*` 的服务端校验结果，不直接信任
 * 地址栏参数。这里只做「解析 + 拒绝」：字段缺失、类型不符、回调地址或提交地址越界
 * 一律抛 OAuthPageError，由页面转成带 requestId 的安全错误，绝不降级成可继续的授权界面。
 */

export interface ScopeView {
  value: string;
  label: string;
  description: string;
}

export interface LoginContext {
  csrfToken: string;
  failed: boolean;
  loggedOut: boolean;
}

export interface ConsentContext {
  csrfToken: string;
  clientId: string;
  clientName: string;
  platform: string;
  scopes: ScopeView[];
  /** 已同意过、本次仍被请求的 scope；这些项锁定勾选，取消勾选不会撤销既有授权。 */
  grantedScopes: string[];
  redirectUri: string;
  state: string;
  /** 已校验为同源的授权端点（path + query），直接作为表单 action。 */
  action: string;
  /** 服务端专用的拒绝端点；不能复用 SAS 的同意端点，空 scope 在已有同意时会签发授权码。 */
  denyAction: string;
  accessTokenTtlSeconds: number;
  refreshTokenTtlSeconds: number;
  refreshTokenRotation: boolean;
}

export interface FormField {
  name: string;
  value: string;
}

export interface Notice {
  kind: 'info' | 'warning' | 'danger';
  title: string;
  message: string;
}

export interface MetaItem {
  label: string;
  value: string;
}

export class OAuthPageError extends Error {
  requestId: string;
  status: number;

  constructor(message: string, requestId = '', status = 0) {
    super(message);
    this.name = 'OAuthPageError';
    this.requestId = requestId;
    this.status = status;
  }
}

const INVALID_DATA = '授权服务返回的数据不完整，请从客户端重新发起授权。';
const INVALID_CALLBACK = '授权服务返回的回调地址无效，请从客户端重新发起授权。';
const INVALID_ENDPOINT = '授权服务地址无效，请从客户端重新发起授权。';
const CROSS_ORIGIN_ENDPOINT = '授权服务地址与当前页面不一致，已阻止提交。请从客户端重新发起授权。';

function record(value: unknown): Record<string, unknown> {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new OAuthPageError(INVALID_DATA);
  }
  return value as Record<string, unknown>;
}

function requiredString(source: Record<string, unknown>, key: string, maxLength: number): string {
  const value = source[key];
  if (typeof value !== 'string' || value.length === 0 || value.length > maxLength) {
    throw new OAuthPageError(INVALID_DATA);
  }
  return value;
}

function optionalString(source: Record<string, unknown>, key: string, maxLength: number): string {
  const value = source[key];
  if (value === undefined || value === null) return '';
  if (typeof value !== 'string' || value.length > maxLength) {
    throw new OAuthPageError(INVALID_DATA);
  }
  return value;
}

function requiredNumber(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
    throw new OAuthPageError(INVALID_DATA);
  }
  return value;
}

function requiredBoolean(source: Record<string, unknown>, key: string): boolean {
  const value = source[key];
  if (typeof value !== 'boolean') {
    throw new OAuthPageError(INVALID_DATA);
  }
  return value;
}

function assertHttpUrl(value: string, message: string): void {
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new OAuthPageError(message);
  }
  if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || parsed.username || parsed.password) {
    throw new OAuthPageError(message);
  }
}

function parseScope(value: unknown): ScopeView {
  const scope = record(value);
  return {
    value: requiredString(scope, 'value', 128),
    label: requiredString(scope, 'label', 64),
    description: requiredString(scope, 'description', 256),
  };
}

export function parseLoginContext(value: unknown): LoginContext {
  const source = record(value);
  return {
    csrfToken: requiredString(source, 'csrfToken', 4096),
    failed: requiredBoolean(source, 'failed'),
    loggedOut: requiredBoolean(source, 'loggedOut'),
  };
}

/**
 * 表单 action 只接受与页面同源的 http(s) 地址，返回 path + query，避免把表单提交到
 * 其它来源；CSP 的 form-action 'self' 是第二道防线，不是唯一防线。
 */
export function authorizationAction(endpoint: string, pageOrigin: string): string {
  let parsed: URL;
  try {
    parsed = new URL(endpoint, pageOrigin);
  } catch {
    throw new OAuthPageError(INVALID_ENDPOINT);
  }
  if ((parsed.protocol !== 'http:' && parsed.protocol !== 'https:') || parsed.username || parsed.password) {
    throw new OAuthPageError(INVALID_ENDPOINT);
  }
  if (parsed.origin !== pageOrigin) {
    throw new OAuthPageError(CROSS_ORIGIN_ENDPOINT);
  }
  return `${parsed.pathname}${parsed.search}`;
}

export function parseConsentContext(value: unknown, pageOrigin: string): ConsentContext {
  const source = record(value);
  const rawScopes = source.scopes;
  if (!Array.isArray(rawScopes) || rawScopes.length > 32) {
    throw new OAuthPageError(INVALID_DATA);
  }
  const scopes: ScopeView[] = [];
  const seen = new Set<string>();
  for (const item of rawScopes) {
    const scope = parseScope(item);
    if (seen.has(scope.value)) continue;
    seen.add(scope.value);
    scopes.push(scope);
  }
  const rawGranted = source.grantedScopes;
  if (!Array.isArray(rawGranted) || rawGranted.length > 32) {
    throw new OAuthPageError(INVALID_DATA);
  }
  const grantedScopes: string[] = [];
  for (const item of rawGranted) {
    if (typeof item !== 'string' || !seen.has(item) || grantedScopes.includes(item)) {
      throw new OAuthPageError(INVALID_DATA);
    }
    grantedScopes.push(item);
  }
  const redirectUri = requiredString(source, 'redirectUri', 2048);
  assertHttpUrl(redirectUri, INVALID_CALLBACK);
  return {
    csrfToken: requiredString(source, 'csrfToken', 4096),
    clientId: requiredString(source, 'clientId', 256),
    clientName: optionalString(source, 'clientName', 512),
    platform: optionalString(source, 'platform', 256),
    scopes,
    grantedScopes,
    redirectUri,
    state: requiredString(source, 'state', 512),
    action: authorizationAction(requiredString(source, 'authorizationEndpoint', 2048), pageOrigin),
    denyAction: authorizationAction(requiredString(source, 'denyEndpoint', 2048), pageOrigin),
    accessTokenTtlSeconds: requiredNumber(source, 'accessTokenTtlSeconds'),
    refreshTokenTtlSeconds: requiredNumber(source, 'refreshTokenTtlSeconds'),
    refreshTokenRotation: requiredBoolean(source, 'refreshTokenRotation'),
  };
}

export function parseErrorBody(value: unknown, status: number): OAuthPageError {
  let message = '';
  let requestId = '';
  if (typeof value === 'object' && value !== null && !Array.isArray(value)) {
    const source = value as Record<string, unknown>;
    if (typeof source.message === 'string' && source.message.trim()) {
      message = source.message.trim().slice(0, 300);
    }
    if (typeof source.requestId === 'string') {
      requestId = source.requestId.slice(0, 128);
    }
  }
  return new OAuthPageError(message || defaultErrorMessage(status), requestId, status);
}

function defaultErrorMessage(status: number): string {
  if (status === 400 || status === 404) {
    return '授权请求无效或已过期，请从客户端重新发起授权。';
  }
  if (status === 401 || status === 403) {
    return '登录状态已失效，请从客户端重新发起授权。';
  }
  if (status >= 500) {
    return '授权服务暂时不可用，请稍后重试。';
  }
  return '授权页面加载失败，请稍后重试。';
}

export function toOAuthPageError(error: unknown): OAuthPageError {
  if (error instanceof OAuthPageError) return error;
  return new OAuthPageError('授权页面暂时无法加载，请稍后重试。');
}

/**
 * 拒绝授权必须不带任何 scope 参数：SAS 把「没有 scope」解释为 access_denied，
 * 而空选择下的「同意」在协议上不可表达，因此允许表单只由 allowFields 生成。
 */
export function consentFields(context: ConsentContext, selectedScopes: readonly string[]): FormField[] {
  const known = new Set(context.scopes.map((scope) => scope.value));
  const fields: FormField[] = [
    { name: 'client_id', value: context.clientId },
    { name: 'state', value: context.state },
    { name: '_csrf', value: context.csrfToken },
  ];
  for (const value of selectedScopes) {
    if (known.has(value) && !fields.some((field) => field.name === 'scope' && field.value === value)) {
      fields.push({ name: 'scope', value });
    }
  }
  return fields;
}

export function allowFields(context: ConsentContext, selectedScopes: readonly string[]): FormField[] {
  const fields = consentFields(context, selectedScopes);
  if (!fields.some((field) => field.name === 'scope')) {
    throw new OAuthPageError('请至少选择一项权限；如果不需要授权，请拒绝。');
  }
  return fields;
}

export function denyFields(context: ConsentContext): FormField[] {
  return consentFields(context, []);
}

const DURATION_UNITS: readonly (readonly [number, string])[] = [
  [86400, '天'],
  [3600, '小时'],
  [60, '分钟'],
  [1, '秒'],
];

/** 只取最大的两个单位，避免「1天2小时3分钟」这类噪声。 */
export function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds <= 0) return '未配置';
  let remaining = Math.floor(seconds);
  const parts: string[] = [];
  for (const [size, label] of DURATION_UNITS) {
    const count = Math.floor(remaining / size);
    if (count > 0) {
      parts.push(`${count}${label}`);
      remaining -= count * size;
    }
    if (parts.length === 2) break;
  }
  return parts.join('');
}

export function accessPolicyText(context: ConsentContext): string {
  return `访问令牌有效期 ${formatDuration(context.accessTokenTtlSeconds)}。`;
}

export function refreshPolicyText(context: ConsentContext): string {
  if (context.refreshTokenTtlSeconds <= 0) {
    return '不签发刷新令牌，访问令牌到期后需要重新授权。';
  }
  const rotation = context.refreshTokenRotation ? '每次刷新都会轮换令牌' : '刷新时沿用同一令牌';
  return `刷新令牌有效期 ${formatDuration(context.refreshTokenTtlSeconds)}，${rotation}。`;
}

export function loginNotice(context: LoginContext): Notice | null {
  if (context.failed) {
    return {
      kind: 'danger',
      title: '无法登录',
      message: '账号或密码不正确，或账号已停用/不可登录。凭证由管理员签发，请确认后再试。',
    };
  }
  if (context.loggedOut) {
    return { kind: 'info', title: '已退出登录', message: '你已安全退出，可重新登录继续授权。' };
  }
  return null;
}

export function consentTitle(context: ConsentContext): string {
  const name = context.clientName.trim();
  return `授权给 ${name || context.clientId}`;
}

export function clientMeta(context: ConsentContext): MetaItem[] {
  const items: MetaItem[] = [];
  const platform = context.platform.trim();
  if (platform) items.push({ label: '平台', value: platform });
  items.push({ label: '客户端 ID', value: context.clientId });
  return items;
}

export function scopeSummary(scopes: readonly ScopeView[]): string {
  if (scopes.length === 0) return '未申请任何权限范围';
  return scopes.map((scope) => scope.label).join('、');
}

export function defaultSelected(scopes: readonly ScopeView[]): string[] {
  return scopes.map((scope) => scope.value);
}
