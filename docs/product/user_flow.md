# PTMCP 用户业务流程

版本：1.0-design · 日期：2026-10-23 · 状态：待实现、待走查。

流程关联[PRD](PRD.md)中的 FR 编号。[总体方案](../总体方案.md)规定安全边界；[页面规格](../frontend/page-spec.md)、[仓库内低保真](../frontend/wireframes.md)和[API 契约](../backend/api-spec.md)承接实现。暂无外部原型或已部署地址。

## 1. 管理员完成首次开通（FR-02～08）

前置条件：部署已完成，管理员有管理权限，网络策略允许访问测试源，已准备只读数据库账号与平台接入类别。操作不需要管理员先获得业务表 READ。

```mermaid
flowchart TD
  A[控制台账密登录] --> B[维护部门/用户/角色]
  B --> C[新建数据源: 类型/地址/库/只读账号/池预算]
  C --> D[保存并测试连接]
  D -->|连接失败| E[查看脱敏原因和requestId]
  E --> C
  D -->|通过| F[触发Schema同步并查看任务]
  F -->|失败或过期| G[修正配置/处理差异后重新同步]
  G --> F
  F -->|完整快照| H[确认对象变化并维护业务字典]
  H --> I[创建平台并确定Tier/Scope/目录模式]
  I --> J[选择部门/角色/个人]
  J --> K[编辑META/READ Allow/Deny与起止时间]
  K --> L[按目标用户和平台预览有效权限及差异]
  L -->|无预期READ| K
  L -->|核对通过| M[提交授权并显示版本/生效说明]
  M --> N[目标员工通过OAuth或PAT真实验证]
  N --> O[目录→结构→只读查询→审计requestId核对]
```

测试连接只证明网络与认证，不代表 Schema 完整、SQL 方言已兼容或用户有查询权。同步失败保留任务错误，不发布部分成功快照。平台 Scope 与个人权限各自检查，缺一不可。管理员查看“有效权限预览”不等于代替员工执行查询；最终验证必须使用目标员工自己的凭证。

页面保存包含资源版本；409 时重新读取服务端变更并人工合并，不静默覆盖。删除/重建对象需重新确认授权；不能以同名显示名恢复旧授权。正常任务目标 10 分钟，网络/证书/数据库账号准备时间不计入。

## 2. 员工 OAuth 首次接入（FR-09、18）

```mermaid
sequenceDiagram
  actor U as 员工
  participant P as 个人门户
  participant C as 外部Agent客户端
  participant G as MCP网关
  participant AS as 授权服务
  U->>P: 登录并查看已验证客户端配置
  P-->>U: 通用/mcp地址与接入说明（不含个人token）
  U->>C: 粘贴配置
  C->>G: 连接/mcp
  G-->>C: 标准认证发现/挑战
  C->>AS: 授权请求(client_id, redirect_uri, S256)
  AS->>U: 独立登录与授权页
  U->>AS: 校验客户端/平台/范围并同意
  AS-->>C: 一次性code及state
  C->>AS: code + verifier（公开端不使用共享secret）
  AS-->>C: access token；满足闸门时签发refresh token
  C->>G: Bearer + Tier1所需逐请求证明
  G->>G: 签名/受众/期限/平台/用户版本/授权族核验
  G-->>C: 按有效权限响应MCP工具
```

授权页拒绝、账号锁定、回调不匹配、PKCE 不合法或平台停用时不创建可用授权。回调 scheme 和客户端版本以 M0 实测清单为准。控制台的 `PTMCP_ADMIN_SESSION` 不直接当作 `PTMCP_AS_SESSION`；一期可能再次登录，页面需明确原因。

## 3. 到期、刷新与竞争（FR-10）

```mermaid
flowchart TD
  A[Access JWT到期:10分钟] --> B{客户端与平台是否已通过公开刷新验收?}
  B -->|否或无refresh token| R[重新走授权流程；不支持时使用个人PAT]
  B -->|是| C[客户端single-flight提交当前refresh token]
  C --> D{账户/平台/授权族有效且未过绝对30天或空闲7天?}
  D -->|否| R
  D -->|是| E{事务中旧token是否未消费?}
  E -->|是| F[消费旧token并保存墓碑，签发新token对]
  E -->|否| G[检测旧token重用，撤销整族及关联JWT]
  F --> H{响应是否成功收到?}
  H -->|是| I[客户端原子替换本地凭证]
  H -->|响应丢失/并发冲突| R
  G --> R
```

不存在“几秒内旧 token 重试无条件放行”的产品保证。网关不自动刷新用户凭证，也不重放 SQL；客户端串行刷新、限次重试和重新授权能力属于兼容性验证项。公开刷新默认关闭到 M0 验收通过；只修改 token generator 不满足验收。

## 4. PAT 创建、使用和撤销（FR-11）

```mermaid
flowchart TD
  A[个人门户→PAT] --> B[名称/有效期:默认30天、最长90天]
  B --> C[CSRF校验后创建]
  C --> D[仅本次响应展示完整PAT]
  D --> E[复制至本地客户端Authorization Bearer]
  E --> F[关闭弹窗并清除页面内存中的PAT]
  F --> G[列表仅见前缀/有效期/状态]
  G --> H{需要终止或遗失?}
  H -->|终止/疑似泄露| I[撤销PAT，显示影响说明]
  H -->|遗失且仍需使用| J[撤销旧PAT并新建]
  I --> K[新查询最大5秒生效；在途尽力取消]
  J --> B
```

完整值不进入 URL、localStorage、sessionStorage、分析事件、错误上报或页面快照。用户没有复制成功时不自动重新创建；应检查列表并撤销不再使用的凭证。PAT 固定为个人终端平台，添加 Header 不能提升信任等级。

## 5. 目录、结构与拒绝处理（FR-12～15、17）

```mermaid
flowchart TD
  A[已认证Agent选择datasource_id] --> B[查询库表目录]
  B --> C{目录模式}
  C -->|DISCOVER| D[平台范围内表名/说明/authorized，META Deny仍隐藏]
  C -->|HIDE| E[仅有效META或READ表]
  D --> F{有效READ?}
  E --> F
  F -->|否| R[不返回字段；说明仅目录可见/联系管理员]
  F -->|是| G[读取表结构:1至20张]
  G --> H[Agent生成单SELECT]
  H --> I[预算/词法/AST/只读/白名单/关系列绑定/ACL]
  I -->|静态拒绝| X[Tool isError=true；不借业务连接]
  I -->|通过| J[限定名与LIMIT改写→重解析→持久START]
  J --> K[最后状态核验→只读执行]
  K -->|完成| L[持久结束事件→类型安全结果与截断元数据]
  K -->|超时/取消| M[确认终止并清理；否则淘汰连接]
  M --> X
  X --> N[按requestId查本人历史；不自动重放SQL]
  R --> O[联系管理员申请，不提供一期内置审批流]
```

HTTP 401/403 表示认证或平台过滤链拒绝；进入 Tool 后的 SQL/权限/超时错误统一 `isError=true`。拒绝不是空结果。不能通过反复试错获取被隐藏对象名；提示仅包含调用者可见的信息。截断成功也不是完整结果，客户端应收窄查询或使用聚合。

## 6. 撤销与组织变更（FR-01、02、06～08、10、11）

```mermaid
sequenceDiagram
  actor A as 管理员/本人
  participant Admin as 管理服务
  participant DB as MySQL主库
  participant R as Redis/事件通知
  participant G as 各网关副本
  A->>Admin: 停用/改密/角色部门变更/撤销/Scope调整
  Admin->>DB: 同事务写状态/版本/global epoch与安全事件
  DB-->>Admin: 提交成功
  Admin-->>A: 返回权威版本与影响说明
  Admin->>R: 发布失效通知
  R-->>G: 触发失效（快速路径）
  G->>DB: 最迟在权威状态陈旧5秒前重新核验
  G->>G: 排队出队与JDBC提交前再次核验
  G-->>G: 拒绝新查询；在途尽力取消
  G->>DB: 每30秒补扫安全事件（补偿）
```

权限自然到期/未来 DENY 生效无需新事件：有效权限缓存同时保存最近未来时间边界并在每次命中检查。主库不可确认、版本已变但权限重建未完成时拒绝新 SQL。消息丢失、Redis 命中和前端“保存成功”均不能绕过此规则。

## 7. 审计排障与解密（FR-16、17、19）

普通用户从个人历史选择 requestId，查看本人脱敏调用摘要和下一步动作。安全审计员使用审计列表按 verdict/error 分类，进入详情核对守门规则、对象、耗时与持久化状态。无解密权时不渲染解密按钮，服务端仍独立校验。

解密要求独立 `ptmcp:audit:decrypt`、受控审计范围与理由，任何角色默认无权。满足条件的人员填写理由，确认将显示敏感内容后请求解密；服务端先审计该访问，再返回短期展示内容。解密失败不回退显示密文或明文日志。关闭详情/会话失效后立即清空内存，禁止缓存和常规导出。

参考[用户手册](../ops/user-manual.md)、[FAQ](faq.md)。当前这些流程均为设计目标；必须由测试证据将状态推进至已验收。
