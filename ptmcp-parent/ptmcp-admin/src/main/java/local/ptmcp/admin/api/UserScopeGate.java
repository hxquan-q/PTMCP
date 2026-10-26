package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.DelegationScopeResolver;
import local.ptmcp.persistence.org.OrgStore;

import java.time.Instant;
import java.util.List;

/**
 * Delegation-aware gate for the users console, mirroring {@link DepartmentScopeGate}: platform
 * admins keep full access; a delegated admin manages only users whose department sits inside the
 * delegation subtree, and only with capability codes the delegation actually carries (PRD:
 * capability code AND valid delegation). A caller with neither scope nor delegation is denied
 * outright.
 */
public final class UserScopeGate {
    private final DelegationScopeResolver resolver;
    private final OrgStore orgStore;

    public UserScopeGate(DelegationScopeResolver resolver, OrgStore orgStore) {
        this.resolver = resolver;
        this.orgStore = orgStore;
    }

    /**
     * PRD cross-scope role guard (AC-02): a delegated admin may only assign roles whose permission
     * codes stay inside the delegation's action set. A role carrying management codes beyond the
     * delegation would silently escalate the subject past the delegated boundary. Platform admins
     * are unrestricted.
     */
    public void requireRoleAssignment(
            HttpServletRequest request, long targetDeptId, java.util.List<Long> roleIds) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin() || roleIds == null || roleIds.isEmpty()) {
            return;
        }
        List<DelegationScopeResolver.EffectiveDelegation> delegations = effective(principal);
        java.util.Set<String> delegatedActions =
                resolver.effectiveActions(principal.userId(), java.time.Instant.now());
        for (long roleId : roleIds) {
            List<String> codes;
            try {
                codes = orgStore.getRole(roleId).permissionCodes();
            } catch (DomainException error) {
                throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            boolean withinDelegation =
                    codes.stream()
                            .filter(code -> code.startsWith("ptmcp:"))
                            .allMatch(delegatedActions::contains);
            boolean subjectCovered =
                    delegations.stream()
                            .anyMatch(
                                    delegation ->
                                            resolver.covers(
                                                    delegation,
                                                    DelegationScopeResolver.Target.department(
                                                            targetDeptId),
                                                    null,
                                                    java.time.Instant.now()));
            if (!withinDelegation || !subjectCovered) {
                throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
            }
        }
    }

    /** Platform admins see all users; delegated admins only users inside their subtree. */
    public List<OrgStore.UserRow> visibleUsers(
            HttpServletRequest request, List<OrgStore.UserRow> all) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin()) {
            return all;
        }
        List<DelegationScopeResolver.EffectiveDelegation> delegations = effective(principal);
        return all.stream()
                .filter(
                        user ->
                                delegations.stream()
                                        .anyMatch(
                                                delegation ->
                                                        resolver.covers(
                                                                delegation,
                                                                DelegationScopeResolver.Target
                                                                        .department(user.deptId()),
                                                                null,
                                                                Instant.now())))
                .toList();
    }

    /** Read: the target user's department must sit inside the delegation subtree. */
    public void requireUserAccess(HttpServletRequest request, long targetDeptId) {
        requireCovered(request, targetDeptId, null);
    }

    /** Write: subtree coverage plus the exact capability code (e.g. ptmcp:user:update). */
    public void requireUserAction(
            HttpServletRequest request, long targetDeptId, String capability) {
        requireCovered(request, targetDeptId, capability);
    }

    private void requireCovered(HttpServletRequest request, long targetDeptId, String action) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin()) {
            return;
        }
        boolean covered =
                effective(principal).stream()
                        .anyMatch(
                                delegation ->
                                        resolver.covers(
                                                delegation,
                                                DelegationScopeResolver.Target.department(
                                                        targetDeptId),
                                                action,
                                                Instant.now()));
        if (!covered) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
    }

    private List<DelegationScopeResolver.EffectiveDelegation> effective(AdminPrincipal principal) {
        List<DelegationScopeResolver.EffectiveDelegation> delegations =
                resolver.effectiveDelegations(principal.userId(), Instant.now());
        if (delegations.isEmpty()) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        return delegations;
    }
}
