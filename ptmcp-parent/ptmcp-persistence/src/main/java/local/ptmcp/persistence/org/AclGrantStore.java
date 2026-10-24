package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

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

import javax.sql.DataSource;

/**
 * ACL grant admission over {@code acl_grant}. Duplicate protection is application level: a grant
 * with the same normalized content (subject, target, action, effect, descendants flag and window)
 * that is still ACTIVE is rejected with {@code DUPLICATE_GRANT}. Revocation is a terminal state
 * transition, never a physical delete, and stays idempotent.
 */
public final class AclGrantStore {
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

    public AclGrantRecord create(AclGrantRecord draft, long actorId) {
        validateDraft(draft);
        OrgSql.requireActor(actorId);
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                requireSubjectExists(connection, draft.subjectType(), draft.subjectId());
                requireResourceExists(
                        connection,
                        draft.resourceType(),
                        draft.resourceId(),
                        draft.resourceGeneration());
                requireNoDuplicate(connection, draft);
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
                    statement.setString(1, draft.subjectType());
                    statement.setLong(2, draft.subjectId());
                    statement.setString(3, draft.resourceType());
                    statement.setLong(4, draft.resourceId());
                    statement.setLong(5, draft.resourceGeneration());
                    statement.setString(6, draft.action());
                    statement.setString(7, draft.effect());
                    statement.setInt(8, draft.includeDescendants() ? 1 : 0);
                    statement.setObject(9, OrgSql.ofInstant(draft.validFrom()));
                    if (draft.validTo() == null) {
                        statement.setNull(10, java.sql.Types.TIMESTAMP);
                    } else {
                        statement.setObject(10, OrgSql.ofInstant(draft.validTo()));
                    }
                    statement.setString(11, draft.reason());
                    statement.setObject(12, OrgSql.nowUtc());
                    statement.setObject(13, OrgSql.nowUtc());
                    statement.setLong(14, actorId);
                    statement.setLong(15, actorId);
                    statement.executeUpdate();
                    id = generatedKey(statement);
                }
                connection.commit();
                return get(id);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public AclGrantRecord revoke(long id, long expectedVersion, String reason, long actorId) {
        OrgSql.requireText(reason, 500);
        OrgSql.requireActor(actorId);
        if (expectedVersion < 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = OrgSql.configure(primary)) {
            AclGrantRecord existing = get(id);
            if ("REVOKED".equals(existing.status())) {
                if (existing.version() != expectedVersion) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                return existing;
            }
            try (PreparedStatement statement =
                    OrgSql.statement(
                            connection,
                            "UPDATE acl_grant SET status = 'REVOKED', reason = ?, version = version + 1,"
                                    + " update_time = ?, updated_by = ? WHERE id = ? AND version = ?"
                                    + " AND status = 'ACTIVE'")) {
                statement.setString(1, reason);
                statement.setObject(2, OrgSql.nowUtc());
                statement.setLong(3, actorId);
                statement.setLong(4, id);
                statement.setLong(5, expectedVersion);
                if (statement.executeUpdate() != 1) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
            }
            return get(id);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
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
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT id, subject_type, subject_id, resource_type, resource_id,"
                                        + " resource_generation, action, effect, include_descendants,"
                                        + " valid_from, valid_to, status, reason, version, create_time,"
                                        + " update_time FROM acl_grant WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return record(result);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
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

    private static void requireNoDuplicate(Connection connection, AclGrantRecord draft)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM acl_grant WHERE subject_type = ? AND subject_id = ?"
                                + " AND resource_type = ? AND resource_id = ? AND resource_generation = ?"
                                + " AND action = ? AND effect = ? AND include_descendants = ?"
                                + " AND (valid_to IS NULL OR valid_to > ?)"
                                + " AND (valid_from < ? OR ? IS NULL)"
                                + " AND status = 'ACTIVE'")) {
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
