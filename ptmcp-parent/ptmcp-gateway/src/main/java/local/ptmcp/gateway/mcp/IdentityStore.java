package local.ptmcp.gateway.mcp;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Primary-database lookups behind bearer authentication: the PAT row by SHA-256 digest, the refresh
 * family by id and the live user status. The store returns rows without interpreting them; status,
 * expiry and binding decisions belong to the authentication component. No token material, hash or
 * driver text is ever logged or returned to callers.
 */
final class IdentityStore {
    private static final int STATEMENT_SECONDS = 5;

    private final DataSource primary;

    IdentityStore(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    record PatRecord(
            long id,
            long userId,
            long platformId,
            long authVersion,
            LocalDateTime expiresAt,
            String status,
            String scopesJson,
            boolean platformActive,
            boolean userActive) {}

    record FamilyRecord(
            long userId,
            long platformId,
            long authVersion,
            String status,
            String clientId,
            LocalDateTime absoluteExpiresAt,
            LocalDateTime idleExpiresAt,
            boolean platformActive,
            boolean userActive,
            boolean clientActive) {

        boolean withinWindow() {
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            return absoluteExpiresAt != null
                    && absoluteExpiresAt.isAfter(now)
                    && idleExpiresAt != null
                    && idleExpiresAt.isAfter(now);
        }
    }

    Optional<PatRecord> findPat(byte[] tokenHash) {
        Objects.requireNonNull(tokenHash, "tokenHash");
        try (Connection connection = openRead();
                PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT pt.id, pt.user_id, pt.platform_id, pt.auth_version,"
                                        + " pt.expires_at, pt.status, pt.scopes, p.status, u.status"
                                        + " FROM personal_token pt"
                                        + " LEFT JOIN platform_registry p ON p.id = pt.platform_id"
                                        + " LEFT JOIN sys_user_profile u ON u.user_id = pt.user_id"
                                        + " WHERE pt.token_hash = ?")) {
            statement.setBytes(1, tokenHash);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new PatRecord(
                                result.getLong(1),
                                result.getLong(2),
                                result.getLong(3),
                                result.getLong(4),
                                result.getObject(5, LocalDateTime.class),
                                result.getString(6),
                                result.getString(7),
                                "ACTIVE".equals(result.getString(8)),
                                "ACTIVE".equals(result.getString(9))));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    Optional<FamilyRecord> findFamily(UUID familyId) {
        Objects.requireNonNull(familyId, "familyId");
        try (Connection connection = openRead();
                PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT f.user_id, f.platform_id, f.auth_version, f.status,"
                                        + " c.client_id, f.absolute_expires_at,"
                                        + " f.idle_expires_at, p.status, u.status, c.status"
                                        + " FROM oauth_refresh_family f"
                                        + " LEFT JOIN platform_registry p ON p.id = f.platform_id"
                                        + " LEFT JOIN sys_user_profile u ON u.user_id = f.user_id"
                                        + " LEFT JOIN oauth2_registered_client c ON c.id ="
                                        + " f.registered_client_id"
                                        + " WHERE f.id = ?")) {
            statement.setString(1, familyId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(
                        new FamilyRecord(
                                result.getLong(1),
                                result.getLong(2),
                                result.getLong(3),
                                result.getString(4),
                                result.getString(5),
                                result.getObject(6, LocalDateTime.class),
                                result.getObject(7, LocalDateTime.class),
                                "ACTIVE".equals(result.getString(8)),
                                "ACTIVE".equals(result.getString(9)),
                                "ACTIVE".equals(result.getString(10))));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    boolean patStillActive(long patId) {
        try (Connection connection = openRead();
                PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT status, expires_at FROM personal_token WHERE id = ?")) {
            statement.setLong(1, patId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                LocalDateTime expiresAt = result.getObject(2, LocalDateTime.class);
                return "ACTIVE".equals(result.getString(1))
                        && expiresAt != null
                        && expiresAt.isAfter(LocalDateTime.now(ZoneOffset.UTC));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    boolean userActive(long userId) {
        try (Connection connection = openRead();
                PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT status FROM sys_user_profile WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "ACTIVE".equals(result.getString(1));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    private Connection openRead() throws SQLException {
        Connection connection = primary.getConnection();
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return connection;
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
