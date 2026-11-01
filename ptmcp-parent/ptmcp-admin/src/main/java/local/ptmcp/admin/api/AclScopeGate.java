package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.DelegationScopeResolver;
import local.ptmcp.persistence.org.OrgStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

/**
 * Delegation-aware gate for the ACL console (PRD AC-02/AC-07). Platform admins keep full access. A
 * delegated admin manages grants only when every dimension fits the delegation: the subject's
 * department sits inside the delegation subtree, the grant targets exactly the delegated resource
 * (type + id), the granted action is one the delegation carries, and the grant window stays inside
 * the delegation window — a grant may never outlive or predate the delegation that empowers it.
 * Failures surface as MANAGEMENT_SCOPE_DENIED without revealing which dimension failed.
 */
public final class AclScopeGate {
    private final DelegationScopeResolver resolver;
    private final OrgStore orgStore;

    public AclScopeGate(DelegationScopeResolver resolver, OrgStore orgStore) {
        this.resolver = resolver;
        this.orgStore = orgStore;
    }

    public enum GrantAction {
        LIST,
        CREATE,
        UPDATE,
        REVOKE
    }

    /**
     * Grants visible to the caller: platform admins see everything; delegated admins only grants
     * whose subject department sits inside their subtree.
     */
    public List<AclGrantStoreGrant> visibleGrants(
            HttpServletRequest request, List<AclGrantStoreGrant> all) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin()) {
            return all;
        }
        Instant now = Instant.now();
        List<DelegationScopeResolver.EffectiveDelegation> delegations = effective(principal);
        return all.stream()
                .filter(
                        grant -> {
                            Long deptId =
                                    subjectDepartmentOrNull(grant.subjectType(), grant.subjectId());
                            if (deptId == null) {
                                return false;
                            }
                            return delegations.stream()
                                    .anyMatch(
                                            delegation ->
                                                    delegation.resourceGeneration()
                                                                    == grant.resourceGeneration()
                                                            && resolver.covers(
                                                                    delegation,
                                                                    DelegationScopeResolver.Target
                                                                            .department(deptId),
                                                                    null,
                                                                    now)
                                                            && resolver.covers(
                                                                    delegation,
                                                                    DelegationScopeResolver.Target
                                                                            .resource(
                                                                                    grant
                                                                                            .resourceType(),
                                                                                    grant
                                                                                            .resourceId()),
                                                                    null,
                                                                    now));
                        })
                .toList();
    }

    /** One complete delegation must cover both sides of an update under the write fence. */
    public void requireMutationAllowed(
            Connection connection,
            long actorId,
            local.ptmcp.persistence.org.AclGrantStore.AclGrantRecord before,
            local.ptmcp.persistence.org.AclGrantStore.AclGrantRecord after)
            throws SQLException {
        Instant now = Instant.now();
        for (DelegationScopeResolver.EffectiveDelegation delegation :
                resolver.effectiveDelegations(connection, actorId, now)) {
            if ((before == null || coversRecord(connection, delegation, before, null, now))
                    && (after == null
                            || (coversRecord(
                                            connection,
                                            delegation,
                                            after,
                                            "ptmcp:acl:"
                                                    + after.action()
                                                            .toLowerCase(java.util.Locale.ROOT),
                                            now)
                                    && windowCovers(
                                            delegation, after.validFrom(), after.validTo())))) {
                return;
            }
        }
        throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
    }

    private boolean coversRecord(
            Connection connection,
            DelegationScopeResolver.EffectiveDelegation delegation,
            local.ptmcp.persistence.org.AclGrantStore.AclGrantRecord row,
            String action,
            Instant now)
            throws SQLException {
        Long department = subjectDepartment(connection, row.subjectType(), row.subjectId());
        return department != null
                && row.resourceGeneration() == delegation.resourceGeneration()
                && resolver.covers(
                        connection,
                        delegation,
                        DelegationScopeResolver.Target.department(department),
                        action,
                        now)
                && resolver.covers(
                        connection,
                        delegation,
                        DelegationScopeResolver.Target.resource(
                                row.resourceType(), row.resourceId()),
                        null,
                        now);
    }

    private static Long subjectDepartment(Connection connection, String type, long id)
            throws SQLException {
        if ("DEPT".equals(type)) return id;
        if (!"USER".equals(type)) return null;
        try (PreparedStatement query =
                connection.prepareStatement(
                        "SELECT dept_id FROM sys_user_profile WHERE user_id=?")) {
            query.setQueryTimeout(5);
            query.setLong(1, id);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getObject(1, Long.class) : null;
            }
        }
    }

    /**
     * Write check for one grant draft (create and update share this; update additionally proves
     * ownership of the existing row through the same subject/resource dimensions).
     */
    public void requireGrantAllowed(
            HttpServletRequest request,
            String subjectType,
            long subjectId,
            String resourceType,
            long resourceId,
            String grantedAction,
            Instant grantFrom,
            Instant grantTo) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin()) {
            return;
        }
        List<DelegationScopeResolver.EffectiveDelegation> delegations = effective(principal);

        Long subjectDept = subjectDepartmentOrNull(subjectType, subjectId);
        if (subjectDept == null) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        DelegationScopeResolver.Target subjectTarget =
                DelegationScopeResolver.Target.department(subjectDept);
        DelegationScopeResolver.Target resourceTarget =
                DelegationScopeResolver.Target.resource(resourceType, resourceId);
        boolean covered =
                delegations.stream()
                        .anyMatch(
                                delegation ->
                                        resolver.covers(
                                                        delegation,
                                                        subjectTarget,
                                                        grantedAction,
                                                        Instant.now())
                                                && resolver.covers(
                                                        delegation,
                                                        resourceTarget,
                                                        null,
                                                        Instant.now())
                                                && windowCovers(delegation, grantFrom, grantTo));
        if (!covered) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
    }

    /** Revoke check: the existing grant must be inside the delegation (subject + resource). */
    public void requireRevokeAllowed(
            HttpServletRequest request,
            String subjectType,
            long subjectId,
            String resourceType,
            long resourceId) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin()) {
            return;
        }
        List<DelegationScopeResolver.EffectiveDelegation> delegations = effective(principal);
        Long subjectDept = subjectDepartmentOrNull(subjectType, subjectId);
        if (subjectDept == null) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        boolean covered =
                delegations.stream()
                        .anyMatch(
                                delegation ->
                                        resolver.covers(
                                                        delegation,
                                                        DelegationScopeResolver.Target.department(
                                                                subjectDept),
                                                        null,
                                                        Instant.now())
                                                && resolver.covers(
                                                        delegation,
                                                        DelegationScopeResolver.Target.resource(
                                                                resourceType, resourceId),
                                                        null,
                                                        Instant.now()));
        if (!covered) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
    }

    /** AC-07: the grant window must sit inside the delegation window (left-closed comparisons). */
    private static boolean windowCovers(
            DelegationScopeResolver.EffectiveDelegation delegation, Instant from, Instant to) {
        if (from == null || from.isBefore(delegation.validFrom())) {
            return false;
        }
        if (to == null) {
            // An open-ended grant would outlive the delegation.
            return delegation.validTo() == null;
        }
        return delegation.validTo() == null || !to.isAfter(delegation.validTo());
    }

    private Long subjectDepartmentOrNull(String subjectType, long subjectId) {
        try {
            return switch (subjectType) {
                case "USER" -> orgStore.getUser(subjectId).deptId();
                case "DEPT" -> subjectId;
                default -> null; // ROLE subjects are shared aggregates, never delegation-scoped.
            };
        } catch (DomainException error) {
            return null;
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

    /** Structural alias so the filter above does not depend on the store's row type directly. */
    public interface AclGrantStoreGrant {
        String subjectType();

        long subjectId();

        String resourceType();

        long resourceId();

        long resourceGeneration();
    }
}
