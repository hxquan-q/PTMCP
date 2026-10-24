package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

/**
 * The acting admin identity for one request. Until the /session slice lands, the identity comes
 * from the test-profile filter ({@code X-Test-User-Id}, {@code X-Test-Platform-Admin}); requests
 * without that shim are rejected as unauthenticated rather than assumed anonymous.
 */
public record AdminPrincipal(long userId, boolean platformAdmin) {
    public static final String ATTRIBUTE = "ptmcp.adminPrincipal";

    public static AdminPrincipal require(HttpServletRequest request) {
        Object principal = request.getAttribute(ATTRIBUTE);
        if (principal instanceof AdminPrincipal resolved) {
            return resolved;
        }
        throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED);
    }
}
