# 数据源、Schema 同步与安全执行器设计

版本：1.0-design · 2026-10-23 · 状态：待实现。下述连接取消、宽行限幅和DDL身份边界必须实测，不能仅凭JDBC API名称宣称安全。

## 1. 模块接口和连接注册

ptmcp-datasource只依赖common/JDBC/Hikari，不反向依赖guard。SafeExecutor公共入口接收由guard构造的ApprovedQuery及当前执行上下文；模块通过common的受限接口验证计划，不能提供接收任意SQL字符串的业务执行入口。健康探针/Schema采集使用内部固定命令适配，与用户SQL执行接口分开，禁止前端传自定义测试SQL。

每个动态池的身份键为(datasourceId,configVersion,engine,endpoint,databaseName,credentialGeneration,tlsPolicyVersion)。同名库不能跨数据源共池；同一dataSource更换密码/数据库/TLS/驱动属性后新代池，不就地修改已借连接身份。用户名、凭证hash不得进入普通日志，池标签只用非敏感ID/version。Hikari候选7.0.2按BOM收敛，Driver完整补丁及参数由M0 BOM锁定。

注册状态DRAFT→健康检查/目的地校验→ACTIVE；DISABLED/DELETED禁止新借用，提升安全版本，旧池DRAINING等待在途完成/取消；新配置只有校验成功才成为ACTIVE。测试新凭证失败不能覆盖旧有效密文。凭证从KMS解密后仅在连接工厂短期内存中持有，DTO与日志只暴露credentialConfigured。

只允许MYSQL/POSTGRESQL模板，服务器构建JDBC URL；拒任意driver class、URL、socket factory、init SQL、SSL弱化属性。域名全部解析并按运维允许CIDR校验，连接时固定校验结果/连接工厂策略防DNS rebinding；企业私网数据库地址只有显式允许才可访问。TLS VERIFY_FULL，验证服务器证书链和host；不以“内网”默认trustAll。

## 2. 连接/并发预算

| 层 | 一期默认 | 获取/释放规则 |
| --- | --- | --- |
| 用户 | 3个并发SQL | 同一gateway副本不能仅本地限制后声称全局3；用Redis原子租约或主库权威并发租约实现跨副本用户3，租约续期失败阻止新执行并取消在途；M0验证崩溃回收，Redis故障拒新SQL |
| 每数据源/副本 | 8 | 进入执行队列前取得/预占，不因池数增加突破 |
| 每gateway副本 | 16 | CPU/堆与线程预算共同约束 |
| 排队/副本 | 最多32，最长3秒 | 公平有界队列，超限429；队列出队重新核验权限 |
| 借池 | 3秒 | 申请超时不得再后台悄悄借到连接执行 |
| 数据库全局 | 由DBA连接预算配置 | 总需求Σ(源poolMax×该源可调度副本数)+同步/测试/管理/余量≤DBA限额；仅本地pool_max合法不代表全局可扩容 |

用户3的跨副本租约是实现目标，非已验证能力；多区域/分布式限流复杂度不得被静默降为每副本3。借池/健康测试/Schema同步也计入数据库总连接，采用独立受限worker池，不可绕开预算。租约有唯一执行ID，释放compare-and-delete防删新请求租约；过期不意味着旧DB任务已结束，回收必须结合执行租约/取消确认，不能一到TTL释放预算让旧新任务叠加。

每次执行在拿连接前已完成START审计持久确认。获取global/user/source额度的固定顺序、排队清理和异常释放都要try/finally，只有真实执行任务结束才释放用户执行额度。请求ID贯穿worker/连接/取消任务，不能用池connection.toString当稳定请求身份。

## 3. Schema 同步与资源生命周期

同步是异步Job，每数据源仅一个RUNNING，用主库租约+configVersion防双worker；lease过期必须确认旧任务未发布才接管。流程为读取配置→目标网络/TLS验证→最小元数据连接→采集staging→规范化标识符/系统对象过滤→类型/视图依赖校验→差异计算→主库事务复核configVersion→原子发布snapshot并升resourceVersion/epoch→事件通知。失败保留旧快照但超过expiresAt不得用于新查询；不发布半张表或一半列。

MySQL采集database/lower_case_table_names、表类型、列顺序/JDBC类型/最大字节宽度、键、注释、视图定义摘要；PG采集database/schema、quoted大小写、relation OID、类型OID/基础类型/domain、视图依赖。元数据连接只执行内建已参数化查询，用户工具不能借该路径访问information_schema/pg_catalog。表注释/字典内容是数据，不得被解释为代理指令。

资源有稳定objectId+generation。新增创建新ID；DROP标MISSING/RETIRED并撤旧grant；同名CREATE用新generation，旧grant不自动迁移。列变更/视图定义变化提升objectVersion/resourceVersion，ApprovedQuery失效需重新守门。连接配置变化使旧快照若绑定旧catalog/configVersion不可继续使用。

MySQL无法靠常规表名/DDL hash可靠识别所有DROP+CREATE ABA，PG OID也可能回收。故一期约束接入库DDL走受信变更：先停用源/命名空间→排空查询→DBA变更→完整同步→管理员重新确认对象授权→启用。无此管控的源不能承诺删除重建永不继承；有DDL变更事件可辅助但不可取代验证。若要开放在线DDL，M0/M1需给出双引擎事务/锁/OID复核方案及竞争证明，否则保留上述维护窗边界。

视图默认不开放执行；只有定义可解析、依赖闭包无未知/外部对象、全部依赖READ、定义hash和快照版本有效时启用。未知类型/自定义类型对象可显示受控表级目录但不作为可执行输出；无READ不返回列与键。snapshot最大年龄是启用源必填配置，M0根据DDL运维与负载锁定，不设置一个未经验证的长TTL来掩盖新鲜性。

## 4. MySQL 执行协议

借出物理连接先核对池代/目标catalog、server版本/会话安全profile与无活动事务；setReadOnly(true)并关闭autoCommit，以该驱动已验证方式建立明确READ ONLY事务（START TRANSACTION READ ONLY或等价受测API顺序）。设置SESSION max_execution_time=15000及受控sql_mode；JDBC statement queryTimeout=15s，network/connect兜底20s。URL启用useCursorFetch=true及经M0验证的服务端prepare参数，fetchSize普通初值100，setMaxRows(effectiveLimit≤500)。不能假设只设fetchSize就一定是游标。

全部关系database限定，连接database必须等于ApprovedQuery上下文，不执行用户USE/SET。驱动是否在特定类型/预编译模式回退全量缓存需要实测；不满足有界profile的路径关闭。关闭ResultSet/Statement后rollback，重置max_execution_time/sql_mode/catalog/autoCommit/readOnly/networkTimeout等修改项到已知基线，校验成功才归还。会话恢复失败直接淘汰，不能只把autoCommit改回true而残留其他状态。

## 5. PostgreSQL 执行协议

顺序必须为：借出干净连接→setReadOnly(true)→setAutoCommit(false)→显式确立READ ONLY事务→SET LOCAL search_path TO pg_catalog→SET LOCAL statement_timeout='15000ms'及已配置lock_timeout（不大于查询预算）→配置Statement/fetchSize/maxRows→执行。setAutoCommit(false)本身不表示数据库已经收到BEGIN，必须由驱动受测事务起始语句/SET TRANSACTION READ ONLY确认，不能在事务外发SET LOCAL后假装生效。lock_timeout的具体值在M0配置清单冻结，未知配置拒绝源激活。

业务关系全部schema限定，search_path中不加入可写public/业务schema；函数仅允许已验证无路径内置签名。临时schema/用户类型/操作符路径由guard拒绝，连接账号也不得创建临时/函数或持有危险角色。fetchSize=100需服务端游标、autoCommit=false、ResultSet forward-only/read-only等完整条件；禁止在取数前commit。结束关闭ResultSet/Statement→rollback→恢复readOnly/autoCommit/networkTimeout→归还，SET LOCAL随rollback结束，但仍核对session基线。

## 6. 输出类型推导与数据库端限幅

4KiB是单格产品输出上限，1MiB是最终序列化JSON-RPC响应UTF-8体上限，包含JSON-RPC壳/CallToolResult、structuredContent与text双份以及text二次转义，不能只数单份rows。getCharacterStream/getBinaryStream不保证驱动没先把单格放入内存，尤其pgJDBC相关实现可能复制数据；fetchSize=100只限制批行数，不限制单行宽度。禁止把“10MB TEXT用流读再截4KiB”当成防OOM证据。

guard在借连接前生成OutputPlan，对所有输出（包括SELECT *展开、表达式、CTE、UNION、聚合/窗口输出）推导数据库类型、最大字节宽、编码扩张及返回budget。UNKNOWN类型、用户自定义类型、无界numeric/array/composite/range、无法推断函数结果/二进制表达式均拒绝RESULT_TYPE_UNSUPPORTED，即使查询最终返回0行也拒绝。运行时ResultSetMetaData与计划类型/列数不一致，禁止取未知大值，终止并审计版本漂移。

一期按三个profile逐个验证开放：

| Profile | 范围 | 是否允许 |
| --- | --- | --- |
| BOUNDED_NATIVE | 基础数值/日期/布尔及从快照/表达式推导最大字节宽≤4KiB的字符/二进制 | 最小窄行路径，可完整SELECT/JOIN/CTE/UNION/窗口但每个节点/类型均已回归 |
| BOUNDED_PROJECTION | 可变宽TEXT/BLOB/JSON或宽VARCHAR，需要数据库端截取后才出网 | M0数据库/驱动实测通过的具体语法组合才开放，以下严格规则；非默认全覆盖 |
| UNSUPPORTED | 任意无法证明最终行宽有界、投影可能改DISTINCT/聚合/排序/UNION语义的组合 | 拒绝，用户可改为受控窄投影/聚合；不能执行后才赌内存 |

BOUNDED_PROJECTION初始只考虑简单最外层SELECT：无DISTINCT/GROUP/HAVING/集合操作/窗口，无依赖原始宽值别名或序号的排序；排序若存在必须是可复制的有界稳定键并同步外层排序。将原查询先完成过滤/连接/原行限制，再以受控派生层对每个输出列限幅，禁止在WHERE/JOIN/GROUP/DISTINCT前截断。所有排序键以内部唯一别名传播，外层重建相同ORDER/OFFSET/LIMIT位置，隐藏键不输出；无法证明行前缀相同则拒绝该组合。对复杂结构不以“包一层子查询通常没问题”放行。

数据库端字符限幅用当前引擎验证的字节感知表达式：输出限定前缀≤4KiB UTF-8、保留NULL、不切断Unicode码点，另产生受控truncation标志（只返回是否超限，不回传原始长值）。可先SUBSTRING取最多4096字符（utf8mb4最坏≤16KiB的受控中间值），再在服务器对该有界片段求UTF-8字节前缀；具体模板为方言实现的一部分，必须经EXPLAIN/执行验证返回的wire值确实有界，不允许函数意外把完整LOB送给驱动。BINARY用服务器SUBSTRING限制4096字节；JSON/JSONB先按服务器文本表示做受测有界转换，输出logicalType=JSON/encoding=JSON_TEXT，不承诺截后JSON合法。字符编码转换不支持/超限标志需要执行自定义函数则拒绝，不在业务库安装UDF来扩大安全边界。

LOB/JSON输入用于比较、排序、聚合仍可能消耗数据库端资源，由只读账号/statement_timeout/DBA资源组防护；本设计的有界证明针对网关JVM与网络输出，不能宣称消除数据库OOM。原始完整结果值不用于应用层精确长度计算，originalBytes无法安全取得时返回null；服务端仅用经验证的长度/前缀标志。重复列名改为内部p0/p1别名，输出按ordinal恢复显示标签；输出绑定不能靠列名map丢失重复列。

注：上述投影模板仍是待实现设计，M0必须冻结每方言精确SQL模板、允许结构矩阵与回归证据；未完成模板和内存验证前只开放BOUNDED_NATIVE，10MB TEXT/JSON/LOB路径关闭。此收紧优先于原review中“统一字符流即可防OOM”的假设。

## 7. 取数、字节预算与内存核算

数据库端输出限幅后，再用固定缓冲流读取防止意外拷贝，禁止对无界值调用getString/getBytes。BoundedNative的有界值可按类型提取，但任何未知值不能先转Object/toString。DECIMAL/BIGINT为string、二进制BASE64、日期时区/NULL按api-spec编码；每追加完整行前按最终JSON-RPC响应（含双份内容与二次转义）的实际字节预算估计并在最终编码再次核验1MiB，预留truncation元数据空间，放不下整行停止并标RESPONSE_LIMIT。不能先发结构/行再补算text导致超限。单格截断独立元数据，不伪造[TRUNCATED]文本。

预估最大行宽W=sum(各列最大wire字节+协议开销)，effectiveFetchSize=min(100,按worker内存预算/W的下取整)，最小1。若单行即超过worker允许预算则guard拒绝，不能fetchSize=1仍赌堆。容器2C4G还要预留JVM堆外/TLS/线程/解析/审计/元数据空间，worker内存配额由M0 profiler实测冻结；最大16执行worker同时fetch、响应两份structuredContent/text编码与中间buffer都计入峰值。若SDK复制text/结构两次必须计入，不能只数最终1MiB。

堆峰估算=固定基线+Σ(执行worker fetch批宽×驱动实测复制系数+编码buffer)+排队/解析快照+审计缓冲，复制系数不是假定1。M0在10MB/100MB TEXT/JSON/BLOB、Unicode、多列宽行、空结果、重复列、聚合/UNION/窗口、并发16与客户端慢读下用JFR/堆统计证明上界；unsupported应静态拒绝且executor=0。容器内存限额只隔离故障，OOMKill不是通过。驱动/数据库补丁变更重跑对应profile证据。

## 8. 超时、取消与连接状态机

~~~mermaid
stateDiagram-v2
  [*] --> ACQUIRED
  ACQUIRED --> CONFIGURED
  CONFIGURED --> EXECUTING
  EXECUTING --> DRAINING
  DRAINING --> CLEANING
  CLEANING --> RETURNED
  ACQUIRED --> CANCELLING
  CONFIGURED --> CANCELLING
  EXECUTING --> CANCELLING
  DRAINING --> CANCELLING
  CANCELLING --> CLEANING: 原执行任务与取消任务均已终止
  CANCELLING --> EVICTED: 超时或终止不确定
  CLEANING --> EVICTED: 回滚或恢复失败
  RETURNED --> [*]
  EVICTED --> [*]
~~~

预算：checkout3s，connect/network20s，SQL15s，cancel3s，cleanup2s。network20s是网络兜底，不代替15s数据库timeout，也不是总请求deadline。单调时钟追踪每阶段；deadline触发由唯一owner登记取消，不允许多个定时任务同时操作相同连接。超时、用户断开、字节预算提前停止都进入受控结束，正常读取达到LIMIT若驱动仍有在途fetch同样先关闭/取消并确认。

PG用原生statement_timeout与Statement.cancel；MySQL用max_execution_time与受测Statement.cancel。不得维护应用自建、可能晚到的KILL QUERY定时线程。驱动自己的cancel可能在内部使用辅助连接/kill，同样需要等待真实执行worker终止、cancel辅助任务join、ResultSet/Statement关闭、rollback成功、会话恢复成功；cancel()返回只表示调用完成，不能代表DB任务和driver辅助任务均结束。owner一直持有连接引用与池租约，其他线程不得提前close触发归还。

取消完成信号是联合条件：executionTask已终止 AND cancellationTask已终止/无晚到工作 AND statement已关闭 AND transaction已rollback AND baseline恢复通过。取消3s或清理2s仍不确定，标连接TAINTED，物理abort/池evict，等物理销毁后才释放池租约；不能将被abort的代理连接正常close后误作可复用。驱动无法提供辅助取消join/确认机制则该组合不开放复用：超时连接一律淘汰；M0用网络延迟注入证明无迟到取消复用风险。socket阻塞、cancel线程卡住时不能无限占线程，新查询由并发预算背压并告警。

连接RETURNED前保留executionGeneration与owner标记，迟到回调检测不匹配应直接隔离连接/告警，不能向新请求发取消。最后事件区分QUERY_TIMEOUT/CLIENT_CANCELLED/CLEANUP_FAILED与UNKNOWN，不因客户端断开省略审计。

## 9. M0/M1 必测闸门

SQL最终对象=授权对象；PG事务内SET LOCAL真实生效；连接污染不跨请求；MySQL游标模式真实启用；宽行wire投影有界；未知类型/结构拒绝；取消竞态中下一请求零误杀；五阶段timeout可分别触发；全局用户并发3/副本源8/gateway16不越限；主库撤销出队/执行前生效；START前池借用0；审计终结失败不能返回成功。每项记录数据库、驱动、JVM、Hikari、配置、SQL、JFR/网络/数据库侧证据及实测结果，未验证组合不加入对外支持矩阵。
