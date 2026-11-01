package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.SecurityChangeTransaction;
import local.ptmcp.persistence.SecurityEvent;
import local.ptmcp.persistence.security.UserSecurityStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * ACL grant admission over {@code acl_grant}. Duplicate protection is application level: a grant
 * with the same normalized content (subject, target, action, effect, descendants flag and window)
 * that is still ACTIVE is rejected with {@code DUPLICATE_GRANT}. Revocation is a terminal state
 * transition, never a physical delete, and stays idempotent.
 */
public final class AclGrantStore {
    private static final String COLUMNS =
            "id, subject_type, subject_id, resource_type, resource_id, resource_generation,"
                    + " action, effect, include_descendants, valid_from, valid_to, status, reason,"
                    + " version, create_time, update_time";
    private static final MutationCheck TRUSTED_REPOSITORY = (connection, before, after) -> {};
    private final DataSource primary;

    public AclGrantStore(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record AclGrantRecord(
            long id,
            String subjectType,
            long subjectId,
            String resourceType,
            long resourceId,
            long resourceGeneration,
            String action,
            String effect,
            boolean includeDescendants,
            Instant validFrom,
            Instant validTo,
            String status,
            String reason,
            long version) {}

    /** Trusted repository entry; HTTP callers must supply their transactional mutation policy. */
    public AclGrantRecord create(AclGrantRecord draft, long actorId) {
        return create(draft, actorId, null, TRUSTED_REPOSITORY);
    }

    public AclGrantRecord create(
            AclGrantRecord draft, long actorId, String requestId, MutationCheck check) {
        validateDraft(draft);
        OrgSql.requireActor(actorId);
        Objects.requireNonNull(check, "check");
        return new SecurityChangeTransaction(primary)
                .executeReturning(
                        connection -> {
                            check.authorize(connection);
                            check.validate(connection, null, draft);
                            requireLiveDraft(connection, draft);
                            requireNoDuplicate(connection, draft, 0);
                            long id;
                            try (PreparedStatement statement =
                                    OrgSql.statement(
                                            connection,
                                            "INSERT INTO acl_grant (subject_type, subject_id, resource_type,"
                                                    + " resource_id, resource_generation, action, effect,"
                                                    + " include_descendants, valid_from, valid_to, status, reason,"
                                                    + " version, create_time, update_time, created_by, updated_by)"
                                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, 1, ?, ?, ?, ?)",
                                            Statement.RETURN_GENERATED_KEYS)) {
                                bindDraft(statement, draft);
                                statement.setObject(12, OrgSql.nowUtc());
                                statement.setObject(13, OrgSql.nowUtc());
                                statement.setLong(14, actorId);
                                statement.setLong(15, actorId);
                                if (statement.executeUpdate() != 1) {
                                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                                }
                                id = generatedKey(statement);
                            }
                            AclGrantRecord created = get(connection, id, false);
                            log(connection, actorId, requestId, "ACL_CREATE", null, created);
                            return outcome(created);
                        })
                .value();
    }

    /** Full replacement retains the row's identity and historical creator. */
    public AclGrantRecord update(
            long id,
            long expectedVersion,
            AclGrantRecord draft,
            long actorId,
            String requestId,
            MutationCheck check) {
        validateDraft(draft);
        requireVersion(expectedVersion);
        OrgSql.requireActor(actorId);
        Objects.requireNonNull(check, "check");
        return new SecurityChangeTransaction(primary)
                .executeReturning(
                        connection -> {
                            check.authorize(connection);
                            AclGrantRecord before = get(connection, id, true);
                            requireCurrentVersion(before, expectedVersion);
                            if (!"ACTIVE".equals(before.status())) {
                                throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                            }
                            check.validate(connection, before, draft);
                            requireLiveDraft(connection, draft);
                            requireNoDuplicate(connection, draft, id);
                            try (PreparedStatement statement =
                                    OrgSql.statement(
                                            connection,
                                            "UPDATE acl_grant SET subject_type=?, subject_id=?, resource_type=?,"
                                                    + " resource_id=?, resource_generation=?, action=?, effect=?,"
                                                    + " include_descendants=?, valid_from=?, valid_to=?, reason=?,"
                                                    + " version=version+1, update_time=UTC_TIMESTAMP(6), updated_by=?"
                                                    + " WHERE id=? AND version=? AND status='ACTIVE'")) {
                                bindDraft(statement, draft);
                                statement.setLong(12, actorId);
                                statement.setLong(13, id);
                                statement.setLong(14, expectedVersion);
                                if (statement.executeUpdate() != 1) {
                                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                }
                            }
                            AclGrantRecord updated = get(connection, id, false);
                            log(connection, actorId, requestId, "ACL_UPDATE", before, updated);
                            return outcome(updated);
                        })
                .value();
    }

    public AclGrantRecord revoke(long id, long expectedVersion, String reason, long actorId) {
        return revoke(id, expectedVersion, reason, actorId, null, TRUSTED_REPOSITORY);
    }

    public AclGrantRecord revoke(
            long id,
            long expectedVersion,
            String reason,
            long actorId,
            String requestId,
            MutationCheck check) {
        OrgSql.requireText(reason, 500);
        OrgSql.requireActor(actorId);
        requireVersion(expectedVersion);
        Objects.requireNonNull(check, "check");
        // REVOKED is terminal. Repeating an authorized no-op must not emit a new policy event.
        try (Connection connection = OrgSql.configure(primary)) {
            AclGrantRecord existing = get(connection, id, false);
            if ("REVOKED".equals(existing.status())) {
                if (existing.version() != expectedVersion) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                connection.setAutoCommit(false);
                try {
                    UserSecurityStore.lockGlobal(connection);
                    check.authorize(connection);
                    check.validate(connection, existing, null);
                    return existing;
                } finally {
                    connection.rollback();
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return new SecurityChangeTransaction(primary)
                .executeReturning(
                        connection -> {
                            check.authorize(connection);
                            AclGrantRecord before = get(connection, id, true);
                            requireCurrentVersion(before, expectedVersion);
                            check.validate(connection, before, null);
                            try (PreparedStatement statement =
                                    OrgSql.statement(
                                            connection,
                                            "UPDATE acl_grant SET status='REVOKED', reason=?, version=version+1,"
                                                    + " update_time=UTC_TIMESTAMP(6), updated_by=?"
                                                    + " WHERE id=? AND version=? AND status='ACTIVE'")) {
                                statement.setString(1, reason);
                                statement.setLong(2, actorId);
                                statement.setLong(3, id);
                                statement.setLong(4, expectedVersion);
                                if (statement.executeUpdate() != 1) {
                                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                }
                            }
                            AclGrantRecord revoked = get(connection, id, false);
                            log(connection, actorId, requestId, "ACL_REVOKE", before, revoked);
                            return outcome(revoked);
                        })
                .value();
    }

    /** Trusted same-connection policy; never closes, commits or retains the metadata connection. */
    @FunctionalInterface
    public interface MutationCheck {
        default void authorize(Connection connection) throws SQLException {}

        void validate(Connection connection, AclGrantRecord before, AclGrantRecord after)
                throws SQLException;
    }

    public List<AclGrantRecord> list(String subjectType, Long subjectId) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, subject_type, subject_id, resource_type, resource_id,"
                                + " resource_generation, action, effect, include_descendants, valid_from,"
                                + " valid_to, status, reason, version, create_time, update_time"
                                + " FROM acl_grant WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (subjectType != null) {
            sql.append(" AND subject_type = ?");
            parameters.add(subjectType);
        }
        if (subjectId != null) {
            sql.append(" AND subject_id = ?");
            parameters.add(subjectId);
        }
        sql.append(" ORDER BY id");
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement = OrgSql.statement(connection, sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                List<AclGrantRecord> records = new ArrayList<>();
                while (result.next()) {
                    records.add(record(result));
                }
                return records;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public AclGrantRecord get(long id) {
        try (Connection connection = OrgSql.configure(primary)) {
            return get(connection, id, false);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static AclGrantRecord get(Connection connection, long id, boolean lock)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT "
                                + COLUMNS
                                + " FROM acl_grant WHERE id = ?"
                                + (lock ? " FOR UPDATE" : ""))) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return record(result);
            }
        }
    }

    private static void requireVersion(long version) {
        if (version < 1) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
    }

    private static void requireCurrentVersion(AclGrantRecord row, long expected) {
        if (row.version() != expected) throw new DomainException(ErrorCode.VERSION_CONFLICT);
        if (row.version() == Long.MAX_VALUE) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    private static void requireLiveDraft(Connection connection, AclGrantRecord draft)
            throws SQLException {
        requireSubjectExists(connection, draft.subjectType(), draft.subjectId());
        requireResourceExists(
                connection, draft.resourceType(), draft.resourceId(), draft.resourceGeneration());
    }

    private static void bindDraft(PreparedStatement statement, AclGrantRecord draft)
            throws SQLException {
        statement.setString(1, draft.subjectType());
        statement.setLong(2, draft.subjectId());
        statement.setString(3, draft.resourceType());
        statement.setLong(4, draft.resourceId());
        statement.setLong(5, draft.resourceGeneration());
        statement.setString(6, draft.action());
        statement.setString(7, draft.effect());
        statement.setInt(8, draft.includeDescendants() ? 1 : 0);
        statement.setObject(9, OrgSql.ofInstant(draft.validFrom()));
        statement.setObject(10, draft.validTo() == null ? null : OrgSql.ofInstant(draft.validTo()));
        statement.setString(11, draft.reason());
    }

    private static SecurityChangeTransaction.Outcome<AclGrantRecord> outcome(AclGrantRecord row) {
        return new SecurityChangeTransaction.Outcome<>(
                row,
                new SecurityEvent(
                        UUID.randomUUID(),
                        SecurityEvent.Kind.ACL_CHANGED,
                        new SecurityEvent.NumericId(row.id()),
                        row.version()));
    }

    private static void log(
            Connection connection,
            long actor,
            String requestId,
            String action,
            AclGrantRecord before,
            AclGrantRecord after)
            throws SQLException {
        try (PreparedStatement insert =
                OrgSql.statement(
                        connection,
                        "INSERT INTO admin_operation_log (actor_id, request_id, action, aggregate_type,"
                                + " aggregate_id, before_masked, after_masked, occurred_at, create_time)"
                                + " VALUES (?, ?, ?, 'ACL', ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
            insert.setLong(1, actor);
            insert.setString(2, requestId == null ? UUID.randomUUID().toString() : requestId);
            insert.setString(3, action);
            insert.setString(4, Long.toString(after.id()));
            insert.setString(5, summary(before));
            insert.setString(6, summary(after));
            insert.executeUpdate();
        }
    }

    private static String summary(AclGrantRecord row) {
        return row == null
                ? null
                : "{\"id\":"
                        + row.id()
                        + ",\"version\":"
                        + row.version()
                        + ",\"subjectType\":\""
                        + row.subjectType()
                        + "\",\"subjectId\":"
                        + row.subjectId()
                        + ",\"resourceType\":\""
                        + row.resourceType()
                        + "\",\"resourceId\":"
                        + row.resourceId()
                        + ",\"action\":\""
                        + row.action()
                        + "\",\"effect\":\""
                        + row.effect()
                        + "\",\"status\":\""
                        + row.status()
                        + "\"}";
    }

    private static AclGrantRecord record(ResultSet result) throws SQLException {
        java.sql.Timestamp validTo = result.getTimestamp(11);
        return new AclGrantRecord(
                result.getLong(1),
                result.getString(2),
                result.getLong(3),
                result.getString(4),
                result.getLong(5),
                result.getLong(6),
                result.getString(7),
                result.getString(8),
                result.getInt(9) == 1,
                OrgSql.toInstant(result.getObject(10, java.time.LocalDateTime.class)),
                validTo == null ? null : validTo.toInstant(),
                result.getString(12),
                result.getString(13),
                result.getLong(14));
    }

    private static void validateDraft(AclGrantRecord draft) {
        if (draft == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (draft.id() != 0
                || !Set.of("USER", "ROLE", "DEPT").contains(draft.subjectType())
                || draft.subjectId() <= 0
                || !Set.of("DATASOURCE", "NAMESPACE", "OBJECT").contains(draft.resourceType())
                || draft.resourceId() <= 0
                || draft.resourceGeneration() <= 0
                || !Set.of("META", "READ").contains(draft.action())
                || !Set.of("ALLOW", "DENY").contains(draft.effect())
                || draft.validFrom() == null
                || (draft.validTo() != null && !draft.validTo().isAfter(draft.validFrom()))
                || !"ACTIVE".equals(draft.status())
                || draft.version() != 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        OrgSql.requireText(draft.reason(), 500);
    }

    private static void requireSubjectExists(
            Connection connection, String subjectType, long subjectId) throws SQLException {
        String sql =
                switch (subjectType) {
                    case "USER" -> "SELECT 1 FROM sys_user_login_name WHERE user_id = ?";
                    case "ROLE" -> "SELECT 1 FROM sys_role WHERE role_id = ? AND del_flag = '0'";
                    default -> "SELECT 1 FROM sys_dept WHERE dept_id = ? AND del_flag = '0'";
                };
        try (PreparedStatement statement = OrgSql.statement(connection, sql)) {
            statement.setLong(1, subjectId);
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

    private static void requireNoDuplicate(
            Connection connection, AclGrantRecord draft, long excludedId) throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM acl_grant WHERE subject_type = ? AND subject_id = ?"
                                + " AND resource_type = ? AND resource_id = ? AND resource_generation = ?"
                                + " AND action = ? AND effect = ? AND include_descendants = ?"
                                + " AND (valid_to IS NULL OR valid_to > ?)"
                                + " AND (valid_from < ? OR ? IS NULL)"
                                + " AND status = 'ACTIVE' AND id <> ?")) {
            statement.setString(1, draft.subjectType());
            statement.setLong(2, draft.subjectId());
            statement.setString(3, draft.resourceType());
            statement.setLong(4, draft.resourceId());
            statement.setLong(5, draft.resourceGeneration());
            statement.setString(6, draft.action());
            statement.setString(7, draft.effect());
            statement.setInt(8, draft.includeDescendants() ? 1 : 0);
            // Same subject/resource/action with overlapping validity is the same live grant.
            statement.setObject(9, OrgSql.ofInstant(draft.validFrom()));
            if (draft.validTo() == null) {
                statement.setNull(10, java.sql.Types.TIMESTAMP);
                statement.setNull(11, java.sql.Types.TIMESTAMP);
            } else {
                statement.setObject(10, OrgSql.ofInstant(draft.validTo()));
                statement.setObject(11, OrgSql.ofInstant(draft.validTo()));
            }
            statement.setLong(12, excludedId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new DomainException(ErrorCode.DUPLICATE_GRANT);
                }
            }
        }
    }

    private static long generatedKey(Statement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return keys.getLong(1);
        }
    }
}
