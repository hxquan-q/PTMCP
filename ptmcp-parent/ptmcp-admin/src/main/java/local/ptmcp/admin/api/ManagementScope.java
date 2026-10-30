package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.authz.SubjectScope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.AclDirectory;
import local.ptmcp.persistence.org.DelegationStore;

/**
 * Management scope gate. Delegation ({@code admin_delegation}) is not implemented yet, so only
 * platform admins may exercise the admin surface; every other caller gets MANAGEMENT_SCOPE_DENIED
 * instead of a narrower decision.
 */
public final class ManagementScope {
    private ManagementScope() {}

    public static void requirePlatformAdmin(HttpServletRequest request) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (!principal.platformAdmin()) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
    }

    public static AdminPrincipal requirePlatformAdminPrincipal(HttpServletRequest request) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (!principal.platformAdmin()) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        return principal;
    }

    /**
     * Credentials and datasource changes require both facts: a platform admin that also holds the
     * action's capability code (api-spec section 2). Platform admins are not exempt from the code.
     */
    public static AdminPrincipal requirePlatformAdminPrincipal(
            HttpServletRequest request, DelegationStore delegations, String permissionCode) {
        AdminPrincipal principal = requirePlatformAdminPrincipal(request);
        if (!delegations.permissionsOf(principal.userId()).contains(permissionCode)) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        return principal;
    }

    public static SubjectScope callerScopeOrNull(AclDirectory directory, AdminPrincipal principal) {
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
