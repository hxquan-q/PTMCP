# DEV-20261030-schema-context：授权字典上下文

ID: DEV-20261030-schema-context
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-10-30

## 目标与边界

承接[网关收尾 G6](DEV-20261028-gateway-completion.md)与[T15-c S7](T15-c.md)，
落实 FR-05、get_table_schema 批量授权及不可信文本边界。模型可读取已维护的表/列业务字典，
工具说明与现有参数一致；不新增工具、resources、数据库迁移或独立 NL2SQL 服务。

不变量：先整批 READ 授权，再取指定对象字典；平台与用户权限交集、Deny 优先、当前快照绑定；
无 READ 不返回字段/字典；关系目标仍须 READ。元数据调用不使用业务连接或执行器。
物理注释和业务说明分开，均作为不可信数据；单字段和整批文本有 UTF-8 预算，完整响应继续受 1MiB 硬限制。

已有 AuditRecoveryWorker/McpAuditWriter 与 React 变更归另一窗口，不编辑、不纳入本切片。

## 实施与验证

当前 Agent 串行独占 PublishedSnapshotReader、McpToolService、ToolPermissionService 当前时间复核入口、
上下文预算辅助类及对应测试、API 合同的 G6 段落。
沿用[收尾提案](../../.agents/notes/proposed/architecture/2026-10-28-gateway-capability-hardening.md)
的 G6 决定，不创建平行架构笔记。先建立真实 HTTP/MySQL RED，随后实现并跑双方言相关组合。
使用独立 WSL 测试副本，避免与其他窗口共享 target；隔离 Testcontainers，不连接开发业务库。
安全关键 diff 完成后依工作流委派只读独立评审，不委派实现。

验收：三工具非空准确说明；表/列字典带来源/版本；旧列字典不可见；混合批次和撤权拒绝；
恶意注释仅在数据字段；Unicode 不被截断为非法字符；文本预算与全包预算同时生效。
检查入口为定向 Maven test/verify、python scripts/check.py 与 git diff --check。

## 结果与证据

### 已实现

- 三工具及参数说明与运行时一致，真实 tools/list 对完整 JSON 合同做树比较。
  PG 目录允许先发现 database/schema；get/execute 仍要求 schema，schema 筛选必须带 database。
- 整批 READ 后，按已授权对象参数化读取表/列业务字典，绑定 snapshot/config/resourceVersion；
  不返回旧列词条或维护人身份，物理注释与 businessDictionary 分开并带来源/版本。
- 字典读取后重载 ACL；返回前复核身份、安全状态及发布版本，再以当前时间评估请求对象和已披露外键目标。
  复核观察到撤权/到期/版本变化时整批拒绝，不承诺与网络发送瞬间零时延同步。
- 注释/字典按完整 Unicode code point 截取，单字段 2048 UTF-8 字节、整批共享 32768 字节，
  保留局部/整体截断信号；标记 UNTRUSTED_DATA，完整 JSON-RPC 双份响应仍受 1MiB 硬门禁约束。

### 实际执行

隔离环境为 WSL `PTMCP-Dev`、Java 21.0.12.1、Maven 3.9.9、Docker Server 29.1.3；
测试副本 `/tmp/ptmcp-g6-workspace`，仅启动合成 Testcontainers 元库与 TLS 业务 MySQL/PG。
Wrapper 下载失败后使用本机已有相同版本 Maven；副本增量时间戳曾导致未重编译，最终以 clean 消除该环境问题。

- RED：`/tmp/ptmcp-g6-red.log`，工具说明、授权字典、Unicode 预算三项，
  **3 tests / 3 failures / 0 errors / 0 skipped**。
- 安全 RED：`/tmp/ptmcp-g6-security-red.log`，字典读取中撤权、无 epoch 变化的授权到期、
  PG 首次目录发现，合计 **3 tests / 3 failures / 0 errors / 0 skipped**。
- 首轮组合 **37 tests / 2 failures / 0 errors / 0 skipped**；发现 JSON 合同漏入两条说明、
  旧 PG 目录断言仍要求 schema。已修正合同与旧断言，保留 schema/SQL 的方言上下文拒绝测试。
- 最终 `/tmp/ptmcp-g6-green.log`：**BUILD SUCCESS，exit 0，37 tests / 0 failures / 0 errors / 0 skipped**；
  MySQL HTTP 28、PG HTTP 6、文本预算 2、权限拒绝 1。
  完成于 **2026-10-30 00:57:08 +08:00**。命令在副本 parent 下执行：

~~~sh
/mnt/d/config/apache-maven-3.9.9/bin/mvn -B -ntp -o \
  -Dmaven.repo.local=/mnt/d/config/ptmcp/cache/maven \
  -pl ptmcp-gateway -am \
  -Dtest=McpQueryFlowTest,McpPgQueryFlowTest,SchemaContextTextTest,ToolPermissionDenyTest \
  -Dsurefire.failIfNoSpecifiedTests=false clean spotless:apply verify
~~~

HTTP 覆盖表/列字典、旧列过滤、混合批次拒绝、在途撤权/到期、版本变化、外键双端 READ、
Unicode/恶意注释的数据表达、schema 不借业务连接及 text/structuredContent 一致。
预算单测覆盖 32KiB 整批耗尽与 2048 字节精确边界。
仅同步本片三个 Java 文件的格式结果；七个拥有的 Java 文件及工具 JSON 合同与已测副本逐项一致（忽略换行差异）。

独立只读安全评审由 `review_g6` 完成，未修改文件、未运行动态测试。
已按反馈修复字典读取后的 ACL 重载、最终主库 I/O 后按当前时间复核以及 PG 目录发现偏差；
最终静态复审未发现本片新增阻断问题，后续合同/测试修订复核也通过。
动态 GREEN 来自上述执行环境，不能将静态评审算成第二次动态验证。

仓库检查：`python scripts/check.py` exit 0，文档/链接、任务/笔记结构、质量配置、HEAD 提交格式均 PASS，
检查器回归 **62/62** 通过；`git diff --check` exit 0。
笔记语义自审确认动机、真实备选及元库事务代价均有记录，宽表总读取内存缺口及重访条件已明确。

未验证：宽表字典总读取内存、真实 MCP 客户端、部署联调与人工接受。
32KiB 是输出文本预算，字典行仍在输出限幅前物化，不构成聚合读取内存有界的证明。
本结果只覆盖 G6/S7；全仓组合与其余网关切片仍由父项分别验收。

## 交接

本切片本地实施已获用户授权。安全关键合入/推送仍受单独授权边界约束；准备可评审候选后报告。
不将 G6 局部结果写成整个网关完成，不纳入其他窗口 diff。
部分 G6 修改已被并发窗口的 React 提交收录；保留历史，不回退或重写，不以当前 HEAD diff 代替完整 G6 范围。
本轮未另外提交、推送或发布；父窗口继续拥有审计/身份及 V10/V11 迁移，均未占用。
