# 工程标准与自动化门禁

版本：1.2-quality · 2026-10-23；工程路径核对：2026-10-30。配置解析、环境探针、实际构建、业务与安全验收分别记录；旧 Vue 工具证据不能代替 React 检查。

## 标准分工

| 范围 | 规范 | 自动化责任 |
| --- | --- | --- |
| Java 语义、异常、集合、并发、日志 | [Alibaba p3c](https://github.com/alibaba/p3c/tree/6c59c8c36ecd8722c712d5685b8c3822c1c8b030) 与现有安全契约 | Checkstyle 覆盖已配置规则；其余依靠类型、测试和语义评审，不声称覆盖整本手册 |
| Java 排版/缩进/导入顺序 | google-java-format AOSP | Spotless 3.1.0 + formatter 1.28.0，四空格，Checkstyle 不重复判定格式 |
| React/TypeScript 语义与类型 | [前端指南](../frontend/dev-guide.md)与实际工程配置 | ESLint JS/TS 规则和 TypeScript strict；不声称存在未配置的 Vue/React 插件规则 |
| JS/TS/TSX 排版 | 工程内 Prettier 配置 | Prettier 独立执行；eslint-config-prettier 关闭冲突规则 |
| MySQL 表与项目 SQL | p3c MySQL 章节与[数据库设计](../backend/db-design.md) | 工程已有迁移；实库结构与 SQL AST/Mapper 检查按相应工作项证据判断，不由文件存在推导通过 |
| REST/错误分段 | [API 契约](../backend/api-spec.md) | 已校验码段/唯一性及从登记生成的纯Java ErrorCode一致性；OpenAPI、前端类型和实际协议序列化/映射仍待适配片测试 |
| 提交与安全自查 | Conventional Commits / ASVS 5.0.0 | Git 规范及[ASVS 清单](../test/asvs-checklist.md)；格式器不替代安全验证 |

代码格式由工具结果决定，人工/Agent 评审只处理正确性、边界、兼容和安全，不逐行讨论缩进、换行、引号或导入排序。禁止通过全局关闭规则、跳过检查或修改生成结果消除失败。

## Java 配置

[Maven 插件片段](../../config/quality/java-plugins.xml)固定 Spotless 及 Checkstyle 插件/引擎版本，[Checkstyle 配置](../../config/quality/checkstyle.xml)覆盖命名、通配导入、冗余导入、文件/类型对应和部分易错结构。它不包含 Indentation、LineLength、Whitespace、ImportOrder 等排版规则，避免与 formatter 冲突。

片段已合入实际父POM的build/plugins，Checkstyle仍在仓库根config/quality；后续修改以实际POM为准。对子模块和测试源码继承同一规则；生成目录按事实排除，不能全局排除业务源码。检查绑定 `validate`，由 `verify` 自然覆盖；CI 只 check，不 apply。

父 POM 必须显式定义 `ptmcp.qualityRoot`：父工程在 `ptmcp-parent/` 时用 `${maven.multiModuleProjectDirectory}/../config/quality`，POM 就在仓库根时用 `${maven.multiModuleProjectDirectory}/config/quality`。Wrapper 的 `.mvn` 根、`-f` 和当前工作目录可能不同，M0 以实际目录和子模块继承验证，不能靠本机绝对路径或猜测相对根。

以下命令在实际 ptmcp-parent 目录执行；`python scripts/check.py --all` 设计为在 Maven verify 后运行隔离门禁正反例与前端检查，前端目标已于 2026-10-30 迁移为 `ptmcp-ui-react` 的 `pnpm check` + `pnpm test`，GitHub CI 同步更新；Windows 本机已实测两者退出码 0，GitHub Actions 与 Linux 脚本尚未实际执行，不能据此声称跨平台全量门禁可用。可选 `--maven-cache-seed <现成Maven仓库目录>` 仅只读复制种子到临时仓库以加速（例如本机D:/config/ptmcp/cache/maven），不改变检查范围、不写种子；未指定仍运行而非跳过。实际探针只在临时工程注入main/test格式、已规范通配import和common Spring边界反例，不修改项目源码/全局Maven缓存，不把下载失败或超时当拒绝通过。

```powershell
# Maven Wrapper 就绪后，在实际父工程执行
.\mvnw.cmd --version
.\mvnw.cmd spotless:apply
.\mvnw.cmd validate
.\mvnw.cmd verify
```

核对 `mvnw --version` 的 Java 实际版本必须为 21；`java -version` 显示 21 不证明 Maven 使用 21（本机探针已发现 JAVA_HOME 指向 8 的差异）。工具版本升级需重新跑格式/命名正反例，不因 Maven 的默认插件版本变化隐式升级。

未直接引入 p3c-pmd：上游 2.1.1 基于旧 PMD，不把未经 Java 21 语法验证的插件塞入构建。M0 如补 PMD/SpotBugs，应单独验证 record、sealed class 等语法及与既有检查的重复，不把 Checkstyle PASS 称为 p3c 全量符合。

## 前端现行配置与历史来源

当前前端为 [ptmcp-ui-react](../../ptmcp-ui-react/README.md)。实际规则链见 [eslint.config.mjs](../../ptmcp-ui-react/eslint.config.mjs)：JS/TypeScript recommended、浏览器全局变量、禁止显式 any 和 eslint-config-prettier；TypeScript 启用 strict，Prettier 独立执行。`pnpm check` 与 `pnpm test` 在 React 工程内分别运行。配置不包含 Vue 插件，也不声称自动检查全部 React 无障碍或业务安全规则。

### 历史 Vue 选型与配置

以下保留 2026-10-23 的旧前端来源，不描述当前 React 工程。原 ptmcp-ui 曾为最小裁剪骨架，未引入完整 plus-ui 业务系统。已核对官方镜像 [CrazyLionCat/plus-ui](https://github.com/CrazyLionCat/plus-ui) 的 `6.X-Vue` 提交 `045fc006725c014455def9fed19f93c781633d26`，它已改用 oxlint/oxfmt，不能声称当前还提供 ESLint。

按用户指定使用 ESLint + Prettier：沿用其历史提交 [cc38d23d1469ad7b684b61b0bf2e9a58a11d3c74](https://github.com/CrazyLionCat/plus-ui/tree/cc38d23d1469ad7b684b61b0bf2e9a58a11d3c74) 的 `eslint.config.js` 结构及 `.prettierrc` 排版值。该提交只用作配置来源，**不降级/导入历史前端运行时**。改动如下：

- JS recommended → TypeScript recommended → Vue flat 配置的解析链保留；Vue 从 essential 提升为 recommended。
- 恢复多词组件名、有效 Props、Props 类型/默认值、禁止直接修改 Props、显式 emits；新增 no-explicit-any 和禁止 v-html。App 为唯一显式组件命名例外；不以 index 等泛化例外放过业务组件。
- ESLint 不运行 prettier 插件；Prettier 单独格式化，最后合入 eslint-config-prettier 消除冲突。保留上游 150 列、2 空格、分号、单引号等参数，仅将 endOfLine 固定 LF。
- 自动导入 globals 若存在则读取；首次 Vite 生成前不虚构全局声明，未知符号仍由 lint/typecheck 检查。不得从上游请求拦截器继承浏览器存 token 或自动重试写请求的行为。

历史模板文件：[eslint.config.mjs](../../config/quality/frontend/eslint.config.mjs)、[Prettier](../../config/quality/frontend/.prettierrc.json)、[排除项](../../config/quality/frontend/.prettierignore)、[脚本/依赖片段](../../config/quality/frontend/package.fragment.json)、[MIT 声明](../../config/quality/frontend/UPSTREAM-LICENSE)。它们是旧 Vue 初始化来源，不代表 React 实际使用的规则；完整依赖图须核对当前锁文件及供应链。

旧 Vue CI 曾使用 `pnpm install --frozen-lockfile --ignore-scripts` 及其专有 `test:contracts` 脚本；当前仓库的 GitHub CI 和 `check.py --all` 已改为 `ptmcp-ui-react` 的 `pnpm check` + `pnpm test`，并在 Windows 本机实测退出码 0，云端 Flow 配置仍待回读更新。依赖安装脚本需审查；本地修复可用工程内的 `pnpm lint:fix` 与 `pnpm format`，CI 不应执行写入式修复。

## MySQL 采用与明确适配

新建 PTMCP 元库表名/字段采用小写 snake_case，避免保留字，实体表用单数；每表有显式主键、`create_time/update_time DATETIME(6) NOT NULL`，UTC 写入。唯一键 `uk_`、普通索引 `idx_`；MySQL 主键实际索引名为 `PRIMARY`，不虚构可重命名的 pk_ 结果。金额/精确数值用 DECIMAL，条件查询参数化，变更和索引须实库验证。

手册原文采用 `id/gmt_create/gmt_modified`；本项目按用户要求及若依审计字段适配为“显式主键 + create_time/update_time”。保留已设计的协议 ID/摘要主键与复合主键，不凭规范新增一个破坏幂等语义的自增 ID。既有领域中的事件/到期时间不由通用时间字段取代；不可变表更新语义见数据库设计。

p3c 文档对外键、分页 total 等给出通用规则；本项目是单元库、事务安全网关，保留既有 RESTRICT/唯一键/完整性设计，禁止级联破坏历史；保留无 total 的游标分页。上述为显式架构适配，不静默删约束或强制 count。Boolean 与已锁定协议字段命名不做无关破坏迁移；新增自有布尔字段按 is_xxx。

**SQL 投影禁令**适用于自有 Mapper、仓储、迁移附带查询及工具脚本：显式列名，禁止 SELECT * 和 t.*，不把 `COUNT(*)` 错判为违规。MyBatis 禁止 `${}` 拼接用户值，标识符从服务端白名单生成。历史漏洞夹具、外部 Agent SQL 和 guard AST 探针不按开发投影规范拒绝；网关仍按快照展开星号并完整绑定、鉴权与预算验证。不能用文本 grep 命中字符串/注释就声称完成 SQL 安全扫描。

## 当前门禁与 M0 接线验收

`python scripts/check.py` 检查配置 XML/JSON、REST码段及生成的Java错误枚举一致性，CI自动使用同一入口。分配错误码只修改唯一JSON登记，再执行 `python scripts/generate_error_codes.py`；`--check` 与门禁只读，不自动修复漂移。生成器反例覆盖缺失/漂移、非法登记不写入和--check不覆盖；它不证明实际R<T>/MCP/OAuth错误映射已实现。真实 Java/前端工具探针结果登记在[本轮工作项](../../tasks/work-items/DEV-20261023-quality-standards.md)；它们只验证配置，不是产品构建。

T01-b提供实际父子POM的隔离正反例入口 `python scripts/check_backend_quality.py`（可选 --seed-repository/--timeout）；验证脚本的 mock 单测不替代实际 Maven 输出。M0 的解析器/依赖边界、真实元库 DDL、项目 SQL 规则和 REST 序列化等证据按对应工作项逐项核对；旧 Vue 错命名/Props 探针只对历史工程有效，React 应针对其实际 lint/类型/格式配置另行验证。缺少证据保持待验收，不用模板解析 PASS 代替。合入格式修复的重复 diff 与行为变更分开审查。

来源补充：[Spotless Maven 文档](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md)、[google-java-format 1.28.0](https://github.com/google/google-java-format/tree/v1.28.0)、[Checkstyle](https://checkstyle.org/checks.html)、[eslint-plugin-vue 配置](https://eslint.vuejs.org/user-guide/)、[eslint-config-prettier](https://github.com/prettier/eslint-config-prettier)。

当前实际参数以 [ptmcp-parent/pom.xml](../../ptmcp-parent/pom.xml) 和 [React package.json](../../ptmcp-ui-react/package.json)/锁文件为准；config/quality 中的插件/依赖片段保留为历史初始化来源。[框架记录](../evidence/framework-bootstrap-validation.md)是旧时点证据，不代表当前业务或 React 测试完成。
