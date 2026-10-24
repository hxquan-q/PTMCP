package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.authz.SubjectScope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.AclDirectory;

/**
 * Management scope gate. Delegation ({@code admin_delegation}) is not implemented yet, so only
 * platform admins may exercise the admin surface; every other caller gets MANAGEMENT_SCOPE_DENIED
 * instead of a narrower decision.
 */
final class ManagementScope {
    private ManagementScope() {}

    static void requirePlatformAdmin(HttpServletRequest request) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (!principal.platformAdmin()) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
    }

    static AdminPrincipal requirePlatformAdminPrincipal(HttpServletRequest request) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (!principal.platformAdmin()) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        return principal;
    }

    static SubjectScope callerScopeOrNull(AclDirectory directory, AdminPrincipal principal) {
        try {
            return directory.subjectScope(principal.userId());
        } catch (DomainException error) {
            if (error.errorCode() == ErrorCode.RESOURCE_NOT_FOUND) {
                return null;
            }
            throw error;
        }
    }
}
