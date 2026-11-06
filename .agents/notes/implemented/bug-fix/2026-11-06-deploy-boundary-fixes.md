# Agent Note: 部署入口和运行身份边界

Status: implemented

## Problem

部署包跨容器、Linux、Windows 共享配置含义，但模板和生成器未保持这些边界：
OAuth 登录路由缺失、代理 authority 丢端口、Windows nginx BOM/空格路径阻断；
生成器接受应用拒绝的资源 URI，秘密权限与运行身份不匹配，元库运行用户持有 DDL。

## Decision

三份 nginx 保留原始 Host authority，并显式转发 `/login` 和 `/logout`。
原生路径参数用引号，Windows nginx 输出显式使用无 BOM UTF-8。
配置生成器在写秘密前校验无路径的 HTTP(S) 基址，HTTP 只允许回环，
原生直接 HTTP 的监听端口来自基址。空业务端口是显式拒绝，只有未配置时使用默认值。
Windows 配置和 secrets 在写入前限制为当前运行身份与 SYSTEM。
容器签名钥在受控的一次性步骤中复制至仅 authz 挂载的卷，所有权设为 UID 10001，
不扩大宿主私钥可读范围。MySQL 密码也通过专属卷转换为 UID 999 可读，避免同类 0600 挂载失败。
Compose 的迁移和各服务使用不同元库账号，DDL 仅授迁移账号。
表级授权依赖迁移建表，因此 `configure-db` 在迁移后运行，应用依赖它成功完成；
不能把授权放进 MySQL 的空卷初始化钩子。运行账号权限按表/列枚举，admin 仅能撤销 OAuth
授权，不能改授权码，gateway 不能读取宿主密码表。

Windows 在写秘密前收紧配置目录及现有文件的 DACL，保留当前身份与 SYSTEM，
使用 .NET 文件系统 ACL API 持久化，避免 PowerShell 5.1 的 Set-Acl 额外特权问题。
原生命令两路输出异步消费，避免 OpenSSL 进度填满 stderr 后与 stdout 读取死锁。
Windows nginx 启动轮询允许 PID 文件先创建、后填充；空内容继续等待，不对 null 调用 Trim。
镜像和原生打包使用 `skipTests`，保留下游依赖的 test-jar；不能使用同时禁止夹具生成的
`maven.test.skip`。测试执行仍由独立的 verify 门禁负责。

## Alternatives considered

- 将宿主私钥设为所有人可读：操作最少，但暴露签名能力，拒绝。
- Compose secret 指定 uid/mode：声明简洁，但本地文件 secret 的绑定挂载不落实所有权转换，不能解决实际失败。
- 继续共用 MySQL 官方 MYSQL_USER：初始化简单，但所有应用都持有 DDL 和跨服务写入权限，违反部署约束。
- 在空卷初始化时授予所有未来表的权限：没有额外启动步骤，但 MySQL 对不存在的表拒绝该 DML 授权，实际运行失败；因此使用迁移后的显式步骤。

## Consequences

数据库权限变化需对既有卷显式执行受控账号配置；旧共用账号不能仅靠换配置消除权限。
增加两个一次性步骤及两个秘密卷，换取运行身份匹配与分进程最小授权；已有卷升级需维护窗口，
不能用 `--force` 轮换加密密钥代替升级。原生外部元库没有自动账号分权步骤。
真实外部 TLS 入口、初始管理员与完整客户端验收仍由部署父工作项负责。

## Verification

`deploy/tests/` 的真实 nginx、三个生成器、Compose/MySQL 与 PowerShell 5.1 探针覆盖
GET/POST login、logout、含端口 Host、含空格路径、无 BOM、URL 拒绝先于秘密写入、
显式空端口及 Windows 子进程环境、目录和既有文件 ACL、64 KiB stderr 并发读取。
原生 Windows nginx 1.30.6 在 PowerShell 5.1 下实际完成含空格目录的配置检查、启动、
健康/静态访问及停止，空 PID 文件有确定性 red/green 回归。
MySQL 从空卷迁移至 V20，运行账号 DDL/跨职责写入与宿主密码表边界均经过实际 SQL 拒绝验证；
UID 10001 可读签名卷，UID 10002 被拒，宿主私钥仍为 0600。
命令及全量检查的实际结果、缺口保存在关联工作项，不将自审当成独立评审。
