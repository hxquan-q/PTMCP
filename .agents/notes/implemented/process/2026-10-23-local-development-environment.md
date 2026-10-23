# Agent Note: D盘项目工具选择与专用WSL开发服务

Status: implemented

## Problem

本机存在多个JDK与全局Node，JAVA_HOME8会使Maven与java命令显示不同版本；直接更改机器默认会影响其他项目。Windows无Docker Desktop/数据库服务，且用户要求工具与环境放到D:/config。

## Decision

复用现有JDK21/Maven，新增D:/config/ptmcp下的Node24 LTS和固定pnpm，以当前终端的Enter-Dev入口选择版本。MySQL/PG/Redis放在专用PTMCP-Dev WSL2及D盘VHD中，Docker命名卷保留Linux文件系统语义；固定镜像摘要和本机端口，随机凭据不入仓库。

Windows Java通过loopback双向TLS访问Docker，支持Testcontainers。服务启动/停止脚本拥有保活进程，核对PID、创建时刻和命令再停止；不调用全局WSL shutdown。实际入口与状态由[环境指南](../../../../docs/ops/development-environment.md)持有，本篇仅记录原因。

## Alternatives considered

- **改机器JAVA_HOME和PATH**：默认命令简单，但会改变现有Java8/其他Node项目；采用会话内选择，IDE另选项目JDK。
- **安装Docker Desktop**：集成便捷，但当前无安装，已有WSL可运行；专用发行版不需要Windows管理员安装桌面服务，也不改变原Ubuntu。
- **Windows原生数据库加Redis兼容版/共享Ubuntu**：组件可以分别安装，但运行语义与状态分散；统一到专用Linux环境，数据存D盘且便于容器测试。
- **直接把数据库目录bind到NTFS**：路径直观，但大小写/权限等文件系统语义不同；采用D盘VHD中的Linux卷。

## Consequences

构建和测试使用一致工具，旧环境保留；代价是首次进入终端需要加载脚本，WSL服务需要保活，TLS材料需维护有效期。该发行版用于本地开发，不代表生产隔离或产品部署验收。

## Verification

Maven/Spring Boot与Vue构建/HTTP探针、Windows JDBC/Redis、TLS正反例、Testcontainers与数据持久性均有实际结果，见[环境验证](../../../../docs/evidence/development-environment-validation.md)。产品工程仍未实现；原有研发合同与工具规范继续有效，无既有同主题Note被替代。
