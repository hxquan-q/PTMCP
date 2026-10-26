package local.ptmcp.gateway.mcp;

import local.ptmcp.common.error.ErrorCode;

import java.util.Map;
import java.util.Set;

/**
 * Stable tool-level error presentation: one registered code maps to a fixed user-facing message and
 * the retryability contract of the API spec. Infrastructure text, SQL and object names never enter
 * these messages.
 */
final class McpErrorCatalog {
    private static final Set<ErrorCode> RETRYABLE =
            Set.of(
                    ErrorCode.SCHEMA_STALE,
                    ErrorCode.RESOURCE_VERSION_CHANGED,
                    ErrorCode.SECURITY_STATE_UNAVAILABLE,
                    ErrorCode.AUDIT_UNAVAILABLE,
                    ErrorCode.USER_CONCURRENCY_LIMIT,
                    ErrorCode.DATASOURCE_CONCURRENCY_LIMIT,
                    ErrorCode.GATEWAY_BUSY,
                    ErrorCode.DEPENDENCY_UNAVAILABLE,
                    ErrorCode.CONNECTION_POOL_UNAVAILABLE);

    private static final Map<ErrorCode, String> MESSAGES =
            Map.ofEntries(
                    Map.entry(ErrorCode.INVALID_ARGUMENT, "请求参数不合法"),
                    Map.entry(ErrorCode.TOKEN_INVALID, "当前凭证缺少执行该操作所需的授权范围"),
                    Map.entry(ErrorCode.TOKEN_REVOKED, "当前凭证已被撤销，请重新认证"),
                    Map.entry(ErrorCode.PLATFORM_MISMATCH, "请求声明的平台与凭证绑定平台不一致"),
                    Map.entry(ErrorCode.PLATFORM_PROOF_REQUIRED, "该平台要求每请求来源证明"),
                    Map.entry(ErrorCode.RESOURCE_NOT_FOUND, "请求的数据资源不存在或不可见"),
                    Map.entry(ErrorCode.DIALECT_CONTEXT_INVALID, "方言或上下文参数不合法"),
                    Map.entry(ErrorCode.SCHEMA_STALE, "元数据快照缺失或已过期，请先完成同步"),
                    Map.entry(ErrorCode.RESOURCE_VERSION_CHANGED, "资源版本已变化，请重试"),
                    Map.entry(ErrorCode.TABLE_NOT_AUTHORIZED, "当前账号无权查询该资源，请联系管理员"),
                    Map.entry(ErrorCode.SQL_SYNTAX_INVALID, "SQL 语法不合法或包含不支持的语法"),
                    Map.entry(ErrorCode.SQL_NOT_READONLY, "仅允许只读查询语句"),
                    Map.entry(ErrorCode.SQL_UNSUPPORTED, "包含不受支持的 SQL 结构"),
                    Map.entry(ErrorCode.SQL_BUDGET_EXCEEDED, "SQL 复杂度或大小超出预算"),
                    Map.entry(ErrorCode.FUNCTION_NOT_ALLOWED, "包含不允许的函数"),
                    Map.entry(ErrorCode.TYPE_NOT_ALLOWED, "包含不允许的类型"),
                    Map.entry(ErrorCode.RESULT_TYPE_UNSUPPORTED, "输出类型无法安全返回"),
                    Map.entry(ErrorCode.REWRITE_VALIDATION_FAILED, "SQL 改写校验失败，已拒绝执行"),
                    Map.entry(ErrorCode.RESULT_METADATA_TOO_LARGE, "结果超出响应预算"),
                    Map.entry(ErrorCode.QUERY_TIMEOUT, "查询超时"),
                    Map.entry(ErrorCode.CLIENT_CANCELLED, "请求已取消"),
                    Map.entry(ErrorCode.DRIVER_ERROR, "数据库执行失败"),
                    Map.entry(ErrorCode.CLEANUP_FAILED, "执行清理失败"),
                    Map.entry(ErrorCode.CONNECTION_POOL_UNAVAILABLE, "数据源连接暂不可用"),
                    Map.entry(ErrorCode.SECURITY_STATE_UNAVAILABLE, "安全状态暂不可用，请稍后重试"),
                    Map.entry(ErrorCode.AUDIT_UNAVAILABLE, "审计存储暂不可用，请求被拒绝"),
                    Map.entry(ErrorCode.INTERNAL_ERROR, "请求处理失败"));

    private McpErrorCatalog() {}

    static String message(ErrorCode code) {
        return MESSAGES.getOrDefault(code, "请求处理失败");
    }

    static boolean retryable(ErrorCode code) {
        return RETRYABLE.contains(code);
    }
}
