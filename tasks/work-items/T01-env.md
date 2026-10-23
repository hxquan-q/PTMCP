# T01-env：配置本机开发环境

ID: T01-env
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-23

## 目标与边界

来源：用户要求安装、配置开发所需环境，工具放在 D:/config。父任务 T01 的环境准备子项，不等于应用工程或业务功能完成。

已知：D:/config 有 JDK8/17/21 和 Maven3.9.9；JDK21.0.10 可用，但系统 JAVA_HOME 指向8，mvn 不在当前 PATH；全局 Node26.3.1/pnpm11.9.0/Git 已存在。Windows 没有 Docker CLI/数据库服务，WSL2 已有 Ubuntu-26.04。保持旧工具和已有环境，新增项目隔离工具选择入口，不擅自重置数据库、关闭其他服务或改变现有 Ubuntu 配置。

## 实施与验证

单 Agent 串行。读取实际工具、端口、存储和 WSL 状态及官方安装资料；先复用 JDK21/Maven，再安装项目使用的 Node24 LTS/固定 pnpm。按实际可用运行环境建立独立的 MySQL8.4、Redis 及 PostgreSQL18 开发运行方式，数据/配置尽量归入 D:/config/ptmcp，凭据不入仓库/输出。

写范围：本任务、环境说明/项目启动入口/必要检查和忽略项；D:/config 下新增自有环境目录、下载及校验记录。用户级环境变量若修改必须备份且可恢复，不变更无关机器级配置。服务只绑定本机，凭据文件最小权限；只使用合成数据验证连接。记录每个工具实际版本、启动/停止方法与验证边界。

验收：项目环境入口选中 Java21/Maven/Node/pnpm；实际构建与前端工具探针通过；本次建立的数据库/缓存启动、认证、读写和重启持久性按实际覆盖报告；文档与仓库检查通过。若系统条件阻碍某组件，完成其他独立工作后如实记录阻断与最小人工动作，不编造成功。

## 结果与证据

完成：复用 JDK21.0.10/Maven3.9.9，安装 D盘 Node24.22.1/pnpm11.9.0；独立 PTMCP-Dev WSL2、Docker29.1.3/Compose2.40.3；MySQL8.4.13、PG18.6、Redis7.4.11，以固定镜像digest、本机端口、命名卷与随机密码运行。Windows Docker双向TLS接口支持真实Testcontainers2.0.5。工具与数据位置及使用方式见[环境指南](../../docs/ops/development-environment.md)。

已验证：环境入口选择实际Java/Maven/Node/pnpm与D盘缓存，重复进入不累加Maven参数；Spring Boot探针格式/静态检查/编译/打包与HTTP200；Vue lint/format/typecheck/build及HTTP200；Windows JDBC/Redis认证读写；Docker重启、容器重建及stop/start后只读核验合成数据仍存在；Docker严格双向TLS正反例；Testcontainers启动/连接/关闭临时容器且最终无残留；服务完整启停及本机自检通过。细节和版本见[环境验证](../../docs/evidence/development-environment-validation.md)。仓库统一检查在最终落盘后执行，不把环境检查计作产品业务PASS。

排障：Java命令与Maven选中不同JDK、pnpm11 run不接受普通store-dir参数、PowerShell拆分Maven点号-D参数、WSL保活、CA缺少keyUsage均已通过实际检查定位/修正；不禁用TLS或跳过门禁。服务停止后WSL可有回收延迟，未调用全局shutdown或误停旧Ubuntu。作者自审，无独立评审；内部环境交付不需要产品功能人工验收。

未执行：产品工程启动、业务数据库迁移、客户端OAuth、安全/性能门禁与生产部署。原工作树的 README 修改及 .agents/.github/config/docs/scripts/tasks 等未提交文件全部保留；没有提交、推送。机器级环境与旧Ubuntu未改动，新增环境由当前终端选择和独立脚本管理。

## 交接

环境子项完成，服务已再次启动并保持healthy；Spring Boot/Vite探针进程已关闭。下一步收到工程实现请求后续接T01的父POM/Wrapper/common/guard/gateway，不能把T01-env或环境探针当成产品工程完成。源脚本在本仓库scripts，本机安装副本与清单在D:/config/ptmcp。凭据/客户端私钥在同目录secrets，仅本机受控读取，不进入Git。
