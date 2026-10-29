import { newUuid } from './uuid';

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

export function setCsrfToken(value: string | null): void {
  csrfToken = value;
}

function object(value: unknown): Record<string, unknown> | null {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

export function parseApiJson(text: string): unknown {
  // 与 ptmcp-ui 同源：16 位以上纯整数（> 2^53）保留为字符串，防 ID 精度丢失。
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
    const pending = request<{ csrfToken: string }>('/api/v1/session/csrf', { csrf: false })
      .then(({ data }) => {
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
  const method = (options.method ?? 'GET').toUpperCase();
  const headers = new Headers(options.headers);
  headers.set('Accept', 'application/json');
  const writes = !['GET', 'HEAD', 'OPTIONS'].includes(method);
  if (writes && options.csrf !== false) {
    headers.set('X-CSRF-Token', csrfToken ?? (await refreshCsrf()));
  }
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');
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
    throw new ApiError(response.status, code, failureMessage(response.status, code, root, nested), requestId, !!nested?.retryable, nested ?? {});
  }
  const data = wrapped ? root?.data : body;
  const etag = response.headers.get('ETag');
  return { data: data as T, etag, requestId };
}

function failureMessage(status: number, code: string, root: Record<string, unknown> | null, nested: Record<string, unknown> | null): string {
  const message = typeof nested?.message === 'string' ? nested.message : typeof root?.message === 'string' ? root.message : null;
  if (message) return message;
  if (status === 401) return '会话已过期，请重新登录。';
  if (status === 403) return '没有执行该操作的权限。';
  if (status === 404) return '请求的资源不存在。';
  if (status === 409 || status === 412) return '数据已被他人修改，请刷新后重试。';
  if (status >= 500) return '服务暂时不可用，请稍后重试。';
  return `请求失败（${code}）。`;
}

export function errorText(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof DOMException && error.name === 'AbortError') return '请求已取消。';
  return '操作未完成，请稍后重试。';
}

export function idempotencyKey(): string {
  return newUuid();
}

export function queryString(values: Record<string, string | number | boolean | null | undefined>): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (value !== null && value !== undefined && value !== '') params.set(key, String(value));
  }
  const encoded = params.toString();
  return encoded ? `?${encoded}` : '';
}
