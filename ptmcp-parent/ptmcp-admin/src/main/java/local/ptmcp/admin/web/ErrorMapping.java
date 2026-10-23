package local.ptmcp.admin.web;

import local.ptmcp.common.error.ErrorCode;

import org.springframework.http.HttpStatus;

import java.util.EnumMap;
import java.util.Map;

/**
 * Maps every registered {@link ErrorCode} to the HTTP status and retry hint defined by {@code
 * docs/backend/api-spec.md} §10.
 *
 * <p>Codes that the contract only labels as Tool rejections are projected to {@code 422} because
 * the request is understood but refused by policy; authorization-specific codes use {@code 403}.
 * Execution-only failures use {@code 500} because no dedicated REST status exists. A missing entry
 * still fails closed to {@code 500 INTERNAL_ERROR}, never {@code 200}.
 */
public final class ErrorMapping {
    private static final ErrorDescriptor FALLBACK =
            new ErrorDescriptor(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, false);

    private static final Map<ErrorCode, ErrorDescriptor> DESCRIPTORS = build();

    private ErrorMapping() {}

    public static ErrorDescriptor descriptor(ErrorCode errorCode) {
        ErrorDescriptor descriptor = errorCode == null ? null : DESCRIPTORS.get(errorCode);
        return descriptor == null ? FALLBACK : descriptor;
    }

    private static Map<ErrorCode, ErrorDescriptor> build() {
        Map<ErrorCode, ErrorDescriptor> map = new EnumMap<>(ErrorCode.class);
        // api-spec §10: 400/422 argument-shaped failures.
        put(map, ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST, false);
        put(map, ErrorCode.UNSUPPORTED_FIELD, HttpStatus.BAD_REQUEST, false);
        put(map, ErrorCode.PRECONDITION_REQUIRED, HttpStatus.BAD_REQUEST, false);
        put(map, ErrorCode.IDEMPOTENCY_KEY_REUSED, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.DIALECT_CONTEXT_INVALID, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.CONNECTION_BUDGET_EXCEEDED, HttpStatus.UNPROCESSABLE_CONTENT, false);
        // api-spec §10: authentication failures never replay the original SQL.
        put(map, ErrorCode.AUTHENTICATION_REQUIRED, HttpStatus.UNAUTHORIZED, false);
        put(map, ErrorCode.AUTHENTICATION_FAILED, HttpStatus.UNAUTHORIZED, false);
        put(map, ErrorCode.TOKEN_INVALID, HttpStatus.UNAUTHORIZED, false);
        put(map, ErrorCode.TOKEN_REVOKED, HttpStatus.UNAUTHORIZED, false);
        put(map, ErrorCode.SESSION_EXPIRED, HttpStatus.UNAUTHORIZED, false);
        put(map, ErrorCode.PLATFORM_PROOF_REQUIRED, HttpStatus.FORBIDDEN, false);
        put(map, ErrorCode.PLATFORM_MISMATCH, HttpStatus.FORBIDDEN, false);
        put(map, ErrorCode.CSRF_INVALID, HttpStatus.FORBIDDEN, false);
        put(map, ErrorCode.MANAGEMENT_SCOPE_DENIED, HttpStatus.FORBIDDEN, false);
        put(map, ErrorCode.SELF_PRIVILEGE_ESCALATION, HttpStatus.FORBIDDEN, false);
        put(map, ErrorCode.TABLE_NOT_AUTHORIZED, HttpStatus.FORBIDDEN, false);
        put(map, ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, false);
        // api-spec §10: conflict requires refreshing state before another attempt.
        put(map, ErrorCode.VERSION_CONFLICT, HttpStatus.CONFLICT, false);
        put(map, ErrorCode.CURSOR_STALE, HttpStatus.CONFLICT, false);
        put(map, ErrorCode.REFERENCES_EXIST, HttpStatus.CONFLICT, false);
        put(map, ErrorCode.SYNC_IN_PROGRESS, HttpStatus.CONFLICT, false);
        put(map, ErrorCode.OPERATION_IN_PROGRESS, HttpStatus.CONFLICT, false);
        put(map, ErrorCode.OPERATION_UNKNOWN, HttpStatus.CONFLICT, false);
        put(map, ErrorCode.TOKEN_ALREADY_CREATED, HttpStatus.CONFLICT, false);
        put(map, ErrorCode.DUPLICATE_GRANT, HttpStatus.CONFLICT, false);
        put(map, ErrorCode.METADATA_TOO_LARGE, HttpStatus.CONTENT_TOO_LARGE, false);
        // Policy rejections at the Tool boundary.
        put(map, ErrorCode.RESOURCE_AMBIGUOUS, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.SQL_SYNTAX_INVALID, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.SQL_NOT_READONLY, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.SQL_UNSUPPORTED, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.SQL_BUDGET_EXCEEDED, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.FUNCTION_NOT_ALLOWED, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.TYPE_NOT_ALLOWED, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.RESULT_TYPE_UNSUPPORTED, HttpStatus.UNPROCESSABLE_CONTENT, false);
        put(map, ErrorCode.REWRITE_VALIDATION_FAILED, HttpStatus.UNPROCESSABLE_CONTENT, false);
        // api-spec §10: retry with bounded backoff after re-validation.
        put(map, ErrorCode.SCHEMA_STALE, HttpStatus.SERVICE_UNAVAILABLE, true);
        put(map, ErrorCode.RESOURCE_VERSION_CHANGED, HttpStatus.SERVICE_UNAVAILABLE, true);
        put(map, ErrorCode.SECURITY_STATE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, true);
        put(map, ErrorCode.AUDIT_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, true);
        put(map, ErrorCode.USER_CONCURRENCY_LIMIT, HttpStatus.TOO_MANY_REQUESTS, true);
        put(map, ErrorCode.DATASOURCE_CONCURRENCY_LIMIT, HttpStatus.TOO_MANY_REQUESTS, true);
        put(map, ErrorCode.GATEWAY_BUSY, HttpStatus.TOO_MANY_REQUESTS, true);
        put(map, ErrorCode.LOGIN_LOCKED, HttpStatus.TOO_MANY_REQUESTS, true);
        // Execution failures: the outcome must be looked up by requestId, never retried blindly.
        put(map, ErrorCode.QUERY_TIMEOUT, HttpStatus.INTERNAL_SERVER_ERROR, false);
        put(map, ErrorCode.CLIENT_CANCELLED, HttpStatus.INTERNAL_SERVER_ERROR, false);
        put(map, ErrorCode.DRIVER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, false);
        put(map, ErrorCode.CLEANUP_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, false);
        put(map, ErrorCode.RESULT_METADATA_TOO_LARGE, HttpStatus.INTERNAL_SERVER_ERROR, false);
        put(map, ErrorCode.DEPENDENCY_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, true);
        put(map, ErrorCode.CONNECTION_POOL_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, true);
        put(map, ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, false);
        return Map.copyOf(map);
    }

    private static void put(
            Map<ErrorCode, ErrorDescriptor> map,
            ErrorCode code,
            HttpStatus status,
            boolean retryable) {
        ErrorDescriptor previous = map.put(code, new ErrorDescriptor(code, status, retryable));
        if (previous != null) {
            throw new IllegalStateException("duplicate descriptor for " + code);
        }
    }
}
