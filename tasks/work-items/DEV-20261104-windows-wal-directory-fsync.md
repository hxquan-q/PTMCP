# DEV-20261104-windows-wal-directory-fsync：Windows 上 WAL 目录 fsync 不能打开目录

ID: DEV-20261104-windows-wal-directory-fsync
Status: ready
Owner: current-agent
Acceptance: pending
Verification: NOT_RUN
Updated: 2026-11-04

## 目标与边界

来源：QA 在 [DEV-20261102-mcp-transport-hardening](DEV-20261102-mcp-transport-hardening.md) 的 Windows native 复验中发现。四个 MCP 真实集成类 47 项全部 ERROR、0 断言失败，根因不是该片的 Origin/协议头改动。

`WalDirectoryOwner.acquire` 在拿到 `.owner` 锁后调用 `sync(directory)`。`sync` 用 `FileChannel.open(directory, StandardOpenOption.READ)` 再 `force(true)`，这是 Unix 目录 fsync 语义。Windows 上目录不能作为 `FileChannel` 打开，因此任何带 `TerminalWal` 的 `@SpringBootTest` 在 Windows native 都无法初始化。QA 用 JDK 21.0.10 在 NTFS 临时目录做了最小探针复现。该 `sync` 由 2026-10-29 `a3776d0` 引入，早于传输边界片。

不做项：不并入传输边界片的验收；不绕过审计持久化；不把「跳过 WAL」当成 Windows 的通过条件。Linux/WSL 上现有审计恢复行为必须保持。

## 实施与验证

未开工。下一步先在 Windows 上用会失败的回归钉住 `sync` 对目录的 `FileChannel.open`，再改成 Windows 可持久的目录刷盘方式，并在 Linux 上重跑受影响的审计/网关集成，确认没有放宽唯一目录 owner 或 terminal 持久准入。

## 结果与证据

本文件只登记缺口。Windows 47 项 ERROR 的原始观察在 QA 的传输边界复验里；WSL/Linux 上同一四类是 47/0/0/0，说明业务断言本身未因此失败。本项自己的修复验证 NOT_RUN。

## 交接

未改 `WalDirectoryOwner` / `TerminalWal`。不要把这项修进传输边界片的提交。
