# ADR-0003：Web 独立会话，MCP 使用 JWT 与 PAT

状态：纳入设计基线，待端到端认证验证。日期：2026-10-23。

## 背景

若依后台已有Sa-Token，MCP依赖OAuth资源服务器。强行统一成同一种浏览器/协议凭证会混淆audience、登录态、CSRF与调用方边界。

## 决策

控制台保留Sa-Token服务端会话，适配HttpOnly/Secure/SameSite Cookie及CSRF；AS使用独立会话，共享账号规则而非一期SSO。MCP使用10分钟JWT或个人PAT。JWT除签名外核验issuer、audience、auth_version、sid/family状态与platform_id；PAT哈希存储、一次展示、不可放URL。

平台Tier1每次MCP请求需受信代理证明或mTLS；公开client_id和CIMD用于标识/注册，不能作为高权限平台证明。private_key_jwt只认证token端点，不替代资源调用证明。平台Header不得覆盖token内平台。

## 替代方案

- 浏览器localStorage长期Bearer：扩大XSS后凭证暴露范围，不采用。
- JWT纯本地验签：不能及时撤销单个授权族，不采用。
- 把Sa-Token改造成所有MCP OAuth端点：协议维护成本高，不采用。

## 后果与验证

存在两个明确的Web会话边界；一期用户可能分别登录控制台与AS。需要统一的账号状态、失败锁定和改密版本服务。验证CSRF、会话固定、kid轮换、audience错误、family撤销、Header冲突和跨用户历史访问。

关联：[认证设计](../../backend/authentication.md)、[刷新ADR](0006-公开客户端刷新令牌轮换.md)。

账号权威版本与跨库改密的后继细化见[ADR-0011](0011-跨库身份变更与权威版本.md)；独立安全域和非一期 SSO 的决定继续成立。
