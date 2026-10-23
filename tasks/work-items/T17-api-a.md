# T17-api-a：HTTP 边界原语

ID: T17-api-a
Status: done
Owner: current-agent
Acceptance: not-required
Verification: PASS
Updated: 2026-10-24

## 目标与边界

来源/父任务或用户请求：[parallel-prompts-20261024](../parallel-prompts-20261024.md) A4，父任务 T17；[API 契约](../../docs/backend/api-spec.md) §3/§4/§7/§9/§10。

目标：提供协议无关的 HTTP 边界原语：服务端请求 ID、全部登记错误码到 HTTP 状态与 retryable 的映射、严格 JSON 解析。

不做项：不新建 `org.dromara.*` 外壳、不实现 `R<T>` 响应封装（上游构件不可得，留给协调者决策）、不新增业务端点/认证/数据库依赖、不改 POM。

不变量：请求 ID 永远由服务端生成，客户端头不能覆盖；未登记/空错误码 fail-closed 到 500 `INTERNAL_ERROR`，任何映射都不是 200；错误响应不含 message/stack/exception/对象名；JSON 仅 UTF-8、拒绝未知字段与重复 key、>1MiB 返回 413、数字 ID 可按字符串接收。

## 实施与验证

新增 `ptmcp-admin/src/main/java/local/ptmcp/admin/web`：
- `RequestIdFilter`：每请求生成 UUID，写入响应头 `X-Request-Id` 与 request attribute；从不读取入站头。
- `ErrorDescriptor(ErrorCode, HttpStatus, retryable)`、`ErrorMapping`：以 `EnumMap` 追加式登记 52 个枚举值；空/缺失回落 500 `INTERNAL_ERROR`。
- `StrictJson`：1MiB 上限（超出 `METADATA_TOO_LARGE`→413）、严格 UTF-8 解码、`STRICT_DUPLICATE_DETECTION`、`FAIL_ON_UNKNOWN_PROPERTIES`；失败仅抛 `INVALID_ARGUMENT`，消息只含码名；Jackson 数字类型天然接受字符串 ID。

HTTP 选择（依据 api-spec.md）：
- 400：`INVALID_ARGUMENT`、`UNSUPPORTED_FIELD`（§10 行 217 的 400/422）；`PRECONDITION_REQUIRED`（§3 行 25 明示漏传 400）。
- 422：`IDEMPOTENCY_KEY_REUSED`（§4 行 27 明示 422）、`DIALECT_CONTEXT_INVALID`、`CONNECTION_BUDGET_EXCEEDED`（§10 行 217 的 400/422）；`RESOURCE_AMBIGUOUS`、`SQL_*`、`FUNCTION/TYPE/RESULT/REWRITE`（§10 行 222 Tool拒止，投影为 422）。
- 401/403/404/409/429/503/500：按 §10 行 218–227 对应分组。`TABLE_NOT_AUTHORIZED` 属 §10 行 222 Tool拒止，REST 投影为 403；`TOKEN_ALREADY_CREATED`（§4 行 27）与 `DUPLICATE_GRANT`（§7 行 111）取 409；`METADATA_TOO_LARGE`（§9 行 177）取 413；执行错误组取 500。
- retryable 仅 429 与 503 两组为 true（§10 行 224、226）。

RED：首次全量门禁在 Spotless 阶段失败（新增文件未格式化）；格式化后 admin `verify` 首次编译并通过全部测试。测试与实现同批编写，本切片未形成严格 RED，记录为已知过程缺口。

验证命令与结果：

```text
.\mvnw.cmd -B -ntp -pl ptmcp-admin -am spotless:apply
.\mvnw.cmd -B -ntp -pl ptmcp-admin -am verify
```

结果：common 210 tests、admin 22 tests，0 failures / 0 errors / 0 skipped；`BUILD SUCCESS`。新增：
- `ErrorMappingTest` 6 例：52 码逐个非 200 且码自洽；按契约分组断言状态与 retryable；空码 fail-closed。
- `StrictJsonTest` 8 例：字符串 ID（含 `Long.MAX_VALUE`）、UTF-8 中文、未知字段、重复 key、非法 UTF-8、错误不回显正文、1MiB+1 413、边界与空输入。
- `WebBoundaryMockMvcTest` 6 例：服务端 ID 覆盖客户端头、未知字段/重复 key/非法 UTF-8 400、缩略响应仅 4 字段、413、正常请求回显 ID。

## 结果与证据

实际修改：
- `ptmcp-parent/ptmcp-admin/src/main/java/local/ptmcp/admin/web/{RequestIdFilter,ErrorDescriptor,ErrorMapping,StrictJson}.java`
- `ptmcp-parent/ptmcp-admin/src/test/java/local/ptmcp/admin/web/{ErrorMappingTest,StrictJsonTest,WebBoundaryMockMvcTest}.java`
- 本工作项

未执行项：未接入真实异常过滤器（等协调者确定 `R<T>` 落地方式）；未覆盖真实容器对 1MiB 的早停（当前在解析原语与 MockMvc 验证）；未做浏览器/前端联调。

评审：自审（§10 逐行对照），非独立评审。

人工接受：内部研发任务，不适用产品接受。

## 交接

当前焦点：A4 完成；`R<T>` 复用方式仍是协调者待决事项，未自造外壳。

实际 git status / 提交：本切片提交见本轮 commit（`feat(admin)`: HTTP 边界原语）。

已有授权：计划允许逐片提交推送；默认单 Agent。待决定事项：上游 `R<T>` 以 vendor 源码还是等价方式落地。
