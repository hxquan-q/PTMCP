# 安全架构与威胁模型

版本：1.0-design · 2026-10-23 · 状态：控制设计，待实现验证。审查依据为[原审查报告](../reviews/PTMCP_方案审查_2026-10-23.md)及用户[review](../reviews/review.md)。

## 1. 资产、边界与假设

资产为业务数据、数据库凭证、组织账号、平台Scope、授权规则、OAuth/PAT、审计原文、密钥与备份。员工输入、Agent生成的SQL、数据库文本、元数据注释、客户端注册元数据和浏览器输入均不可信；数据库返回文本只能当数据，不能成为服务端执行指令。

边界包括：浏览器↔控制台、Agent↔MCP、客户端↔AS、管理配置↔业务网络、CIMD↔互联网、guard↔执行器、网关↔权威安全状态、应用↔审计与密钥服务。

业务库管理员及部署管理员为受信角色，接入库必须限制DDL与自定义代码变更；本系统不能对抗拥有数据库所有者或生产宿主root权限的攻击者。只读账号是写入防线，不会自动提供不同员工之间的读隔离。用户已经合法取得并下载的数据无法因事后撤权回收。

## 2. 威胁与验证映射

| 威胁 | 场景 | 强制控制 | 验证依据 |
| --- | --- | --- | --- |
| 平台冒充 | public client_id/隐藏Header冒充后端 | Tier1每请求证明、默认最小Scope、Header冲突拒绝 | R4、FR-06、认证测试 |
| 用户凭证盗用 | PAT/RT/JWT外泄 | TTL、哈希/密文、auth_version/sid、严格轮换、吊销 | R3/R5、FR-10/11 |
| SQL表越权 | 同名CTE/派生别名抹表 | 作用域关系/列绑定、默认拒绝 | R1/R2、四反例回归 |
| 副作用与文件访问 | 可执行注释、UDF、CAST、表函数 | 词法、全树白名单、可信解析环境、最小DB权限 | R7、guard测试 |
| 资源耗尽 | 递归SQL、笛卡尔积、大字段、并发 | 输入/AST预算、SQL超时、数据库端输出限幅、配额 | R6、内存/取消测试 |
| 撤权失效 | 丢消息、缓存续命、grant自然到期 | 主库确认≤5s、绝对时间边界、提交前复核 | R5、故障注入 |
| 审计缺失 | 队列满、执行后进程崩溃 | START准入持久、terminal/WAL、UNKNOWN恢复 | R9、崩溃恢复测试 |
| 控制台越权 | 改userId查他人历史、越级授权 | 服务端主体、菜单和资源委托范围、独立解密权限 | FR-07/16/17、API测试 |
| SSRF | CIMD指向metadata、JDBC目标滥用 | 不同出站允许名单、重定向/DNS校验 | 注册/配置测试 |

测试名称和输入以[测试用例](../test/test-case.md)为准，不把表格中的控制描述写成“已通过”。

## 3. 身份与授权约束

AS 与 admin 使用同一账号目录和密码/停用/锁定规则，独立会话。MCP JWT 验签还需issuer/audience、用户状态/version、family状态、平台证明；忽略这些在线状态的“纯JWT无状态认证”不符合本方案。

管理权限不等于可任意授予任何资源。平台管理员维护最高Scope；授权管理员只能在获委托的部门/角色/资源范围操作，不得给自己额外授权或解除更高层Deny。安全审计员默认只查看脱敏日志，原文解密需要单独权限和访问理由。所有角色的授权修改记录前后值、操作者与请求ID。

READ与META规则、平台交集、到期语义以[权限设计](../backend/authorization.md)为权威细节。二期行列限制未启用前，界面不能以灰色字段暗示已执行脱敏。

## 4. 凭证与密码材料

数据库密码和原SQL使用AEAD信封加密；记录算法、key_id、nonce、ciphertext/tag与绑定对象AAD。随机nonce不得复用。主密钥不写入Git、数据库同卷或镜像；轮换支持新写新密钥、旧数据受控重加密，密钥撤销前完成恢复验证。

PAT/平台随机secret用高熵生成，仅创建时展示一次；数据库保存索引前缀及不可逆哈希/密钥化哈希，校验使用常量时间比较。日志和URL不能出现这些值。密码保留若依已验证的BCrypt适配；不把随机token与低熵用户密码混用同一存储策略。

授权服务器签名密钥独立于数据加密密钥，JWKS发布公钥；新旧签名kid并存至旧JWT最大有效期结束并考虑时钟偏差，私钥轮换有版本记录。时钟使用UTC且监控NTP偏移，超出预算停止签发/拒绝不可靠时间决策。

## 5. 客户端注册与外联

DCR公开注册仅创建最小Scope客户端，不信任请求自报platform_id、client_name、logo、scope。客户端映射需管理确认；保存的redirect_uri必须精确匹配授权请求，禁止扩大到任意路径/任意域名。原生回调只在经过测试的scheme与loopback规则中例外，不能整体放宽校验器。

CIMD必须HTTPS、精确client_id、有限响应大小/读取超时、JSON schema校验；验证DNS所有解析结果并限制跳转，拒绝loopback、私有、链路本地、云metadata及IPv6等价地址，必要时固定解析IP或使用出站代理。元数据缓存需有TTL/变更校验，不无限缓存已变更的redirect_uri。

业务数据源需要访问公司内网，不能直接套用CIMD公网规则。数据源只允许预设数据库类型、驱动和连接属性；根据批准的业务网络/端口允许名单连接，禁止任意JDBC URL、任意driverClass、文件路径、动态插件和数据库凭证出网。保存与连通测试均做同样校验，DNS变化重新验证。

## 6. SQL与输出安全

词法扫描必须按方言处理引号、注释和字面量，不能用简单正则把字符串内容误当写入语句。长度/深度/表达式预算先于昂贵解析。原版TablesNamesFinder仅用于差异诊断，任何“为空就允许”的快捷分支都不允许。

最终SQL来自单一AST，物理关系、列引用和输出投影必须维持可证明的等价语义；参数/限定标识符分别处理，不能用绑定参数代替标识符。Guard输入/输出上下文版本在执行前核验。执行器没有对外暴露的executeRaw接口。

4KiB单元格与1MiB总响应是产品输出约束，不能作为驱动内存证明。M0要测试实际锁定驱动的字段/行缓存、Unicode、JSON/BLOB、类型转换、宽列和高并发；不可控输出在业务执行前拒绝，不能把OOM当正常截断。

数据库文本和注释在UI使用转义文本展示，不使用未经清洗的innerHTML/Markdown链接执行。错误响应不回传数据库栈、连接串、密码、系统文件路径或超出可披露范围的对象细节。

## 7. 审计、隐私与故障响应

外部日志只发错误分类、阶段、request_id、版本与指纹；userinfo/IP等字段最小化并限制访问。查询结果不持久化。解析失败只保留哈希/长度与拒绝码，不将未脱敏SQL写入普通日志。

审计存储不可用时不发起新SQL；已执行但无法确认结局的调用标为UNKNOWN，不自动重放。原SQL解密本身必须先有审计准入；不存在“审计员能无痕查看原文”的例外。

凭证疑似泄露时先撤销family/PAT/平台secret并提升相关版本，再调查审计；不先删日志。恢复事件须验证所有副本失效、在途取消结果和密钥影响范围。详细步骤见[运维手册](../ops/operation-manual.md)。

## 8. 设计证据与待验证项

认证、会话、授权、日志/错误处理以 [OWASP ASVS 5.0.0 自查清单](../test/asvs-checklist.md)跟踪具体条款、证据和已知差距。选定条款映射不代表完整 L2/L3 达标；规则变更同步自查，格式/静态检查不能代替安全运行验证。

已证实事实包括原解析器漏表、Spring默认公开客户端不发RT及相关默认入口限制、SET LOCAL的事务范围、MySQL分区唯一键约束、特定pgJDBC版本stream方法中的物化路径。参考：[Spring刷新生成器](https://raw.githubusercontent.com/spring-projects/spring-security/7.1.2/oauth2/oauth2-authorization-server/src/main/java/org/springframework/security/oauth2/server/authorization/token/OAuth2RefreshTokenGenerator.java)、[pgJDBC源码](https://raw.githubusercontent.com/pgjdbc/pgjdbc/REL42.7.8/pgjdbc/src/main/java/org/postgresql/jdbc/PgResultSet.java)、[PG SET](https://www.postgresql.org/docs/18/sql-set.html)、[MySQL分区限制](https://dev.mysql.com/doc/refman/8.4/en/partitioning-limitations-partitioning-keys-unique-keys.html)。

新guard、完整refresh扩展、多客户端兼容、驱动输出限幅、≤5秒撤权、内存/性能/恢复均尚待实现验证。生产准入必须提供与构件版本一致的证据包，不以本文的设计描述替代测试结论。
