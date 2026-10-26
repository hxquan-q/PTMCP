# Ideas Backlog

- **sql_masked 网关侧脱敏器（设计级缺口）**：当前 gateway 所有 terminal 调用对 maskedSql 传 null——audit_log.sql_masked 恒空，个人历史/管理台的 SQL 脱敏列无内容（掩码列截断逻辑 mask() 已在 admin 读侧就绪）。需在 gateway 实现字面量脱敏（保留结构、去除字面值，DESIGN.md §审计定义形态），成功/拒绝路径统一填充。规模：脱敏器设计（SQL 解析级，非正则）+ 双路径接线 + 测试，属独立切片。
- **sqlFinal 密文字段**：S9 已接 sqlRaw 密文（encryptedRaw）；sql_final_cipher 需 AuditTerminal 增加 encryptedFinal 字段+AuditRepository 列映射+terminal 重载扩展+WAL schema 2 演进（WalFormat SCHEMA_VERSION 严格校验，加字段需版本演进与重放兼容）。decrypt 端点 sqlFinal 维度已就绪（null 时返回不含该字段）。决策：增量价值低于 WAL 演进成本，搁置。
- **委派作用域执行（T16 核心）**：ManagementScope 目前仅平台管理员二值判定。下一步把 admin_delegation 纳入判定（组织授权管理员=功能码∩有效委派∩部门子树），PRD AC-02/AC-07 的越界拒绝用例。涉及所有 admin 控制器的门禁改造，需单独切片+完整测试。
- ~~Idempotency-Key 补接线~~ 已完成（S8）：全部 admin 写端点核验——POST 类 IdempotentWrites、PUT/DELETE 类 IfMatch；AuditController 的 decrypt 是查询型解密不适用幂等租约（语义正确）。
- **本机 MySQL 8.4 验证**：测试镜像参数化后本机用 8.0；8.4 digest 拉取卡层（已多次失败勿重试无脑拉）。待网络好转或镜像源可用时重拉，跑一遍全量 mvn test（不带覆盖参数）恢复 8.4 边界验证。
- **browser 端到端走查**：解禁后的委派/平台/客户端/Scope/审计解密页面在 5173 + Dev-Linux 后端做真实浏览器走查（合成账号 + Testcontainers 库），截图存 .playwright-mcp/，补 T17-ui-business 的浏览器证据表。
- **UI 打磨切片池**（用户主线 1，逐轮做）：委派列表管理列窄屏折行、平台表单 proof 配置的留空保持提示、客户端创建后的 secret 一次性弹窗（对照 PAT 模式）、Scope 差异确认框的 diff 可读性、审计解密倒计时的可访问性（aria-live）、EffectiveAccessPane 空态文案。
