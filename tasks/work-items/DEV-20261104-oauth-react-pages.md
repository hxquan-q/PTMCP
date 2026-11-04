# DEV-20261104-oauth-react-pages：OAuth 登录与同意页 React 实现

ID: DEV-20261104-oauth-react-pages
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-11-04

## 目标与边界

来源：用户要求为外部 Agent 首次接入的 OAuth 流程提供 React 前端页面并完成功能实现。
现有 `ptmcp-authz` 仅返回最小服务端渲染 HTML，同意页未展示 clientId、平台、精确回调目标
与令牌有效期，不满足 [P-17](../../docs/frontend/page-spec.md#18-p-17-授权服务登录与同意页)
与 [WF-01](../../docs/frontend/wireframes.md#wf-01-登录账号安全与-as-同意)。

Given 外部客户端被带到 AS 的 `/oauth2/authorize`，When 未登录或需同意，Then 浏览器在 AS
同源看到 React 登录/同意页；登录与同意仍以原生表单 POST 走 Spring Security / SAS，
携带服务端下发的 CSRF 与 state，同意后按标准授权码 + PKCE 回调。

不变量：

- 页面与 `PTMCP_AS_SESSION`、CSRF 同源：页面由 `ptmcp-authz` 在 AS 源上提供，不引入跨源会话。
- 同意页只展示服务端核验的 clientName/clientId、平台、已注册 Scope、精确回调 URI 与
  `TokenSettings` 实际策略；客户端名称/描述按纯文本渲染，不执行其 HTML/脚本。
- client、state、scope 缺失或非法时返回安全错误与 requestId，不提供“忽略并继续”；
  拒绝走标准 `access_denied`，不创建授权。
- 页面 URL 不出现 token；HTML 与上下文响应均 `no-store`；CSP 禁止内联脚本与外部源。
- 不改变既有 OAuth 协议、客户端注册、权限或审计语义；未配置 React 产物时保留现有
  服务端渲染页作为回退。

## 架构决定

后端 CI（`scripts/check.py` 的 Maven job）没有 Node/pnpm，且本地 Maven 缓存没有
`exec-maven-plugin`，因此不把前端构建耦合进 Maven。React 产物由前端工程独立构建：

- `ptmcp-ui-react/vite.oauth.config.ts` 输出 `dist-oauth/`（两个 HTML 入口 + `assets/`）。
- `ptmcp-authz` 通过 `PTMCP_AUTHZ_OAUTH_PAGES_DIR` 指向该目录，运行时读取
  `oauth/login.html` / `oauth/consent.html` 并提供 `/oauth/assets/**`；未配置时回退旧页面。
- 开发：`pnpm build:oauth` 后，以
  `PTMCP_AUTHZ_OAUTH_PAGES_DIR=<repo>/ptmcp-ui-react/dist-oauth` 启动 authz。
- 部署：AS 宿主的静态资源目录随发布物提供；未提供时仍可回退，但不再作为验收路径。

## 实施与验证

改动范围：`ptmcp-authz` 页面控制器/过滤链/客户端只读映射、`ptmcp-ui-react` OAuth 入口与
组件、HTTP 边界测试、前端单测与构建脚本。不触碰控制台管理页面、数据库迁移或 OAuth 协议实现。

验收标准与命令：

- `pnpm --dir ptmcp-ui-react check`、`pnpm --dir ptmcp-ui-react test`
- `./mvnw -pl ptmcp-authz -am -Dtest=OAuthAuthorizationCodeFlowTest test`
- `python scripts/check.py --all`
- 浏览器验证：真实 authz + 已播种客户端，登录失败/成功、同意/拒绝、非法 client/state、
  窄屏与键盘操作；记录未验证范围。

## 结果与证据

实现（2026-11-04）：

- 后端 `ptmcp-authz`：`OAuthPageController` 在配置 `ptmcp.authz.oauth.pages-directory`
  时返回 React 壳页；新增 `/oauth2/ui/login`、`/oauth2/ui/consent` JSON 上下文端点
  （壳页仍为 `/login`、`/oauth2/consent`）。同意上下文按 SAS 生成的 consent state 通过
  `OAuth2AuthorizationService.findByToken(state, STATE_TOKEN_TYPE)` 查回待授权请求，校验
  客户端绑定、登录主体绑定、scope 属于已存请求且已注册、回调 URI 来自重建的授权请求且
  在注册列表内；客户端 state 只作为回显值，不用于查询。SAS 7.1.2 自行生成 consent
  state，因此删除了原先按客户端 state 暂存的 `ConsentRequestState`。
- 静态资源：`OAuthPageAssetsConfiguration` 提供 `/oauth/assets/**`（365 天 immutable，
  路径遍历返回 404）；页面响应保持 no-store、DENY、no-referrer 与 React CSP。
- 前端 `ptmcp-ui-react`：`src/oauth/` 登录/同意页、`oauth/*.html` 入口、
  `vite.oauth.config.ts` 输出 `dist-oauth/`；`pnpm check` 纳入 `build:oauth`。
- 开发接线：`scripts/Dev-Linux.sh` 在 `dist-oauth/oauth/login.html` 存在时为 authz 导出
  `PTMCP_AUTHZ_OAUTH_PAGES_DIR`；`ptmcp-ui-react/README.md` 与
  `docs/ops/development-linux-server.md` 记录构建与 Windows 启动方式；`dist-oauth/`
  加入 `.gitignore`。

评审与修复（2026-11-04，只读评审发现）：

- High-1：SAS 7.1.2 同意 provider 在已有 `OAuth2AuthorizationConsent` 时会把旧 scope
  合并回 `authorizedScopes`；空 scope POST 不会走 `access_denied`，而会签发携带旧授权的
  授权码。修复：新增 `POST /oauth2/ui/consent/deny`，校验 pending authorization 的
  client/state/principal 绑定与已注册回调，只删除该待授权记录（保留用户既有同意，拒绝
  只针对本次请求），按 SAS 的失败分支返回 `error=access_denied` + 客户端原始 state，
  不创建授权码；拒绝表单改投此端点。独立评审指出首版实现会连带删除既有同意，与页面
  “已授权保留”的承诺矛盾，已按上述语义修正。
- Medium-1：取消勾选“已授权”scope 不会撤销既有授权（SAS 合并行为）。修复：上下文返回
  `grantedScopes`；回退页与 React 页把已授权项渲染为 checked+disabled 并标注“已授权”，
  文案改为“已授权的权限会保留，本次只调整新增权限”。
- Low-1：授权码签发后 consent state 仍可重开同意页或重放同意 POST。修复：
  `SELECT_BY_STATE` 增加 `authorization_code_hash IS NULL`，只解析在途授权；重放返回
  400/无 code。
- Low-4：回退同意页此前直接信任查询串。修复：同意页与上下文只从 SAS 存储的待授权请求
  取 client、scope、redirect_uri，未注册 scope、未注册回调、缺失/伪造 state、跨用户与
  跨客户端均 400；无 scope 时只显示拒绝。
- 评审其余结论：Medium-2 为既定设计（React 壳页本身不含数据，上下文端点才做校验并
  fail-closed，壳页 GET 返回 200 不构成信息泄露），记录为接受的偏差；Low-3（隐藏字段
  单引号转义）经核对 Spring 7.0.10 `HtmlCharacterEntityReferences`，`'` 映射为 `&#39;`，
  `HtmlUtils.htmlEscape` 已覆盖，属误报。

检查（实际执行）：

- `OAuthAuthorizationCodeFlowTest`：36/36 PASS（登录 CSRF、consent state 绑定、
  跨用户/跨客户端拒绝、授权码 + PKCE 全流程；新增：已有同意时拒绝仍为 `access_denied`
  且保留既有同意、拒绝需 CSRF、他人 pending state 拒绝、跨 client 拒绝、授权码签发后
  上下文/同意 POST/拒绝 POST 重放拒绝、grantedScopes 上下文）。
- `OAuthReactPagesTest`：5/5 PASS（壳页、硬化头、immutable 缓存、404、路径遍历）。
- `pnpm check`：PASS（eslint、prettier、tsc、vite build、vite build:oauth）。
- `pnpm test`：210/210 PASS（新增 grantedScopes/denyEndpoint 解析与跨源拒绝用例）。
- `python scripts/check.py --all`：PASS（exit 0；8 模块 `mvnw verify`、隔离质量探针、
  `pnpm check`、`pnpm test` 210/210）。首次运行因 Testcontainers Ryuk 在本机 loopback
  连不上而失败，设置 `TESTCONTAINERS_RYUK_DISABLED=true` 后重跑通过（仅禁用资源回收，
  不影响测试执行）；本机前端检查需 `pnpm_config_verify_deps_before_run=false` 绕开家目录
  workspace 的 pnpm 配置，均属本机环境问题，不是仓库缺陷。

未验证：

- 真实外部 Agent 首次 OAuth 登录 E2E（T19）与浏览器人工验收。
- 独立只读评审已执行（只读子代理），发现项见上；修复后由本代理复验，未再委托第二轮。

## 交接

当前焦点：门禁与独立只读评审均完成，提交推送当前分支。
人工验收待用户执行；T19 真实外部 Agent E2E 仍需在隔离环境补跑。
