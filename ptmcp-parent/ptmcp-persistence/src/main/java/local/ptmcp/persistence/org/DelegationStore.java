package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * admin_delegation records: one delegated administrator, one department root, one resource scope,
 * an explicit action subset and a hard validity window. Creation resolves the resource's current
 * active generation and rejects self-delegation, unknown subjects/resources, empty or non-delegable
 * actions and overlapping duplicates. Every mutation appends one {@code admin_operation_log} row
 * inside the same transaction, so a delegation change is never silent. Scope enforcement for
 * delegated (non-platform) administrators is a later slice; this store only owns the records.
 */
public final class DelegationStore {
    private static final Pattern ACTION_CODE =
            Pattern.compile("^ptmcp:[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$");
    private static final Set<String> RESOURCE_TYPES = Set.of("DATASOURCE", "NAMESPACE", "OBJECT");
    private static final int MAX_PAGE = 100;
    private static final int DEFAULT_PAGE = 20;

    private final DataSource primary;

    public DelegationStore(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record DelegationRow(
            long id,
            long userId,
            long departmentRootId,
            String resourceType,
            long resourceId,
            long resourceGeneration,
            List<String> allowedActions,
            Instant validFrom,
            Instant validTo,
            String status,
            long version) {}

    public record DelegationDraft(
            long userId,
            long departmentRootId,
            String resourceType,
            long resourceId,
            List<String> allowedActions,
            Instant validFrom,
            Instant validTo) {}

    /** Derived status: ACTIVE rows past their window read as EXPIRED without a write. */
    public static String displayStatus(DelegationRow row, Instant now) {
        if ("REVOKED".equals(row.status())) {
            return "REVOKED";
        }
        if (row.validTo() != null && !row.validTo().isAfter(now)) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    public List<DelegationRow> list(
            Long userId, String status, String resourceType, String cursor, Integer pageSize) {
        if (status != null && !Set.of("ACTIVE", "EXPIRED", "REVOKED").contains(status)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (resourceType != null && !RESOURCE_TYPES.contains(resourceType)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        int limit = pageSize == null ? DEFAULT_PAGE : pageSize;
        if (limit < 1 || limit > MAX_PAGE) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Long cursorId = parseCursor(cursor);
        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, user_id, department_root_id, resource_type, resource_id,"
                                + " resource_generation, allowed_actions, valid_from, valid_to, status,"
                                + " version FROM admin_delegation");
        List<Object> parameters = new ArrayList<>();
        sql.append(" WHERE status IN ('ACTIVE', 'REVOKED')");
        if (userId != null) {
            sql.append(" AND user_id = ?");
            parameters.add(userId);
        }
        if (resourceType != null) {
            sql.append(" AND resource_type = ?");
            parameters.add(resourceType);
        }
        // EXPIRED is derived: an ACTIVE row whose window already closed.
        sql.append(
                status == null
                        ? ""
                        : switch (status) {
                            case "REVOKED" -> " AND status = 'REVOKED'";
                            case "EXPIRED" ->
                                    " AND status = 'ACTIVE' AND valid_to IS NOT NULL AND valid_to <= UTC_TIMESTAMP(6)";
                            default ->
                                    " AND status = 'ACTIVE' AND (valid_to IS NULL OR valid_to > UTC_TIMESTAMP(6))";
                        });
        if (cursorId != null) {
            sql.append(" AND id < ?");
            parameters.add(cursorId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        parameters.add(limit + 1);
        List<DelegationRow> rows = new ArrayList<>();
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement = OrgSql.statement(connection, sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(row(result));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return rows;
    }

    public DelegationRow get(long id) {
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT id, user_id, department_root_id, resource_type, resource_id,"
                                        + " resource_generation, allowed_actions, valid_from, valid_to, status,"
                                        + " version FROM admin_delegation WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return row(result);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Creates one delegation plus its admin_operation_log entry in a single transaction. The caller
     * passes the actions it is allowed to delegate; the stored subset must stay inside it (AC-07: a
     * delegated admin can never hold actions beyond the delegation).
     */
    public DelegationRow create(
            long actorId, String requestId, DelegationDraft draft, Set<String> delegatableActions) {
        OrgSql.requireActor(actorId);
        if (requestId == null || requestId.isBlank() || requestId.length() > 64) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        requireDraftShape(draft);
        if (draft.userId() == actorId) {
            throw new DomainException(ErrorCode.SELF_PRIVILEGE_ESCALATION);
        }
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        for (String action : draft.allowedActions()) {
            String normalized = action.trim().toLowerCase(Locale.ROOT);
            if (!ACTION_CODE.matcher(normalized).matches()
                    || !delegatableActions.contains(normalized)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            actions.add(normalized);
        }
        if (actions.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        LocalDateTime from = OrgSql.ofInstant(draft.validFrom());
        LocalDateTime to = OrgSql.ofInstant(draft.validTo());
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                requireSubjectExists(connection, draft.userId());
                requireDepartmentExists(connection, draft.departmentRootId());
                long generation =
                        resolveGeneration(connection, draft.resourceType(), draft.resourceId());
                requireNoDuplicate(connection, draft, generation, from, to);
                long id;
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "INSERT INTO admin_delegation (user_id, department_root_id,"
                                        + " resource_type, resource_id, resource_generation,"
                                        + " allowed_actions, valid_from, valid_to, status, version,"
                                        + " create_time, update_time, created_by, updated_by)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1,"
                                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, draft.userId());
                    statement.setLong(2, draft.departmentRootId());
                    statement.setString(3, draft.resourceType());
                    statement.setLong(4, draft.resourceId());
                    statement.setLong(5, generation);
                    statement.setString(6, actionsJson(actions));
                    statement.setTimestamp(7, Timestamp.valueOf(from));
                    statement.setTimestamp(8, Timestamp.valueOf(to));
                    statement.setLong(9, actorId);
                    statement.setLong(10, actorId);
                    statement.executeUpdate();
                    try (ResultSet generated = statement.getGeneratedKeys()) {
                        if (!generated.next()) {
                            throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
                        }
                        id = generated.getLong(1);
                    }
                }
                appendOperationLog(
                        connection,
                        actorId,
                        requestId,
                        "DELEGATION_CREATE",
                        id,
                        null,
                        summary(
                                draft.userId(),
                                draft.departmentRootId(),
                                draft.resourceType(),
                                draft.resourceId(),
                                generation,
                                actions,
                                from,
                                to));
                connection.commit();
                return get(id);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Distinct org/ACL capability codes of one user; mirrors the session permission query. */
    public List<String> permissionsOf(long userId) {
        OrgSql.requireActor(userId);
        String sql =
                "SELECT DISTINCT rp.permission_code FROM sys_user_role ur"
                        + " JOIN sys_role r ON r.role_id = ur.role_id"
                        + " JOIN sys_role_permission rp ON rp.role_id = r.role_id"
                        + " WHERE ur.user_id = ? AND r.status = '0' AND r.del_flag = '0'"
                        + " ORDER BY rp.permission_code";
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement = OrgSql.statement(connection, sql)) {
            statement.setLong(1, userId);
            List<String> permissions = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    permissions.add(result.getString(1));
                }
            }
            return List.copyOf(permissions);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Optimistic revoke: status flips to REVOKED only when the presented version still matches. */
    public DelegationRow revoke(long id, long expectedVersion, long actorId, String requestId) {
        OrgSql.requireActor(actorId);
        if (expectedVersion <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                DelegationRow before = get(id);
                if (before.userId() == actorId) {
                    throw new DomainException(ErrorCode.SELF_PRIVILEGE_ESCALATION);
                }
                if ("REVOKED".equals(before.status())) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                int updated;
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "UPDATE admin_delegation SET status = 'REVOKED', version = version + 1,"
                                        + " update_time = UTC_TIMESTAMP(6), updated_by = ?"
                                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'")) {
                    statement.setLong(1, actorId);
                    statement.setLong(2, id);
                    statement.setLong(3, expectedVersion);
                    updated = statement.executeUpdate();
                }
                if (updated != 1) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                appendOperationLog(
                        connection,
                        actorId,
                        requestId,
                        "DELEGATION_REVOKE",
                        id,
                        summary(
                                before.userId(),
                                before.departmentRootId(),
                                before.resourceType(),
                                before.resourceId(),
                                before.resourceGeneration(),
                                new LinkedHashSet<>(before.allowedActions()),
                                OrgSql.ofInstant(before.validFrom()),
                                OrgSql.ofInstant(before.validTo())),
                        null);
                connection.commit();
                return get(id);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void requireDraftShape(DelegationDraft draft) {
        if (draft == null
                || draft.userId() <= 0
                || draft.departmentRootId() <= 0
                || draft.resourceId() <= 0
                || draft.resourceType() == null
                || !RESOURCE_TYPES.contains(draft.resourceType())
                || draft.validFrom() == null
                || draft.validTo() == null
                || !draft.validTo().isAfter(draft.validFrom())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static void requireSubjectExists(Connection connection, long userId)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM sys_user_profile p JOIN sys_user_login_name l"
                                + " ON l.user_id = p.user_id WHERE p.user_id = ? AND p.status = 'ACTIVE'")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
    }

    private static void requireDepartmentExists(Connection connection, long departmentId)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM sys_dept WHERE dept_id = ? AND del_flag = '0'")) {
            statement.setLong(1, departmentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
    }

    /**
     * DATASOURCE rows carry no generation of their own (convention: 1, mirroring acl_grant);
     * NAMESPACE/OBJECT resolve the highest active generation so the delegation pins the current
     * identity even after delete-and-recreate bumps it.
     */
    private static long resolveGeneration(
            Connection connection, String resourceType, long resourceId) throws SQLException {
        String sql =
                switch (resourceType) {
                    case "DATASOURCE" ->
                            "SELECT 1 FROM datasource WHERE id = ? AND status = 'ACTIVE'";
                    case "NAMESPACE" ->
                            "SELECT MAX(generation) FROM resource_namespace"
                                    + " WHERE id = ? AND status = 'ACTIVE'";
                    default ->
                            "SELECT MAX(generation) FROM resource_object"
                                    + " WHERE id = ? AND status = 'ACTIVE'";
                };
        try (PreparedStatement statement = OrgSql.statement(connection, sql)) {
            statement.setLong(1, resourceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getObject(1) == null) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return resourceType.equals("DATASOURCE") ? 1 : result.getLong(1);
            }
        }
    }

    private static void requireNoDuplicate(
            Connection connection,
            DelegationDraft draft,
            long generation,
            LocalDateTime from,
            LocalDateTime to)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM admin_delegation WHERE user_id = ? AND department_root_id = ?"
                                + " AND resource_type = ? AND resource_id = ? AND resource_generation = ?"
                                + " AND status = 'ACTIVE' AND valid_from < ? AND (valid_to IS NULL OR valid_to > ?)")) {
            statement.setLong(1, draft.userId());
            statement.setLong(2, draft.departmentRootId());
            statement.setString(3, draft.resourceType());
            statement.setLong(4, draft.resourceId());
            statement.setLong(5, generation);
            statement.setTimestamp(6, Timestamp.valueOf(to));
            statement.setTimestamp(7, Timestamp.valueOf(from));
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new DomainException(ErrorCode.DUPLICATE_GRANT);
                }
            }
        }
    }

    private static void appendOperationLog(
            Connection connection,
            long actorId,
            String requestId,
            String action,
            long aggregateId,
            String beforeJson,
            String afterJson)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "INSERT INTO admin_operation_log (actor_id, request_id, action,"
                                + " aggregate_type, aggregate_id, before_masked, after_masked,"
                                + " occurred_at, create_time)"
                                + " VALUES (?, ?, ?, 'ADMIN_DELEGATION', ?, ?, ?, UTC_TIMESTAMP(6),"
                                + " UTC_TIMESTAMP(6))")) {
            statement.setLong(1, actorId);
            statement.setString(2, requestId);
            statement.setString(3, action);
            statement.setLong(4, aggregateId);
            statement.setString(5, beforeJson);
            statement.setString(6, afterJson);
            statement.executeUpdate();
        }
    }

    private static String summary(
            long userId,
            long departmentRootId,
            String resourceType,
            long resourceId,
            long generation,
            Set<String> actions,
            LocalDateTime from,
            LocalDateTime to) {
        return "{\"userId\":"
                + userId
                + ",\"departmentRootId\":"
                + departmentRootId
                + ",\"resource\":\""
                + resourceType
                + ":"
                + resourceId
                + ":"
                + generation
                + "\",\"actions\":"
                + actionsJson(actions)
                + ",\"validFrom\":\""
                + from
                + "\",\"validTo\":\""
                + (to == null ? "" : to)
                + "\"}";
    }

    /** Action codes match ACTION_CODE, so plain JSON string joining needs no escaping. */
    private static String actionsJson(Set<String> actions) {
        StringBuilder json = new StringBuilder("[");
        for (String action : actions) {
            if (!ACTION_CODE.matcher(action).matches()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            if (json.length() > 1) {
                json.append(',');
            }
            json.append('"').append(action).append('"');
        }
        return json.append(']').toString();
    }

    private static List<String> parseActions(String raw) {
        String trimmed = raw.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        String body = trimmed.substring(1, trimmed.length() - 1).trim();
        if (body.isEmpty()) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        for (String part : body.split(",")) {
            String value = part.trim();
            if (value.length() < 2 || !value.startsWith("\"") || !value.endsWith("\"")) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            String action = value.substring(1, value.length() - 1);
            if (!ACTION_CODE.matcher(action).matches()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            actions.add(action);
        }
        return List.copyOf(actions);
    }

    private static DelegationRow row(ResultSet result) throws SQLException {
        List<String> actions = List.of();
        String rawActions = result.getString(7);
        if (rawActions != null && !rawActions.isBlank()) {
            actions = parseActions(rawActions);
        }
        LocalDateTime validTo = result.getObject(9, LocalDateTime.class);
        return new DelegationRow(
                result.getLong(1),
                result.getLong(2),
                result.getLong(3),
                result.getString(4),
                result.getLong(5),
                result.getLong(6),
                actions,
                OrgSql.toInstant(result.getObject(8, LocalDateTime.class)),
                OrgSql.toInstant(validTo),
                result.getString(10),
                result.getLong(11));
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(cursor);
            if (value <= 0) {
                throw new NumberFormatException("not positive");
            }
            return value;
        } catch (NumberFormatException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
