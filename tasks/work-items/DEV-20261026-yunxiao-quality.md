# DEV-20261026-yunxiao-quality：云效代码检测问题清理

ID: DEV-20261026-yunxiao-quality
Status: verifying
Owner: current-agent
Acceptance: pending
Verification: PASS
Updated: 2026-10-26

## 目标与边界

来源/用户请求：修复 `Allstar_AI/ALLSTAR_DATACHAT_SUB` 的 Codeup 代码检测问题
（`quality/issue`），导出文件为用户提供的 `yunxiao_20261026_152729_567.xlsx`。

输入证据：210 条问题（严重 114、警告 96），按规则主要为：

- `@typescript-eslint/member-delimiter-style` 52（生成文件 `ptmcp-ui/src/components.d.ts`）
- `import/no-unresolved` 21（同一生成文件的 `~icons/...` 导入）
- `comma-dangle` 20
- 通用密钥 6 + 信息熵 1（测试夹具中的字面 secret）
- 手动创建线程池 5
- `covering.add` 3
- `no-void` 2
- `import/no-duplicates` 2
- `switch` 缺少 default 1
- `@typescript-eslint/indent` 2 / `semi` 1
- 其余约 70 条为 P3C 对 Java record/类名的 lowerCamelCase 误报

不做项：不重命名业务 record/类以迎合 P3C 误报；不删除代码检测规则或降低安全规则；
不改业务行为；不把网页 issue 明细当作可复跑的 OpenAPI 数据。

## 实施与验证

- 代码检测方案启用文件路径过滤，排除生成文件 `ptmcp-ui/src/components.d.ts`
  （该文件由 unplugin-vue-components 生成，成员分隔符和 `~icons` 导入不应进入质量门禁）。
- 前端：Prettier `trailingComma=all` 并格式化 `src`/`tests`；合并重复 import；
  去掉 `void` 浮动 Promise，改为显式 `.catch`。
- Java 测试：`Executors.new*` 改为有界 `ThreadPoolExecutor`；测试中的字面 secret
  改为运行时随机生成；`ToolPermissionService` 重命名局部集合避免遍历误报；
  `PinnedTlsPoolFactory` 增加 default 分支。

验证：

```text
mvnw.cmd -B -ntp verify        → BUILD SUCCESS（common 224 / persistence 85 / guard 216 /
                                 datasource 35 / admin 45 / authz 32 / gateway 29）
pnpm --dir ptmcp-ui check      → PASS
pnpm --dir ptmcp-ui test:contracts → 68/68 PASS
python scripts/check.py        → 48 项 PASS
```

## 结果与证据

- 生成文件排除后，预计消除 73 条严重问题（52 成员分隔符 + 21 未解析图标导入）。
- 前端格式/重复导入/no-void 改动并通过构建与 68 项契约测试。
- Java 线程池、局部集合、测试 secret 与 default 分支改动通过全仓 Maven verify。
- 剩余 P3C lowerCamelCase 警告为 record/类名误报，保留不改业务命名。

未验证/边界：

- 代码检测 API 不提供 issue 明细查询；无法在本地精确核对每条规则的文件/行。以上分类基于
  用户导出的规则汇总和本地代码审计。
- 需要推送后由云效重新扫描确认剩余问题；扫描结果只能通过网页查看。
- 未做独立安全评审；测试 secret 已改为随机生成，但测试代码仍可能有其他规则误报。

## 交接

下一步：推送 `dev`，等待代码检测重新扫描；如仍有严重问题，按网页明细继续修复。
