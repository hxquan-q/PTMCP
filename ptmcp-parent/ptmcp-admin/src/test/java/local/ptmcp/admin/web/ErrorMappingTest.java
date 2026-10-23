package local.ptmcp.admin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.Set;

class ErrorMappingTest {
    @Test
    void everyRegisteredCodeHasAnExplicitNonSuccessDescriptor() {
        for (ErrorCode code : ErrorCode.values()) {
            ErrorDescriptor descriptor = ErrorMapping.descriptor(code);
            assertEquals(code, descriptor.errorCode(), code.name());
            assertNotEquals(200, descriptor.status().value(), code.name());
        }
    }

    @Test
    void argumentFailuresUse400Or422WithoutRetry() {
        assertGroup(
                HttpStatus.BAD_REQUEST,
                false,
                ErrorCode.INVALID_ARGUMENT,
                ErrorCode.UNSUPPORTED_FIELD,
                ErrorCode.PRECONDITION_REQUIRED);
        assertGroup(
                HttpStatus.UNPROCESSABLE_CONTENT,
                false,
                ErrorCode.IDEMPOTENCY_KEY_REUSED,
                ErrorCode.DIALECT_CONTEXT_INVALID,
                ErrorCode.CONNECTION_BUDGET_EXCEEDED,
                ErrorCode.RESOURCE_AMBIGUOUS,
                ErrorCode.SQL_SYNTAX_INVALID,
                ErrorCode.SQL_NOT_READONLY,
                ErrorCode.SQL_UNSUPPORTED,
                ErrorCode.SQL_BUDGET_EXCEEDED,
                ErrorCode.FUNCTION_NOT_ALLOWED,
                ErrorCode.TYPE_NOT_ALLOWED,
                ErrorCode.RESULT_TYPE_UNSUPPORTED,
                ErrorCode.REWRITE_VALIDATION_FAILED);
    }

    @Test
    void authenticationAuthorizationAndNotFoundGroupsMatchTheContract() {
        assertGroup(
                HttpStatus.UNAUTHORIZED,
                false,
                ErrorCode.AUTHENTICATION_REQUIRED,
                ErrorCode.AUTHENTICATION_FAILED,
                ErrorCode.TOKEN_INVALID,
                ErrorCode.TOKEN_REVOKED,
                ErrorCode.SESSION_EXPIRED);
        assertGroup(
                HttpStatus.FORBIDDEN,
                false,
                ErrorCode.PLATFORM_PROOF_REQUIRED,
                ErrorCode.PLATFORM_MISMATCH,
                ErrorCode.CSRF_INVALID,
                ErrorCode.MANAGEMENT_SCOPE_DENIED,
                ErrorCode.SELF_PRIVILEGE_ESCALATION,
                ErrorCode.TABLE_NOT_AUTHORIZED);
        assertGroup(HttpStatus.NOT_FOUND, false, ErrorCode.RESOURCE_NOT_FOUND);
        assertGroup(
                HttpStatus.CONFLICT,
                false,
                ErrorCode.VERSION_CONFLICT,
                ErrorCode.CURSOR_STALE,
                ErrorCode.REFERENCES_EXIST,
                ErrorCode.SYNC_IN_PROGRESS,
                ErrorCode.OPERATION_IN_PROGRESS,
                ErrorCode.OPERATION_UNKNOWN,
                ErrorCode.TOKEN_ALREADY_CREATED,
                ErrorCode.DUPLICATE_GRANT);
        assertGroup(HttpStatus.CONTENT_TOO_LARGE, false, ErrorCode.METADATA_TOO_LARGE);
    }

    @Test
    void overloadAndAvailabilityGroupsAreRetryable() {
        assertGroup(
                HttpStatus.TOO_MANY_REQUESTS,
                true,
                ErrorCode.USER_CONCURRENCY_LIMIT,
                ErrorCode.DATASOURCE_CONCURRENCY_LIMIT,
                ErrorCode.GATEWAY_BUSY,
                ErrorCode.LOGIN_LOCKED);
        assertGroup(
                HttpStatus.SERVICE_UNAVAILABLE,
                true,
                ErrorCode.DEPENDENCY_UNAVAILABLE,
                ErrorCode.SCHEMA_STALE,
                ErrorCode.RESOURCE_VERSION_CHANGED,
                ErrorCode.SECURITY_STATE_UNAVAILABLE,
                ErrorCode.AUDIT_UNAVAILABLE,
                ErrorCode.CONNECTION_POOL_UNAVAILABLE);
    }

    @Test
    void executionFailuresHideAsInternalErrorsWithoutRetry() {
        assertGroup(
                HttpStatus.INTERNAL_SERVER_ERROR,
                false,
                ErrorCode.INTERNAL_ERROR,
                ErrorCode.QUERY_TIMEOUT,
                ErrorCode.CLIENT_CANCELLED,
                ErrorCode.DRIVER_ERROR,
                ErrorCode.CLEANUP_FAILED,
                ErrorCode.RESULT_METADATA_TOO_LARGE);
    }

    @Test
    void missingCodeFailsClosedToInternalError() {
        ErrorDescriptor missing = ErrorMapping.descriptor(null);

        assertEquals(ErrorCode.INTERNAL_ERROR, missing.errorCode());
        assertEquals(500, missing.status().value());
        assertFalse(missing.retryable());
    }

    private static void assertGroup(
            HttpStatus expectedStatus, boolean expectedRetryable, ErrorCode... codes) {
        Set<ErrorCode> group = Set.of(codes);
        assertEquals(codes.length, group.size(), "test group contains duplicate codes");
        for (ErrorCode code : group) {
            ErrorDescriptor descriptor = ErrorMapping.descriptor(code);
            assertEquals(code, descriptor.errorCode(), code.name());
            assertEquals(expectedStatus, descriptor.status(), code.name());
            assertEquals(expectedRetryable, descriptor.retryable(), code.name());
            assertTrue(
                    descriptor.status().isError(), code.name() + " must not be a success status");
        }
    }
}
