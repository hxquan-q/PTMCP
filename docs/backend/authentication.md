# 认证、平台证明与令牌生命周期设计

版本：1.0-design · 2026-10-23 · 状态：待实现。公开客户端refresh、平台证明和跨服务撤销均为自研安全扩展，未通过M0前不开放。技术候选Spring Security 7.1.2/mcp-security 0.1.14，最终依赖以effective POM及真实客户端矩阵锁定。

## 1. 三个独立安全域

| 域 | 凭证/框架 | 信任边界 |
| --- | --- | --- |
| 管理/个人控制台 ptmcp-admin | Sa-Token服务端会话，PTMCP_ADMIN_SESSION Cookie | Cookie仅对应admin后端路径/域，HttpOnly/Secure/SameSite=Lax；写操作CSRF；不接受MCP Bearer当后台登录 |
| OAuth AS ptmcp-authz | Spring Security独立登录会话，PTMCP_AS_SESSION Cookie | 独立登录页/同意页/CSRF；读取同一账号认证规则，不承诺一期跨应用SSO |
| MCP Resource Server ptmcp-gateway | OAuth JWT或个人PAT + 平台proof | 每次/mcp请求独立认证；不依赖前一次initialize、Cookie或Mcp-Session-Id授权状态 |

三应用分进程且gateway依赖树不得出现Sa-Token。共同身份仅通过最小持久适配映射sys_user/security扩展，不把若依LoginUser/ORM实体作为跨模块DTO。所有公网端点HTTPS；反向代理转发头仅信任明确代理地址，issuer/resource/aud不由请求Host生成。

## 2. 账号、登录和会话

用户有效条件为sys_user.status='0'且del_flag='0'；部门/角色停用在权限计算中影响授权，不默认使普通用户永久无法登录，组织政策若需部门停用同步停人需显式批量命令。密码BCrypt按参考源码规则验证；新增密码字符/字节长度须防BCrypt72字节静默截断（API限制见api-spec）；禁止明文/可逆密码。不存在用户仍执行固定代价假哈希以减小时序差异。

账号与IP双限流；密码错误5次锁10分钟，失败响应统一，锁状态写入受控存储，登录成功清账号连续失败但不清恶意IP总限流。登录/失败/锁定/解锁/注销均有安全日志，不包含密码/完整session。Redis故障不关闭限流，不通过仅本地计数允许多副本绕过。

ADMIN和AS session最长8小时，不因页面轮询无限续期；成功登录轮换session ID防固定会话。会话携带userId/authVersion/issuedAt/expiresAt，主库索引与权威状态配合撤销，Redis只是内容缓存。GET /api/v1/session/csrf建立同源预会话，返回csrfToken；登录、改密、注销与任何写需X-CSRF-Token且Origin同源。AS表单登录/同意由独立过滤链生成和校验其CSRF，不接受admin token替代。Cookie Domain最小化、不对整个企业顶级域共享；跨站部署须先ADR确定CSRF与SameSite，不能简单改SameSite=None后跳过校验。

用户主动改密或管理员重置：同一主库事务更新password/auth_version，撤销ADMIN/AS session、所有OAuth family与PAT，写security_event。正常改密不把账号status改DISABLED。停用/删除同样撤销但改变status/del_flag；重新启用也不能恢复旧令牌，必须重新登录/授权。

## 3. 平台Tier与每请求来源证明

平台是服务器分配的platform_id，与client注册/PAT绑定；任何工具入参或X-Platform-ID不能覆盖。请求Header若声明platform_id而与令牌绑定不符，直接403 PLATFORM_MISMATCH，不能“以Header为准”。平台Scope永远是授权上限。

| Tier | 典型部署 | 身份绑定与证明 |
| --- | --- | --- |
| 1 受信后端 | 企业Agent服务 | token端点可用private_key_jwt/mTLS/经审核confidential认证；每次MCP请求还必须mTLS或受核验代理secret+网络约束 |
| 2 管理公开客户端 | 受管理桌面客户端 | PUBLIC + none + S256，平台配置限制scope/目录模式；没有共享secret，不声称公开client_id证明来源 |
| 3 个人终端 | 本人脚本/本地工具 | 绑定个人终端平台，PAT或公开OAuth；不能用隐藏Header升级到Tier1 |

Tier1 proof选择：

1. mTLS：在gateway或受控入口验证证书链、有效期、用途、轮换/撤销和platform映射；如TLS在Nginx终止，gateway只允许来自该代理的连接，代理删除外部同名证书Header再设置签名/受信转发信息，公网不可直达gateway。仅接受浏览器自填X-Client-Cert毫无证明力。可进一步绑定JWT cnf，但是否启用取决于客户端M0实测，不伪称默认支持。
2. 代理secret：仅用于企业自管后端代理，secret由代理注入、外部入站同名Header必须剥离；gateway同时验证固定来源网络/代理身份和secret轮换版本。secret存密钥服务/加密配置，恒时比较，不记录，不下发桌面/浏览器。该模式证明的是受信代理边界，不是假装持有client_id的所有请求都可信。

private_key_jwt证明token端点的一次客户端认证，后续普通Bearer若被盗仍能使用，故不能替代上述proof。CIMD域名、DCR登记、User-Agent、Origin、自报平台Header也不能代替。Tier1 proof失败在工具调用前HTTP403，不能先执行再审计告警。

实现契约（2026-10-26候选，待部署联调）：`platform_registry.proof_config_cipher`存放用部署凭证密钥加密的JSON，`proof_key_id`记录密钥版本。

- MTLS：`{"mode":"MTLS","fingerprints":["<SHA-256 hex>"],"proxyCidrs":["<CIDR>"]}`。直连TLS读取Servlet `X509Certificate`；经代理转发时仅在`proxyCidrs`内读取`X-PTMCP-Client-Cert-Fingerprint`（hex SHA-256）。
- PROXY_SECRET：`{"mode":"PROXY_SECRET","secret":"<base64>","proxyCidrs":["<CIDR>"]}`。仅在`proxyCidrs`内读取`X-PTMCP-Proxy-Secret`（base64），恒时比较。
- 入口必须删除外部入站的同名证明Header；missing/格式不符按`PLATFORM_PROOF_REQUIRED`，不匹配/不受信来源按`PLATFORM_MISMATCH`，策略不可解密按`SECURITY_STATE_UNAVAILABLE`。证明值不进入日志或审计payload。

## 4. Authorization Code + PKCE 完整流程

~~~mermaid
sequenceDiagram
  participant C as 客户端
  participant G as gateway
  participant A as authz
  participant U as 用户
  participant M as 元库主库
  C->>G: 无Bearer访问/mcp
  G-->>C: 401 + protected-resource发现
  C->>A: authorize(client_id,redirect_uri,state,resource,scope,challenge S256)
  A->>U: 独立AS登录/同意
  A->>M: 校验用户/平台/客户端+持久化一次code摘要
  A-->>C: 精确redirect_uri + code + state
  C->>A: token(code,verifier,同一redirect_uri/resource)
  A->>M: 原子消费code + 创建授权族/refresh摘要
  A-->>C: access JWT(10分钟) + 受控refresh
  C->>G: Bearer + Tier1时每请求proof
  G->>M: 主库确认安全状态（最多陈旧5秒）
  G-->>C: MCP响应
~~~

authorize必须response_type=code、PKCE=S256、精确注册redirect_uri、客户端与platform有效，scope是客户端允许∩平台能力∩用户同意；最终表授权仍执行时计算。resource/aud只允许部署配置的MCP资源，防token redirect。code短时有效期由AS安全配置固定并验收，一次消费，绑定client/redirect_uri/resource/challenge/user/authVersion。拒plain PKCE、无challenge的PUBLIC、implicit/password grant、任意回调前缀匹配。

资源标识使用部署的 canonical MCP URI，例如 `https://gateway.example/mcp`；
`ptmcp.authz.oauth.audience`、`ptmcp.gateway.resource` 与发现文档的 `resource`
必须逐字一致。仅 HTTPS 或开发用回环 HTTP，路径固定 `/mcp`，拒绝 userinfo、query、fragment、
非法端口及控制字符；不从 Host/Forwarded 生成。授权码与 refresh 的 token 请求均为无查询参数的
表单 POST，携带且只携带一个相同 `resource`；缺失、重复或不匹配返回 OAuth `invalid_target`，
在 grant 处理和消费前拒绝；PUBLIC PKCE 的前置客户端认证仍可只读查找 code。
原始资源持久保存在授权 attributes，换码/刷新生成 JWT 时复核该绑定。
旧的 opaque audience 或未保存 resource 的授权不能自动迁移到新 audience，须重新授权；
修改部署 URI 时同步 AS/gateway 配置，存量 code/refresh 不得用新配置重新绑定。

桌面custom scheme（如workbuddy://）只可逐客户端精确登记，须核实实际回调格式/ownership；loopback允许RFC规定的端口差异时单独配置明确URI模板，仅127.0.0.1/[::1]，不把localhost任意DNS或公网http都放开。Web redirect必须https，禁止fragment、userinfo、开放重定向嵌套参数；同意页显示可信client显示名、平台、请求scope，metadata文本不能注入HTML。

JWT至少iss、sub(userId字符串)、aud、exp、iat、nbf、jti、client_id、platform_id、sid、auth_version、scope，TTL10分钟；不装整套表授权。算法显式白名单，禁止none/算法混淆/JWT自带任意jku/x5u；通过固定issuer发现/JWKS缓存取公钥，签名key轮换保留覆盖未过期token，时钟偏差参数全局固定且测试。sid指向主库可撤销family/授权会话，签名有效但family撤销依然拒绝。每请求核验用户、客户端、平台、安全版本与family状态；PAT采用独立认证provider，不能按JWT格式失败后任意降级接受未知token。

## 5. 公开客户端 Refresh 扩展链

默认Spring生成器对none客户端不发refresh只是第一处限制；必须全链实现以下组件，均配置精确支持范围，未实装前PUBLIC refresh feature flag关闭：

| 组件 | 必须实现的行为 |
| --- | --- |
| Token endpoint converter | 只接受application/x-www-form-urlencoded的refresh_token grant；无重复参数；读取client_id、refresh_token、scope/resource；不从Header补平台；严格大小限制，不记录token |
| PUBLIC client authentication converter/provider | 仅已登记PUBLIC、method=none、grant允许refresh时认证client_id；拒secret伪造/降级；确认客户端未停用且平台仍有效；不能复用仅支持authorization_code的默认provider后假装refresh已通 |
| Refresh grant AuthenticationProvider | 定位hash/family/client/user；验证绑定、scope不得扩大、resource不变、到期、authVersion、用户/平台/客户端状态；执行下节原子轮换和重放撤销 |
| Token generator/customizer | 强随机refresh值，仅返回一次；生成JWT正确sid/authVersion/platform_id/aud；PUBLIC是否有refresh取显式批准配置，不能无条件对全部none发放 |
| Authorization/family persistence | 摘要索引、旧token墓碑、generation、乐观锁/行锁、清理、事务提交后响应；不把raw refresh塞默认attributes明文 |
| Revocation + Resource Server校验 | 整族撤销传播到JWT；每次MCP验证主库新鲜状态；注销/改密/用户或client停用必须穿透 |

CONFIDENTIAL refresh同样用family模型，其客户端认证按注册method执行，不允许提交client_id就变PUBLIC。刷新是认证续期，不是绕过账号锁/停用和平台scope检查的后门。因密码错误导致的短暂登录锁可按认证规则禁止新授权/refresh，状态语义与人工停用不同。

公开客户端撤销也须完整扩展：RevocationRequestConverter仅接受表单client_id、token、可选token_type_hint且无重复字段；PublicRevocationClientAuthenticationProvider只对已登记PUBLIC/method=none客户端开放该端点，不要求code_verifier（它不是code兑换）。RevocationProvider/Handler必须验证所提交token的摘要/签名对应本client及family绑定，持有真实token是撤销该token/族的能力，不能仅凭公开client_id撤销其他人的族；未知token、错误hint或token不属于该client统一标准HTTP200且不泄露存在性，不产生实际跨client撤销。已知refresh撤销整族，已知access根据sid撤销对应族；提交事务后才完成响应，写失败返回标准暂不可用而非虚假成功。CONFIDENTIAL沿注册认证方式验证，并同样检查拥有关系。该PUBLIC revocation converter/provider/handler与refresh一起纳入M0受控扩展及重放/越权测试；即使扩展尚未开启，用户仍可经/me/authorizations在本人会话撤销。

一期不开放公共introspection端点；若后续需要仅限明确受信CONFIDENTIAL客户端、绑定资源audience和最小scope，PUBLIC不得用作token存在性/用户信息探测接口。

## 6. 严格轮换与重放事务

family绝对寿命30天，idle寿命7天；refresh新过期=min(now+7d,family.absoluteExpiresAt)。首次授权创建family和generation=0 token。令牌≥256bit随机，数据库只保存hash/HMAC摘要和keyId；已消费摘要为墓碑，保留至family完整过期及access JWT覆盖窗口结束。

~~~text
begin transaction
  read token digest -> family_id (不暴露存在性)
  SELECT sys_security_state FOR UPDATE
  SELECT sys_user_security FOR UPDATE
  SELECT family FOR UPDATE
  SELECT refresh_token FOR UPDATE
  verify client/user/platform/authVersion/family validity and all time bounds
  if token.status == CONSUMED:
      family.status = REVOKED; revoke_reason = REPLAY
      persist security event; commit; return invalid_grant
  if token.status != ACTIVE: rollback; return invalid_grant
  mark token CONSUMED with consumed_at
  insert successor ACTIVE with generation + 1 and unique digest
  update family idle deadline/version and authorization
  prepare JWT/refresh response in memory (not visible to network)
  persist security event; commit
return tokens only after commit confirmed
~~~

竞争同一旧token只一次消费成功，后到请求看到CONSUMED会撤销整族；即使先成功的响应已到客户端，其JWT也在最多5秒权威核验窗口内失效。不得为了体验无条件给“几秒内”重用旧token再发新token，也不缓存可重放的refresh明文响应。客户端必须single-flight刷新；响应丢失/进程重试产生冲突则重新授权，无法满足该行为的客户端禁用PUBLIC refresh并用重新授权/PAT路径。

数据库事务提交结果不明时不能重试消费后宣称成功；按invalid_grant/暂不可用结束并要求重新授权或查族状态，旧token重用仍走严格规则。事务回滚可继续使用旧token的语义只由数据库持久状态决定，不由客户端异常猜测。签名token已生成但事务未提交时绝不能写HTTP响应。主动撤销family是幂等，墓碑仍保留；批量用户撤销以auth_version即时生效，逐family清理可以异步。

## 7. PAT

普通用户在/me/tokens创建，绑定本人Tier3平台和范围子集；默认30天最大90天，随机≥256bit，只展示一次，存prefix和摘要。HTTP Authorization: Bearer <pat>，不放URL/日志/剪贴板历史自动保存；明文只在用户主动复制时返回。PAT不能用于后台登录/AS客户端secret，也不能凭X-Platform-ID变受信后端。

验证按prefix定位候选，恒时摘要比较；检查expiresAt/status/user authVersion/platform状态、安全快照新鲜性和scope。last_used_at可节流写，不能影响撤销判断；删除PAT提升安全版本并写outbox。权限变化不必重新发PAT，每次工具仍动态授权。创建请求丢失不重放一次明文，客户端通过元数据撤销后另创。

## 8. DCR/CIMD 与 SSRF 防线

DCR为可关闭能力；初始注册最小mcp:catalog、无平台资源范围、不可成为Tier1、不可自行声明platform_id/可信proof。管理员审核/绑定平台后才可能有业务能力，注册/同意不自动产生表授权。配额限制每IP/账号注册数、请求长度、URI数量和client总数；registration access token独立摘要存储、最小权限，客户端不能编辑Tier与scope上限。
DCR保持关闭时，客户端须先由平台管理员通过 `POST /api/v1/admin/platforms/{id}/clients` 预注册为
PUBLIC + none + S256，登记精确回调URI、scope和平台绑定；客户端侧只配置该 `client_id` 与回调，
静态`client_id`不证明平台归属，仍需用户授权与平台/用户权限交集。当前未提供匿名自助注册入口。

CIMD/jwks_uri/metadata_uri一期外抓只允许公网https/443和批准域名；先解析所有A/AAAA拒loopback、link-local、云元数据、private/保留地址，企业受管域白名单也必须解析公网IP，不设私网例外（业务数据源连接另走独立允许内网名单）。连接时固定已验证IP并校验TLS域名，避免DNS rebinding。禁止userinfo/fragment、非HTTP协议；默认不跟随重定向，如业务需要则每跳同样验证并限制次数。限制连接/读取时间、压缩后/解压后体积、内容类型、JSON深度，不能把凭证/cookie带向metadata域。内容缓存含来源/摘要/有效期，变更redirect/JWKS安全参数需重新核验；URL可被控制不代表调用者是后端。

## 9. 验收与失败策略

M0必须一公开客户端none+S256与一受信后端真实验证：发现、准确回调、AS登录/CSRF、code一次消费、access过期、refresh全链、并发重放、响应丢失、撤销、平台proof、资源audience。M2补WorkBuddy/Cursor/Claude Code/VS Code具体版本矩阵，未实测项标未验证，不能填“兼容”。

负测包括公开客户端冒secret、confidential降级none、同名平台Header覆盖、其他client刷新、scope/resource扩大、CIMD SSRF/DNS变化、错误签名/issuer/aud、family撤销后JWT、用户改密后PAT/session、主库不可确认、Redis丢消息等。认证/平台失败在HTTP过滤链401/403；Tool内授权另按isError=true。日志只留token指纹/ID不留raw，异常信息不能回显refresh或client secret。
