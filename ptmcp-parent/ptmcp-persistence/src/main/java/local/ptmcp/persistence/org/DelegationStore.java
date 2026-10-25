package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Delegated-administration store over {@code admin_delegation} (db-design §6): one record grants a
 * user administration over a department subtree crossed with one resource scope, bounded by an
 * explicit action list and validity window. Only platform admins create or revoke delegations and a
 * delegation may never target its own creator (self-escalation). Every mutation writes {@code
 * admin_operation_log} in the same transaction (audit.md §5).
 */
public final class DelegationStore {
    private static final int MAX_ACTIONS = 32;

    private final DataSource primary;

    public DelegationStore(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record DelegationRecord(
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
            long version) {

        public DelegationRecord {
            allowedActions = List.copyOf(allowedActions);
        }

        public boolean coversAction(String action) {
            return allowedActions.contains(action);
        }
    }

    public record Draft(
            long userId,
            long departmentRootId,
            String resourceType,
            long resourceId,
            long resourceGeneration,
            List<String> allowedActions,
            Instant validFrom,
            Instant validTo) {}

    /**
     * Creates one delegation. The operator may not delegate to themselves, the target user,
     * department root and resource must exist with the supplied generation, and the window must be
     * well formed. Writes the audit row in the same transaction.
     */
    public DelegationRecord create(Draft draft, long operatorId) {
        long[] createdId = {-1};
        Objects.requireNonNull(draft, "draft");
        if (draft.userId() == operatorId) {
            // A delegation is privilege handed to someone else; delegating to yourself is the
            // definition of self-escalation.
            throw new DomainException(ErrorCode.SELF_PRIVILEGE_ESCALATION);
        }
        if (draft.validTo() != null && !draft.validTo().isAfter(draft.validFrom())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        List<String> actions = normalizeActions(draft.allowedActions());
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                requireUserExists(connection, draft.userId());
                requireDepartmentExists(connection, draft.departmentRootId());
                requireResourceExists(
                        connection,
                        draft.resourceType(),
                        draft.resourceId(),
                        draft.resourceGeneration());
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "INSERT INTO admin_delegation (user_id, department_root_id,"
                                        + " resource_type, resource_id, resource_generation,"
                                        + " allowed_actions, valid_from, valid_to, status,"
                                        + " version, create_time, update_time, created_by,"
                                        + " updated_by)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, draft.userId());
                    statement.setLong(2, draft.departmentRootId());
                    statement.setString(3, draft.resourceType());
                    statement.setLong(4, draft.resourceId());
                    statement.setLong(5, draft.resourceGeneration());
                    statement.setString(6, encodeActions(actions));
                    statement.setObject(7, OrgSql.ofInstant(draft.validFrom()));
                    statement.setObject(
                            8, draft.validTo() == null ? null : OrgSql.ofInstant(draft.validTo()));
                    Timestamp now = new Timestamp(System.currentTimeMillis());
                    statement.setTimestamp(9, now);
                    statement.setTimestamp(10, now);
                    statement.setLong(11, operatorId);
                    statement.setLong(12, operatorId);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        keys.next();
                        createdId[0] = keys.getLong(1);
                    }
                }
                audit(connection, operatorId, "DELEGATION_CREATE", createdId[0]);
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            failure.printStackTrace();
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return get(createdId[0]);
    }

    /** Delegations of one user, newest first, capped. Filtering is entirely in the query. */
    public List<DelegationRecord> list(long userId, int limit) {
        if (userId <= 0 || limit < 1 || limit > 200) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String sql =
                "SELECT id, user_id, department_root_id, resource_type, resource_id,"
                        + " resource_generation, allowed_actions, valid_from, valid_to, status,"
                        + " version FROM admin_delegation WHERE user_id = ?"
                        + " ORDER BY id DESC LIMIT ?";
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement = OrgSql.statement(connection, sql)) {
            statement.setLong(1, userId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                List<DelegationRecord> records = new ArrayList<>();
                while (result.next()) {
                    records.add(map(result));
                }
                return List.copyOf(records);
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public DelegationRecord get(long id) {
        DelegationRecord record = find(id);
        if (record == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return record;
    }

    public DelegationRecord find(long id) {
        String sql =
                "SELECT id, user_id, department_root_id, resource_type, resource_id,"
                        + " resource_generation, allowed_actions, valid_from, valid_to, status,"
                        + " version FROM admin_delegation WHERE id = ?";
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement = OrgSql.statement(connection, sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Revokes with optimistic locking; a missing id is {@code RESOURCE_NOT_FOUND}, a stale version
     * is {@code VERSION_CONFLICT}, revoking an already-revoked delegation returns its current state
     * (idempotent). Writes the audit row in the same transaction.
     */
    public DelegationRecord revoke(long id, long expectedVersion, long operatorId) {
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                int changed;
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "UPDATE admin_delegation SET status = 'REVOKED',"
                                        + " version = version + 1, update_time = ?"
                                        + " WHERE id = ? AND version = ? AND status = 'ACTIVE'")) {
                    statement.setTimestamp(1, new Timestamp(System.currentTimeMillis()));
                    statement.setLong(2, id);
                    statement.setLong(3, expectedVersion);
                    changed = statement.executeUpdate();
                }
                if (changed != 1) {
                    DelegationRecord current = findIn(connection, id);
                    if (current == null) {
                        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                    }
                    if ("REVOKED".equals(current.status())
                            && current.version() == expectedVersion) {
                        // Idempotent replay of the same revocation: the end state is identical.
                        return current;
                    }
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                audit(connection, operatorId, "DELEGATION_REVOKE", id);
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return get(id);
    }

    /**
     * The only lookup the enforcement path uses: an ACTIVE delegation of this user over this
     * resource, valid at {@code at}, whose recorded generation still matches the resource. Absent
     * or expired delegations are indistinguishable from no delegation.
     */
    public DelegationRecord findValid(
            long userId, String resourceType, long resourceId, Instant at) {
        String sql =
                "SELECT id, user_id, department_root_id, resource_type, resource_id,"
                        + " resource_generation, allowed_actions, valid_from, valid_to, status,"
                        + " version FROM admin_delegation WHERE user_id = ? AND status = 'ACTIVE'"
                        + " AND resource_type = ? AND resource_id = ?"
                        + " AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)";
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement = OrgSql.statement(connection, sql)) {
            statement.setLong(1, userId);
            statement.setString(2, resourceType);
            statement.setLong(3, resourceId);
            statement.setObject(4, OrgSql.ofInstant(at));
            statement.setObject(5, OrgSql.ofInstant(at));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    DelegationRecord record = map(result);
                    if (generationStillCurrent(connection, record)) {
                        return record;
                    }
                }
                return null;
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static boolean generationStillCurrent(Connection connection, DelegationRecord record)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(connection, currentGenerationSql(record.resourceType()))) {
            statement.setLong(1, record.resourceId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                return result.getLong(1) == record.resourceGeneration();
            }
        }
    }

    private static String currentGenerationSql(String resourceType) {
        return switch (resourceType) {
            case "DATASOURCE" ->
                    "SELECT config_version FROM datasource WHERE id = ? AND status = 'ACTIVE'";
            case "NAMESPACE" ->
                    "SELECT generation FROM resource_namespace WHERE id = ? AND status = 'ACTIVE'";
            default -> "SELECT generation FROM resource_object WHERE id = ? AND status = 'ACTIVE'";
        };
    }

    private static DelegationRecord findIn(Connection connection, long id) throws SQLException {
        String sql =
                "SELECT id, user_id, department_root_id, resource_type, resource_id,"
                        + " resource_generation, allowed_actions, valid_from, valid_to, status,"
                        + " version FROM admin_delegation WHERE id = ?";
        try (PreparedStatement statement = OrgSql.statement(connection, sql)) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? map(result) : null;
            }
        }
    }

    private static DelegationRecord map(ResultSet result) throws SQLException {
        List<String> actions = decodeActions(result.getBytes("allowed_actions"));
        Instant validTo =
                result.getObject("valid_to", Timestamp.class) == null
                        ? null
                        : result.getObject("valid_to", Timestamp.class).toInstant();
        return new DelegationRecord(
                result.getLong("id"),
                result.getLong("user_id"),
                result.getLong("department_root_id"),
                result.getString("resource_type"),
                result.getLong("resource_id"),
                result.getLong("resource_generation"),
                actions,
                result.getObject("valid_from", Timestamp.class).toInstant(),
                validTo,
                result.getString("status"),
                result.getLong("version"));
    }

    /**
     * The JSON envelope for {@code allowed_actions}. Every action is validated to {@code
     * [a-z][a-z0-9:._-]*} before it gets here, so plain concatenation is safe; the decoder
     * re-checks the same shape and rejects anything else.
     */
    private static String encodeActions(List<String> actions) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < actions.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(actions.get(index)).append('"');
        }
        return json.append(']').toString();
    }

    private static List<String> decodeActions(byte[] json) {
        String text = new String(json == null ? new byte[0] : json, StandardCharsets.UTF_8);
        String body = text.trim();
        if (!body.startsWith("[") || !body.endsWith("]")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        body = body.substring(1, body.length() - 1).trim();
        List<String> actions = new ArrayList<>();
        if (body.isEmpty()) {
            return actions;
        }
        for (String part : body.split(",")) {
            String action = part.trim();
            if (action.length() < 3 || !action.startsWith("\"") || !action.endsWith("\"")) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            action = action.substring(1, action.length() - 1);
            if (!action.matches("[a-z][a-z0-9:._-]{0,63}")) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            actions.add(action);
        }
        return List.copyOf(actions);
    }

    private static List<String> normalizeActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (actions.size() > MAX_ACTIONS) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        List<String> normalized = new ArrayList<>();
        for (String action : actions) {
            if (action == null || !action.matches("[a-z][a-z0-9:._-]{0,63}")) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            if (!normalized.contains(action)) {
                normalized.add(action);
            }
        }
        return List.copyOf(normalized);
    }

    /**
     * The user identity lives in {@code sys_user_login_name} (+ profile), not in a host {@code
     * sys_user} table that this deployment does not own.
     */
    private static void requireUserExists(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection, "SELECT 1 FROM sys_user_login_name WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
    }

    private static void requireDepartmentExists(Connection connection, long deptId)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(connection, "SELECT 1 FROM sys_dept WHERE dept_id = ?")) {
            statement.setLong(1, deptId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
    }

    private static void requireResourceExists(
            Connection connection, String resourceType, long resourceId, long generation)
            throws SQLException {
        String sql =
                switch (resourceType) {
                    case "DATASOURCE" ->
                            "SELECT 1 FROM datasource WHERE id = ? AND status = 'ACTIVE'";
                    case "NAMESPACE" ->
                            "SELECT 1 FROM resource_namespace WHERE id = ? AND generation = ?"
                                    + " AND status = 'ACTIVE'";
                    default ->
                            "SELECT 1 FROM resource_object WHERE id = ? AND generation = ?"
                                    + " AND status = 'ACTIVE'";
                };
        try (PreparedStatement statement = OrgSql.statement(connection, sql)) {
            statement.setLong(1, resourceId);
            if (!"DATASOURCE".equals(resourceType)) {
                statement.setLong(2, generation);
            } else if (generation <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
    }

    /** Same-transaction audit row; a failed audit fails the mutation. */
    private static void audit(
            Connection connection, long operatorId, String action, long delegationId)
            throws SQLException {
        String payload = "{\"schemaVersion\":1,\"delegationId\":" + delegationId + "}";
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "INSERT INTO admin_operation_log (actor_id, request_id, action,"
                                + " aggregate_type, aggregate_id, before_masked, after_masked,"
                                + " occurred_at, create_time) VALUES (?, UUID(), ?, 'DELEGATION',"
                                + " ?, NULL, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
            statement.setLong(1, operatorId);
            statement.setString(2, action);
            statement.setLong(3, delegationId);
            statement.setString(4, payload);
            statement.executeUpdate();
        }
    }
}
