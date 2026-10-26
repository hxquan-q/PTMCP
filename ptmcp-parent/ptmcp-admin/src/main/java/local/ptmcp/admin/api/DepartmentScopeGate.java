package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.DelegationScopeResolver;
import local.ptmcp.persistence.org.OrgStore;

import java.time.Instant;
import java.util.List;

/**
 * Delegation-aware gate for the departments console. Platform admins keep full access; everyone
 * else must hold an effective delegation whose department subtree covers the target and whose
 * action set contains the requested capability code — the PRD's "capability code AND valid
 * delegation" rule. Failures surface as MANAGEMENT_SCOPE_DENIED without revealing which check
 * failed. The delegated admin sees only their subtree; a caller with neither platform scope nor a
 * delegation is denied outright, never shown an empty tree.
 */
public final class DepartmentScopeGate {
    private final DelegationScopeResolver resolver;

    public DepartmentScopeGate(DelegationScopeResolver resolver) {
        this.resolver = resolver;
    }

    /** Platform admins see the whole tree; delegated admins only their subtree rows. */
    public List<OrgStore.DepartmentRow> visibleDepartments(
            HttpServletRequest request, List<OrgStore.DepartmentRow> all) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin()) {
            return all;
        }
        List<DelegationScopeResolver.EffectiveDelegation> delegations = effective(principal);
        return all.stream()
                .filter(
                        row ->
                                delegations.stream()
                                        .anyMatch(
                                                delegation ->
                                                        resolver.covers(
                                                                delegation,
                                                                DelegationScopeResolver.Target
                                                                        .department(row.id()),
                                                                null,
                                                                Instant.now())))
                .toList();
    }

    /** Read check: the department itself must sit inside a delegation subtree. */
    public void requireDepartmentAccess(HttpServletRequest request, long departmentId) {
        requireCovered(request, DelegationScopeResolver.Target.department(departmentId), null);
    }

    /** Write check: subtree coverage plus the exact capability code (ptmcp:department:update). */
    public void requireDepartmentAction(
            HttpServletRequest request, long departmentId, String capability) {
        requireCovered(
                request, DelegationScopeResolver.Target.department(departmentId), capability);
    }

    /** Create check: the parent must sit inside a delegation subtree (roots stay platform-only). */
    public void requireParentForCreate(
            HttpServletRequest request, Long parentId, String capability) {
        if (AdminPrincipal.require(request).platformAdmin()) {
            return;
        }
        if (parentId == null) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        requireCovered(request, DelegationScopeResolver.Target.department(parentId), capability);
    }

    private void requireCovered(
            HttpServletRequest request, DelegationScopeResolver.Target target, String action) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin()) {
            return;
        }
        boolean covered =
                effective(principal).stream()
                        .anyMatch(
                                delegation ->
                                        resolver.covers(delegation, target, action, Instant.now()));
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
