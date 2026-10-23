# ADR-0010：REST 复用若依 R<T>，协议响应分离

状态：纳入设计基线，待工程接入与契约测试。日期：2026-10-23。来源：用户明确要求 REST 返回使用 RuoYi-Vue-Plus R<T>，错误码按模块分段。

## 背景

既有设计为 REST 自定义 requestId/data 或 requestId/error 外壳；本地若依 R.java 实际包含 int code、String msg、T data，成功码为 200。直接复用 R<T> 需要同步前端与错误映射，不能仅改文档口号或把所有协议统一包装。

## 决策

管理/个人/会话 REST 采用实际 R<T> 三字段，成功 code=200；失败使用[登记表](../../../config/quality/rest-error-codes.json)的模块数字码，data 为 requestId/errorCode/retryable/details。HTTP 状态保留语义，requestId 同时通过 X-Request-Id 返回，不扩展 R 的顶层字段。游标分页仍是 data.items/nextCursor，不引入 total。

R<T> 只属于 admin 的若依 Web 适配层，领域错误和纯 Java common/guard 不引用若依。MCP 保留 JSON-RPC/CallToolResult，OAuth 保留规范成功/错误/发现响应；跨协议适配使用同一领域错误但采用各自线格式。

## 替代方案

- 继续自定义 REST 外壳：可直观表达追踪/错误，但与用户要求和若依 UI 生态不一致，当前尚未实现，直接统一成本较低。
- 继承 R 加顶层 requestId/error：前端改动较小，但形成另一种 R 形状，增加并行契约。
- R<T> 包住 OAuth/MCP 或所有错误都 HTTP200：统一处理表面简单，却破坏协议、认证挑战和客户端行为，拒绝。

## 后果与验证

需要更新前端拦截器、异常过滤器、OpenAPI、DTO 示例和 TC-API-009；不能误将数字业务码当 HTTP 状态。错误码登记检查保证分段/唯一性，不证明异常映射已实现。新增/移除错误码须兼容评审，已分配数字与符号不复用。

当前仅完成设计与配置，应用序列化/身份失败/创建201/异步202及 OAuth/MCP 无包装均待实际契约测试。具体合同见[API](../../backend/api-spec.md)、[前端指南](../../frontend/dev-guide.md)。
