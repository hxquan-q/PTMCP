# DEV-20261028-gateway-completion：完整问数鉴权网关收尾

ID: DEV-20261028-gateway-completion
Status: in_progress
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-10-28

## 目标与边界

来源：用户在能力核查后要求立项并实施完整功能。承接
[能力核查](DEV-20261028-gateway-capability-review.md) 与
[收尾提案](../../.agents/notes/proposed/architecture/2026-10-28-gateway-capability-hardening.md)，
保留现有架构，不建立替代网关或平行连接方案。

完整范围是既定 MySQL/PG 只读网关的资源发现/预注册 OAuth 接入、审计恢复、候选连接发布与健康、
请求取消/容量、密钥兼容以及授权字典上下文；新增数据库、写 SQL、事务/插件、开放 DCR/CIMD、
生产操作和收费服务不在本轮。DCR 保持关闭，CIMD 不在缺少实际连接 pinning 时启用。

不变量：平台与用户权限交集、Deny 优先、guard 前零业务连接、主库 START 准入、terminal 持久后返回、
执行前版本与权限复核；不重试业务 SQL，不因取消 handler 提前释放数据库额度。

本轮基准 HEAD 为 `795fb347a295bb4b65fe0a15f607eac50eaab9cd`。
已有 UI/T17 改动、前轮三份评估文档与 `.impeccable/critique/` 均保留。
UI 改动涵盖 App、PageState、useCursorPage、styles、权限/审计/数据源/角色/用户/字典页面及相应测试；
本轮先不写这些被占用文件。不得把其他工作的 diff 纳入本轮提交。

## 实施与验证

按根工作流一次完成一个可验证切片；执行为单 Agent。安全关键切片需实际边界测试和独立评审，
适用规则要求的独立评审只委派只读审查，不并行实现或修改共享源码。

| 切片 | 承接 | 完成标准 | 当前状态 |
| --- | --- | --- | --- |
| G1 资源发现 | T19/T13/T14 | 真实 HTTP 401 能发现受信资源与 AS，URI 配置严格验证，预注册路径明确 | completed |
| G2 审计恢复 | T09/T20 | 唯一目录 owner、仅回放关闭段、幂等恢复/损坏告警/UNKNOWN 收敛与故障测试 | pending |
| G3 候选与健康 | T15-c S4 | 错候选不替换 active、CAS 发布、健康绑定版本与目标 | pending |
| G4 取消与容量 | T15-c S5/T19-d | 主体隔离取消、执行清理后归还额度、部署预算语义明确且有边界测试 | pending |
| G5 密钥兼容 | T15-c S6 | 新旧钥兼容、对象绑定与迁移格式、日志不泄漏、恢复仍可读 | pending |
| G6 字典上下文 | T15-c S7 | 三工具契约说明、授权后字典/关系/预算、撤权与恶意注释测试 | pending |
| G7 组合验收 | T19-e/T20 | 双引擎、OAuth、恢复、前后端适用门禁；真实客户端与部署证据单列 | pending |

G1 写入范围为 gateway 元数据/过滤器/测试、API 合同、相关工作项与笔记。
资源元数据和 challenge 共享唯一受信配置，不从 Host/Forwarded 头生成 URI；
声明协议保持 2025-06-18。

验证环境：使用系统 JDK21 `/usr/lib/jvm/java-21-openjdk-amd64`，`bash ./mvnw --version`
已确认 Maven 3.9.9 / Java 21.0.12.1；Docker Server 29.5.3 可访问。
wrapper 未设置 executable，使用 bash 调用，不改文件权限。已有开发实例不启动/清理，实库验证使用隔离 Testcontainers。

适用门禁：逐片定向测试、gateway/authz/admin 及双方言组合 `verify`、`python scripts/check.py`、
最终 `python scripts/check.py --all`；失败必须修正或保留未完成，不能跳过测试伪造通过。

## 结果与证据

G1 已完成：`McpResourceMetadata` 由四处部署配置（`resource-uri`、`resource`、`issuer`、
`resource-metadata-uri`）生成 RFC 9728 文档，只允许 HTTPS 或回环 HTTP，拒绝 userinfo/query/fragment/
控制字符/错误端口/跨源 metadata URL；`/.well-known/oauth-protected-resource` 与 401 challenge
引用同一文档，未配置时发现端点 404 以保留仅 PAT 部署。无 DCR 时预注册路径写入认证文档。

实际执行（系统 JDK21，2026-10-28）：

- `bash ./mvnw -q -pl ptmcp-gateway -am -Dspotless.check.skip=false test` 退出 0；
  含 `McpResourceMetadataTest`、`McpResourceMetadataHttpTest`（真实 HTTP 发送伪造
  `X-Forwarded-Host`/`Forwarded`，响应仍只含受信配置）及模块内既有测试。
- `python scripts/check.py` 退出 0：60 项检查器回归 PASS，文档与链接检查 PASS。
- `git diff --check` 退出 0。

未验证：目标真实 MCP 客户端/授权服务器的发现、PKCE、refresh、撤销联调；公网证书与域名；
多副本部署。G1 完成仅表示服务端发现端点与预注册文档闭环，不等于真实客户端验收。

前轮 746 项属于历史基线，不覆盖本轮代码。
安全关键变更的独立评审、真实客户端版本与环境、生产证书/域名、故障恢复及人工接受均待实际证据。
用户已授权本地实施；生产/发布/跨分支或破坏性动作不随本工作项自动授权。

## 交接

当前焦点 G1：资源发现与配置校验，完成一个切片再推进 G2。
整体保持 in_progress，不把立项、计划或局部测试通过报告为完整交付。
尚未提交、推送、部署或发布；原有 UI 修改归原工作所有者。
