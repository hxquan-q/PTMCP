package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.authz.SubjectScope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.AclDirectory;
import local.ptmcp.persistence.org.DelegationStore;
import local.ptmcp.persistence.org.OrgStore;

import java.time.Instant;

/**
 * Management scope gate. Platform admins exercise the whole admin surface. Other callers must hold
 * a valid {@code admin_delegation} whose resource scope, action list, validity window and
 * department subtree all cover the exact operation they attempt; anything narrower — expired,
 * revoked, out of scope, wrong action, cross-subtree subject — is MANAGEMENT_SCOPE_DENIED without
 * disclosing which dimension failed.
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
     * Gate for one ACL grant operation. Platform admins pass as themselves. A delegated admin
     * passes only when a valid delegation covers the action over the exact resource at now and the
     * target subject's department sits inside the delegated subtree; ROLE subjects span departments
     * and stay platform-only. The returned principal drives the self-escalation guard, which
     * applies to delegated admins exactly like platform admins.
     */
    public static AdminPrincipal requireGrantOperator(
            HttpServletRequest request,
            DelegationStore delegations,
            OrgStore orgStore,
            String action,
            String resourceType,
            long resourceId,
            Long targetSubjectDeptId) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin()) {
            return principal;
        }
        DelegationStore.DelegationRecord delegation =
                delegations.findValid(principal.userId(), resourceType, resourceId, Instant.now());
        if (delegation == null || !delegation.coversAction(action)) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        if (targetSubjectDeptId != null
                && !withinSubtree(orgStore, targetSubjectDeptId, delegation.departmentRootId())) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        return principal;
    }

    /** Walks the parent chain with a loop bound; a missing department is out of every subtree. */
    private static boolean withinSubtree(OrgStore orgStore, long deptId, long subtreeRootId) {
        long current = deptId;
        for (int hops = 0; hops < 64; hops++) {
            if (current == subtreeRootId) {
                return true;
            }
            OrgStore.DepartmentRow department;
            try {
                department = orgStore.getDepartment(current);
            } catch (DomainException error) {
                return false;
            }
            if (department == null || department.parentId() == null || department.parentId() <= 0) {
                return false;
            }
            current = department.parentId();
        }
        return false;
    }

    /** Resolves the department a grant subject belongs to; null means platform-admin-only. */
    public static Long subjectDepartment(OrgStore orgStore, String subjectType, long subjectId) {
        return switch (subjectType) {
            case "USER" -> orgStore.getUser(subjectId).deptId();
            case "DEPT" -> subjectId;
            default -> null;
        };
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
