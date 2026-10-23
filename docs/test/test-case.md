# PTMCP 验收与安全回归用例

版本：1.0-design · 日期：2026-10-23 · 状态：82个稳定主用例规格，全部PTMCP用例待实现后执行。

使用 [test-plan.md](test-plan.md) 中的合成夹具和环境；FR编号见 [PRD](../product/PRD.md)，R编号见 [review](../reviews/review.md)。每行是独立稳定主用例，可展开方言、引号、主体与故障参数形成≥300条内部回归；主用例数量本身不冒充已经达到300条。新增用例续号，删除需求保留编号并注明原因，不重新分配旧编号。

统一步骤约定：配置前置→发送所列请求→断言协议响应、业务连接/语句计数、状态与审计→清理夹具。所有预期均为**设计预期**。表中“0业务连接”指guard否决发生前不借业务池，元库审计连接允许存在。除单元规则测试外，结果等价必须执行真实MySQL/PG，并采用有序/无序的正确比较口径。

## 1. SQL作用域、关系与列改写

前置：U-A可读orders/users不可读salary；安全测试账号U-B可读salary且平台包含salary；数据库存在对应夹具。R1六例同时以假执行器验证集合、以有权限账号验证合法SQL结果、以无权限账号验证拒绝。

| ID | 级别 / 溯源 | 前置与输入 | 操作步骤 | 预期 |
| --- | --- | --- | --- | --- |
| TC-GRD-001 | P0 / FR-14,R1 | `WITH x AS (SELECT * FROM orders) SELECT * FROM x JOIN users u ON 1=1` | 绑定→改写→有权账号执行原SQL/final_sql | 物理集合恰为orders/users，无CTE伪表；实际结果等价 |
| TC-GRD-002 | P0 / FR-14,R1 | `WITH users AS (SELECT * FROM salary) SELECT * FROM users` | U-B执行；再U-A执行 | 物理集合salary；有权结果等价，无权isError且0业务连接 |
| TC-GRD-003 | P0 / FR-14,R1 | `SELECT s.* FROM salary s CROSS JOIN (SELECT 1 AS n) salary` | 比较绑定及列别名；分别授权/拒绝 | salary不可被派生别名移除，s.*保持绑定；无权0业务连接 |
| TC-GRD-004 | P0 / FR-14,R1 | `SELECT * FROM salary WHERE EXISTS (WITH salary AS (SELECT 1 AS n) SELECT * FROM salary)` | 核对子查询作用域并执行 | 外层salary为物理表，内层salary为CTE；有权结果等价 |
| TC-GRD-005 | P0 / FR-14,R1 | `WITH salary AS (SELECT * FROM salary) SELECT * FROM salary` | 双方言对照非递归规则；执行与拒绝各一次 | 定义体的salary绑定真实表，后续引用为CTE；无法确认方言语义则拒绝，不漏表 |
| TC-GRD-006 | P0 / FR-14,R1 | `SELECT 1 FROM salary UNION ALL SELECT * FROM (SELECT 1 AS n) salary` | 分支独立绑定与等价比较 | 第一分支salary仍存在，第二分支别名不污染；无权0业务连接 |
| TC-GRD-007 | P0 / FR-14,R1,R2 | 自连接`orders a JOIN orders b`与相关子查询 | 用a/b限定列和`t.*`分别执行原SQL/final_sql | 别名、列与相关外层关系保持一致；无合成别名遗漏 |
| TC-GRD-008 | P0 / FR-14,R2 | MySQL `erp.orders.id`、PG `sales.orders.id`及别名列 | 在不触发截断的夹具执行；改变默认schema后再执行 | 改写后列引用仍指向原关系；与授权对象及结果一致 |
| TC-GRD-009 | P0 / FR-04,FR-14,R2 | DS-MY-A/B同名erp.orders；PG sales/public同名orders | 以明确datasource/catalog/schema交叉调用 | 结果仅含选定源/schema标记，不模糊跨源匹配 |
| TC-GRD-010 | P0 / FR-04,FR-14,R2 | PG `"Orders"`/orders，MySQL按服务器大小写模式 | 查询带/不带引号对象并更换连接 | 规范化遵循真实方言；不存在、歧义、快照过期均拒绝，0业务连接 |
| TC-GRD-011 | P0 / FR-14,R2 | `pg_authid`、`pg_catalog.pg_class`、`information_schema.tables`等 | 无限定与显式限定分别请求 | 系统关系均拒绝，0业务连接，不靠默认search_path逃逸 |
| TC-GRD-012 | P0 / FR-14,R7 | 写CTE、多语句、锁、INTO、`/*!...*/`、`/*+...*/`、赋值 | 每种输入独立发送，包含大小写/注释变体 | 词法/AST明确拒绝且0业务连接；错误不泄露原始SQL |
| TC-GRD-013 | P0 / FR-14,R7 | `public.custom_fn()`、表函数、`CAST('x' AS public.leaky_type)`、未知AST | 分别经过完整访问器 | 路径函数、表函数、自定义类型/未知节点fail-closed |
| TC-GRD-014 | P1 / FR-14,R7 | 方言内允许函数/CAST、窗口、普通CTE/JOIN/UNION | 在支持类型上执行原SQL/final_sql | 所有子表达式均被访问；合法场景结果等价，不以移植dbx之名意外禁止已承诺窗口能力 |
| TC-GRD-015 | P0 / FR-14,FR-15,R6 | 无LIMIT、LIMIT10/1000、OFFSET、PG FETCH FIRST、UNION外层排序 | max_rows=1/100/500逐项执行 | 外层上限min(原限制,用户收紧,500)；保留OFFSET/排序；WITH TIES等未支持变体拒绝 |
| TC-GRD-016 | P0 / FR-14,FR-15,R7 | >64KiB SQL、深度>64、物理引用>64、NUL/坏编码 | 在边界前后发送，观测解析任务与连接 | 超预算及时拒绝，不无限递归/占用线程，0业务连接 |
| TC-GRD-017 | P0 / FR-14,R2,R10 | 29条原始VQ及参数渲染器齐备 | 记录原SQL→guard→final_sql→两引擎结果 | 未触发预算者列/值/NULL/精度/多重集合等价；缺资产标BLOCKED，不只测解析 |
| TC-GRD-018 | P0 / FR-14,R2 | 篡改ApprovedQuery、改写后引入新物理对象、直接传原SQL给执行器 | 类型/边界测试与final_sql重解析复核 | 无公开原始SQL执行入口；绑定清单不一致直接拒绝 |

## 2. 授权、目录与时效

| ID | 级别 / 溯源 | 前置与输入 | 操作步骤 | 预期 |
| --- | --- | --- | --- | --- |
| TC-ACL-001 | P0 / FR-07,R4 | 用户Allow salary，平台Scope不含salary | list/schema/execute依次请求 | 有效权限为交集，不能通过用户Allow扩大平台Scope |
| TC-ACL-002 | P0 / FR-02,FR-07,R5 | 父部门Allow、子表Deny；角色Allow与个人Deny | 切换继承开关/角色状态重算 | Deny优先；无Allow默认拒绝；停用角色不贡献Allow |
| TC-ACL-003 | P0 / FR-07,FR-12,FR-13,R10 | READ Allow、META Deny与READ Deny三组 | 两种目录模式逐项检查 | READ隐含META；META Deny也阻断READ；READ Deny仍可保留META但不可看列 |
| TC-ACL-004 | P0 / FR-08,R5 | `[T0,T1)`授权，热缓存 | 固定时钟在T0前、T0、T1前、T1调用 | 起点包含终点排除；T1时拒绝，不等待缓存标准TTL |
| TC-ACL-005 | P0 / FR-08,R5 | 当前Allow、未来生效Deny；缓存已预热 | 跨过Deny生效时刻且不写数据库 | nextBoundaryAt包含未来Deny；边界后立即按新规则判定 |
| TC-ACL-006 | P0 / FR-08,FR-10,R5 | JWT、RT、PAT、权限热缓存均有效 | 停用用户→每副本循环新请求，关闭Pub/Sub | 已提交停用对新查询≤5秒；令牌不可刷新，在途尽力取消 |
| TC-ACL-007 | P0 / FR-02,FR-08,R5 | 同时持有角色、部门、平台及数据源权限 | 分别移部门、改树、停角色/平台/数据源、改Scope | 主库epoch/version同步更新；出队和提交前复核；旧权限≤5秒停止 |
| TC-ACL-008 | P0 / FR-08,FR-19,R5 | 主库安全快照已确认；Redis仍正常 | 阻断主库>5秒，持续Redis命中/心跳/缓存套缓存 | `authoritativelyCheckedAt`不续命；超5秒新查询拒绝；30秒补扫不成为放行理由 |
| TC-ACL-009 | P0 / FR-04,FR-08,R2,R5 | 表删除重建/视图定义变化，存在旧授权与快照 | 同步对象变化再查询 | 资源版本更新，旧授权不自动转移到新对象；未知版本拒绝 |
| TC-ACL-010 | P0 / FR-08,FR-15,R5 | 请求在队列，排队期间撤销权限 | 释放槽位并观察JDBC开始前复核 | 撤销后请求不提交SQL；若已借连接则安全清理，审计记录拒绝阶段 |
| TC-ACL-011 | P1 / FR-12,FR-13,R10 | DISCOVER/HIDE；无READ但可META；显式META Deny | 分页list、批量schema，含授权/无权混合表 | DISCOVER只披露平台内可披露表；HIDE仅有效META；无READ不返回任何列/键/字段注释；批量行为遵循API拒绝契约 |

## 3. 账号、OAuth、刷新与PAT

| ID | 级别 / 溯源 | 前置与输入 | 操作步骤 | 预期 |
| --- | --- | --- | --- | --- |
| TC-AUT-001 | P0 / FR-01,R5 | 活跃/停用/删除账号，错误密码 | 管理后台与AS分别登录；连续错5次再试 | 状态检查与BCrypt一致；锁10分钟、限速和日志生效，不泄露账号细节 |
| TC-AUT-002 | P0 / FR-09,R3 | 指定版本公开客户端、S256、精确redirect_uri | 实际客户端执行发现→登录→授权码换token→工具 | 真实链闭环；错误/缺失verifier、code重用、非法redirect、plain挑战被拒绝 |
| TC-AUT-003 | P0 / FR-09,FR-10,R3 | 公开刷新扩展已启用；注册NONE+refresh grant | 发送不带code_verifier的标准refresh请求 | 专门converter/provider/grant链接住；依赖RT持有与client/family校验；不误走要求PKCE verifier的code provider |
| TC-AUT-004 | P0 / FR-10,R3 | 有效RT与绝对/空闲到期边界 | 成功刷新后查持久记录并尝试跨client/aud/platform | 旧RT消费、新RT写入同事务；client/aud/platform不扩大；到期拒绝 |
| TC-AUT-005 | P0 / FR-10,R3 | 同一旧RT，两个副本同时刷新 | 屏障并发提交，再提交旧RT一次 | 至多一次轮换成功；重用撤销整族，不能按“数秒内”豁免；相关JWT≤5秒拒绝 |
| TC-AUT-006 | P0 / FR-10,R3 | 刷新事务已提交，网络丢失响应 | 客户端重试旧RT；另测事务写失败 | 重试不能生成第二套token；按重授权恢复；事务失败不响应可用token |
| TC-AUT-007 | P0 / FR-10,R5 | 用户有两个授权family及PAT | 撤销一个family，再改密，再停用 | 单family撤销不依赖删表验签；改密bump版本并撤销会话/凭证但用户保持正常；停用改变状态 |
| TC-AUT-008 | P0 / FR-06,R4 | Tier2公开client_id、CIMD、同名DCR、隐藏Header | 复制/伪造平台信号申请token并调用 | 不获Tier1；DCR默认最小Scope；服务端绑定platform_id不被Header覆盖，冲突403 |
| TC-AUT-009 | P0 / FR-06,R4 | Tier1机密客户端，token端点private_key_jwt成功 | MCP调用分别带/不带规定mTLS或后端证明 | 仅客户端AS认证不自动证明Bearer请求来源；每次调用仍校验平台证明 |
| TC-AUT-010 | P0 / FR-11,R4,R5 | 个人PAT，默认/最大寿命 | 创建→关闭弹窗→再次读取→撤销→跨平台用 | 只展示一次、仅哈希持久化；默认30天最大90天；绑定个人平台且不可提升；撤销≤5秒 |
| TC-AUT-011 | P0 / FR-09,R10 | 缺token、错iss/aud、过期/未生效JWT、改签名 | 请求/mcp，不进入工具 | HTTP401/403按契约返回，不包装成工具成功；无凭证日志泄露 |
| TC-AUT-012 | P1 / FR-09,FR-18,R3 | WorkBuddy/Cursor/Claude Code/VS Code指定真实版本 | 各做首次登录、过期、刷新或重授权、拒绝、重启 | 每客户端记录真实行为和支持路径；未联调标NOT_RUN，不由模拟请求推定支持 |
| TC-AUT-013 | P0 / FR-09,FR-10,R3,R5 | PUBLIC none客户端A/B各有授权族，受控revoke扩展已启用 | A不带code_verifier提交标准表单client_id/token/token_type_hint；再提交未知token、B的token、仅client_id、重复参数，并注入撤销事务写失败 | 专门revoke converter/provider/handler可达；A真实token只撤其对应族且JWT≤5秒失效；未知/跨client token均标准200但不影响B且不泄露存在性；缺真实token或非法参数拒绝；写失败不假报成功；机密客户端不能降级none |

## 4. JDBC、输出预算与取消

| ID | 级别 / 溯源 | 前置与输入 | 操作步骤 | 预期 |
| --- | --- | --- | --- | --- |
| TC-EXE-001 | P0 / FR-15,R2,R6 | PG初始autocommit=true，池中连接重复使用 | 观测只读事务→SET LOCAL→final_sql→rollback顺序 | SET LOCAL在有效事务内；search_path=pg_catalog；超时有效且状态恢复 |
| TC-EXE-002 | P0 / FR-15,R2,R6 | MySQL当前catalog/会话超时已污染 | 借出配置→查询→归还→新请求 | catalog/只读/超时明确绑定，不能复用污染状态；重置失败淘汰 |
| TC-EXE-003 | P0 / FR-15,R6 | 单格10MiB TEXT/JSON/BLOB和放大表达式 | 记录网络、驱动分配、RSS/heap及输出 | 只运行已证明的数据库端限幅路径；否则拒绝；单格4KiB、总1MiB只是输出约束，不把getCharacterStream当内存证明 |
| TC-EXE-004 | P0 / FR-15,R6 | 多宽列×100fetch×并发16；Unicode/JSON/LOB | 调整fetch行宽策略并施压 | 有明确请求/进程内存上界与余量，无OOM；未知类型/不能限幅表达式拒绝 |
| TC-EXE-005 | P1 / FR-15,R6 | NULL、BIGINT、DECIMAL、时间、重复列名、4KiB边界；含引号/反斜杠/Unicode的近上限结果 | 查询并抓取最终协议响应UTF-8字节数，分别覆盖双份结构/文本、字符串二次转义和JSON-RPC封装 | 精度按字符串、NULL为null；列按索引不丢重复名；截断元数据不伪装真实字段值；完整JSON-RPC响应含structuredContent、text、双份/二次转义与封装≤1MiB，不能只数data或单份内容 |
| TC-EXE-006 | P0 / FR-15,R6 | >15秒查询、网络半断开 | 触发query/cancel/cleanup/socket各阶段超时 | 独立预算可观测；不能确认原任务终止则物理连接淘汰，绝不抢先还池 |
| TC-EXE-007 | P0 / FR-15,R6 | MySQL查询刚结束、取消已调度未到达 | 注入延迟，尝试下一个请求复用；重复高频竞态 | 取消在途连接不复用；下一请求零误杀；无应用自建迟到KILL队列 |
| TC-EXE-008 | P0 / FR-15,R6 | PG CancelRequest与回滚异常/超时 | 断开客户端或触发超限，延迟原执行线程 | cancel返回不等于清理完成；等待原线程/语句/事务确认或EVICTED |
| TC-EXE-009 | P1 / FR-15,R6 | 用户并发4、源并发9、实例并发17与队列33 | 各维度分别施压，再跨副本施压 | 用户3、源/副本8、实例16，等待≤32/3秒；有界拒绝；多副本总连接不超预算 |
| TC-EXE-010 | P0 / FR-15,R6 | 总字节超限/客户端断开时仍有结果 | 中止读取，观察状态机及数据库会话 | 连接持续被原请求持有；取消/关闭/回滚完成后归还，否则淘汰；不后台无界drain |

## 5. 审计、故障与隐私

| ID | 级别 / 溯源 | 前置与输入 | 操作步骤 | 预期 |
| --- | --- | --- | --- | --- |
| TC-AUD-001 | P0 / FR-16,R9 | 审计START持久化失败 | 执行SQL并观测业务连接计数 | 无持久START不借业务连接；错误有requestId及运维告警 |
| TC-AUD-002 | P0 / FR-16,R9 | START已提交、SQL执行前后分别kill gateway | 恢复后扫描悬挂记录 | 存在调用痕迹，无法确认结局标UNKNOWN；不自动重跑SQL |
| TC-AUD-003 | P0 / FR-16,R9 | 结局DB不可用、WAL可写；fsync后崩溃 | 恢复并多次重放同一WAL事件 | 成功只在结局有持久确认后返回；request_id幂等，audit_log不重复、不篡改已有结局 |
| TC-AUD-004 | P0 / FR-16,FR-19,R9 | 队列满、WAL磁盘满/只读、元库不可写 | 持续新SQL及在途SQL | 停新SQL并告警；在途尽力终止；不静默丢日志或删除未确认WAL |
| TC-AUD-005 | P0 / FR-16,R9 | SQL含姓名/证件/数字，另有语法错误SQL | 查日志中心/列表/导出/数据库/WAL | 常规只见脱敏指纹；raw/final加密；解析失败默认哈希/长度；无token/结果/明文SQL |
| TC-AUD-006 | P0 / FR-16,R9 | U-AUD无解密权限，另授特权安全账号 | 列表→尝试解密→授权后解密→查解密审计 | 列表可见不等于可解密；未授权403；授权解密行为单独审计，保留180天策略覆盖归档 |
| TC-AUD-007 | P0 / FR-12,FR-13,FR-16,R9,R10 | 已认证且通过请求大小预算的目录/结构/execute调用，含guard语法/权限拒绝 | 观察工具入口→guard前最小START持久确认→guard拒绝或执行→TERMINAL；在guard中途杀进程，恢复核对 | guard前已有最小身份/哈希/长度START，不先存未脱敏SQL；拒绝也有同requestId的唯一terminal，0业务连接；中途崩溃留START并可恢复UNKNOWN；业务拒绝isError=true且verdict区分系统故障；未认证流量仅安全访问日志 |

## 6. UI与接口权限

| ID | 级别 / 溯源 | 前置与输入 | 操作步骤 | 预期 |
| --- | --- | --- | --- | --- |
| TC-UI-001 | P0 / FR-01,FR-02,R5 | 普通用户与各管理角色 | 隐藏菜单后直接请求管理API、越级编辑他部门 | 服务端权限阻断；菜单隐藏不算鉴权；平台管理员也不自动获表READ |
| TC-UI-002 | P0 / FR-17,R9 | U-A/U-B均有查询记录 | U-A修改URL/请求中的userId、requestId、导出参数 | /me从安全上下文取用户；不能获取U-B历史、raw或结果 |
| TC-UI-003 | P0 / FR-01,FR-11 | 管理/AS独立Cookie会话 | 跨站写请求、缺CSRF、会话过期、退出后后退 | HttpOnly/Secure/SameSite正确；写请求CSRF校验；过期拒绝；退出不残留PAT明文 |
| TC-UI-004 | P1 / FR-03,FR-07,FR-18 | 新部门+数据源+平台+角色 | 管理员按手册完成配置；员工复制MCP配置 | 10分钟内闭环；配置无共享secret/URL token；元数据与有效权限预览一致 |
| TC-UI-005 | P1 / FR-05,FR-12,FR-13 | 字典注释含脚本/特殊字符；键盘/小屏 | 查看表树、详情、拒绝提示与PAT弹窗 | 文本转义、无XSS；键盘可达、焦点明确；无READ不泄露列注释；时间显示Asia/Taipei |
| TC-UI-006 | P0 / FR-02,FR-07,FR-08 | 组织管理员仅获D-A部门根、orders资源、META动作的限时admin_delegation；本人及所在role/dept有不同Allow/Deny | 尝试修改D-B、grant salary/READ、延长期限、自建委派；再给本人/所在role/dept增Allow、删改命中Deny、改成员/移部门使本人增权；到期瞬间操作 | 委派主体/部门根/资源/动作/时间逐项校验；越界和直接/间接自增META/READ全部拒绝且无副作用，需平台管理员另行操作；不能借移除Deny、共享角色或组织变化自提权 |

## 7. 管理API与元数据契约

| ID | 级别 / 溯源 | 前置与输入 | 操作步骤 | 预期 |
| --- | --- | --- | --- | --- |
| TC-API-001 | P0 / FR-03,FR-19,R2 | 数据源新增/测试：非批准目标、云metadata/loopback、任意JDBC URL/属性、TLS证书错 | 逐项提交并查看DTO/日志；再用批准内网只读源 | 非白名单/任意连接属性拒绝；VERIFY_FULL失败不降级；密码/真实连接栈不回显；批准源可受控测试 |
| TC-API-002 | P0 / FR-04,R2,R5 | 已发布snapshot，另启动Schema同步；中途失败/表删除重建 | 同步中并发list/schema/execute，再注入同步失败 | 不发布半成品快照；失败保留可用旧版本或按过期拒绝；新对象generation不继承旧授权 |
| TC-API-003 | P0 / FR-12,FR-13,R10 | 可READ orders，其外键引用salary不可READ | 获取orders结构、批量混合授权/无权对象 | 无权引用对象的foreignKey省略且foreignKeysRestricted=true；批量schema整次拒绝，无部分列泄露 |
| TC-API-004 | P1 / FR-03,FR-05,FR-07 | 同一版本资源由两个管理员并发修改 | 带/不带If-Match更新字典/Scope/授权 | 缺失前置版本按契约拒绝；旧版本409且无副作用；字典仅改说明不改业务DDL；富文本/未知列拒绝 |
| TC-API-005 | P0 / FR-03,FR-11,FR-16 | Idempotency-Key重复、body相同/不同；PAT响应丢失 | 重试创建/测试/同步/撤销/解密；重新获取PAT创建结果 | 同意图无重复副作用；不同body拒绝；处理中/未知结局不重做；PAT/secret不二次回显；OAuth不套REST重放规则 |
| TC-API-006 | P0 / FR-01,FR-07,FR-17 | 未知字段、重复JSON key、超body、伪造userId；过期/跨用户cursor | 请求管理/个人列表和写接口 | 绑定白名单、拒绝篡改服务器字段；游标签名绑定主体/筛选/权限；不先分页后前端过滤；不可见个人资源404 |
| TC-API-007 | P0 / FR-06,FR-09,R3,R4 | DCR/CIMD的回调/元数据URL含不批准scheme、私有/metadata、重定向或DNS重绑；批准企业域解析到私网 | 逐项注册/解析，覆盖企业批准域A/AAAA为私有地址与公网转私网，再用合法公网元数据注册 | CIMD企业域白名单也只允许公网解析，不存在私网例外，不复用业务库CIDR放行规则；出站限速/大小预算/回调规则生效；新客户端零扩展最小Scope，不借元数据URL证明Tier1 |
| TC-API-008 | P0 / FR-16,R9 | 有解密权限但审计写不可持久；普通审计员直接调decrypt | 两类主体请求带理由解密，检查缓存/日志 | 无权限或解密行为无法持久审计时不返回明文；成功no-store且只返回批准字段，后续访问不命中明文缓存 |

## 8. 运维与性能

| ID | 级别 / 溯源 | 前置与输入 | 操作步骤 | 预期 |
| --- | --- | --- | --- | --- |
| TC-OPS-001 | P0 / FR-19,R5,R9 | 主库/Redis/审计依次故障 | 对照告警和readiness/新查询准入 | 安全状态不新鲜/审计不可持久化时失败关闭；liveness不因依赖故障反复重启 |
| TC-OPS-002 | P0 / FR-19,R6,R9 | 活动查询与WAL积压 | 滚动停止、优雅排空、强制终止后重启 | 停新请求；清理/淘汰活动连接；WAL持久卷保留、归属唯一，无重复SQL |
| TC-OPS-003 | P0 / FR-10,FR-16,FR-19 | 元库备份、binlog、WAL、历史key_id齐备 | 隔离恢复→重放WAL→对账→恢复准入 | 在批准RPO/RTO内；历史密文可解；恢复导致的状态回退不复活token，未核对前只读/停服 |
| TC-OPS-004 | P0 / FR-19 | N与N-1构件、expand migration | 灰度→注入故障→流量滚回 | N-1读写兼容新增schema；不执行自动破坏性down migration；安全规则不因回滚变宽 |
| TC-OPS-005 | P0 / FR-06,FR-10,FR-16 | JWT签名、加密、平台secret和TLS待轮换 | 加新钥→签发切换→兼容旧钥→退旧钥 | kid/key_id可追踪；历史SQL与WAL仍可解；公开JWKS不含私钥；平台轮换不临时免校验 |
| TC-PERF-001 | P1 / FR-14,FR-15,R10 | 2C4G、50并发、≤4KiB SQL、热权限缓存 | 预热后3轮稳态，导出各span/直方图 | 内部非数据库开销P95<50ms，包含解析/授权/审计准入；队列/池/业务库单列 |
| TC-PERF-002 | P1 / FR-15,R6 | 从低负载升至过载；多数据源/多副本 | 同时报成功、拒绝、等待、吞吐与DB连接 | 不以快速拒绝美化成功延迟；队列有界；Σpool_max×副本含取消/管理预留不超DB预算 |
| TC-PERF-003 | P0 / FR-15,R6 | 10MiB单格、多宽列与并发，包含被拒路径 | 对照驱动物化分配、GC、RSS、容器重启 | 未证明上界路径关闭；已开放路径无OOM且符合书面预算，不以4KiB输出或OOM Kill代替证明 |
| TC-DEL-001 | P1 / FR-20,R8,R10 | 发布manifest、设计文档、PoC与测试资产交付 | 核查所有入口/链接、版本/hash、许可/内部资产位置，再按说明复跑 | 无虚构UI/Swagger/镜像/测试通过声明；缺失VQ等资产明确BLOCKED；构件/有效POM与可复跑证据成套，不以文档齐全替代测试通过 |

## 9. 执行记录与升级规则

执行时每个主ID追加参数集ID（如`TC-GRD-010/pg-quoted-case-01`），保留SQL/fixtures哈希、测试引擎、客户端版本、结果与证据目录。一个主用例的部分参数通过不能把整个主用例标PASS。任何安全绕过先保留失败输入与种子，再修复；不得通过禁用断言、跳过方言或降低上限掩盖失败。

历史Finder探针单独使用`HIST-FINDER-*`标识，当前状态见 [test-report.md](test-report.md)，不能与新ScopedTableBinder用例混用。

## 工程标准补充验收

| ID | 级别 / 溯源 | 前置与输入 | 操作步骤 | 预期结果 |
| --- | --- | --- | --- | --- |
| TC-API-009 | P0 / FR-19 / ADR0010 | REST 成功/失败、201/202、身份过滤器与 OAuth/MCP | 验证真实 R<T> 序列化、HTTP 状态、模块数字码/符号码、X-Request-Id、游标 DTO，再请求标准协议端点 | REST 只有 code/msg/data 三字段，数字码唯一且在模块段内，错误有追踪；OAuth/MCP 不包 R，不把所有状态变200，不泄漏栈/凭证 |
| TC-DB-001 | P0 / FR-01,FR-16 | MySQL 8.4 空库与升级迁移，所有自有 Mapper/SQL | 实库读取 information_schema 校验表名、主键和 create_time/update_time；AST 检查自有查询投影并测试 COUNT(*)/字符串/注释和 guard 夹具 | 所有 PTMCP 元库表满足通用字段/显式主键；不可变事件时间不被自动覆盖；自有 SELECT * / t.* 拒绝，COUNT(*) 不误报，外部 SQL 继续受控展开；不修改接入业务库 |
