import { OAuthPageError, parseConsentContext, parseErrorBody, parseLoginContext, type ConsentContext, type LoginContext } from './model';

const CONTEXT_HEADERS = { Accept: 'application/json' };

function isAbort(error: unknown): boolean {
  return typeof error === 'object' && error !== null && 'name' in error && (error as { name?: unknown }).name === 'AbortError';
}

async function fetchContext(path: string, signal: AbortSignal): Promise<unknown> {
  let response: Response;
  try {
    response = await fetch(path, {
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'follow',
      headers: CONTEXT_HEADERS,
      signal,
    });
  } catch (error) {
    if (isAbort(error)) throw error;
    throw new OAuthPageError('无法连接授权服务，请检查网络后重试。');
  }
  if (!response.ok) {
    let body: unknown = null;
    try {
      body = await response.json();
    } catch {
      body = null;
    }
    throw parseErrorBody(body, response.status);
  }
  const contentType = response.headers.get('content-type') ?? '';
  if (!contentType.toLowerCase().includes('application/json')) {
    throw new OAuthPageError('登录状态已失效，请从客户端重新发起授权。', '', response.status);
  }
  try {
    return await response.json();
  } catch {
    throw new OAuthPageError('授权服务返回了无法识别的响应，请稍后重试。', '', response.status);
  }
}

export async function loadLoginContext(signal: AbortSignal): Promise<LoginContext> {
  const value = await fetchContext(`/oauth2/ui/login${window.location.search}`, signal);
  return parseLoginContext(value);
}

export async function loadConsentContext(signal: AbortSignal): Promise<ConsentContext> {
  const value = await fetchContext(`/oauth2/ui/consent${window.location.search}`, signal);
  return parseConsentContext(value, window.location.origin);
}
