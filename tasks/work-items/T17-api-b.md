# T17-api-b：/session 与 /me 接口（前端对接面）

ID: T17-api-b
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-25

## 目标与边界

来源/父任务或用户请求：[parallel-prompts-20261024](../parallel-prompts-20261024.md) C6；父任务 T17；
[API 规格](../../docs/backend/api-spec.md) §3/§6、[前端指南](../../docs/frontend/dev-guide.md)、
[页面规格](../../docs/frontend/page-spec.md)、[安全架构](../../docs/architecture/security.md)。

实现个人门户与会话的完整前端对接面：

1. **会话**（`ptmcp-admin` 新包 `local.ptmcp.admin.session`）：
   - `GET /api/v1/session/csrf`：匿名签发预会话（`PTMCP_ADMIN_PRESESSION`，10 分钟）与 CSRF token；
     已登录时原地轮换会话 CSRF，不重签 Cookie。
   - `POST /session/login`：预会话 CSRF 绑定校验；用户名归一化；未知用户与错误密码同一
     `AUTHENTICATION_FAILED`；10 分钟窗口内第 5 次失败锁定 10 分钟（`LOGIN_LOCKED`，正确密码也拒绝）；
     成功签发不透明会话 Cookie `PTMCP_ADMIN_SESSION`（HttpOnly/Secure/SameSite=Lax/Path=/、
     Max-Age=28800），清预会话，响应 no-store。
   - `GET /session/me`：用户、部门、角色、权限码、管理范围（`DATASOURCE:<id>`）、到期时间。
   - `POST /session/logout`：撤销内存会话与 `web_session_index` 行，清两类 Cookie。
   - `PUT /session/password`：旧密码复核；新密码 12~64 字符且 ≤72 UTF-8 字节、不得与旧密码相同；
     成功后撤销本人全部 ADMIN 会话、PAT、refresh family、authorization 行，清 Cookie 要求重新登录。
   - 每次会话解析都回查 `web_session_index`（`revoked_at IS NULL AND expires_at > now`），
     重启后内存会话消失即失效（fail-closed），不引入 Sa-Token/Spring Security 会话框架。
   - CSRF：Cookie 鉴权的非安全方法必须携带 `X-CSRF-Token`；`Sec-Fetch-Site: cross-site` 拒绝；
     `/me/**` 拒绝 `userId` 查询参数（身份只来自会话）。
2. **个人门户**（`ptmcp-admin` 新包 `local.ptmcp.admin.me`）：
   - `GET /me/access-context`：PLATFORM/DATASOURCE/NAMESPACE 三层，DECLARED 键
     `platform:<id>` 版本 = `scopeVersion/securityVersion`。
   - `GET /me/dictionary`、`GET /me/dictionary/{objectId}`：目录按平台 `catalogMode`
     ——DISCOVER 需 META、HIDE 需 READ；明细 META 缺失 404、READ 缺失 403 `TABLE_NOT_AUTHORIZED`；
     仅返回 ACTIVE 快照列；外键存在时 `foreignKeys=[]` 且 `foreignKeysRestricted=true`。
   - `GET /me/tokens`、`POST /me/tokens`、`DELETE /me/tokens/{id}`：`ptmcp_pat_` + 32 字节随机，
     只存 SHA-256；明文仅创建响应出现一次；默认 30 天/最长 90 天；仅 Tier3 平台可签发；
     列表按状态与游标分页、绝不含明文；撤销本人资源幂等，他人 404。
   - `GET /me/history`、`GET /me/history/{requestId}`：仅本人；默认 7 天、最长 31 天窗口；
     不返回原始 SQL 密文/明文（`sqlMasked` 脱敏）；游标分页。
   - `GET /me/mcp-config`：`transport=streamable-http`、URL 来自 `ptmcp.mcp.url`、认证 `oauth/pat`、
     `validatedVersion=null`、`compatibilityStatus=UNVERIFIED`（不编造兼容结论）。
   - `GET /me/authorizations`、`DELETE /me/authorizations/{familyId}`：本人 refresh family；
     撤销同时置 family 与其 authorization 为 REVOKED，幂等，他人 404。
3. **不做**：前端页面接入与联调（后续切片）、真实 MCP 会话消费 PAT 的校验端点、
   `R<T>` 响应封装（A4 待决）、会话共享存储/多实例（当前进程内存 + 元库撤销索引、
   单实例语义已记录）、用户级 `auth_version`（现按具体会话行撤销）。

不变量：身份只从会话取；未知/他人资源统一 404；写操作 CSRF；敏感响应 no-store；
权限按平台封顶与用户授权交集；拒绝 `userId` 参数；错误响应仅 `{code, codeNumber, retryable}`；
不输出凭证、PAT 明文或原始 SQL。

## 实施与验证

代码：
- `local.ptmcp.admin.session`：`AdminSessionService`（登录、锁定、预会话 CSRF、会话 Cookie、
  撤销索引回查、改密批量撤销）、`SessionController`、`SessionAuthFilter`、`CsrfInterceptor`、
  `NoStoreInterceptor`、`IdentityParamInterceptor`、`AdminWebConfiguration`。
- `local.ptmcp.admin.me`：`MeService`（访问上下文、字典、历史、mcp-config、authorizations）、
  `PersonalTokenService`、`MeController`。
- `AdminStoresConfiguration`：装配会话/门户 Bean 与 `SessionAuthFilter`；`TestPrincipalFilter`
  改为必须显式 `ptmcp.admin.test-principal=true` 才启用（C5 测试同步开启）。
- `ptmcp-admin/pom.xml`：增加 `spring-security-crypto`（BCrypt，版本由 BOM 管理）。
- Hikari 元库池 `initializationFailTimeout=15s`：数据库容器端口转发启动瞬时不可达时等待重试，
  超过 15 秒仍失败即启动失败（不放宽任何鉴权/测试断言）。

真实 MySQL 测试（`SessionApiTest`，Testcontainers + V1..V5 Flyway，MockMvc）：
1. 会话全生命周期：Cookie 属性（HttpOnly/Secure/SameSite=Lax/Max-Age=28800）、预会话清除、
   CSRF 轮换、`/session/me` 字段、登出、旧 Cookie `SESSION_EXPIRED`、匿名
   `AUTHENTICATION_REQUIRED`、跨站 `Sec-Fetch-Site` 拒绝、`web_session_index` 哈希
   与 Cookie 的 SHA-256 一致（防重复哈希回归）。
2. 登录失败不区分原因、5 次锁定且正确密码仍拒绝。
3. 改密撤销旧会话、PAT、refresh family、authorization 与索引行；新密码可重新登录。
4. PAT：无 CSRF 403、201 明文仅一次、前缀 16 字符、库内存哈希、列表无明文、跨用户删除 404、
   撤销幂等、Tier1 平台 `MANAGEMENT_SCOPE_DENIED`、非法 scope/期限 400。
5. 字典：DISCOVER 列出 META 可见对象（payments READ DENY 仍可见但 `authorized=false`），
   明细 orders 正常且外键受限，payments 403，secret（META DENY）404；切 HIDE 后只剩 READ 对象；
   访问上下文 PLATFORM/DATASOURCE/NAMESPACE 与版本键。
6. 历史：仅本人、游标分页、详情、他人 404、`userId` 参数 400、非法窗口 400、无原始 SQL。
7. mcp-config：VSCODE 模板、未知 clientType 400、`validatedVersion` 为空、authorizations 列表、
   跨用户撤销 404、幂等撤销且库状态 REVOKED。

## 结果与证据

- `SessionApiTest`：7/7 绿（真实 MySQL，最终一轮 29.15s）。
- `ptmcp-admin` 全包测试：34/34 绿（`SessionApiTest` 7、`AdminApiTest` 5、
  `AdminApplicationHttpStartupTest` 2、web 边界 20）。
- `ptmcp-persistence` 全量测试：`SecurityStateTest` 唯一失败为 WSL Docker 端口转发
  `Communications link failure`（非断言失败）；单类重跑 11/11 绿。
- 本轮修复的真实缺陷：
  - `AdminSessionService.key()` 双重 SHA-256（`hashHex` 内部已哈希，调用处再次哈希），
    导致登录后所有请求 `SESSION_EXPIRED`；测试新增库内哈希一致性断言防回归。
  - `MeService.decisions()` 以编码资源 ID 建表、却用裸表 ID 查询，ACL 决策全部落空
    （字典 0 行、mcp-config 404）；改为 `ResourceIds.tableIdOf`。
  - 撤销他人 authorization 的测试预期写反（Bob 的 family 应为 ACTIVE），已更正。
  - `CAST(token_hash AS CHAR)` 在 utf8mb4 下对二进制摘要返回 NULL；改用 `HEX()`。
- 环境抖动：WSL2 Docker TLS 转发偶发瞬断；未改断言、未降阈值、未跳过用例。
- `python scripts/check.py --all` 本轮在 `ptmcp-persistence` 的
  `MetadataSchemaMigrationTest` 上再次遇到容器启动期 `Communications link failure`，
  reactor 未进入 admin；单类复跑 6/6 绿，确认与 C6 代码无关，未放宽任何检查。

## 决策点（按要求记录）

- 未引入 Sa-Token（Boot 4 适配未验证）、未引入 Spring Security 会话框架；
  会话为服务端不透明 Cookie + 进程内状态 + 元库撤销索引，单实例范围。
- 平台管理员判定沿用已落地权限码 `ptmcp:platform:scope`；尚无 AS（授权服务器）侧会话。

## 交接

已知缺口（前端联调时需注意）：
- `/me` 系列需要有效 ADMIN 会话 Cookie；`/session/login` 前必须先 `GET /session/csrf`
  并同时携带预会话 Cookie 与 `X-CSRF-Token`。
- 会话与预会话 CSRF 存于进程内存：重启使全部会话失效；多实例部署需外部会话存储（未实现）。
- 改密撤销按具体会话行执行，无用户级 `auth_version`；若未来增加其他凭证类型需扩展撤销集合。
- PAT 目前没有消费端校验端点（MCP 会话切片未完成）；跨平台使用拒绝由签发时平台范围限制体现。
- 字典明细不返回外键列表（仅 `foreignKeysRestricted` 标志）；`R<T>` 外壳未接入。
- mcp-config 兼容性固定 UNVERIFIED，未做真实客户端握手验证。

下一步：C7（T15-b 数据源管理与字典 API），随后 D1（公开客户端 refresh 轮换）与 E1 协调器集成。

### 附录 A：前端可用 OpenAPI 片段（手写，与实现同步）

```yaml
openapi: 3.1.0
info: {title: PTMCP admin session & personal portal, version: 0.1.0}
servers: [{url: /}]
components:
  securitySchemes:
    sessionCookie: {type: apiKey, in: cookie, name: PTMCP_ADMIN_SESSION}
    csrf:
      type: apiKey
      in: header
      name: X-CSRF-Token
  headers:
    NoStore:
      schema: {type: string, enum: [no-store]}
  schemas:
    Error:
      type: object
      required: [code, codeNumber, retryable]
      properties:
        code: {type: string}
        codeNumber: {type: integer}
        retryable: {type: boolean}
    CsrfResponse:
      type: object
      required: [csrfToken, expiresAt]
      properties:
        csrfToken: {type: string}
        expiresAt: {type: string, format: date-time}
    LoginRequest:
      type: object
      required: [username, password]
      properties:
        username: {type: string}
        password: {type: string}
    LoginResponse:
      type: object
      properties:
        userId: {type: integer, format: int64}
        displayName: {type: string}
        expiresAt: {type: string, format: date-time}
    PasswordRequest:
      type: object
      required: [oldPassword, newPassword]
      properties:
        oldPassword: {type: string}
        newPassword: {type: string, minLength: 12, maxLength: 64}
    CsrfIssued:
      type: object
      properties:
        presessionId: {type: object, nullable: true}
        csrfToken: {type: string}
        expiresAt: {type: string, format: date-time}
  responses:
    Unauthorized:
      description: AUTHENTICATION_REQUIRED / SESSION_EXPIRED
      content:
        application/json:
          schema: {$ref: '#/components/schemas/Error'}
    Forbidden:
      description: CSRF_INVALID / MANAGEMENT_SCOPE_DENIED / TABLE_NOT_AUTHORIZED
      content:
        application/json:
          schema: {$ref: '#/components/schemas/Error'}
    NotFound:
      description: RESOURCE_NOT_FOUND（他人资源同此）
      content:
        application/json:
          schema: {$ref: '#/components/schemas/Error'}
paths:
  /api/v1/session/csrf:
    get:
      summary: 匿名签发预会话 CSRF；已登录时轮换会话 CSRF
      responses:
        '200':
          description: OK
          headers: {Cache-Control: {$ref: '#/components/headers/NoStore'}}
          content:
            application/json:
              schema: {$ref: '#/components/schemas/CsrfResponse'}
  /api/v1/session/login:
    post:
      summary: 登录（需预会话 Cookie + X-CSRF-Token）
      parameters: [{$ref: '#/components/parameters/Csrf'}]
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/LoginRequest'}
      responses:
        '200':
          description: 设置 PTMCP_ADMIN_SESSION，清除预会话
          headers: {Cache-Control: {$ref: '#/components/headers/NoStore'}}
          content:
            application/json:
              schema: {$ref: '#/components/schemas/LoginResponse'}
        '401': {$ref: '#/components/responses/Unauthorized'}
        '403': {$ref: '#/components/responses/Forbidden'}
        '429': {description: LOGIN_LOCKED, retryable=true}
  /api/v1/session/me:
    get:
      summary: 当前会话用户、角色、权限与管理范围
      security: [{sessionCookie: []}]
      responses:
        '200': {description: OK}
        '401': {$ref: '#/components/responses/Unauthorized'}
  /api/v1/session/logout:
    post:
      summary: 撤销会话并清除 Cookie
      security: [{sessionCookie: []}, {csrf: []}]
      responses:
        '200': {description: OK}
        '403': {$ref: '#/components/responses/Forbidden'}
  /api/v1/session/password:
    put:
      summary: 修改密码；成功撤销本人全部会话/令牌并清 Cookie
      security: [{sessionCookie: []}, {csrf: []}]
      requestBody:
        required: true
        content:
          application/json:
            schema: {$ref: '#/components/schemas/PasswordRequest'}
      responses:
        '200': {description: 需重新登录}
        '400': {description: INVALID_ARGUMENT（长度/旧密码相同）}
        '401': {description: 旧密码错误或会话失效}
        '403': {$ref: '#/components/responses/Forbidden'}
  /api/v1/me/access-context:
    get:
      summary: PLATFORM/DATASOURCE/NAMESPACE 目录
      security: [{sessionCookie: []}]
      parameters:
        - {name: level, in: query, required: true, schema: {enum: [PLATFORM, DATASOURCE, NAMESPACE]}}
        - {name: platformId, in: query, schema: {type: integer, format: int64}}
        - {name: datasourceId, in: query, schema: {type: integer, format: int64}}
        - {name: cursor, in: query, schema: {type: string}}
        - {name: pageSize, in: query, schema: {type: integer, maximum: 100}}
      responses:
        '200': {description: 返回 items/nextCursor/evaluatedAt/versions}
        '401': {$ref: '#/components/responses/Unauthorized'}
        '404': {$ref: '#/components/responses/NotFound'}
  /api/v1/me/dictionary:
    get:
      summary: 对象目录（DISCOVER 需 META，HIDE 需 READ）
      security: [{sessionCookie: []}]
      parameters:
        - {name: platformId, in: query, required: true, schema: {type: integer, format: int64}}
        - {name: datasourceId, in: query, required: true, schema: {type: integer, format: int64}}
        - {name: databaseName, in: query, schema: {type: string}}
        - {name: schemaName, in: query, schema: {type: string}}
        - {name: keyword, in: query, schema: {type: string}}
        - {name: cursor, in: query, schema: {type: string}}
        - {name: pageSize, in: query, schema: {type: integer, maximum: 100}}
      responses:
        '200': {description: items[] {objectId,databaseName,schemaName,tableName,objectType,tableComment,authorized,metadataVisible}}
        '401': {$ref: '#/components/responses/Unauthorized'}
        '404': {$ref: '#/components/responses/NotFound'}
  /api/v1/me/dictionary/{objectId}:
    get:
      summary: 对象明细（列、字典标题、快照版本；外键受限）
      security: [{sessionCookie: []}]
      parameters:
        - {name: objectId, in: path, required: true, schema: {type: integer, format: int64}}
        - {name: platformId, in: query, required: true, schema: {type: integer, format: int64}}
      responses:
        '200': {description: 明细}
        '401': {$ref: '#/components/responses/Unauthorized'}
        '403': {$ref: '#/components/responses/Forbidden'}
        '404': {$ref: '#/components/responses/NotFound'}
  /api/v1/me/tokens:
    get:
      summary: PAT 列表（不含明文）
      security: [{sessionCookie: []}]
      parameters:
        - {name: status, in: query, schema: {enum: [ACTIVE, REVOKED, EXPIRED]}}
        - {name: cursor, in: query, schema: {type: string}}
        - {name: pageSize, in: query, schema: {type: integer, maximum: 100}}
      responses:
        '200': {description: items[] {id,name,prefix,scopes,platformId,status,createdAt,expiresAt,lastUsedAt}}
        '401': {$ref: '#/components/responses/Unauthorized'}
    post:
      summary: 创建 PAT；明文只在 201 响应出现一次
      security: [{sessionCookie: []}, {csrf: []}]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [name, platformId, scopes]
              properties:
                name: {type: string}
                platformId: {type: integer, format: int64}
                scopes:
                  type: array
                  items: {enum: [mcp:catalog, mcp:schema, mcp:query]}
                expiresAt: {type: string, format: date-time}
      responses:
        '201': {description: '{token, metadata}'}
        '400': {description: INVALID_ARGUMENT}
        '401': {$ref: '#/components/responses/Unauthorized'}
        '403': {$ref: '#/components/responses/Forbidden'}
  /api/v1/me/tokens/{id}:
    delete:
      summary: 撤销 PAT（幂等）
      security: [{sessionCookie: []}, {csrf: []}]
      parameters:
        - {name: id, in: path, required: true, schema: {type: integer, format: int64}}
      responses:
        '200': {description: '{id,prefix,status,...}，status=REVOKED'}
        '403': {$ref: '#/components/responses/Forbidden'}
        '404': {$ref: '#/components/responses/NotFound'}
  /api/v1/me/history:
    get:
      summary: 本人审计历史（默认 7 天、最长 31 天，无原始 SQL）
      security: [{sessionCookie: []}]
      parameters:
        - {name: from, in: query, schema: {type: string, format: date-time}}
        - {name: to, in: query, schema: {type: string, format: date-time}}
        - {name: tool, in: query, schema: {type: string}}
        - {name: result, in: query, schema: {type: string}}
        - {name: verdict, in: query, schema: {type: string}}
        - {name: cursor, in: query, schema: {type: string}}
        - {name: pageSize, in: query, schema: {type: integer, maximum: 100}}
      responses:
        '200': {description: items[] AuditSummary + nextCursor}
        '400': {description: 窗口非法（含 userId 参数）}
        '401': {$ref: '#/components/responses/Unauthorized'}
  /api/v1/me/history/{requestId}:
    get:
      summary: 本人单条审计详情
      security: [{sessionCookie: []}]
      parameters:
        - {name: requestId, in: path, required: true, schema: {type: string}}
      responses:
        '200': {description: AuditDetail}
        '401': {$ref: '#/components/responses/Unauthorized'}
        '404': {$ref: '#/components/responses/NotFound'}
  /api/v1/me/mcp-config:
    get:
      summary: 客户端接入模板（compatibilityStatus=UNVERIFIED）
      security: [{sessionCookie: []}]
      parameters:
        - {name: platformId, in: query, required: true, schema: {type: integer, format: int64}}
        - {name: clientType, in: query, required: true, schema: {enum: [GENERIC, WORKBUDDY, CURSOR, CLAUDE_CODE, VSCODE]}}
      responses:
        '200': {description: '{transport,url,authentication,configTemplate,compatibilityStatus,validatedVersion:null}'}
        '400': {description: INVALID_ARGUMENT}
        '404': {$ref: '#/components/responses/NotFound'}
  /api/v1/me/authorizations:
    get:
      summary: 本人 OAuth 授权（refresh family）
      security: [{sessionCookie: []}]
      parameters:
        - {name: cursor, in: query, schema: {type: string}}
        - {name: pageSize, in: query, schema: {type: integer, maximum: 100}}
      responses:
        '200': {description: items[] {id,clientName,platformId,issuedAt,lastUsedAt,status,absoluteExpiresAt}}
        '401': {$ref: '#/components/responses/Unauthorized'}
  /api/v1/me/authorizations/{familyId}:
    delete:
      summary: 撤销授权（幂等）
      security: [{sessionCookie: []}, {csrf: []}]
      parameters:
        - {name: familyId, in: path, required: true, schema: {type: string}}
      responses:
        '200': {description: '{id,status:REVOKED,revoked:true}'}
        '403': {$ref: '#/components/responses/Forbidden'}
        '404': {$ref: '#/components/responses/NotFound'}
components:
  parameters:
    Csrf:
      name: X-CSRF-Token
      in: header
      required: true
      schema: {type: string}
```

### 附录 B：Cookie 与 CSRF 交互摘要（前端实现要点）

1. 打开登录页：`GET /api/v1/session/csrf`（携带浏览器 Cookie），读取 `csrfToken` 与
   `PTMCP_ADMIN_PRESESSION`。
2. `POST /api/v1/session/login` 同时携带预会话 Cookie 与 `X-CSRF-Token`；成功后浏览器保存
   `PTMCP_ADMIN_SESSION`，预会话被清除。
3. 所有非 GET/HEAD/OPTIONS 请求携带 `X-CSRF-Token`（登录后由 `GET /session/csrf` 轮换取得）。
4. 401 `AUTHENTICATION_REQUIRED`：未登录；401 `SESSION_EXPIRED`：会话失效/撤销，跳登录。
5. 所有响应按 `code` 映射展示；429 `LOGIN_LOCKED` 可提示重试时间；503 可重试。
