# DEV-20261104-windows-wal-directory-fsync：Windows 上 WAL 目录 fsync 不能打开目录

ID: DEV-20261104-windows-wal-directory-fsync
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-11-04

## 目标与边界

来源：QA 在 [DEV-20261102-mcp-transport-hardening](DEV-20261102-mcp-transport-hardening.md) 的 Windows native 复验中发现。四个 MCP 真实集成类 47 项全部 ERROR、0 断言失败，根因不是该片的 Origin/协议头改动。

`WalDirectoryOwner.acquire` 在拿到 `.owner` 锁后调用 `sync(directory)`。`sync` 用 `FileChannel.open(directory, StandardOpenOption.READ)` 再 `force(true)`，这是 Unix 目录 fsync 语义。Windows 上目录不能作为 `FileChannel` 打开，因此任何带 `TerminalWal` 的 `@SpringBootTest` 在 Windows native 都无法初始化。该 `sync` 由 2026-10-29 `a3776d0` 引入，早于传输边界片。

不做项：不绕过审计持久化；不把「跳过 WAL」当成 Windows 的通过条件；Linux/WSL 上现有审计恢复行为必须保持。

## 实施与验证

`WalDirectoryOwner.sync(Path)` 按平台分流，语义差异写进代码注释而不是静默吞掉：

- 非 Windows：保持原实现（打开目录 + `force(true)`），POSIX 语义与既有行为不变。
- Windows：目录无法作为 `FileChannel` 打开（需 `FILE_FLAG_BACKUP_SEMANTICS`，纯 NIO 不可达），退化为
  「WAL 目录内建 `.fsync-*.tmp` → `force(true)` → 删除」。强制刷盘的是文件数据而非目录项，
  属于尽力而为而非等价保证；临时文件前缀 `.fsync-` 不会与 `recoverAbandonedSegments` 的
  `wal-*.open` 扫描撞车。刷盘失败仍向上抛 `IOException`，调用方按 `AUDIT_UNAVAILABLE` 失败关闭。

平台判定用 `os.name` 前缀，不做启动期 IO 探测，避免探测本身成为新的失败点。

## 结果与证据

新增 `WalDirectorySyncTest`（5 项）：目录刷盘在运行平台可用、不残留探针文件、刷盘后
`TerminalWal` 仍能 append 并留下 WAL 段、缺失目录失败关闭、同目录第二个 owner 被拒。

- **RED（Windows 11 + JDK 21.0.10，Windows native Maven）**：把 `sync` 还原成 pre-fix 实现后跑
  `mvn -pl ptmcp-persistence -am -Dtest=WalDirectorySyncTest test` → `Tests run: 5, Failures: 1, Errors: 3`，
  全部为 `java.nio.file.AccessDeniedException: C:\Users\...\Temp\junit-...`。这正是 QA 报的根因。
- **GREEN（同一 Windows native 环境，应用修复后）**：`Tests run: 5, Failures: 0, Errors: 0`，BUILD SUCCESS。
- **GREEN（WSL/Linux 同命令）**：`Tests run: 5, Failures: 0, Errors: 0`。
- 独立探针 `.local/probe/DirSyncProbe.java` 在 Windows 上直接对照两种写法：
  `POSIX_DIRECTORY_FSYNC=FAILED AccessDeniedException` / `SENTINEL_FSYNC=OK`，无残留文件。
- Spotless 已 `apply`；后端全量回归（admin/authz/gateway 1352 项）见本轮 measure 结果。

## 未验证 / 遗留

- Windows native 上四个 MCP 真实集成类（47 项）**未重跑**：它们要 Docker + Testcontainers，
  宿主 Docker API 在 Windows 侧此前多次 `Connection reset`（DEV-20261102 已记录）。本片只证明
  「WAL 目录 owner 初始化不再必然失败」，不等于 47 项已通过。需在 Docker API 稳定的 Windows
  环境重跑后再把该项从 BLOCKED 转 PASS。
- Windows 的目录项刷盘是尽力而为；若将来要求与 POSIX 等价，需要在 Windows 侧引入
  `FlushFileBuffers`（JNI/FFM），超出本片范围，届时应新开 ADR 说明取舍。

## 交接

- 改动文件：`ptmcp-persistence/.../audit/wal/WalDirectoryOwner.java`、新增
  `.../audit/wal/WalDirectorySyncTest.java`。
- 下一步：Docker API 可用时在 Windows 上跑 `McpQueryFlowTest,McpPgQueryFlowTest,McpTier1ProofTest,McpAuditResilienceTest`，
  把结果补进 DEV-20261102 的验证表。
