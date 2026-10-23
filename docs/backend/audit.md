# 审计可靠性、脱敏与恢复设计

版本：1.0-design · 2026-10-23 · 状态：待实现和故障注入验证。审计保证范围是所有已认证、进入Tool处理的调用；未认证/非法HTTP只写不含凭证的安全访问日志。不能把内存队列、日志打印成功或Redis Pub/Sub当持久确认。

## 1. 审计事件和状态语义

每次Tool调用服务端生成全局唯一request_id。业务result、策略verdict、error_code独立存储：result=RUNNING/SUCCESS/ERROR/UNKNOWN；verdict=ALLOW/DENY/ERROR/UNKNOWN；error_code描述原因。拒绝权限是ERROR+DENY，不计成网关系统故障；查询在DB成功但终结审计不能持久/响应丢失，不能对调用者宣称成功。

START表示工具调用获准进入受控处理，包含requestId/tool/userId/platformId/datasourceId/startedAt/SQL哈希与长度（若有），不包含原始token或结果。入口先对请求做基本预算/认证，再持久化最小START；词法/语法/ACL拒绝也写终结。SQL guard通过后检查START已确认、完善执行上下文，最后安全核验才借业务连接；executionStartedAt与tool startedAt分开。此两层准入避免解析阶段崩溃漏调用，也确保“未获持久START不借业务连接”。

TERMINAL包含result/verdict/errorCode、完成时间、命中规则、版本快照、物理对象ID、脱敏SQL/加密原SQL、阶段耗时、行/字节/截断信息；无结果数据。MCP对外成功必须在terminal获持久确认之后，故不能边fetch边将成功结果流给客户端后再尝试记审计。一期结果先在1MiB预算内完成封装，终结持久后才构造成功响应；JSON编码错误也要作为ERROR终结。

~~~mermaid
sequenceDiagram
  participant T as Tool handler
  participant M as 元库主库
  participant D as 业务数据库
  participant W as 加密本地WAL
  participant C as 客户端
  T->>M: START outbox事务提交
  M-->>T: 持久确认
  T->>T: guard+权限+执行前状态复查
  T->>D: ApprovedQuery执行（此时才借池）
  D-->>T: 有界结果/错误
  T->>T: 清理/取消确认；编码预算
  T->>M: TERMINAL outbox事务提交
  alt 主库写失败
    T->>W: 加密frame + fsync
    W-->>T: 持久确认
  end
  T-->>C: 持久terminal之后返回结果
~~~

元库START不可写时即拒绝新Tool/SQL并503 AUDIT_UNAVAILABLE，不接受仅WAL START后继续业务查询。一期START仍要求主库，因为安全版本也依赖主库，简化恢复与准入证明。TERMINAL允许DB或本地WAL已fsync；WAL不可用而DB可写仍可运行。两者均不可写时停止新调用，取消在途并尽力释放连接，返回明确错误/可能UNKNOWN，不能返回成功。

## 2. MySQL outbox 与物化

audit_outbox非分区，UNIQUE(request_id,event_kind)，START seq=0、TERMINAL seq=1。event_id唯一且payload_hash校验。一次调用最多一个持久终结（可重放同payload）；不同hash的同一request终结为冲突，不覆盖先前结局，隔离并P1告警。元库事务提交结果不明确时按同event_id/hash重试查询/写入，只要已存在且一致即可认定持久，不生成新requestId掩盖重复。

有界内存队列只作消费加速，不承诺可靠。消费者从主库按未published事件领取有限批次/租约，同事务幂等upsert audit_log并标published；崩溃后租约过期重领。先落audit_log再确认outbox，即使重复读取也由request_id+event_kind/payload hash幂等。终结可先到物化表（例如START消费者暂阻塞），TERMINAL携完整身份，先创建终结；晚到START只补空字段，绝不回退result。

audit_log.request_id为全局唯一主键，一期不月分区。MySQL分区要求与全局唯一request_id之间存在设计冲突，不能随意把started_at加进唯一键后声称仍全局幂等。未来规模演进需新ADR、去重账本/归档索引方案与迁移测试；当前按保留期索引批量删除+归档。

START更新RUNNING时写execution lease owner与心跳（可放扩展执行租约表）；活跃任务不因审计消费者延迟被误判UNKNOWN。进程重启扫描旧START，结合网关实例bootId/执行租约/数据库连接确认无活跃任务后，在恢复事务中写TERMINAL(result=UNKNOWN,verdict=UNKNOWN,errorCode=EXECUTION_OUTCOME_UNKNOWN)。不能看到START就推定“失败未执行”或“成功”，也不能据此自动重放SQL。

## 3. 本地加密 WAL

WAL只存需要补偿的TERMINAL与审计敏感读取事件，不存查询结果。目录绑定gateway实例及持久卷；容器临时层/emptyDir不满足节点重启保证。每个segment有schemaVersion、segmentId、gatewayInstanceId、bootId、createdAt、keyId和随机加密数据密钥的KMS封装；每frame含长度、eventId/requestId/eventKind/seq、payloadHash、AES-256-GCM nonce/cipher/tag、CRC或等价完整性校验。GCM nonce每key唯一，禁止以进程重启后从0的计数直接重用。

写入顺序：分配event ID→序列化版本化payload→加密→追加完整frame→FileChannel.force(true)/受测平台等价fsync→返回durable ack。分组提交可以提高吞吐，但每个等待者必须在覆盖其offset的fsync成功后被唤醒；超时不能假定落盘。segment新建/rename/checkpoint涉及目录元数据持久性，Windows/Linux分别做断电/重启恢复测试；仅API返回不等于存储设备真的持久，部署卷需支持已验收的durability。

写WAL失败/磁盘满/key服务不可用导致无法安全加密时拒绝，不写明文临时文件。启动时无法解密现存segment只读隔离并告警，不删除后继续假装恢复完成；如果审计积压达到容量阈值停止新SQL。WAL密钥按keyId保留至所有segment消费完且审计保留要求满足，密钥撤销/轮换与备份恢复联动。

恢复worker按segment/offset验证frame长度、GCM tag、payload hash；最后不完整frame截断至最后有效fsync边界并产生恢复安全事件；中段校验失败隔离整个segment，人工处理不能跳过丢失事件后宣称审计完整。有效frame按event ID/request ID幂等补写audit_outbox；确认MySQL提交后推进持久checkpoint。只有segment所有frame已入库且checkpoint持久化才删除，删除/重启重复扫描不会重复物化。

WAL回放不重新执行业务SQL。TERMINAL来自旧bootId也可补写，新实例安全验证与事件回放隔离；不能让回放payload成为SQL执行/Java对象反序列化入口。允许payload schema按版本解析，未知版本隔离而非“尽量读”。WAL所在节点完全丢失而未做远端持久化时无法保证终结全恢复，这一失效域须在运维容量/备份方案显式记录；若上线要求容忍节点永久丢失，必须采用已验证持久远程卷/同步复制，而不是口头承诺本地磁盘可靠。

## 4. SQL 脱敏与加密

解析成功的SQL由同一受控AST访问器将字符串、数字、日期/时间/二进制字面量、IN列表值等替为占位符，生成sql_masked；指纹对方言+规范AST结构+资源身份做hash，可按版本区分。脱敏包括SELECT投影常量、CASE、CAST中的字面量、注释移除，不只匹配WHERE。默认外发Loki/ELK、管理列表、用户历史只含sql_masked/指纹；表名/注释本身敏感时按访问权限进一步隐藏。

解析失败/未支持节点无法证明正确脱敏：默认只记录SHA-256、UTF-8长度、errorCode与安全规则ID，不记录原文或“正则脱敏后原文”。不能把失败解析器生成的AST当可靠掩码。异常栈/driver错误可能含完整SQL，日志过滤层必须清除；JDBC代理/SQL调试日志生产关闭。

sql_raw与sql_final仅在策略允许且解析/保留处理明确时AES-256-GCM信封加密，独立nonce，AAD绑定requestId/field/schemaVersion/tenant或部署ID，记录key_id。数据密钥KMS封装，主密钥不在元库/镜像/env明文长期保存。解析失败SQL默认不保留raw，即使加密也非必须；严禁结果行/密码/token进入audit_payload。

SQL来源原文可能超64KiB/含NUL，仅对预算内安全字节计算hash长度，不为了审计把无限请求体读进内存。SQL参数化脱敏属于安全模块白名单覆盖的一部分，新增AST节点必须补敏感字面量测试，失败就降为hash-only而非明文回退。

## 5. 访问控制与解密审计

全局审计列表/详情分别audit:list/read，且受管理审计范围；安全审计员默认不含decrypt。个人历史服务器以当前userId过滤，不接受前端传userId，不提供个人原SQL解密。分页查询在SQL层做scope/owner限制，不能先取全局再前端裁剪。

POST /api/v1/admin/audit/{requestId}/decrypt需独立ptmcp:audit:decrypt、明确授权范围、操作理由、CSRF、幂等键；可加部署审批策略，但不是默认凭空要求人工确认。解密访问事件先持久化（审计DB或加密fsync WAL）再返回明文；未获确认拒绝。明文响应no-store、不可写日志/追踪/缓存，用户关闭弹窗/离开页面立即清内存，不能把decrypt明文保存在服务端session。读取失败/越权尝试同样记录决策但不回显密文。

## 6. 监控与保留

指标至少包括START提交P95/失败数、terminal DB/WAL路径数、outbox积压/最老年龄、WAL字节/剩余空间/最老frame、回放成功/冲突/损坏、UNKNOWN计数、脱敏降级hash-only计数、解密允许/拒绝数。标签仅tool/结果码/源ID等受限维度，不能把SQL/token/requestId当Prometheus高基数label。审计不可写、WAL损坏/快满、终结冲突、积压超过恢复SLO触发告警；具体阈值随容量在ops配置清单冻结，未配置不能上线。

在线与归档统一保留180天，自startedAt UTC计算，不以归档日重新计时。按时间索引小批归档/删，维护全局requestId去重直到重放窗口结束；未消费outbox/未回放WAL不能先删除。raw密文、脱敏文本、访问记录的删除/合法保留例外由运营策略明确，默认相同保留上限。备份的延迟销毁见运维手册，恢复旧备份后先重放安全版本与删除清单，避免被撤销凭证/已删审计复活。

## 7. 故障验收矩阵

| 注入点 | 必须观察的结果 |
| --- | --- |
| START提交前/后崩溃 | 前者业务连接0；后者记录存在，恢复为UNKNOWN而非成功 |
| guard拒绝/解析超时 | 记录DENY/ERROR，业务连接0，不漏原SQL |
| DB已执行、TERMINAL写前崩溃 | 旧START恢复UNKNOWN，不自动重放 |
| TERMINAL DB失败、WAL成功 | 只有fsync确认后才可成功返回，恢复后只一条终结 |
| DB+WAL失败/磁盘满 | 新SQLfail-closed，在途取消/清理，绝不成功返回未持久结局 |
| 消费者写库后确认前崩溃 | 重放无重复audit_log，终结不回退RUNNING |
| WAL末尾半帧/中段损坏/密钥缺失 | 最后有效边界恢复或隔离告警，不静默丢事件 |
| 同request冲突终结 | 不覆盖，隔离+告警，有可定位证据 |
| 解析失败字面量/driver异常日志 | 仅hash/长度/码，无姓名/证件号/密码/token |
| 未授权解密/个人历史IDOR | 拒绝且有审计，个人无跨用户信息 |
| 180天边界/备份恢复 | 在线归档均按UTC保留，撤销/删除不复活 |

测试报告必须提供DB提交、fsync、进程kill、重启回放和审计行数/哈希对账的真实记录。当前文档没有实测“零丢失”结论，只有上述待验收目标与故障边界。
