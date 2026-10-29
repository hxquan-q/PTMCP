package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.DelegationScopeResolver;
import local.ptmcp.persistence.org.DelegationStore;
import local.ptmcp.persistence.org.OrgStore;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Delegation-aware gate for the users console, mirroring {@link DepartmentScopeGate}: platform
 * admins keep full management scope; a delegated admin manages only users whose department sits inside the
 * delegation subtree, and only with capability codes the delegation actually carries (PRD:
 * capability code AND valid delegation). A caller with neither scope nor delegation is denied
 * outright.
 */
public final class UserScopeGate {
    private final DelegationScopeResolver resolver;
    private final OrgStore orgStore;
    private final DelegationStore delegationStore;

    public UserScopeGate(
            DelegationScopeResolver resolver, OrgStore orgStore, DelegationStore delegationStore) {
        this.resolver = resolver;
        this.orgStore = orgStore;
        this.delegationStore = delegationStore;
    }

    /** A complete user edit must fit one delegation, including every changed restricted field. */
    public void requireUserUpdate(
            HttpServletRequest request,
            OrgStore.UserRow before,
            long targetDeptId,
            boolean enabled,
            List<Long> roleIds) {
        Set<Long> requestedRoles = roleIds == null ? null : checkedRoleIds(roleIds);
        boolean rolesChanged =
                requestedRoles != null && !requestedRoles.equals(Set.copyOf(before.roleIds()));
        Set<String> actions = new HashSet<>();
        actions.add("ptmcp:user:update");
        if (before.deptId() != targetDeptId) {
            actions.add("ptmcp:user:move");
        }
        if (before.enabled() != enabled) {
            actions.add("ptmcp:user:status");
        }
        if (rolesChanged) {
            actions.add("ptmcp:user:assign-role");
        }
        requireCommand(
                request,
                before.deptId(),
                targetDeptId,
                actions,
                rolesChanged ? requestedRoles : null);
    }

    public void requireUserStatus(HttpServletRequest request, OrgStore.UserRow before) {
        requireCommand(
                request, before.deptId(), before.deptId(), Set.of("ptmcp:user:status"), null);
    }

    public void requireRoleAssignment(
            HttpServletRequest request, OrgStore.UserRow before, List<Long> roleIds) {
        if (roleIds == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        requireCommand(
                request,
                before.deptId(),
                before.deptId(),
                Set.of("ptmcp:user:assign-role"),
                checkedRoleIds(roleIds));
    }

    public void requireUserMove(
            HttpServletRequest request, OrgStore.UserRow before, long targetDeptId) {
        requireCommand(request, before.deptId(), targetDeptId, Set.of("ptmcp:user:move"), null);
    }

    private void requireCommand(
            HttpServletRequest request,
            long sourceDeptId,
            long targetDeptId,
            Set<String> actions,
            Set<Long> requestedRoles) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (!delegationStore.permissionsOf(principal.userId()).containsAll(actions)) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        if (principal.platformAdmin()) {
            return;
        }
        Instant now = Instant.now();
        List<DelegationScopeResolver.EffectiveDelegation> candidates =
                resolver.effectiveDelegations(principal.userId(), now).stream()
                        .filter(delegation -> delegation.allowedActions().containsAll(actions))
                        .filter(
                                delegation ->
                                        resolver.covers(
                                                delegation,
                                                DelegationScopeResolver.Target.department(
                                                        sourceDeptId),
                                                null,
                                                now))
                        .filter(
                                delegation ->
                                        resolver.covers(
                                                delegation,
                                                DelegationScopeResolver.Target.department(
                                                        targetDeptId),
                                                null,
                                                now))
                        .toList();
        if (candidates.isEmpty()) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        if (requestedRoles != null) {
            Set<String> assignedCodes = new HashSet<>();
            for (long roleId : requestedRoles) {
                assignedCodes.addAll(orgStore.getRole(roleId).permissionCodes());
            }
            if (candidates.stream()
                    .noneMatch(
                            delegation -> delegation.allowedActions().containsAll(assignedCodes))) {
                throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
            }
        }
    }

    private static Set<Long> checkedRoleIds(List<Long> roleIds) {
        Set<Long> unique = new HashSet<>();
        for (Long id : roleIds) {
            if (id == null || id <= 0 || !unique.add(id)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        return unique;
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
