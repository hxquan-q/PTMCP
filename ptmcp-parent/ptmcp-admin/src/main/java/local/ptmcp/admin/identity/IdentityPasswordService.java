package local.ptmcp.admin.identity;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.SecurityChangeTransaction;
import local.ptmcp.persistence.SecurityEvent;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Password reset with real credential invalidation. One metadata-side {@link
 * SecurityChangeTransaction} bumps the global security epoch, records the USER_SECURITY_CHANGED
 * event, revokes every ACTIVE refresh family, bumps the console aggregate version (the If-Match
 * target) and writes the masked operation-log row. Only after that receipt does the writer touch
 * the host identity store — so the front end's promise "old cookies, OAuth and PAT credentials stop
 * working" is backed by the same commit, and a failed identity write fails closed (credentials stay
 * revoked; the operator retries the hash write).
 *
 * <p>The plaintext password exists only inside this method's frame: 12–64 chars, UTF-8 ≤ 72 bytes
 * (BCrypt's real limit), encoded and immediately dropped. Nothing about it is logged.
 */
public final class IdentityPasswordService {
    private static final int MIN_LENGTH = 12;
    private static final int MAX_LENGTH = 64;
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final int MIN_REASON = 10;
    private static final int MAX_REASON = 500;

    private final DataSource primary;
    private final IdentityPasswordWriter writer;
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    public IdentityPasswordService(DataSource primary, IdentityPasswordWriter writer) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /**
     * @return the new aggregate version (If-Match target for the next console edit).
     */
    public long reset(
            long userId, long expectedVersion, String newPassword, String reason, long operatorId) {
        if (operatorId <= 0 || userId <= 0 || expectedVersion <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        requirePassword(newPassword);
        if (reason == null || reason.trim().length() < MIN_REASON || reason.length() > MAX_REASON) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        long authVersion = currentAuthVersion(primary, userId);
        String hash = encoder.encode(newPassword);
        SecurityChangeTransaction transaction = new SecurityChangeTransaction(primary);
        SecurityChangeTransaction.Receipt receipt =
                transaction.execute(
                        new SecurityEvent(
                                UUID.randomUUID(),
                                SecurityEvent.Kind.USER_SECURITY_CHANGED,
                                new SecurityEvent.NumericId(userId),
                                authVersion + 1),
                        connection -> {
                            int bumped;
                            try (PreparedStatement statement =
                                    statement(
                                            connection,
                                            "UPDATE sys_user_profile SET version = version + 1,"
                                                    + " update_time = UTC_TIMESTAMP(6)"
                                                    + " WHERE user_id = ? AND version = ?")) {
                                statement.setLong(1, userId);
                                statement.setLong(2, expectedVersion);
                                bumped = statement.executeUpdate();
                            }
                            if (bumped != 1) {
                                throw new DomainException(ErrorCode.VERSION_CONFLICT);
                            }
                            try (PreparedStatement statement =
                                    statement(
                                            connection,
                                            "UPDATE oauth_refresh_family SET status = 'REVOKED',"
                                                    + " revoked_at = UTC_TIMESTAMP(6), revoke_reason = ?,"
                                                    + " version = version + 1, update_time = UTC_TIMESTAMP(6)"
                                                    + " WHERE user_id = ? AND status = 'ACTIVE'")) {
                                statement.setString(1, "password reset via admin console");
                                statement.setLong(2, userId);
                                statement.executeUpdate();
                            }
                            try (PreparedStatement statement =
                                    statement(
                                            connection,
                                            "INSERT INTO admin_operation_log (actor_id, request_id, action,"
                                                    + " aggregate_type, aggregate_id, before_masked, after_masked,"
                                                    + " occurred_at, create_time)"
                                                    + " VALUES (?, ?, ?, 'USER', ?,"
                                                    + " '{\"action\":\"password-reset\"}',"
                                                    + " '{\"action\":\"password-reset\",\"performed\":true}',"
                                                    + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
                                statement.setLong(1, operatorId);
                                statement.setString(2, UUID.randomUUID().toString());
                                statement.setString(3, "USER_PASSWORD_RESET");
                                statement.setLong(4, userId);
                                statement.executeUpdate();
                            }
                        });
        boolean written = writer.updatePasswordHash(userId, hash);
        if (!written) {
            // Security side already committed (credentials revoked); the account is gone or
            // disabled in the identity store. Surface the authoritative console state.
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return receipt.epoch();
    }

    private static long currentAuthVersion(DataSource dataSource, long userId) {
        // The auth version exists on oauth_refresh_family aggregates; a user without any family
        // still needs a positive event version, so read MAX(version) with a 1 floor.
        try (java.sql.Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT COALESCE(MAX(version), 1) FROM oauth_refresh_family"
                                        + " WHERE user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return 1;
                }
                long version = result.getLong(1);
                return version < 1 ? 1 : version;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void requirePassword(String password) {
        if (password == null
                || password.length() < MIN_LENGTH
                || password.length() > MAX_LENGTH
                || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                        > BCRYPT_MAX_BYTES) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static PreparedStatement statement(java.sql.Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(5);
        return statement;
    }
}
