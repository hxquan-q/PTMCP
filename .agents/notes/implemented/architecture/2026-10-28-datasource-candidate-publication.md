# Agent Note: 数据源候选验证与 CAS 发布

Status: implemented

## Problem

管理端 `/datasources/{id}/test` 只返回一次连接测试结果，验证过的凭据与目标没有可发布的载体：
管理员只能把同一份草稿再提交到 `PUT`，测试与生效之间没有版本绑定，测试期间配置被他人改动时
仍可能按旧结果覆盖新配置。若直接把失败草稿写入 `datasource`，错误凭据会破坏正在服务的连接；
若按目标切换发布，旧 host 上的资源身份、对象授权、平台范围和委派又会被新目标静默继承。

健康状态同样需要一个可判定的证据来源：不能把任意历史探针或候选草稿算作当前配置的健康结果，
否则一次失败的候选测试就会让可用数据源显示 `UNHEALTHY`，或旧版本的成功结果掩盖新版本故障。

## Decision

探针结果与发布分成两个状态，发布只在主库安全事务内按 CAS 生效：

- **候选表只追加**：V14 `datasource_candidate` 保存 `base_config_version`、完整目标、加密凭据、
  `probe_scope`、`state`、`checked_at`/`valid_until`。`ACTIVE_PROBE` 来自无 draft 的诊断，
  `DRAFT_CANDIDATE` 来自带 draft 的测试；行本身不可变，只有生命周期状态从 `VERIFIED`
  变为 `PUBLISHED`。
- **失败候选不保存凭据**：`recordTest` 在同一事务写入候选与 `TEST` job；失败时
  `credential_cipher`/`key_id` 为 NULL，成功时才加密保存。`JobDTO` 返回 `candidateId`，
  调用方可从任务关联到候选，但响应与日志都不含凭据或密文。
- **发布必须显式 CAS**：`POST /datasources/{id}/candidates/{candidateId}/publish` 要求
  `If-Match` 与 `Idempotency-Key`，`base_config_version` 必须等于 `If-Match` 当前版本。
  错误顺序固定为：当前版本不符 → `VERSION_CONFLICT`；候选失败/过期/已发布/无凭据 →
  `INVALID_ARGUMENT`；候选基准陈旧 → `VERSION_CONFLICT`。先锁 `datasource` 行并校验当前
  版本，再读候选，保证陈旧版本配不存在候选时仍返回版本冲突而不是参数错误。
- **发布与撤权同事务**：复制候选目标到 active 行并升 `configVersion`；host/port/database
  变化或停用时同时升 `resourceVersion`、清 `activeSnapshotId`/`lastSyncAt`，调用
  `DatasourceTargetIsolation.retire` 退役旧资源身份并撤销对象/命名空间授权、平台范围与
  委派。凭据或名称轮换但目标不变时保留资源身份与授权，不清快照指针；但 `configVersion`
  已升高，而 `schema_snapshot` 以发布时的 `config_version` 绑定，因此该快照立即不再可用
  （`PublishedSnapshotReader.usable()` 为 false），必须重新同步才能恢复查询，实现沿用
  [数据源设计](../../../../docs/backend/datasource.md)的保守拒绝而非静默沿用旧目录。
  任何一步失败连同审计事件一起回滚，
  不会出现"配置已换、旧授权仍在"的中间态。
- **健康只认当前证据**：`DatasourceDTO.health` 只取 `ACTIVE_PROBE` 中
  `base_config_version = configVersion`、host/port/database/username 与当前行一致、
  `valid_until` 未过期的最新一条；`VERIFIED → HEALTHY`、`FAILED → UNHEALTHY`，
  其余及非 `ACTIVE` 状态为 `UNKNOWN`。`DRAFT_CANDIDATE` 永不参与 active 健康。
- **候选有效期 5 分钟**：过期候选不能发布；这是固定常量，暂无部署配置。
- **数据源端点按动作校功能码**：`ManagementScope.requirePlatformAdminPrincipal(request,
  delegations, code)` 同时要求平台管理员与 `ptmcp:datasource:{list,read,create,update,delete,
  test,sync}`（publish 复用 `update`），平台管理员不豁免校码，缺码统一 403
  `MANAGEMENT_SCOPE_DENIED`。校码早于 body 解析与幂等占位，越权请求不会留下 idempotency
  记录；`If-Match` 校验仍按原顺序在校码之前，只影响缺前置条件时的错误码，不泄露资源内容。
  其余管理面仍只有 `requirePlatformAdmin`，逐动作码待各自切片承接。

## Alternatives considered

1. **测试通过后直接写 active**：最少代码，但测试与生效之间没有 CAS，管理员看到旧版本
   测试成功时可能覆盖别人刚改的配置；失败草稿也会污染服务中的凭据。拒绝。
2. **只保存候选、发布时重新测试**：能进一步确认目标当时可用；但发布变成慢操作，
   测试与发布之间仍需同样的版本校验，且重复建立业务连接扩大失败面。当前保留候选结果 +
   版本绑定，实际可用性由发布后的健康探针补证。
3. **新目标沿用旧资源身份与授权**：发布实现更简单；但 host 变化意味着旧授权指向另一套
   数据，继承等于越权。拒绝，沿用 V8 的目标隔离与撤权路径。

## Consequences

- 收益是错候选不会替换 active、并发发布只有一个赢家、旧版本健康结果不能覆盖新版本，
  目标切换不会复活旧权限；代价是多一张追加表和一次显式发布调用。
- `datasource_candidate` 目前没有保留/清理策略，会持续保存加密凭据历史；需在密钥与
  运维清理切片中按审计保留期处理，当前风险已记入
  [网关收尾工作项](../../../../tasks/work-items/DEV-20261028-gateway-completion.md)。
- 候选有效期与健康窗口是固定常量，尚无部署配置；在高延迟或跨时区部署中需重新评估。
- 校码是 fail-closed 的收紧：迁移没有为任何角色预置 `ptmcp:datasource:*`，功能码只能由平台
  管理员经 `/roles` 管理面写入 `sys_role_permission`。已部署环境升级后，未持码的角色（包括
  原本仅靠 platformAdmin 通过的管理员）会立即对数据源管理面得到 403，需先为相应角色配码；
  该上线前置步骤已记入网关收尾工作项，不作为静默兼容处理。
- 幂等重放只恢复状态码与响应体，`api_idempotency` 没有响应头列，因此重放不返回首次的
  `ETag`；已在 [API 规格](../../../../docs/backend/api-spec.md) 记为已知限制，调用方以返回体
  `version` 作为后续 `If-Match` 依据。确有客户端依赖重放 ETag 时再迁移存响应头。
- 本切片完成的是后端候选发布与健康语义，不代表管理 UI、真实客户端或整体 S4 验收完成。

## Verification

- `DatasourceTargetIsolationTest`：真实 MySQL 覆盖发布原子性、目标切换撤权、
  失败/过期/陈旧/已发布候选拒绝、并发只允许一个赢家、陈旧版本配不存在候选仍返回
  `VERSION_CONFLICT`、健康只认当前版本与当前目标的未过期 active probe；另覆盖写
  `sys_security_event` 失败时发布整体回滚（候选仍 `VERIFIED`、active 配置与授权、
  `policy_epoch` 不变、发布审计日志为 0），以及同目标发布后旧快照 `usable()` 为 false、
  重新 stage/publish 到新 configVersion 后恢复。
- `DatasourceApiTest`：真实 HTTP 覆盖 `/test` 返回 `candidateId`、失败草稿不落凭据、
  publish 缺 `If-Match`、陈旧版本、幂等重放、非空 publish body 在任何状态变更前被拒绝、
  响应无凭据、候选变为 `PUBLISHED`；并以不持任何数据源功能码的平台身份断言 8 个端点
  全部 403 `MANAGEMENT_SCOPE_DENIED`。
- `SnapshotPublicationMigrationTest`：V7 → V14 升级路径可重复执行。
- 定向命令 `mvn -pl ptmcp-admin,ptmcp-persistence -am test` 退出 0；
  完整门禁与独立评审结果见工作项，不以本笔记替代验收。
