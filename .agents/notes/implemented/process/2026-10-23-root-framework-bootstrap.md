# Agent Note: 项目框架在仓库根，工具环境与业务实现分离

Status: implemented

## Problem

环境探针只能证明工具可用，无法替代用户需要的根目录前后端工程。用户进一步明确本轮只搭框架/环境，并要求支持Element Plus、图标、Tailwind和动效库，业务编码尚未授权。

## Decision

根目录ptmcp-parent保存正式后端骨架、ptmcp-ui保存正式前端骨架，D:/config保留工具/缓存/基础服务。后端只使用标准启动入口和健康检查；前端只使用最小启动壳验证库加载，不提供业务页面/接口/认证。实际启动与构建由各工程README、POM/package/锁文件持有。

CSS关闭Tailwind Preflight，明确Element Plus/工具类层级并保留box-sizing；Animate.css独立导入，Motion注册但不默认触发动效。Iconify使用已安装图标集、关闭自动安装。完整框架检查使用根脚本--all和对应CI任务。

## Alternatives considered

- **继续用D盘smoke示例**：能快速验证环境，但用户找不到本项目源码，不能满足正式框架初始化。
- **整仓复制plus-ui业务系统**：默认功能齐全，但会带入不符合当前安全合同的登录/token/租户行为，超出只搭框架范围；采用明确来源的最小裁剪。
- **先做状态面板和业务拒绝接口**：易于演示，但用户已明确暂不编码，因此撤回本轮未完成计划，保持业务实现未开始。

## Consequences

框架路径清晰且可构建，代价是业务能力仍需后续任务逐一实现；不能把Spring Boot健康状态、依赖存在或CI构建当安全验收。后续引入认证/SQL/数据访问必须先满足既有合同和测试要求。

## Verification

真实构建、三个JAR健康、业务404、Vite代理和桌面/手机浏览器依赖组合已验证，见[框架记录](../../../../docs/evidence/framework-bootstrap-validation.md)。[环境决策](2026-10-23-local-development-environment.md)仍有效，本篇只补齐根目录框架边界，不替代环境记录。
