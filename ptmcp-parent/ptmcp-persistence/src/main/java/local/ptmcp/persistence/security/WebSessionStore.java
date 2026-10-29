package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import javax.sql.DataSource;

/** Durable revocation gate shared by the separate ADMIN and AS session namespaces. */
public final class WebSessionStore {
    private final DataSource primary;

    public WebSessionStore(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    /** Called after session fixation protection, using the final cookie/session id. */
    public void issue(
            String kind,
            String id,
            long userId,
            long authVersion,
            Instant issuedAt,
            Instant expiresAt) {
        requireKind(kind);
        try (Connection connection = primary.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                UserSecurityStore.lockGlobal(connection);
                UserSecurityStore.requireReady(
                        UserSecurityStore.read(connection, userId, true), authVersion);
                UserSecurityStore.requireActiveProfile(connection, userId);
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO web_session_index (session_hash, kind, user_id, auth_version,"
                                        + " issued_at, expires_at, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
                    statement.setQueryTimeout(5);
                    statement.setBytes(1, hash(id));
                    statement.setString(2, kind);
                    statement.setLong(3, userId);
                    statement.setLong(4, authVersion);
                    statement.setObject(5, utc(issuedAt));
                    statement.setObject(6, utc(expiresAt));
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    public boolean active(String kind, String id, long userId, long authVersion) {
        requireKind(kind);
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT 1 FROM web_session_index w JOIN sys_user_security s ON s.user_id = w.user_id"
                                        + " JOIN sys_user_profile p ON p.user_id = s.user_id"
                                        + " WHERE w.session_hash = ? AND w.kind = ? AND w.user_id = ? AND w.auth_version = ?"
                                        + " AND w.auth_version = s.auth_version AND s.identity_state = 'READY' AND p.status = 'ACTIVE'"
                                        + " AND w.revoked_at IS NULL AND w.expires_at > UTC_TIMESTAMP(6)")) {
            statement.setQueryTimeout(5);
            statement.setBytes(1, hash(id));
            statement.setString(2, kind);
            statement.setLong(3, userId);
            statement.setLong(4, authVersion);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    public void revoke(String kind, String id) {
        requireKind(kind);
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE web_session_index SET revoked_at = UTC_TIMESTAMP(6), version = version + 1,"
                                        + " update_time = UTC_TIMESTAMP(6) WHERE session_hash = ? AND kind = ? AND revoked_at IS NULL")) {
            statement.setQueryTimeout(5);
            statement.setBytes(1, hash(id));
            statement.setString(2, kind);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    private static void requireKind(String kind) {
        if (!"ADMIN".equals(kind) && !"AS".equals(kind)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static byte[] hash(String id) {
        if (id == null || id.isBlank()) {
            throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
