package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Reads one authoritatively consistent security state from the primary metadata database. Every
 * call opens a new short READ COMMITTED transaction of current reads; {@code checkedAt} is taken
 * before the connection is even acquired, so a slow read can never present an old state as fresh.
 * Read replicas, caches and Redis are never consulted here.
 *
 * <p>Any missing row, non-ACTIVE platform or infrastructure failure fails closed with {@code
 * SECURITY_STATE_UNAVAILABLE}; no driver text, SQL or connection details escape this class.
 */
public final class SecurityStateReader {
    private static final int STATEMENT_SECONDS = 5;
    static final int MAX_FAMILIES = 64;

    private final DataSource primaryMetadata;

    public SecurityStateReader(DataSource primaryMetadata) {
        this.primaryMetadata = Objects.requireNonNull(primaryMetadata, "primaryMetadata");
    }

    /**
     * The state a request depends on. {@code userId} is optional; family ids must be distinct and
     * bounded so a single read stays a short transaction.
     */
    public record SubjectRequest(long platformId, Long userId, List<UUID> familyIds) {
        public SubjectRequest {
            if (platformId <= 0 || userId != null && userId <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            Set<UUID> distinct = familyIds == null ? Set.of() : new LinkedHashSet<>(familyIds);
            if (distinct.size() > MAX_FAMILIES
                    || distinct.size() != (familyIds == null ? 0 : familyIds.size())) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            familyIds = List.copyOf(distinct);
        }
    }

    public SecuritySnapshot read(SubjectRequest request) {
        Objects.requireNonNull(request, "request");
        Instant checkedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        try (Connection connection = primaryMetadata.getConnection()) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            long epoch = readEpoch(connection);
            long platformVersion = readPlatformVersion(connection, request.platformId());
            Map<Long, Long> subjects = readSubject(connection, request.userId());
            Map<UUID, Long> families = readFamilies(connection, request.familyIds());
            connection.commit();
            return new SecuritySnapshot(epoch, subjects, platformVersion, families, checkedAt);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    private static long readEpoch(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT policy_epoch FROM sys_security_state "
                                        + "WHERE singleton_id = 1");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            long epoch = result.getLong(1);
            if (epoch <= 0 || result.next()) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            return epoch;
        }
    }

    private static long readPlatformVersion(Connection connection, long platformId)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT security_version FROM platform_registry "
                                + "WHERE id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, platformId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                }
                long version = result.getLong(1);
                if (version <= 0) {
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                }
                return version;
            }
        }
    }

    private static Map<Long, Long> readSubject(Connection connection, Long userId)
            throws SQLException {
        if (userId == null) {
            return Map.of();
        }
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT MAX(auth_version) FROM oauth_refresh_family "
                                + "WHERE user_id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                long version = result.next() ? result.getLong(1) : 0;
                return Map.of(userId, result.wasNull() ? 0L : version);
            }
        }
    }

    private static Map<UUID, Long> readFamilies(Connection connection, List<UUID> familyIds)
            throws SQLException {
        if (familyIds.isEmpty()) {
            return Map.of();
        }
        String placeholders =
                String.join(",", java.util.Collections.nCopies(familyIds.size(), "?"));
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT id, version FROM oauth_refresh_family "
                                + "WHERE status = 'ACTIVE' AND id IN ("
                                + placeholders
                                + ")")) {
            for (int index = 0; index < familyIds.size(); index++) {
                statement.setString(index + 1, familyIds.get(index).toString());
            }
            Map<UUID, Long> versions = new HashMap<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    versions.put(UUID.fromString(result.getString(1)), result.getLong(2));
                }
            }
            for (Long version : versions.values()) {
                if (version <= 0) {
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                }
            }
            return versions;
        }
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
                // Preserve the original error; the caller maps everything to
                // SECURITY_STATE_UNAVAILABLE.
            }
            throw error;
        }
    }
}
