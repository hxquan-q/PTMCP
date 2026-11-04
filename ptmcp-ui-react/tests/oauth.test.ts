import assert from 'node:assert/strict';
import test from 'node:test';
import {
  OAuthPageError,
  accessPolicyText,
  allowFields,
  authorizationAction,
  clientMeta,
  consentFields,
  consentTitle,
  defaultSelected,
  denyFields,
  formatDuration,
  loginNotice,
  parseConsentContext,
  parseErrorBody,
  parseLoginContext,
  refreshPolicyText,
  scopeSummary,
  toOAuthPageError,
  type ConsentContext,
} from '../src/oauth/model.ts';

/**
 * OAuth 页面纯模型契约。
 *
 * 页面只信任 `/oauth2/ui/*` 的服务端校验结果；这些断言钉住「越界即拒绝」的边界：
 * 跨源提交地址、非 http(s) 回调、缺字段、伪造 scope 都不能降级成可继续的授权界面。
 */

const ORIGIN = 'https://auth.example.com';

function consentBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    csrfToken: 'csrf-token',
    clientId: 'ptmcp-client',
    clientName: '报表助手',
    platform: '数据分析平台',
    scopes: [
      { value: 'mcp:catalog', label: '目录', description: '列出可访问的数据源与库表' },
      { value: 'mcp:schema', label: '结构', description: '读取表结构' },
      { value: 'mcp:query', label: '查询', description: '执行只读查询' },
    ],
    grantedScopes: [],
    redirectUri: 'https://client.example.com/callback',
    state: 'state-1',
    authorizationEndpoint: '/oauth2/authorize?client_id=ptmcp-client',
    denyEndpoint: '/oauth2/ui/consent/deny',
    accessTokenTtlSeconds: 3600,
    refreshTokenTtlSeconds: 86400,
    refreshTokenRotation: true,
    ...overrides,
  };
}

function context(overrides: Record<string, unknown> = {}): ConsentContext {
  return parseConsentContext(consentBody(overrides), ORIGIN);
}

function assertPageError(fn: () => unknown, pattern?: RegExp): void {
  assert.throws(fn, (error: unknown) => {
    assert.ok(error instanceof OAuthPageError, `期望 OAuthPageError，实际 ${String(error)}`);
    if (pattern) assert.match(error.message, pattern);
    return true;
  });
}

// ── 登录上下文 ──────────────────────────────────────────────────────────────

test('登录上下文只接受完整的服务端字段', () => {
  assert.deepEqual(parseLoginContext({ csrfToken: 'token', failed: false, loggedOut: false }), {
    csrfToken: 'token',
    failed: false,
    loggedOut: false,
  });
  assertPageError(() => parseLoginContext({ failed: false, loggedOut: false }));
  assertPageError(() => parseLoginContext({ csrfToken: '', failed: false, loggedOut: false }));
  assertPageError(() => parseLoginContext({ csrfToken: 'token', failed: 'no', loggedOut: false }));
  assertPageError(() => parseLoginContext(null));
  assertPageError(() => parseLoginContext([]));
});

// ── 授权上下文 ──────────────────────────────────────────────────────────────

test('授权上下文解析服务端校验后的客户端、权限与回调', () => {
  const parsed = context();
  assert.equal(parsed.clientId, 'ptmcp-client');
  assert.equal(parsed.clientName, '报表助手');
  assert.equal(parsed.platform, '数据分析平台');
  assert.deepEqual(
    parsed.scopes.map((scope) => scope.value),
    ['mcp:catalog', 'mcp:schema', 'mcp:query'],
  );
  assert.deepEqual(parsed.grantedScopes, []);
  assert.equal(parsed.redirectUri, 'https://client.example.com/callback');
  assert.equal(parsed.action, '/oauth2/authorize?client_id=ptmcp-client');
  assert.equal(parsed.denyAction, '/oauth2/ui/consent/deny');
  assert.equal(parsed.accessTokenTtlSeconds, 3600);
  assert.equal(parsed.refreshTokenTtlSeconds, 86400);
  assert.equal(parsed.refreshTokenRotation, true);
});

test('已授权 scope 必须是本次请求的子集', () => {
  const parsed = context({ grantedScopes: ['mcp:query'] });
  assert.deepEqual(parsed.grantedScopes, ['mcp:query']);
  assertPageError(() => context({ grantedScopes: ['mcp:admin'] }));
  assertPageError(() => context({ grantedScopes: ['mcp:query', 'mcp:query'] }));
  assertPageError(() => context({ grantedScopes: 'mcp:query' }));
  assertPageError(() => context({ grantedScopes: [1] }));
  assertPageError(() => context({ grantedScopes: Array.from({ length: 33 }, () => 'mcp:query') }));
});

test('重复 scope 去重，未知字段被忽略', () => {
  const parsed = context({
    scopes: [
      { value: 'mcp:catalog', label: '目录', description: '一' },
      { value: 'mcp:catalog', label: '目录副本', description: '二' },
    ],
    unexpected: 'ignored',
  });
  assert.equal(parsed.scopes.length, 1);
  assert.equal(parsed.scopes[0]!.label, '目录');
});

test('授权上下文拒绝缺失或畸形的字段', () => {
  assertPageError(() => parseConsentContext({}, ORIGIN));
  assertPageError(() => parseConsentContext(consentBody({ scopes: 'mcp:query' }), ORIGIN));
  assertPageError(() =>
    parseConsentContext(
      consentBody({
        scopes: Array.from({ length: 33 }, (_, index) => ({
          value: `scope-${index}`,
          label: `范围 ${index}`,
          description: '说明',
        })),
      }),
      ORIGIN,
    ),
  );
  assertPageError(() => parseConsentContext(consentBody({ scopes: [{ value: 'mcp:query', label: '查询' }] }), ORIGIN));
  assertPageError(() => parseConsentContext(consentBody({ state: '' }), ORIGIN));
  assertPageError(() => parseConsentContext(consentBody({ grantedScopes: undefined }), ORIGIN));
  assertPageError(() => parseConsentContext(consentBody({ denyEndpoint: '' }), ORIGIN));
  assertPageError(() => parseConsentContext(consentBody({ denyEndpoint: 'https://evil.example/oauth2/ui/consent/deny' }), ORIGIN));
  assertPageError(() => parseConsentContext(consentBody({ denyEndpoint: 'javascript:alert(1)' }), ORIGIN));
  assertPageError(() => parseConsentContext(consentBody({ accessTokenTtlSeconds: -1 }), ORIGIN));
  assertPageError(() => parseConsentContext(consentBody({ refreshTokenRotation: 'true' }), ORIGIN));
});

test('回调地址必须是带 host 的 http(s)，且不能内嵌凭证', () => {
  for (const redirectUri of [
    'javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'ftp://client.example.com/callback',
    'https://user:pass@client.example.com/callback',
    'not a url',
    '',
  ]) {
    assertPageError(() => parseConsentContext(consentBody({ redirectUri }), ORIGIN));
  }
  assert.equal(
    parseConsentContext(consentBody({ redirectUri: 'http://127.0.0.1:28081/callback' }), ORIGIN).redirectUri,
    'http://127.0.0.1:28081/callback',
  );
});

// ── 表单提交地址 ────────────────────────────────────────────────────────────

test('提交地址只接受同源 http(s)，返回 path + query', () => {
  assert.equal(authorizationAction('/oauth2/authorize?client_id=x', ORIGIN), '/oauth2/authorize?client_id=x');
  assert.equal(authorizationAction('https://auth.example.com/oauth2/authorize?client_id=x', ORIGIN), '/oauth2/authorize?client_id=x');
  assertPageError(() => authorizationAction('https://evil.example/authorize', ORIGIN), /不一致/);
  assertPageError(() => authorizationAction('//evil.example/authorize', ORIGIN), /不一致/);
  assertPageError(() => authorizationAction('javascript:alert(1)', ORIGIN));
  assertPageError(() => authorizationAction('https://user:pass@auth.example.com/authorize', ORIGIN));
  assertPageError(() => parseConsentContext(consentBody({ authorizationEndpoint: 'https://evil.example/x' }), ORIGIN));
});

// ── 表单字段 ────────────────────────────────────────────────────────────────

test('同意表单只携带服务端已登记且被勾选的 scope', () => {
  const ctx = context();
  const fields = consentFields(ctx, ['mcp:query', 'mcp:query', 'mcp:unknown']);
  assert.deepEqual(fields, [
    { name: 'client_id', value: 'ptmcp-client' },
    { name: 'state', value: 'state-1' },
    { name: '_csrf', value: 'csrf-token' },
    { name: 'scope', value: 'mcp:query' },
  ]);
});

test('空选择或只选未知 scope 时不能提交同意', () => {
  const ctx = context();
  assertPageError(() => allowFields(ctx, []), /至少选择一项权限/);
  assertPageError(() => allowFields(ctx, ['mcp:unknown']), /至少选择一项权限/);
  assert.equal(allowFields(ctx, ['mcp:catalog']).filter((field) => field.name === 'scope').length, 1);
});

test('拒绝表单永不携带 scope，由专用端点记为 access_denied', () => {
  const fields = denyFields(context());
  assert.deepEqual(
    fields.map((field) => field.name),
    ['client_id', 'state', '_csrf'],
  );
  assert.equal(
    fields.some((field) => field.name === 'scope'),
    false,
  );
});

// ── 展示文本 ────────────────────────────────────────────────────────────────

test('有效期按最大两个单位展示，未配置时说明清楚', () => {
  assert.equal(formatDuration(0), '未配置');
  assert.equal(formatDuration(-5), '未配置');
  assert.equal(formatDuration(Number.NaN), '未配置');
  assert.equal(formatDuration(45), '45秒');
  assert.equal(formatDuration(90), '1分钟30秒');
  assert.equal(formatDuration(3661), '1小时1分钟');
  assert.equal(formatDuration(86400), '1天');
  assert.equal(formatDuration(90061), '1天1小时');
});

test('有效期说明区分是否签发刷新令牌及轮换策略', () => {
  assert.equal(accessPolicyText(context()), '访问令牌有效期 1小时。');
  assert.equal(
    refreshPolicyText(context({ refreshTokenTtlSeconds: 86400, refreshTokenRotation: true })),
    '刷新令牌有效期 1天，每次刷新都会轮换令牌。',
  );
  assert.equal(
    refreshPolicyText(context({ refreshTokenTtlSeconds: 86400, refreshTokenRotation: false })),
    '刷新令牌有效期 1天，刷新时沿用同一令牌。',
  );
  assert.equal(refreshPolicyText(context({ refreshTokenTtlSeconds: 0 })), '不签发刷新令牌，访问令牌到期后需要重新授权。');
});

test('登录提示只由服务端状态决定，失败信息不区分具体原因', () => {
  assert.equal(loginNotice({ csrfToken: 't', failed: false, loggedOut: false }), null);
  const failed = loginNotice({ csrfToken: 't', failed: true, loggedOut: false });
  assert.equal(failed?.kind, 'danger');
  assert.match(failed?.message ?? '', /账号或密码不正确/);
  const loggedOut = loginNotice({ csrfToken: 't', failed: false, loggedOut: true });
  assert.equal(loggedOut?.kind, 'info');
});

test('客户端名称与平台按文本展示，缺失时回退到客户端 ID', () => {
  assert.equal(consentTitle(context()), '授权给 报表助手');
  assert.equal(consentTitle(context({ clientName: '   ' })), '授权给 ptmcp-client');
  assert.deepEqual(clientMeta(context()), [
    { label: '平台', value: '数据分析平台' },
    { label: '客户端 ID', value: 'ptmcp-client' },
  ]);
  assert.deepEqual(clientMeta(context({ platform: '' })), [{ label: '客户端 ID', value: 'ptmcp-client' }]);
  assert.equal(scopeSummary(context().scopes), '目录、结构、查询');
  assert.equal(scopeSummary([]), '未申请任何权限范围');
  assert.deepEqual(defaultSelected(context().scopes), ['mcp:catalog', 'mcp:schema', 'mcp:query']);
});

// ── 错误映射 ────────────────────────────────────────────────────────────────

test('错误响应保留服务端消息与 requestId，并按状态码兜底', () => {
  const server = parseErrorBody({ message: '  授权请求已过期  ', requestId: 'req-1' }, 400);
  assert.equal(server.message, '授权请求已过期');
  assert.equal(server.requestId, 'req-1');
  assert.equal(server.status, 400);

  const truncated = parseErrorBody({ message: 'x'.repeat(400), requestId: 'y'.repeat(200) }, 500);
  assert.equal(truncated.message.length, 300);
  assert.equal(truncated.requestId.length, 128);

  assert.match(parseErrorBody(null, 400).message, /无效或已过期/);
  assert.match(parseErrorBody(null, 401).message, /登录状态已失效/);
  assert.match(parseErrorBody(null, 503).message, /暂时不可用/);
  assert.match(parseErrorBody(null, 418).message, /加载失败/);
});

test('未知异常统一转成页面错误，不泄露原始信息', () => {
  const original = new OAuthPageError('原始', 'req-2', 400);
  assert.equal(toOAuthPageError(original), original);
  const wrapped = toOAuthPageError(new Error('boom'));
  assert.equal(wrapped.message, '授权页面暂时无法加载，请稍后重试。');
  assert.equal(wrapped.requestId, '');
});
