package local.ptmcp.admin.audit;

import local.ptmcp.admin.datasource.AuditCipher;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Sensitive audit reveal: decrypts the stored SQL ciphers for one request and records every
 * disclosure in {@code audit_sensitive_access} inside the same transaction as the read. A reason of
 * at least ten characters is mandatory, the plaintext carries a short expiry the console must
 * honor, and rows without stored ciphers disclose nothing.
 */
public final class AuditDecryptService {
    private static final int MIN_REASON = 10;
    private static final int MAX_REASON = 500;
    private static final Set<String> FIELDS = Set.of("sqlRaw", "sqlFinal");
    private static final long REVEAL_SECONDS = 300;

    /** ASVS 4.2.1: bounded plaintext reveal throughput per operator, sliding one-minute window. */
    private static final int MAX_REVEALS_PER_MINUTE = 10;

    private final DataSource primary;
    private final AuditCipher cipher;

    public AuditDecryptService(DataSource primary, AuditCipher cipher) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    public record Reveal(String sqlRaw, String sqlFinal, Instant expiresAt) {}

    public Reveal decrypt(
            String requestId, String reason, java.util.List<String> fields, long operatorId) {
        if (operatorId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (reason == null || reason.trim().length() < MIN_REASON || reason.length() > MAX_REASON) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Set<String> wanted = new LinkedHashSet<>();
        for (String field : fields == null ? java.util.Collections.<String>emptyList() : fields) {
            if (field == null || !FIELDS.contains(field)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            wanted.add(field);
        }
        if (wanted.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String normalizedRequest = requestId == null ? "" : requestId.trim();
        if (normalizedRequest.length() != 36) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        requireRevealBudget(operatorId);
        try (Connection connection = primary.getConnection()) {
            connection.setAutoCommit(false);
            try {
                byte[] rawCipher = null;
                byte[] finalCipher = null;
                String keyId = null;
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT sql_raw_cipher, sql_final_cipher, key_id FROM audit_log"
                                        + " WHERE request_id = ?")) {
                    statement.setString(1, normalizedRequest);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                        }
                        rawCipher = result.getBytes(1);
                        finalCipher = result.getBytes(2);
                        keyId = result.getString(3);
                    }
                }
                String sqlRaw =
                        wanted.contains("sqlRaw") && rawCipher != null
                                ? cipher.decrypt(rawCipher, keyId)
                                : null;
                String sqlFinal =
                        wanted.contains("sqlFinal") && finalCipher != null
                                ? cipher.decrypt(finalCipher, keyId)
                                : null;
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO audit_sensitive_access (request_id, operator_id, reason,"
                                        + " fields, decision, create_time)"
                                        + " VALUES (?, ?, ?, ?, 'ALLOW', UTC_TIMESTAMP(6))")) {
                    statement.setString(1, normalizedRequest);
                    statement.setLong(2, operatorId);
                    statement.setString(3, reason.trim());
                    statement.setString(4, "[\"" + String.join("\",\"", wanted) + "\"]");
                    statement.executeUpdate();
                }
                connection.commit();
                return new Reveal(sqlRaw, sqlFinal, Instant.now().plusSeconds(REVEAL_SECONDS));
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Sliding-window throughput bound counted over the durable trail itself (every attempt lands in
     * audit_sensitive_access, so the count IS the audit record — no separate state to drain or
     * lose). A soft limit: a burst just over the line may slip through concurrent calls. Counts
     * every attempt so failed reveals cannot be used to probe around the bound.
     */
    private void requireRevealBudget(long operatorId) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM audit_sensitive_access"
                                        + " WHERE operator_id = ?"
                                        + " AND create_time > UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE")) {
            statement.setQueryTimeout(5);
            statement.setLong(1, operatorId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next() && result.getInt(1) >= MAX_REVEALS_PER_MINUTE) {
                    throw new DomainException(ErrorCode.SENSITIVE_REVEAL_LIMIT);
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
