export interface TransportResult<T> {
  data: T;
  etag: string | null;
  requestId: string | null;
}

export interface RequestOptions {
  method?: string;
  body?: unknown;
  headers?: HeadersInit;
  signal?: AbortSignal;
  /** Session endpoints bootstrap CSRF themselves. */
  csrf?: boolean;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string | null;
  readonly retryable: boolean;
  readonly details: Record<string, unknown>;
  constructor(
    status: number,
    code: string,
    message: string,
    requestId: string | null = null,
    retryable = false,
    details: Record<string, unknown> = {},
  ) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.requestId = requestId;
    this.retryable = retryable;
    this.details = details;
  }
}

const base = '/backend/admin';
let csrfToken: string | null = null;
let csrfPromise: Promise<string> | null = null;
let sessionGeneration = 0;
const sessionEvents = new EventTarget();

export function setCsrfToken(value: string | null): void {
  csrfToken = value;
}

/** Retire reads and writes from a former identity, including pending CSRF bootstrap. */
export function invalidateSessionRequests(): void {
  sessionGeneration += 1;
  csrfToken = null;
  csrfPromise = null;
}

function requireSessionGeneration(generation: number): void {
  if (generation !== sessionGeneration) throw new DOMException('会话上下文已变化，请重新发起操作。', 'AbortError');
}

export function onSessionExpired(listener: () => void): () => void {
  sessionEvents.addEventListener('expired', listener);
  return () => sessionEvents.removeEventListener('expired', listener);
}

function object(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

function failureMessage(status: number, code: string): string {
  if (code === 'CSRF_INVALID') return '安全校验已失效，请重新获取后再次提交。';
  if (code === 'LOGIN_LOCKED') return '登录暂时锁定，请稍后再试。';
  if (status === 401) return '会话已失效，请重新登录。';
  if (status === 403) return '你没有执行此操作的权限，或当前管理范围已失效。';
  if (status === 404) return '资源不存在或当前不可见。';
  if (status === 409 || status === 412) return '服务端资源已变化，请重新读取并合并后提交。';
  if (status === 429) return '请求过于频繁，请稍后手动重试。';
  if (status === 503) return '服务暂不可用，请稍后手动重试。';
  if (status === 400 || status === 422) return '请检查输入内容与业务约束。';
  return '请求未完成，请稍后重试。';
}

/** Preserve long numeric IDs before JSON.parse; no application-specific key conversion. */
export function parseApiJson(text: string): unknown {
  let output = '';
  let inString = false;
  let escaped = false;
  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (inString) {
      output += character;
      if (escaped) escaped = false;
      else if (character === '\\') escaped = true;
      else if (character === '"') inString = false;
      continue;
    }
    if (character === '"') {
      inString = true;
      output += character;
      continue;
    }
    if (character === '-' || (character !== undefined && /\d/.test(character))) {
      let end = index + 1;
      while (end < text.length && /[\d.eE+-]/.test(text[end] ?? '')) end += 1;
      const numeric = text.slice(index, end);
      output += /^-?\d{16,}$/.test(numeric) ? `"${numeric}"` : numeric;
      index = end - 1;
      continue;
    }
    output += character;
  }
  return JSON.parse(output) as unknown;
}

export async function refreshCsrf(): Promise<string> {
  if (!csrfPromise) {
    const generation = sessionGeneration;
    const pending = request<{ csrfToken: string }>('/api/v1/session/csrf', { csrf: false })
      .then(({ data }) => {
        requireSessionGeneration(generation);
        if (typeof data.csrfToken !== 'string' || !data.csrfToken) {
          throw new ApiError(502, 'INVALID_RESPONSE', '服务端安全校验响应无效。');
        }
        csrfToken = data.csrfToken;
        return data.csrfToken;
      })
      .finally(() => {
        if (csrfPromise === pending) csrfPromise = null;
      });
    csrfPromise = pending;
  }
  return csrfPromise;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<TransportResult<T>> {
  if (!path.startsWith('/api/v1/')) throw new Error('Only same-origin REST API paths are allowed.');
  const generation = sessionGeneration;
  const method = (options.method ?? 'GET').toUpperCase();
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');
  const writes = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  if (writes && options.csrf !== false) {
    headers.set('X-CSRF-Token', csrfToken ?? (await refreshCsrf()));
  }
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');
  requireSessionGeneration(generation);
  let response: Response;
  try {
    response =
      import.meta.env?.DEV && import.meta.env.MODE === 'frontend-preview'
        ? await (await import('../preview/server')).previewRequest(path, { ...options, method, headers })
        : await fetch(`${base}${path}`, {
            method,
            headers,
            body: options.body === undefined ? undefined : JSON.stringify(options.body),
            credentials: 'same-origin',
            cache: 'no-store',
            signal: options.signal,
          });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'NETWORK_ERROR', '无法连接服务，请检查后端服务是否启动。');
  }
  const text = await response.text();
  requireSessionGeneration(generation);
  let body: unknown = null;
  if (text) {
    try {
      body = parseApiJson(text);
    } catch {
      throw new ApiError(response.status, 'INVALID_RESPONSE', '服务响应格式无效。');
    }
  }
  const root = object(body);
  const nested = object(root?.data);
  const requestId = response.headers.get('X-Request-Id') ?? (typeof nested?.requestId === 'string' ? nested.requestId : null);
  const wrapped = typeof root?.code === 'number' && 'data' in (root ?? {});
  if (!response.ok || (wrapped && root?.code !== 200)) {
    const code = typeof root?.code === 'string' ? root.code : typeof nested?.errorCode === 'string' ? nested.errorCode : 'REQUEST_FAILED';
    if (code === 'CSRF_INVALID') csrfToken = null;
    if (response.status === 401) {
      csrfToken = null;
      sessionEvents.dispatchEvent(new Event('expired'));
    }
    throw new ApiError(
      response.status,
      code,
      failureMessage(response.status, code),
      requestId,
      root?.retryable === true || nested?.retryable === true,
      object(nested?.details) ?? {},
    );
  }
  return { data: (wrapped ? root?.data : body) as T, etag: response.headers.get('ETag'), requestId };
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof DOMException && error.name === 'AbortError') return '请求已取消。';
  return '操作未完成，请稍后重试。';
}

export function idempotencyKey(): string {
  return crypto.randomUUID();
}

export function queryString(values: Record<string, string | number | boolean | null | undefined>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== null && value !== undefined && value !== '') params.set(key, String(value));
  }
  const encoded = params.toString();
  return encoded ? `?${encoded}` : '';
}
