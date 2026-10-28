# DEV-20261028-gateway-capability-review：问数鉴权网关能力核查

ID: DEV-20261028-gateway-capability-review
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-28

## 目标与边界

回答当前连接数据与鉴权的网关是否已实现，对照 universal-db-mcp 与 dbx 提出优化顺序，
按用户指定技能记录取舍。只做源码/证据评估和 proposed 笔记，不改业务代码、不启动数据库、
不开放端点、不安装外部项目依赖、不合并分支、不提交/推送或发布。
维持 guard 前零业务连接、平台与用户权限交集、Deny 优先、审计持久准入、版本复核与只读边界。

开工已有修改：`ptmcp-ui/src/App.vue`、`ptmcp-ui/src/styles/index.css`、
`ptmcp-ui/src/views/admin/org/RolesView.vue`、`tasks/work-items/T17-sap-refine.md`；
开工时还观察到未跟踪的 `ptmcp-ui/.impeccable/`。这些资产不属于本轮，未编辑、清理或提交。

## 实施与验证

项目基准为 `xquan/feat-backend` / `795fb347a295bb4b65fe0a15f607eac50eaab9cd`。
续接 [连接专项](DEV-20261027-datasource-connection-research.md)、[T15-c](T15-c.md)、
[T13-b](T13-b.md)、[T14-a](T14-a.md)、[T19-a](T19-a.md)、[T19-b](T19-b.md)、
[T19-c](T19-c.md)、[T19-d](T19-d.md)、[T19-e](T19-e.md)，检索待办、ADR 与活跃 notes。

委派依据：本轮采用 `research` 技能，其明确要求后台调查。参考 Agent 只读外部源码，
独占 `/tmp/ptmcp-external-review-agent/`；主 Agent 核查 PTMCP 并整合，无并行业务写入。
参考仓通过 authenticated gh 获取，未执行外部代码；dbx 大资源下载已停止，改为精确读取源码。
外部调查不构成 PTMCP 独立安全评审。

写入范围：本工作项、[增强提案](../../.agents/notes/proposed/architecture/2026-10-28-gateway-capability-hardening.md)、
既有 [网关基线笔记](../../.agents/notes/implemented/architecture/2026-10-26-t19a-gateway-implementation.md) 的当前事实与互链。
不另建产品基线或改变既有 ADR。

验收：结论有源码/工作项定位；区分当前源码、历史测试、本轮检查和生产接受；
提案说明参考机制、不迁移项、优先级及反例验收。适用检查为 `python scripts/check.py`、
`git diff --check` 与文档自审，不把文档检查当业务测试。

## 结果与证据

已形成增强提案：核心 MySQL/PG、三工具、PAT/JWT/Tier1、权限交集、guard、审计写入、
连接策略及执行前复核已有实现；尚不能据此宣布完整客户端与生产运行验收通过。
确定收尾点包括资源发现端点、生产 WAL 回放装配、客户端取消与跨副本预算；
候选配置/健康、密钥轮换和字典继续承接 T15-c S4～S7。

参考仓冻结为 universal `dc338cd7740c277c04722743b97eb1681e409016`、
dbx `ee6fb66aa2bccc6232cb5e3d9d79a56ef13cac1b`；固定源码链接已整合到提案。
dbx 的工具契约、紧凑授权元数据和取消生命周期可参考，不迁入写/事务/插件、共享主体或权限覆盖语义。

历史证据：[T15-c](T15-c.md) 记录 746 项全 reactor 测试通过；本轮未复跑。
本轮实际检查：

- `python scripts/check.py` 在新增文档后退出 0：文档 134 个 Markdown / 579 个本地链接，
  流程、质量配置及 HEAD 提交消息检查 PASS，检查器回归 60/60 PASS。
- `git diff --check` 退出 0；写入仅限上述三个文档，原业务/UI 修改保留。
- 自审：区分已实现/已验证/人工接受/发布；不重复报 T15-c 已修的 TLS、配置绑定与出队复核；
  区分 DCR 的 SHOULD 与资源发现的 MUST，MCP resources 与 OAuth 资源元数据，以及执行器取消与客户端取消。
  外部 URL 未由仓库检查器校验，参考链接来自本轮固定版本源码读取。

未运行 Maven/pnpm、参考仓测试、真实客户端、真实业务库、故障恢复或生产部署；
默认 JDK 为 17，本轮没有绕过项目环境要求直接运行 Maven。
Acceptance not-required 仅适用于内部评估文档，不表示用户接受方案、业务功能或发布。

## 交接

评估文档与自审已完成；所有增强保留 proposed，不启动业务实施。本轮无业务更改、提交、推送或发布。
后续优先按既有 T19/T13/T14 补资源发现与预注册客户端闭环，再按 T09/T20 补恢复，
连接/容量/密钥/字典仍沿 T15-c 继续，不复制昨天已修的缺口。
