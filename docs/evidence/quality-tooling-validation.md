# 工程工具配置验证记录

日期：2026-10-23。范围：配置模板的真实工具正反例及设计/登记结构校验。未构建 PTMCP 应用，未运行数据库、OAuth 客户端或 ASVS 实测。

## 环境与配置

Windows 11；隔离目录 `C:/Users/xquan/AppData/Local/Temp/ptmcp-quality-1eeg3ue0/`，不依赖真实账号/业务数据。JDK 21.0.10、Maven 3.9.9、Node 26.3.1、pnpm 11.9.0。Maven 从 Maven Central 下载，ZIP 的 SHA-512 与同源 `.sha512` 比对通过；前端用 `pnpm install --ignore-scripts` 安装固定直接依赖，临时锁文件不代表未来产品锁文件。

Java 探针从[插件片段](../../config/quality/java-plugins.xml)建立临时 POM，使用相同[Checkstyle](../../config/quality/checkstyle.xml)；前端复制[配置目录](../../config/quality/frontend)中的 ESLint/Prettier 和依赖片段。所有临时输入均为合成样例。

## 实际执行结果

| 工具/命令（在对应临时工程） | 输入 | 实际结果 |
| --- | --- | --- |
| `mvn validate` | 一行未格式化 Java 类 | 退出 1，Spotless 指出格式差异；这是预期拒绝 |
| `mvn spotless:apply` 后 `mvn validate` | 同一类，四空格 AOSP 排版 | 均退出 0，Spotless clean，Checkstyle 0 violations |
| `mvn checkstyle:check` | 加 java.util.* 导入及 BadMethod 方法名 | 退出 1，明确出现 AvoidStarImport 与 MethodName；不是只根据非零猜测规则生效 |
| `pnpm exec eslint src --max-warnings 0` | UserProfile.vue，类型化 Props/默认值/只读使用 | 退出 0 |
| `pnpm exec prettier --check src` | 未排版模板 | 退出 1，指出 UserProfile.vue 格式问题 |
| `pnpm exec prettier --write src` 后 check/lint | 格式化后的同一模板 | 均退出 0 |
| `pnpm exec eslint src -f json` | Busy.vue 单词组件、模板修改 props.value；BadProps.vue 使用无类型 Props 数组 | 退出 1；JSON 结果包含 vue/multi-word-component-names、vue/no-mutating-props、vue/require-prop-types |
| `python scripts/check.py` | 当前仓库文档/工作流/质量配置/错误码/回归 | PASS，26 个检查器用例通过；新增 8 个码段/冲突/未知模块等用例 |

环境排障：第一次 Maven 调用实际用了 JAVA_HOME 指向的 Java 8，不能加载插件；只在探针命令中指定 JDK21 后正常运行，没有改用户级 Java 配置。最初 Checkstyle 路径误依赖调用目录，已改为父 POM 显式声明 ptmcp.qualityRoot，并重新跑通过路径和拒绝路径。

## 证据限制与后续

本次证明模板在上述环境可执行且典型错误会被工具拒绝；不证明全部 Vue 规则、Java21 全语法或 p3c 全册均被自动覆盖。产品没有 POM/UI/迁移，T01/T03/T17 仍必须完成真实接线、全量源码扫描、锁文件/供应链核对、MySQL 实库与 REST 序列化测试。CI 通过统一入口解析模板/检查码表，尚未在远端实际运行，也未配置产品格式检查任务。

ASVS 选定条款保持 NOT_RUN/GAP，见[清单](../test/asvs-checklist.md)。本轮作者自审，无独立评审、用户产品验收、提交、集成或发布。详细工作范围见[工作项](../../tasks/work-items/DEV-20261023-quality-standards.md)。
