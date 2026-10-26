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

    public UserScopeGate(DelegationScopeResolver resolver) {
        this.resolver = resolver;
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
