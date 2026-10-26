# Ideas Backlog

- **gateway 审计密文写入**：McpToolService.terminal() 对 encryptedRaw 传 null，audit_log.sql_raw_cipher/sql_final_cipher 恒空。按 AuditCipher 的 iv||ct||tag 格式在 gateway 侧接入 WalKeyProvider 加密写密文，S6 的解密端点即可端到端。属 gateway 切片（ptmcp-gateway），涉及 T09 审计纵切。
- **委派作用域执行（T16 核心）**：ManagementScope 目前仅平台管理员二值判定。下一步把 admin_delegation 纳入判定（组织授权管理员=功能码∩有效委派∩部门子树），PRD AC-02/AC-07 的越界拒绝用例。涉及所有 admin 控制器的门禁改造，需单独切片+完整测试。
- **Idempotency-Key 补接线**：DelegationAdminController/PlatformAdminController/clients.create 已接 IdempotentWrites；org 的 POST /users /departments /roles 尚未接（IdempotencyRecorder 协议在 Store 层已验证）。
- **本机 MySQL 8.4 验证**：测试镜像参数化后本机用 8.0；8.4 digest 拉取卡层（已多次失败勿重试无脑拉）。待网络好转或镜像源可用时重拉，跑一遍全量 mvn test（不带覆盖参数）恢复 8.4 边界验证。
- **browser 端到端走查**：解禁后的委派/平台/客户端/Scope/审计解密页面在 5173 + Dev-Linux 后端做真实浏览器走查（合成账号 + Testcontainers 库），截图存 .playwright-mcp/，补 T17-ui-business 的浏览器证据表。
- **UI 打磨切片池**（用户主线 1，逐轮做）：委派列表管理列窄屏折行、平台表单 proof 配置的留空保持提示、客户端创建后的 secret 一次性弹窗（对照 PAT 模式）、Scope 差异确认框的 diff 可读性、审计解密倒计时的可访问性（aria-live）、EffectiveAccessPane 空态文案。
