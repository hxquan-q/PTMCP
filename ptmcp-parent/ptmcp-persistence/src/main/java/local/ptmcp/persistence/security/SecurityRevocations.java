package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.SecurityChangeTransaction;
import local.ptmcp.persistence.SecurityEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Administrative revocations over refresh-token families. Every command runs through {@link
 * SecurityChangeTransaction}, so the epoch bump, the security event and the family update commit
 * atomically; a returned receipt means the revocation is durably committed. Callers must not wait
 * for any broadcast — the epoch in the receipt is what readers will observe.
 */
public final class SecurityRevocations {
    private static final int REASON_MAX = 200;
    private final DataSource primaryMetadata;
    private final SecurityChangeTransaction transactions;

    public SecurityRevocations(DataSource primaryMetadata) {
        Objects.requireNonNull(primaryMetadata, "primaryMetadata");
        this.primaryMetadata = primaryMetadata;
        this.transactions = new SecurityChangeTransaction(primaryMetadata);
    }

    /** Revokes every ACTIVE family of the user. Zero affected rows is a valid no-op. */
    public SecurityChangeTransaction.Receipt revokeSubject(
            long userId, long authVersion, String reason) {
        if (userId <= 0 || authVersion <= 0 || !validReason(reason)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        SecurityEvent event =
                new SecurityEvent(
                        UUID.randomUUID(),
                        SecurityEvent.Kind.USER_SECURITY_CHANGED,
                        new SecurityEvent.NumericId(userId),
                        authVersion + 1);
        return transactions.execute(
                event,
                connection -> {
                    try (PreparedStatement statement =
                            statement(
                                    connection,
                                    "UPDATE oauth_refresh_family SET status = 'REVOKED', "
                                            + "revoked_at = UTC_TIMESTAMP(6), revoke_reason = ?, "
                                            + "version = version + 1, update_time = UTC_TIMESTAMP(6) "
                                            + "WHERE user_id = ? AND status = 'ACTIVE'")) {
                        statement.setString(1, reason);
                        statement.setLong(2, userId);
                        statement.executeUpdate();
                    }
                });
    }

    /**
     * Revokes one family by optimistic version: the event version is the pre-read version plus one,
     * and the update only lands when the family is still ACTIVE at that version. A concurrent
     * revocation surfaces as {@code RESOURCE_VERSION_CHANGED}, not as a silent no-op.
     */
    public SecurityChangeTransaction.Receipt revokeFamily(UUID familyId, String reason) {
        if (familyId == null || !validReason(reason)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        long expected = activeVersion(familyId);
        SecurityEvent event =
                new SecurityEvent(
                        UUID.randomUUID(),
                        SecurityEvent.Kind.REFRESH_FAMILY_CHANGED,
                        new SecurityEvent.UuidId(familyId),
                        expected + 1);
        return transactions.execute(
                event,
                connection -> {
                    try (PreparedStatement statement =
                            statement(
                                    connection,
                                    "UPDATE oauth_refresh_family SET status = 'REVOKED', "
                                            + "revoked_at = UTC_TIMESTAMP(6), revoke_reason = ?, "
                                            + "version = version + 1, update_time = UTC_TIMESTAMP(6) "
                                            + "WHERE id = ? AND status = 'ACTIVE' AND version = ?")) {
                        statement.setString(1, reason);
                        statement.setString(2, familyId.toString());
                        statement.setLong(3, expected);
                        if (statement.executeUpdate() != 1) {
                            throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
                        }
                    }
                });
    }

    /**
     * Revokes one token inside its family and bumps the family version in the same transaction, so
     * snapshot readers see the family change even though the family row itself keeps its status.
     */
    public SecurityChangeTransaction.Receipt revokeToken(
            UUID familyId, byte[] tokenHash, String reason) {
        if (familyId == null
                || tokenHash == null
                || tokenHash.length != 32
                || !validReason(reason)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        long expected = activeVersion(familyId);
        SecurityEvent event =
                new SecurityEvent(
                        UUID.randomUUID(),
                        SecurityEvent.Kind.REFRESH_FAMILY_CHANGED,
                        new SecurityEvent.UuidId(familyId),
                        expected + 1);
        return transactions.execute(
                event,
                connection -> {
                    try (PreparedStatement statement =
                            statement(
                                    connection,
                                    "UPDATE oauth_refresh_token SET status = 'REVOKED', "
                                            + "update_time = UTC_TIMESTAMP(6) "
                                            + "WHERE token_hash = ? AND family_id = ? "
                                            + "AND status = 'ACTIVE'")) {
                        statement.setBytes(1, tokenHash);
                        statement.setString(2, familyId.toString());
                        if (statement.executeUpdate() != 1) {
                            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                        }
                    }
                    try (PreparedStatement statement =
                            statement(
                                    connection,
                                    "UPDATE oauth_refresh_family SET version = version + 1, "
                                            + "update_time = UTC_TIMESTAMP(6) "
                                            + "WHERE id = ? AND status = 'ACTIVE' AND version = ?")) {
                        statement.setString(1, familyId.toString());
                        statement.setLong(2, expected);
                        if (statement.executeUpdate() != 1) {
                            throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
                        }
                    }
                });
    }

    /**
     * Single-row current read on its own autocommit connection, strictly before the change
     * transaction opens: the value feeds the event version and the optimistic WHERE clause, and the
     * transaction itself re-checks it under the global lock.
     */
    private long activeVersion(UUID familyId) {
        try (Connection connection = primaryMetadata.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (PreparedStatement statement =
                    statement(
                            connection,
                            "SELECT version FROM oauth_refresh_family "
                                    + "WHERE id = ? AND status = 'ACTIVE'")) {
                statement.setString(1, familyId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                    }
                    long version = result.getLong(1);
                    if (version <= 0) {
                        throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                    }
                    return version;
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    private static boolean validReason(String reason) {
        return reason != null && !reason.isBlank() && reason.length() <= REASON_MAX;
    }

    private static PreparedStatement statement(Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(5);
            return statement;
        } catch (SQLException | RuntimeException error) {
            try {
                statement.close();
            } catch (SQLException | RuntimeException ignored) {
                // execute() sanitizes infrastructure failures; keep the original error.
            }
            throw error;
        }
    }
}
