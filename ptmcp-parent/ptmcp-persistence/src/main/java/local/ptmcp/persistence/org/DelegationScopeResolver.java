package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Delegation-scope resolution: which {@code admin_delegation} rows of one user are effective at an
 * instant, and whether a concrete management target sits inside one. Pure decision core for the T16
 * rollout — controllers adopt it slice by slice; nothing here changes platform-admin behavior. A
 * delegation is effective when its stored status is ACTIVE and {@code validFrom <= now < validTo};
 * the derived EXPIRED projection is a read model and never grants anything. Department containment
 * validates the complete authoritative {@code sys_dept.parent_id} chain in one snapshot; action
 * containment is a subset check; resource containment is an exact type+id+generation match
 * (DATASOURCE generation is pinned to 1 by convention, mirroring acl_grant).
 */
public final class DelegationScopeResolver {
    private static final int MAX_DEPARTMENT_NODES = 64;
    private static final String DEPARTMENT_PATH_SQL =
            """
            WITH RECURSIVE department_path AS (
                SELECT dept_id, parent_id, del_flag, 1 AS hop
                FROM sys_dept WHERE dept_id = ?
                UNION ALL
                SELECT d.dept_id, d.parent_id, d.del_flag, p.hop + 1
                FROM department_path p
                JOIN sys_dept d ON d.dept_id = p.parent_id
                WHERE p.hop < ? AND p.parent_id <> 0
            )
            SELECT dept_id, parent_id, del_flag FROM department_path ORDER BY hop
            """;
    private final DataSource primary;

    public DelegationScopeResolver(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record EffectiveDelegation(
            long id,
            long departmentRootId,
            String resourceType,
            long resourceId,
            long resourceGeneration,
            Set<String> allowedActions,
            Instant validFrom,
            Instant validTo) {}

    public enum TargetKind {
        DEPARTMENT,
        RESOURCE,
        SUBJECT
    }

    public record Target(TargetKind kind, String resourceType, long resourceId, Long departmentId) {

        public static Target department(long departmentId) {
            return new Target(TargetKind.DEPARTMENT, null, 0, departmentId);
        }

        public static Target resource(String resourceType, long resourceId) {
            return new Target(TargetKind.RESOURCE, resourceType, resourceId, null);
        }
    }

    /** Effective (ACTIVE, in-window) delegations of one user, newest first. */
    public List<EffectiveDelegation> effectiveDelegations(long userId, Instant now) {
        if (userId <= 0 || now == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String sql =
                "SELECT id, department_root_id, resource_type, resource_id, resource_generation,"
                        + " allowed_actions, valid_from, valid_to FROM admin_delegation"
                        + " WHERE user_id = ? AND status = 'ACTIVE'"
                        + " AND valid_from <= UTC_TIMESTAMP(6)"
                        + " AND (valid_to IS NULL OR valid_to > UTC_TIMESTAMP(6))"
                        + " ORDER BY id DESC";
        List<EffectiveDelegation> rows = new ArrayList<>();
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement = OrgSql.statement(connection, sql)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(
                            new EffectiveDelegation(
                                    result.getLong(1),
                                    result.getLong(2),
                                    result.getString(3),
                                    result.getLong(4),
                                    result.getLong(5),
                                    Set.copyOf(
                                            DelegationStore.readActionsPublic(result.getString(6))),
                                    OrgSql.toInstant(
                                            result.getObject(7, java.time.LocalDateTime.class)),
                                    OrgSql.toInstant(
                                            result.getObject(8, java.time.LocalDateTime.class))));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return List.copyOf(rows);
    }

    /**
     * True when at least one effective delegation covers the target: the target's department chain
     * reaches the delegation root (for department and subject targets), and the requested action
     * plus resource match for resource targets. {@code action} may be null for pure scope checks.
     */
    public boolean covers(
            EffectiveDelegation delegation, Target target, String action, Instant now) {
        Objects.requireNonNull(delegation, "delegation");
        if (target == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!inWindow(delegation, now)) {
            return false;
        }
        if (action != null && !delegation.allowedActions().contains(action)) {
            return false;
        }
        return switch (target.kind()) {
            case DEPARTMENT ->
                    departmentWithin(delegation.departmentRootId(), target.departmentId());
            case SUBJECT -> coversSubject(delegation, target);
            case RESOURCE ->
                    resourceMatches(delegation, target.resourceType(), target.resourceId());
        };
    }

    /** A subject sits in scope when their own department (or an ancestor of it) is the root. */
    private boolean coversSubject(EffectiveDelegation delegation, Target target) {
        if (target.departmentId() == null) {
            return false;
        }
        return departmentWithin(delegation.departmentRootId(), target.departmentId());
    }

    /**
     * Validates the entire parent chain, even when the target equals the delegation root. Disabled
     * departments remain manageable; only deleted targets or roots lose scope membership.
     */
    private boolean departmentWithin(long rootId, Long departmentId) {
        if (departmentId == null || departmentId <= 0) {
            return false;
        }
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement = OrgSql.statement(connection, DEPARTMENT_PATH_SQL)) {
            statement.setLong(1, departmentId);
            statement.setInt(2, MAX_DEPARTMENT_NODES);
            try (ResultSet result = statement.executeQuery()) {
                Set<Long> seen = new HashSet<>();
                long expected = departmentId;
                boolean targetNotDeleted = false;
                boolean rootMatchedNotDeleted = false;
                while (result.next()) {
                    boolean first = seen.isEmpty();
                    Long dept = result.getObject(1, Long.class);
                    Long parent = result.getObject(2, Long.class);
                    if (dept == null
                            || dept <= 0
                            || dept != expected
                            || parent == null
                            || parent < 0
                            || !seen.add(dept)
                            || seen.size() > MAX_DEPARTMENT_NODES) {
                        throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                    }
                    boolean notDeleted = "0".equals(result.getString(3));
                    if (first) {
                        targetNotDeleted = notDeleted;
                    }
                    if (dept == rootId && notDeleted) {
                        rootMatchedNotDeleted = true;
                    }
                    expected = parent;
                }
                if (!seen.isEmpty() && expected != 0) {
                    // Missing parents and depth truncation cannot yield a partial scope.
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                }
                return targetNotDeleted && rootMatchedNotDeleted;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static boolean resourceMatches(EffectiveDelegation delegation, String type, long id) {
        return delegation.resourceType().equals(type) && delegation.resourceId() == id;
    }

    private static boolean inWindow(EffectiveDelegation delegation, Instant now) {
        boolean started = !delegation.validFrom().isAfter(now);
        boolean notEnded = delegation.validTo() == null || delegation.validTo().isAfter(now);
        return started && notEnded;
    }

    /** Distinct actions across all effective delegations; helper for capability intersection. */
    public Set<String> effectiveActions(long userId, Instant now) {
        Set<String> actions = new LinkedHashSet<>();
        for (EffectiveDelegation delegation : effectiveDelegations(userId, now)) {
            actions.addAll(delegation.allowedActions());
        }
        return Set.copyOf(actions);
    }
}
