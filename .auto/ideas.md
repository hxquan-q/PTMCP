# Ideas Backlog

- **业务库 TLS 材料（MCP 查询流 E2E 前置）**：dev business DB（mysql:8.0 容器）无与 127.0.0.1 匹配的 TLS 证书，而 DatasourceConnectionFactory 钉死 sslMode=VERIFY_IDENTITY + allowPublicKeyRetrieval=false（安全设计：无明文回退，勿放宽代码）。需给业务 MySQL 配置真实证书（SAN 含 127.0.0.1/localhost 或走主机名）后，MCP 查询流即可端到端：PAT 认证→guard→执行→审计密封→console 解密。当前已打通：gateway enabled（8082/mcp 401 门）、PAT 创建 E2E、PAT 认证到达 guard（SCHEMA_STALE）、业务库 walker_ro 授权。
- ~~sql_masked 网关侧脱敏器~~ 已完成（4111d09）：SqlLiteralMasker 双解析验证+字面量脱敏，成功/拒绝两路径填充；遗留 MySQL 反斜杠转义字符串保守放弃（null，密文为权威可读形态）。
- ~~sqlFinal 密文字段~~ 已完成（f848c11）：AuditTerminal +encryptedFinal+AuditPayloads JSON+AuditRepository 双路写+WalPayload schema 2（v1 兼容读）+gateway 密封。设计更正：payload 版本内嵌于加密载荷、frame 层不透明、无重编码哈希比对——演进成本远低于早前评估。集成断言验证 guard 重写指纹（schema 限定/别名/LIMIT）。
- **委派作用域执行（T16 核心）**：ManagementScope 目前仅平台管理员二值判定。下一步把 admin_delegation 纳入判定（组织授权管理员=功能码∩有效委派∩部门子树），PRD AC-02/AC-07 的越界拒绝用例。涉及所有 admin 控制器的门禁改造，需单独切片+完整测试。
- ~~Idempotency-Key 补接线~~ 已完成（S8）：全部 admin 写端点核验——POST 类 IdempotentWrites、PUT/DELETE 类 IfMatch；AuditController 的 decrypt 是查询型解密不适用幂等租约（语义正确）。
- ~~本机 MySQL 8.4 验证~~ 已完成（9c4e723）：pinned 8.4.13 digest 拉取成功，全量测试默认参数（无覆盖）全绿，8.4 版本断言实证；measure.sh 恢复默认 8.4 目标。
- **browser 端到端走查**：解禁后的委派/平台/客户端/Scope/审计解密页面在 5173 + Dev-Linux 后端做真实浏览器走查（合成账号 + Testcontainers 库），截图存 .playwright-mcp/，补 T17-ui-business 的浏览器证据表。
- **UI 打磨切片池**（用户主线 1，逐轮做）：委派列表管理列窄屏折行、平台表单 proof 配置的留空保持提示、客户端创建后的 secret 一次性弹窗（对照 PAT 模式）、Scope 差异确认框的 diff 可读性、审计解密倒计时的可访问性（aria-live）、EffectiveAccessPane 空态文案。
