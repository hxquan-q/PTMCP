package local.ptmcp.persistence.org;

import local.ptmcp.common.authz.PermissionGrant;
import local.ptmcp.common.authz.SubjectType;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Read-side authorization loads for the MCP tool path: the effective subject scope of one user, the
 * candidate ACL rows for those subjects and the platform scope ceiling. Rows keep their exact time
 * windows and statuses; expansion and evaluation stay in the shared pure functions. All reads are
 * short, parameterized and read-only on the primary metadata database.
 */
public final class AuthorizationReader {
    private static final int STATEMENT_SECONDS = 5;
    private static final int MAX_SUBJECT_IDS = 500;
    private static final int MAX_DEPT_HOPS = 64;

    private final DataSource primary;

    public AuthorizationReader(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    /** Effective subjects of one user: enabled roles, own department and enabled ancestors. */
    public record SubjectScopeData(Set<Long> roleIds, Long deptId, Set<Long> deptAncestorIds) {
        public SubjectScopeData {
            roleIds = Set.copyOf(Objects.requireNonNull(roleIds, "roleIds"));
            deptAncestorIds =
                    Set.copyOf(Objects.requireNonNull(deptAncestorIds, "deptAncestorIds"));
        }
    }

    /** One stored platform scope row; callers apply the descendant and generation rules. */
    public record PlatformScopeRow(
            String resourceType,
            long resourceId,
            long generation,
            String action,
            boolean descendants) {}

    /**
     * One stored ACL row with the full stored target identity. Callers filter rows to the live
     * resource tree (type and generation must still match) before handing the pure evaluator the
     * subset it understands.
     */
    public record AclGrant(
            long grantId,
            SubjectType subjectType,
            long subjectId,
            String resourceType,
            long resourceId,
            long resourceGeneration,
            PermissionGrant.Action action,
            PermissionGrant.Effect effect,
            boolean includeDescendants,
            java.time.Instant validFrom,
            java.time.Instant validTo) {}

    /** Platform registry fields the tool path needs beyond the security-state reader. */
    public record PlatformInfo(
            long id, int tier, String catalogMode, String proofMode, boolean active) {}

    /** Encrypted Tier1 proof policy; plaintext is never returned by the read path. */
    public record PlatformProofConfig(String proofMode, byte[] configCipher, String configKeyId) {
        public PlatformProofConfig {
            configCipher = configCipher == null ? null : configCipher.clone();
        }
    }

    public SubjectScopeData loadSubjectScope(long userId, boolean departmentInheritance) {
        if (userId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = openRead()) {
            Set<Long> roles = new LinkedHashSet<>();
            readRoles(connection, userId, roles);
            Long deptId = readUserDept(connection, userId);
            Set<Long> ancestors = new LinkedHashSet<>();
            if (deptId != null && departmentInheritance) {
                readAncestors(connection, deptId, ancestors);
            }
            return new SubjectScopeData(roles, deptId, ancestors);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public List<AclGrant> loadGrants(long userId, SubjectScopeData subject) {
        if (userId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Objects.requireNonNull(subject, "subject");
        int subjectCount = subject.roleIds().size() + subject.deptAncestorIds().size() + 2;
        if (subjectCount > MAX_SUBJECT_IDS) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        try (Connection connection = openRead()) {
            List<AclGrant> grants = new ArrayList<>();
            readUserGrants(connection, userId, grants);
            readSubjectGrants(connection, "ROLE", List.copyOf(subject.roleIds()), grants);
            List<Long> depts = new ArrayList<>(subject.deptAncestorIds());
            if (subject.deptId() != null) {
                depts.add(subject.deptId());
            }
            readSubjectGrants(connection, "DEPT", depts, grants);
            return grants;
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public List<PlatformScopeRow> loadPlatformScope(long platformId) {
        if (platformId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = openRead();
                PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT resource_type, resource_id, resource_generation, action,"
                                        + " include_descendants FROM platform_scope WHERE"
                                        + " platform_id = ?")) {
            statement.setLong(1, platformId);
            List<PlatformScopeRow> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(
                            new PlatformScopeRow(
                                    result.getString(1),
                                    result.getLong(2),
                                    result.getLong(3),
                                    result.getString(4),
                                    result.getInt(5) == 1));
                }
            }
            return rows;
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public PlatformProofConfig loadPlatformProofConfig(long platformId) {
        if (platformId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = openRead();
                PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT proof_mode, proof_config_cipher, proof_key_id FROM"
                                        + " platform_registry WHERE id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, platformId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return new PlatformProofConfig(
                        result.getString(1), result.getBytes(2), result.getString(3));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public PlatformInfo loadPlatform(long platformId) {
        if (platformId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = openRead();
                PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT tier, catalog_mode, proof_mode, status FROM"
                                        + " platform_registry WHERE id = ?")) {
            statement.setLong(1, platformId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return new PlatformInfo(
                        platformId,
                        result.getInt(1),
                        result.getString(2),
                        result.getString(3),
                        "ACTIVE".equals(result.getString(4)));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private Connection openRead() throws SQLException {
        Connection connection = primary.getConnection();
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
    }

    private static void readRoles(Connection connection, long userId, Set<Long> roles)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT r.role_id FROM sys_user_role ur JOIN sys_role r ON r.role_id ="
                                + " ur.role_id WHERE ur.user_id = ? AND r.status = '0' AND r.del_flag"
                                + " = '0'")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    roles.add(result.getLong(1));
                }
            }
        }
    }

    private static Long readUserDept(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT dept_id FROM sys_user_profile WHERE user_id = ? AND status ="
                                + " 'ACTIVE'")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.TOKEN_INVALID);
                }
                long deptId = result.getLong(1);
                return result.wasNull() ? null : deptId;
            }
        }
    }

    private static void readAncestors(Connection connection, long deptId, Set<Long> ancestors)
            throws SQLException {
        long current = deptId;
        for (int hop = 0; hop < MAX_DEPT_HOPS; hop++) {
            try (PreparedStatement statement =
                    statement(
                            connection,
                            "SELECT parent_id, status, del_flag FROM sys_dept WHERE dept_id = ?")) {
                statement.setLong(1, current);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        return;
                    }
                    long parent = result.getLong(1);
                    if (parent <= 0) {
                        return;
                    }
                    if ("0".equals(result.getString(2)) && "0".equals(result.getString(3))) {
                        ancestors.add(parent);
                    }
                    if (parent == current) {
                        return;
                    }
                    current = parent;
                }
            }
        }
    }

    private static void readUserGrants(Connection connection, long userId, List<AclGrant> grants)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT id, subject_type, subject_id, resource_type, resource_id,"
                                + " resource_generation, action, effect, include_descendants,"
                                + " valid_from, valid_to FROM acl_grant WHERE status = 'ACTIVE'"
                                + " AND subject_type = 'USER' AND subject_id = ?")) {
            statement.setLong(1, userId);
            appendGrants(statement.executeQuery(), grants);
        }
    }

    private static void readSubjectGrants(
            Connection connection, String subjectType, List<Long> subjectIds, List<AclGrant> grants)
            throws SQLException {
        if (subjectIds.isEmpty()) {
            return;
        }
        String placeholders =
                String.join(",", java.util.Collections.nCopies(subjectIds.size(), "?"));
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT id, subject_type, subject_id, resource_type, resource_id,"
                                + " resource_generation, action, effect, include_descendants,"
                                + " valid_from, valid_to FROM acl_grant WHERE status = 'ACTIVE'"
                                + " AND subject_type = ? AND subject_id IN ("
                                + placeholders
                                + ")")) {
            statement.setString(1, subjectType);
            for (int index = 0; index < subjectIds.size(); index++) {
                statement.setLong(index + 2, subjectIds.get(index));
            }
            appendGrants(statement.executeQuery(), grants);
        }
    }

    private static void appendGrants(ResultSet result, List<AclGrant> grants) throws SQLException {
        while (result.next()) {
            grants.add(
                    new AclGrant(
                            result.getLong(1),
                            SubjectType.valueOf(result.getString(2)),
                            result.getLong(3),
                            result.getString(4),
                            result.getLong(5),
                            result.getLong(6),
                            PermissionGrant.Action.valueOf(result.getString(7)),
                            PermissionGrant.Effect.valueOf(result.getString(8)),
                            result.getInt(9) == 1,
                            instant(result, 10),
                            instant(result, 11)));
        }
    }

    private static java.time.Instant instant(ResultSet result, int index) throws SQLException {
        LocalDateTime value = result.getObject(index, LocalDateTime.class);
        return value == null
                ? null
                : value.toInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    private static PreparedStatement statement(Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(STATEMENT_SECONDS);
            return statement;
        } catch (SQLException | RuntimeException error) {
            try {
                statement.close();
            } catch (SQLException | RuntimeException ignored) {
                // Preserve the original failure.
            }
            throw error;
        }
    }
}
