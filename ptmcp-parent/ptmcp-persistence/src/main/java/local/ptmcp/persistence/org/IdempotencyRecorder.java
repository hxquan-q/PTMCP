package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Idempotency-Key lease over {@code api_idempotency}. One lease per (actor, route, key hash): a
 * fresh key occupies a RUNNING row with a 24 hour expiry, a matching replayed request returns the
 * recorded response, a different body under the same key is {@code IDEMPOTENCY_KEY_REUSED}, and a
 * still-running lease is {@code OPERATION_IN_PROGRESS}. A FAILED lease is deleted so the retry can
 * occupy a fresh one.
 */
public final class IdempotencyRecorder {
    private static final long LEASE_HOURS = 24;

    private final DataSource primary;

    public IdempotencyRecorder(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public enum State {
        OCCUPIED,
        REPLAY
    }

    public record Replay(byte[] responseBody, int httpStatus) {}

    public record OccupyResult(State state, Replay replay) {}

    public OccupyResult occupy(
            long actorId, String routeKey, String idempotencyKey, byte[] requestHash) {
        if (actorId <= 0
                || routeKey == null
                || routeKey.isBlank()
                || routeKey.length() > 128
                || idempotencyKey == null
                || idempotencyKey.isBlank()
                || requestHash == null
                || requestHash.length != 32) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        byte[] keyHash = sha256(idempotencyKey);
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                LocalDateTime now = OrgSql.nowUtc();
                byte[] existingRequestHash = null;
                String state = null;
                LocalDateTime expiresAt = null;
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT request_hash, state, expires_at FROM api_idempotency"
                                        + " WHERE actor_id = ? AND route_key = ? AND key_hash = ?"
                                        + " FOR UPDATE")) {
                    statement.setLong(1, actorId);
                    statement.setString(2, routeKey);
                    statement.setBytes(3, keyHash);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            existingRequestHash = result.getBytes(1);
                            state = result.getString(2);
                            expiresAt = result.getObject(3, LocalDateTime.class);
                        }
                    }
                }
                if (existingRequestHash != null) {
                    if (!Arrays.equals(existingRequestHash, requestHash)) {
                        throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                    if ("SUCCEEDED".equals(state)) {
                        Replay replay = readReplay(connection, actorId, routeKey, keyHash);
                        connection.rollback();
                        return new OccupyResult(State.REPLAY, replay);
                    }
                    if ("RUNNING".equals(state) && expiresAt != null && expiresAt.isAfter(now)) {
                        throw new DomainException(ErrorCode.OPERATION_IN_PROGRESS);
                    }
                    try (PreparedStatement delete =
                            OrgSql.statement(
                                    connection,
                                    "DELETE FROM api_idempotency WHERE actor_id = ? AND route_key = ?"
                                            + " AND key_hash = ?")) {
                        delete.setLong(1, actorId);
                        delete.setString(2, routeKey);
                        delete.setBytes(3, keyHash);
                        delete.executeUpdate();
                    }
                }
                insertLease(connection, actorId, routeKey, keyHash, requestHash);
                connection.commit();
                return new OccupyResult(State.OCCUPIED, null);
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

    public void completeSucceeded(
            long actorId,
            String routeKey,
            String idempotencyKey,
            byte[] responseBody,
            int httpStatus) {
        if (responseBody == null || httpStatus < 100 || httpStatus > 599) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "UPDATE api_idempotency SET state = 'SUCCEEDED', response_cipher = ?,"
                                        + " http_status = ? WHERE actor_id = ? AND route_key = ?"
                                        + " AND key_hash = ? AND state = 'RUNNING'")) {
            statement.setBytes(1, responseBody);
            statement.setInt(2, httpStatus);
            statement.setLong(3, actorId);
            statement.setString(4, routeKey);
            statement.setBytes(5, sha256(idempotencyKey));
            if (statement.executeUpdate() != 1) {
                throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public void completeFailed(long actorId, String routeKey, String idempotencyKey) {
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "DELETE FROM api_idempotency WHERE actor_id = ? AND route_key = ?"
                                        + " AND key_hash = ? AND state = 'RUNNING'")) {
            statement.setLong(1, actorId);
            statement.setString(2, routeKey);
            statement.setBytes(3, sha256(idempotencyKey));
            if (statement.executeUpdate() != 1) {
                throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void insertLease(
            Connection connection,
            long actorId,
            String routeKey,
            byte[] keyHash,
            byte[] requestHash)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "INSERT INTO api_idempotency (actor_id, route_key, key_hash, request_hash, state,"
                                + " resource_id, response_cipher, http_status, create_time, expires_at)"
                                + " VALUES (?, ?, ?, ?, 'RUNNING', NULL, NULL, NULL, ?, ?)")) {
            statement.setLong(1, actorId);
            statement.setString(2, routeKey);
            statement.setBytes(3, keyHash);
            statement.setBytes(4, requestHash);
            LocalDateTime now = OrgSql.nowUtc();
            statement.setObject(5, now);
            statement.setObject(6, now.plusHours(LEASE_HOURS));
            statement.executeUpdate();
        }
    }

    private static Replay readReplay(
            Connection connection, long actorId, String routeKey, byte[] keyHash)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT response_cipher, http_status FROM api_idempotency"
                                + " WHERE actor_id = ? AND route_key = ? AND key_hash = ?")) {
            statement.setLong(1, actorId);
            statement.setString(2, routeKey);
            statement.setBytes(3, keyHash);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
                }
                byte[] body = result.getBytes(1);
                int status = result.getInt(2);
                return new Replay(body, status);
            }
        }
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
