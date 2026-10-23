# SQL 守门、作用域绑定与改写详细设计

版本：1.0-design · 2026-10-23 · 状态：安全扩展待实现，M0/M1 通过后逐条开放。JSQLParser是语法工具，不提供完整安全证明；本方案不沿用 TablesNamesFinder 作为 ACL。

## 1. 边界与处理顺序

开发规范中的“禁止 SELECT * / t.*”限制项目自身 Mapper/脚本，不拒绝外部 Agent 合法星号查询，也不改本设计的快照展开规则。COUNT(*) 单独按聚合签名处理；用于展示漏洞的历史 SQL 和安全夹具保留原输入。

ptmcp-guard 为纯 Java 21 模块，依赖 common 与唯一 JSQLParser 5.4。入参为已认证主体、平台、资源上下文、不可变SchemaSnapshot、有效权限快照、原SQL及只能收紧的请求预算。既不取业务连接，也不调Redis/数据库；外部适配器负责提供版本化快照。返回 ApprovedQuery 或 GuardRejection，不存在“解析失败继续执行”分支。

固定流水线：UTF-8预算→词法扫描→单SELECT解析→递归只读验证→表达式/函数/类型白名单→ScopedTableBinder+列绑定→物理对象READ校验→结果类型/输出限幅计划→限定名和LIMIT改写→重解析及绑定比对→签封ApprovedQuery→审计准入→执行前权威状态复查。任何静态拒绝，业务连接借用计数为0。

预算为SQL输入64KiB UTF-8、AST最大深度64、物理关系引用最多64。关系引用次数含CTE展开的独立物理访问，不仅去重表数。解析/遍历都受隔离任务时间和堆栈预算约束，M0确定解析任务超时配置并压测；仅Future.cancel不能证明解析器停止，无法确认终止的解析worker必须隔离替换/拒流，不能无限创建任务。总请求超时另含排队/借池/取消/清理，不把15s查询超时误作全请求上限。

## 2. 一期语法支持矩阵

| 语法 | 一期策略 | 附加约束 |
| --- | --- | --- |
| SELECT常量、已知列、别名、算术/比较/布尔、CASE、NULL/IS NULL、IN/BETWEEN/LIKE | 按方言节点白名单允许 | 类型可推导；系统变量、赋值、不明操作符拒绝 |
| 单表/INNER/LEFT/RIGHT/CROSS JOIN、子查询/相关子查询 | 允许经回归的结构 | 每个作用域列绑定，ON中禁止歧义；NATURAL JOIN一期拒绝，USING仅能在已知列下显式展开后等价校验 |
| 普通非递归CTE、派生表、UNION/UNION ALL | 分别开放 | 定义顺序与名称规则按方言测试；未知/循环/向前引用拒绝；输出类型一致且列可追踪 |
| GROUP BY/HAVING、白名单聚合、窗口函数 | 允许明确测试的组合 | 不以函数名相同即安全，参数/类型/窗口节点也检查；宽输出路径受执行器profile限制 |
| ORDER BY、LIMIT/OFFSET、PG FETCH FIRST n ROWS ONLY | 允许 | 只能无符号整数常量，外层上限取min；FETCH WITH TIES/百分比限制拒绝 |
| VIEW | 默认关闭，依赖可验证才开放 | 解析快照定义、递归绑定依赖、定义哈希核对、所有依赖READ；不能只给视图READ绕过基础表 |
| WITH RECURSIVE、写CTE、SELECT INTO、FOR UPDATE/SHARE、LOCK IN SHARE MODE | 拒绝 | 无例外管理模式 |
| EXPLAIN/EXPLAIN ANALYZE、SHOW、DESC、USE、SET、CALL、DO、HANDLER、LOAD、COPY | 拒绝 | 元数据通过专用Tool |
| 表函数、LATERAL表函数、JSON_TABLE、dblink、FDW/外部关系、用户变量、PREPARE/EXECUTE | 拒绝 | LATERAL派生查询也默认拒绝，后续单独回归再开放 |
| MySQL可执行注释/优化器hint、PG操作符OPERATOR(...)、自定义类型/函数 | 拒绝 | 注释内容/函数名大小写不能绕过 |
| 未枚举Statement/SelectItem/FromItem/Expression/窗口frame节点 | 拒绝 | 新解析器版本新增节点不会自动放行 |

词法扫描是带状态机的单次扫描，区分字符串、方言引号、注释和转义；不靠正则把引号内的分号当多语句。拒NUL、非法编码、客户端命令\G、任何多语句；可接受一个最终终止分号后仅空白/普通注释，解析仍必须恰好一条Select。引号内出现危险词只是数据，不误判授权；/*!、/*+在引号外一律拒绝。拒sql_mode等影响解析语义的会话修改，MySQL执行配置的ANSI_QUOTES/NO_BACKSLASH_ESCAPES必须与lexer/parser方言一致并在连接借出核对。

## 3. 资源身份与标识符

物理关系 ResourceRef=(datasourceId,catalog,schema,objectName,objectId,generation,objectVersion,snapshotVersion)。公开四元组是数据源/库/schema/对象名，内部再绑定世代和版本。MySQL catalog=database，schema空；PG catalog=database、schema必填。连接database只能等于资源上下文，禁止SQL跨database跳转；MySQL显式other_database.table即使同实例也需请求上下文/平台允许且一期默认拒绝跨database引用。PG三段database.schema.table若database不等当前连接拒绝；四段foreign server无支持。

标识符保存raw、normalized、quoted；内部ResourceIdentifier的raw指解码后的标识符拼写，而非含外层SQL引号/转义的token；normalized由方言producer/binder明确提供，common只原样保存并验证长度/编码，不凭quoted自动变换，也不据此签发可信上下文。AST保留词法引号信息以便安全重渲染。PG未引号标识符折小写，带引号按原样；MySQL按已采集lower_case_table_names与操作系统组合规则，不能在元库统一lower()后判同一表。quoted原文与Unicode编码不做不安全归一化。绑定只查当前已发布且未过期的快照；未知表/列、重复候选、快照过期均拒绝。系统关系先按显式/隐式名称拒绝，再做快照查找，不能因DB默认search_path解析成功就放行。

系统命名空间拒绝至少包括：MySQL mysql/information_schema/performance_schema/sys；PG pg_catalog/information_schema/pg_toast/临时schema及保留pg_*命名空间。无schema的 pg_class/pg_authid 等系统目录也拒绝；PG pg_*关系/函数名默认保留，业务若误用须改名或单独ADR。错误响应不向无META用户泄露存在性。数据库超级用户/可改DDL的DBA属于受信边界，网关不能靠限定名对抗恶意DBA把合法表换成有副作用对象。

## 4. ScopedTableBinder 数据结构与算法

每个查询块创建 Scope(id,parent,cteEnvironment,rangeBindings,outputColumns)。rangeBindings只保存本查询FROM可见关系；物理表、CTE引用、派生表引用是不同BindingKind，禁止用“全局遇到的别名集合”删除物理表。表节点/列节点使用稳定AST nodeId，不能按字符串反向查找。输出同时包含：physicalBindings[nodeId→ResourceRef]、columnBindings[nodeId→ColumnBinding]、queryOutputs[scopeId→输出列定义]、CTE依赖DAG、拒止诊断。

非递归CTE按方言定义顺序构造可见环境：

~~~text
bindWith(query,parentEnv):
  env = inherited CTE environment
  for item in query.withItems in order:
    reject recursive marker / duplicate name / unsupported forward reference
    definitionScope = newScope(parent, copy(env))
    bindQuery(item.query, definitionScope)   // 当前CTE名尚未加入env
    validate explicit column-name count and duplicates
    env = env + (item.name -> CteBinding(definitionScope.outputs, dependencies))
  bindBody(query.body, newScope(parent, env))

bindFrom(node,scope):
  if physical-looking name has no explicit catalog/schema AND matches visible CTE:
    bind CTE reference; do not invent physical table
  else if table node:
    resolve exact physical namespace+name in snapshot; record physical binding
  else if derived select:
    bind child query with legal correlation rules; record only its alias as DerivedBinding
  else reject
  insert unique range alias; alias collision in same scope rejects
~~~

CTE当前名称在自己的非递归定义中不可见，例如 WITH salary AS (SELECT * FROM salary) SELECT * FROM salary 的内层salary按物理表绑定，不能被自己遮蔽；外层引用salary才是CTE。嵌套WITH生成子环境，不能修改外层环境；CTE名与物理表同名只影响无显式限定引用。派生别名只加入范围映射，其子树里已绑定的物理关系不能被移除。UNION各分支独立作用域，其结果输出按序组合；分支别名不得污染其他分支或主查询。WITH RECURSIVE一期拒绝，避免假装支持完整递归可见规则。

列绑定顺序：先FROM关系，再选择列表/ON/WHERE/GROUP/HAVING/ORDER/窗口；方言允许的SELECT别名在特定子句另建可见表，不在所有子句通用。未限定列在当前scope候选列唯一时绑定；多候选拒 RESOURCE_AMBIGUOUS；未找到才按相关子查询允许的parent链查找，不能任意捕获同名外层列。table.column优先匹配range别名；无别名才匹配该作用域物理名，db.table.column匹配物理限定路径；不存在则拒绝。星号展开为明确有序列列表，仅限已知快照，t.*绑定到特定range，所有列类型参与输出预算。

CTE/派生表输出列记录来源列集合及推导类型。表达式列无名称时生成内部稳定名但保留用户显示标签；重复输出名可在最终结果按index保留，若外层引用造成歧义则拒绝。JOIN USING若开放，需建立合并输出列的方言语义和NULL规则，不能简单把左右列都删去；未实现前拒绝。

## 5. 函数、类型和表达式白名单

白名单是按(方言,节点类,函数名,参数签名/类型,返回类型,空值语义)版本化的数据，不能只列几个字符串后默认允许其他Function。初期候选：COUNT/SUM/MIN/MAX/AVG、ABS/ROUND、LOWER/UPPER/LENGTH、COALESCE/NULLIF、经测试的日期提取、ROW_NUMBER/RANK/DENSE_RANK等；每一项须有签名/返回宽度推导和双引擎适用标志，未进入清单就拒绝。PG内置重载/隐式类型转换与MySQL函数不直接共用清单。CONCAT/STRING_AGG/GROUP_CONCAT/JSON聚合可能宽行，默认拒绝或仅在完成有界输出profile时开放。

拒绝带schema/database路径的函数，不管它是否碰巧是内置；拒sleep/pg_sleep/load_file/pg_read_file/set_config/nextval/用户锁/网络/文件/会话与权限检查型敏感函数；无表SELECT同样受函数规则与平台query scope约束。CAST/x::type的目标必须为当前方言基础标量类型，严禁带路径/自定义类型；精度/长度有正整数边界且不能放大到无界。候选类型为CHAR/VARCHAR/TEXT、SMALLINT/INT/INTEGER/BIGINT、DECIMAL/NUMERIC、FLOAT/DOUBLE、DATE/TIME/DATETIME/TIMESTAMP、BOOLEAN、JSON/JSONB中引擎真实支持的子集，不因为文档列出JSON就允许任意CAST实现。

只接受能证明输入/返回是内置类型的列与表达式。PG用户定义domain、enum、composite、range、array、用户自定义cast/operator默认拒绝；显式OPERATOR语法拒绝。仅限制语法中的类型名不能排除列本身触发自定义行为，Schema同步必须标注底层类型和domain链。数据库账号禁止EXECUTE UDF/存储过程/FILE/服务器文件角色，PG默认PUBLIC EXECUTE须针对接入库收口并测试；只读事务不是无副作用函数的万能证明。

## 6. 完全限定名和列引用同步改写

只改写physicalBindings中的节点：MySQL使用经方言quote的database.table，PG使用schema.table；CTE名和derived别名绝不改成物理表。优先保留原别名，table.column/db.table.column/t.*等通过columnBindings定位后同步重写；存在原别名时引用改为alias.column。原无别名且仅表名限定列可生成内部别名 __ptmcp_r<nodeId>，但必须一次性同步所有绑定列、ON、相关子查询、星号、窗口/排序引用，禁止字符串替换。内部别名与用户名碰撞先换唯一名，生成别名不改变显示列标签。

示例：SELECT orders.id FROM orders WHERE orders.id>0 绑定erp.orders后，生成 SELECT __ptmcp_r1.id FROM erp.orders AS __ptmcp_r1 WHERE __ptmcp_r1.id>0 LIMIT 500。显式database.table.column同步为该range别名；引号原样由deparser安全quote，不能删除用户必要引号。表达式名/排序别名与关系别名是不同命名域，不做全局名称替换。

外层LIMIT：effectiveLimit=min(500,request.max_rows,原外层限制值)，无原值则加限制。OFFSET必须非负常量并设置经M0压测确认的可接受预算，未配置上限时只允许OFFSET=0；LIMIT offset,count规范化再收紧count，不把offset误当count。PG FETCH FIRST n ROWS ONLY转换等价LIMIT，WITH TIES/PERCENT/绑定变量/算术LIMIT拒绝。对UNION在整个SetOperationList外加外层limit，不能只限制最后一分支；保留原外层ORDER与OFFSET。内层原LIMIT语义保持，不盲目插入所有子查询改变聚合/连接结果。JDBC setMaxRows只作第二防线。

## 7. 重解析、等价验证与 ApprovedQuery

final_sql从AST生成，重解析后再次执行完整只读/白名单/作用域绑定；比较原允许计划和最终计划的物理资源多重集、列来源、作用域引用图、允许的LIMIT变化、允许的有界输出转换。不能以“表集合相同”证明所有语义相同。校验器维护可允许改写清单，任何未声明结构差异拒绝 REWRITE_VALIDATION_FAILED。metadata snapshot/version与hash签入计划，执行前版本变动整次重新守门而非直接改字符串。

ApprovedQuery为模块私有构造的不可变对象，字段至少包括：queryId/requestId、principalId/platformId、datasourceId/configVersion、catalog/schema、snapshotId/resourceVersion、policyEpoch/authVersion/平台版本、resourceRefs、finalSql、sqlHash、maskedSql/fingerprint、effectiveLimit、outputPlan、预算、guardVersion、approvedAt/expiresAt。构造器只由guard内部成功路径可见；executor公开入口只接受ApprovedQuery，不提供execute(String)。同进程中用包边界/封闭类型防误用；若以后跨进程，必须签名信封+audience+短期nonce并重新验证，不能Java序列化后相信调用者。

执行适配器检查对象不可过期、当前主体/平台与计划一致、hash完整、安全快照不超5秒、出队和JDBC提交前再次核验。原SQL可以加密审计但不能交给JDBC执行；用finalSql的SQL文本日志只输出脱敏版本。

## 8. 关键回归与M0开放条件

R1证据位于 [复现报告](../reviews/PTMCP_审查复现证据_2026-10-23.md)。6例不是全部只返回salary：2个常规例分别保留orders/users与salary；4个遮蔽反例必须包含salary。测试同时检查：绑定结果准确、越权时executor计数0、final_sql仍可执行且所有物理节点全限定、同步列引用与原结果等价。

| 测试族 | 必测反例/预期 |
| --- | --- |
| 作用域 | salary物理表外套同名派生别名；嵌套CTE；非递归同名CTE；UNION分支别名；均不得漏物理salary |
| 列引用 | t.*、db.table.col、schema.table.col、相关子查询、内外同名列/别名、重复列名；歧义拒绝，合法改写等价 |
| 对象上下文 | 双数据源同名库表、PG双schema、引号大小写、系统表无schema、默认库/搜索路径污染；检查对象等于执行对象 |
| 只读与函数 | 写CTE/INTO/锁/可执行注释、schema函数、自定义cast/domain/operator、未知AST节点，全部拒绝 |
| 限制 | 无LIMIT、比500大/小、LIMIT offset,count、外层UNION、FETCH/offset、WITH TIES/负数/表达式拒绝 |
| 输出语义 | 窄/宽值、Unicode/JSON/LOB、聚合/UNION/排序别名、空结果推断；不能因空结果放过未知类型 |
| 预算 | 64KiB边界、深度64/65、64/65物理引用、解析取消竞态、不受控递归和变异输入 |

端到端等价测试比较原SQL与final_sql的行/列/NULL/类型/顺序（无ORDER时按多重集），只允许已声明行数截断和单格输出截断差异；含稳定排序相同的LIMIT前缀必须一致。原29条VQ材料缺失，必须重建/入库真实SQL与期望，不得宣称已通过29条。内部回归≥300条且所有证据留在tests/docs/evidence；未经验证的语法保留关闭，解析器升级要求整集重跑。
