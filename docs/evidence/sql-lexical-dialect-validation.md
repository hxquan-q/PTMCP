# SQL 词法方言事实核对（T05-a）

日期：2026-10-24（UTC）。范围：ptmcp-guard 词法预检的方言假设。本文只记录一手来源与本次实际观测；词法通过不等于只读/授权/可执行，AST、绑定、执行器与权限尚未实施。

## PostgreSQL 18：续接与注释嵌套

来源：官方源码 [REL_18_STABLE `src/backend/parser/scan.l`](https://github.com/postgres/postgres/blob/REL_18_STABLE/src/backend/parser/scan.l)（本次读取 raw.githubusercontent 同路径文件）。关键产生式原文：

```text
space				[ \t\n\r\f\v]
non_newline_space	[ \t\f\v]
newline				[\n\r]
comment			("--"{non_newline}*)
special_whitespace		({space}+|{comment}{newline})
non_newline_whitespace	({non_newline_space}|{comment})
whitespace_with_newline	({non_newline_whitespace}*{newline}{special_whitespace}*)
quotecontinue	{whitespace_with_newline}{quote}
```

由此可判定的规则：

- 相邻字符串续接要求两段之间至少一个 CR/LF；仅空格不续接。
- 续接间隙允许任意 `space`（含 VT `0x0B`、FF `0x0C`）和 `--` 行注释；行注释必须在 CR/LF 之前，CR/LF 之后的行注释必须再跟 CR/LF 才属于 `special_whitespace`。
- **C 风格块注释不允许出现在续接间隙**（`whitespace_with_newline` 只含 `space`/`comment`）；块注释会中断字符串续接。
- PG 的 `/* */` 支持嵌套：`<xc>{xcstart}` 执行 `xcdepth++`，`<xc>{xcstop}` 仅在 `xcdepth<=0` 时回到 INITIAL。

因此独立评审提出的“`E'a' --c\n'b'` 可能被错误续接”属于对该合同的反向误读：按官方产生式该间隙**确实构成续接**；实现允许行注释/ VT/FF 与多 CRLF 的正确方向一致。

## MySQL 8.4：C 风格注释不嵌套

来源：本次在本机隔离环境（PTMCP-Dev Docker，固定镜像 `mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69`，实际报告 8.4.13）启动一次性容器，未发布端口、未挂载数据卷、未连接既有 `ptmcp_dev`，用容器内原生 `mysql` 客户端执行同目录探针脚本（`source /tmp/probe.sql`，经 `docker cp` 复制，不拼 shell 参数）。观测：

```text
t
single-with-tail-comment                       -- SELECT 'single-with-tail-comment' AS t /* plain */ 成功
ERROR 1064 (42000) at line 4 ... near 'c */'   -- SELECT 'nested-attempt' AS t /* a /* b */ c */ 语法错误
```

若 MySQL 嵌套普通块注释，第二条应在第二个 `*/` 结束注释后成为合法单语句；实际是在第一个 `*/` 已关闭注释，剩余的 `c */` 成为语法错误。结论：**MySQL 8.4 普通 `/* */` 不嵌套**，实现按“第一个 `*/` 关闭”处理是正确的；审查意见中的“MySQL 支持嵌套（约 85% 信心）”不成立。

容器在探针后已删除（`--rm`）。探针只含合成常量 SQL，无业务表、无真实数据、无凭证输出。

## 审查发现的分类与处置

| 发现 | 分类 | 处置 |
| --- | --- | --- |
| PG 续接间隙可能过宽（条件性） | 合同误读 | 按上述官方产生式不改逻辑；在测试与源码注释中钉死 `--`/VT/FF/多 CRLF 语义 |
| MySQL 普通注释可能嵌套 | 事实不成立 | 以实库观测否定；实现保持非嵌套，测试覆盖 `/* /*/` |
| 方言矩阵只断言异常类型 | 有效且可行动（测试强度） | 改为断言具体 `ErrorCode`（预算/只读/不支持/语法），并新增确定性优先级用例 |
| null policy 与 null SQL 处理不一致 | 有效权衡 | 有意保留：缺少内部配置是编程错误（NPE），缺少外部 SQL 按 `SQL_SYNTAX_INVALID`；已在 Javadoc 写明 |
| 旧 PASS 依赖 `/tmp` 日志 | 有效（证据位置） | 本文与工作项保留可复跑命令与真实源码/服务端结果，不提交敏感完整日志 |
| 未知普通标点被静默跳过 | 合同内（后续层责任） | 由 AST 白名单拒绝；本层不冒充审批，Javadoc 已声明 |

## 未验证与边界

- 未在真实 PostgreSQL 18.6 容器上执行前述续接字符串；PG 结论来自官方 `scan.l` 产生式与既有单元回归，属源码核对而非服务端观测。
- 词法预检是 `void` 检查，`UPDATE`/`DELETE` 也能通过；只读、单 SELECT、节点白名单必须由后续 AST 层拒绝。
- 连接借出与 parser 的 `sql_mode`/`standard_conforming_strings` 一致性校验尚未实现；`SqlLexicalPolicy` 只接收“已由服务端配置核实”的模式，不得用默认值猜测。
- 未做双数据库原 SQL/final SQL 等价对照（属 T07/T10/T11）。

复跑命令（Windows PowerShell，先 `.\scripts\Enter-Dev.ps1`）：

```powershell
cd ptmcp-parent
.\mvnw.cmd -B -ntp -pl ptmcp-guard -am test
```
