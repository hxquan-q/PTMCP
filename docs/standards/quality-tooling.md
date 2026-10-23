# 工程标准与自动化门禁

版本：1.2-quality · 2026-10-23。根目录Maven/UI基础工程及格式/类型/构建门禁已建立；完整业务/SQL门禁仍待M0。配置解析、环境探针、框架构建与业务验收分别记录。

## 标准分工

| 范围 | 规范 | 自动化责任 |
| --- | --- | --- |
| Java 语义、异常、集合、并发、日志 | [Alibaba p3c](https://github.com/alibaba/p3c/tree/6c59c8c36ecd8722c712d5685b8c3822c1c8b030) 与现有安全契约 | Checkstyle 覆盖已配置规则；其余依靠类型、测试和语义评审，不声称覆盖整本手册 |
| Java 排版/缩进/导入顺序 | google-java-format AOSP | Spotless 3.1.0 + formatter 1.28.0，四空格，Checkstyle 不重复判定格式 |
| Vue 组件/Props/事件/模板 | [Vue 风格指南](https://cn.vuejs.org/style-guide/) | ESLint + eslint-plugin-vue；命名/Props 等在错误级别阻断 |
| JS/TS/Vue 排版 | plus-ui Prettier 基线 | Prettier 独立执行；eslint-config-prettier 最后关闭冲突规则 |
| MySQL 表与项目 SQL | p3c MySQL 章节与[数据库设计](../backend/db-design.md) | M0 接入迁移实库结构断言、SQL AST/Mapper 检查；当前无迁移/Mapper，未执行此门禁 |
| REST/错误分段 | [API 契约](../backend/api-spec.md) | 当前校验码段/唯一性；M0 从登记生成/核对枚举、OpenAPI 与序列化契约测试 |
| 提交与安全自查 | Conventional Commits / ASVS 5.0.0 | Git 规范及[ASVS 清单](../test/asvs-checklist.md)；格式器不替代安全验证 |

代码格式由工具结果决定，人工/Agent 评审只处理正确性、边界、兼容和安全，不逐行讨论缩进、换行、引号或导入排序。禁止通过全局关闭规则、跳过检查或修改生成结果消除失败。

## Java 配置

[Maven 插件片段](../../config/quality/java-plugins.xml)固定 Spotless 及 Checkstyle 插件/引擎版本，[Checkstyle 配置](../../config/quality/checkstyle.xml)覆盖命名、通配导入、冗余导入、文件/类型对应和部分易错结构。它不包含 Indentation、LineLength、Whitespace、ImportOrder 等排版规则，避免与 formatter 冲突。

片段已合入实际父POM的build/plugins，Checkstyle仍在仓库根config/quality；后续修改以实际POM为准。对子模块和测试源码继承同一规则；生成目录按事实排除，不能全局排除业务源码。检查绑定 `validate`，由 `verify` 自然覆盖；CI 只 check，不 apply。

父 POM 必须显式定义 `ptmcp.qualityRoot`：父工程在 `ptmcp-parent/` 时用 `${maven.multiModuleProjectDirectory}/../config/quality`，POM 就在仓库根时用 `${maven.multiModuleProjectDirectory}/config/quality`。Wrapper 的 `.mvn` 根、`-f` 和当前工作目录可能不同，M0 以实际目录和子模块继承验证，不能靠本机绝对路径或猜测相对根。

以下命令已可在实际ptmcp-parent目录执行；全仓框架检查为 python scripts/check.py --all：

```powershell
# Maven Wrapper 就绪后，在实际父工程执行
.\mvnw.cmd --version
.\mvnw.cmd spotless:apply
.\mvnw.cmd validate
.\mvnw.cmd verify
```

核对 `mvnw --version` 的 Java 实际版本必须为 21；`java -version` 显示 21 不证明 Maven 使用 21（本机探针已发现 JAVA_HOME 指向 8 的差异）。工具版本升级需重新跑格式/命名正反例，不因 Maven 的默认插件版本变化隐式升级。

未直接引入 p3c-pmd：上游 2.1.1 基于旧 PMD，不把未经 Java 21 语法验证的插件塞入构建。M0 如补 PMD/SpotBugs，应单独验证 record、sealed class 等语法及与既有检查的重复，不把 Checkstyle PASS 称为 p3c 全量符合。

## 前端继承与增量

当前ptmcp-ui为最小裁剪骨架，未引入完整plus-ui业务系统。已核对官方镜像 [CrazyLionCat/plus-ui](https://github.com/CrazyLionCat/plus-ui) 的 `6.X-Vue` 提交 `045fc006725c014455def9fed19f93c781633d26`，它已改用 oxlint/oxfmt，不能声称当前还提供 ESLint。

按用户指定使用 ESLint + Prettier：沿用其历史提交 [cc38d23d1469ad7b684b61b0bf2e9a58a11d3c74](https://github.com/CrazyLionCat/plus-ui/tree/cc38d23d1469ad7b684b61b0bf2e9a58a11d3c74) 的 `eslint.config.js` 结构及 `.prettierrc` 排版值。该提交只用作配置来源，**不降级/导入历史前端运行时**。改动如下：

- JS recommended → TypeScript recommended → Vue flat 配置的解析链保留；Vue 从 essential 提升为 recommended。
- 恢复多词组件名、有效 Props、Props 类型/默认值、禁止直接修改 Props、显式 emits；新增 no-explicit-any 和禁止 v-html。App 为唯一显式组件命名例外；不以 index 等泛化例外放过业务组件。
- ESLint 不运行 prettier 插件；Prettier 单独格式化，最后合入 eslint-config-prettier 消除冲突。保留上游 150 列、2 空格、分号、单引号等参数，仅将 endOfLine 固定 LF。
- 自动导入 globals 若存在则读取；首次 Vite 生成前不虚构全局声明，未知符号仍由 lint/typecheck 检查。不得从上游请求拦截器继承浏览器存 token 或自动重试写请求的行为。

可复用文件：[eslint.config.mjs](../../config/quality/frontend/eslint.config.mjs)、[Prettier](../../config/quality/frontend/.prettierrc.json)、[排除项](../../config/quality/frontend/.prettierignore)、[脚本/依赖片段](../../config/quality/frontend/package.fragment.json)、[MIT 声明](../../config/quality/frontend/UPSTREAM-LICENSE)。片段是初始化来源；当前实际UI已有package/锁文件和lint/format/typecheck/build，未引入oxlint/oxfmt。后续新增业务时再补真实测试，当前不提供虚构test通过。直接依赖版本是工具探针基线，完整依赖图仍须锁文件及供应链检查。

当前前端CI使用 `pnpm install --frozen-lockfile --ignore-scripts`，需要构建脚本时审查后按需启用；执行 `pnpm lint`、`pnpm format:check`、实际 typecheck/test/build。本地修复使用 `pnpm lint:fix` 与 `pnpm format`；CI 禁止 fix/write。

Vue 命名与 Props 示例见[前端指南](../frontend/dev-guide.md)。风格指南是基础参考，不将其旧 Options API 示例强制套在 Vue 3 `<script setup>`；类型化 defineProps/withDefaults 与运行时校验按实际边界决定。

## MySQL 采用与明确适配

新建 PTMCP 元库表名/字段采用小写 snake_case，避免保留字，实体表用单数；每表有显式主键、`create_time/update_time DATETIME(6) NOT NULL`，UTC 写入。唯一键 `uk_`、普通索引 `idx_`；MySQL 主键实际索引名为 `PRIMARY`，不虚构可重命名的 pk_ 结果。金额/精确数值用 DECIMAL，条件查询参数化，变更和索引须实库验证。

手册原文采用 `id/gmt_create/gmt_modified`；本项目按用户要求及若依审计字段适配为“显式主键 + create_time/update_time”。保留已设计的协议 ID/摘要主键与复合主键，不凭规范新增一个破坏幂等语义的自增 ID。既有领域中的事件/到期时间不由通用时间字段取代；不可变表更新语义见数据库设计。

p3c 文档对外键、分页 total 等给出通用规则；本项目是单元库、事务安全网关，保留既有 RESTRICT/唯一键/完整性设计，禁止级联破坏历史；保留无 total 的游标分页。上述为显式架构适配，不静默删约束或强制 count。Boolean 与已锁定协议字段命名不做无关破坏迁移；新增自有布尔字段按 is_xxx。

**SQL 投影禁令**适用于自有 Mapper、仓储、迁移附带查询及工具脚本：显式列名，禁止 SELECT * 和 t.*，不把 `COUNT(*)` 错判为违规。MyBatis 禁止 `${}` 拼接用户值，标识符从服务端白名单生成。历史漏洞夹具、外部 Agent SQL 和 guard AST 探针不按开发投影规范拒绝；网关仍按快照展开星号并完整绑定、鉴权与预算验证。不能用文本 grep 命中字符串/注释就声称完成 SQL 安全扫描。

## 当前门禁与 M0 接线验收

`python scripts/check.py` 新增配置 XML/JSON 解析和 REST 码段检查，CI 自动使用同一入口。真实 Java/前端工具探针结果登记在[本轮工作项](../../tasks/work-items/DEV-20261023-quality-standards.md)；它们只验证配置，不是产品构建。

M0 必须在 T01/T03/T17 分别提供：父 POM 继承且故意错格式/通配导入使 CI 失败；Vue 错命名/Props 修改使 ESLint 失败且 Prettier 错格式使 CI 失败；真实元库 DDL 每表主键/时间字段断言及项目 SQL AST 规则；REST 序列化/错误映射契约。缺一项保持待验收，不用模板解析 PASS 代替。合入格式修复的重复 diff 与行为变更分开审查。

来源补充：[Spotless Maven 文档](https://github.com/diffplug/spotless/blob/main/plugin-maven/README.md)、[google-java-format 1.28.0](https://github.com/google/google-java-format/tree/v1.28.0)、[Checkstyle](https://checkstyle.org/checks.html)、[eslint-plugin-vue 配置](https://eslint.vuejs.org/user-guide/)、[eslint-config-prettier](https://github.com/prettier/eslint-config-prettier)。

当前实际参数以ptmcp-parent/pom.xml、ptmcp-ui配置/锁文件为准；config/quality中的插件/依赖片段保留为初始化来源。框架接线的构建、启动与UI组合验证见[工程记录](../evidence/framework-bootstrap-validation.md)，不宣称业务实现或测试完成。
