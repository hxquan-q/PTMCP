package local.ptmcp.persistence.audit;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Durable audit admission and termination on the primary metadata database.
 *
 * <p>START owns the request identity: a second START is accepted only with the same payload, and a
 * terminal never regresses a stored outcome. TERMINAL may arrive first with full identity; a late
 * START may then fill blanks but cannot overwrite result, verdict or already-known identity.
 *
 * <p>All statements are bounded, explicit-column, parameterized DML. Infrastructure failures are
 * mapped to registered codes without returning SQL text, connection strings or driver messages.
 */
public final class AuditRepository {
    private static final String INSERT_OUTBOX =
            "INSERT INTO audit_outbox "
                    + "(event_id, request_id, event_kind, seq, event_payload, payload_hash, create_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6))";
    private static final String SELECT_OUTBOX_HASH =
            "SELECT payload_hash FROM audit_outbox WHERE request_id = ? AND event_kind = ?";
    private static final String INSERT_START_LOG =
            "INSERT INTO audit_log "
                    + "(request_id, user_id, platform_id, tool, started_at, result, verdict, raw_length, "
                    + "truncated, create_time) "
                    + "VALUES (?, ?, ?, ?, ?, 'RUNNING', 'UNKNOWN', ?, 0, UTC_TIMESTAMP(6))";
    private static final String FILL_START_LOG =
            "UPDATE audit_log SET user_id = COALESCE(user_id, ?), "
                    + "platform_id = COALESCE(platform_id, ?), raw_length = COALESCE(raw_length, ?) "
                    + "WHERE request_id = ?";
    private static final String INSERT_TERMINAL_LOG =
            "INSERT INTO audit_log "
                    + "(request_id, user_id, platform_id, tool, datasource_id, started_at, completed_at, "
                    + "result, verdict, error_code, sql_masked, sql_fingerprint, sql_raw_cipher, key_id, "
                    + "duration_ms, queue_ms, rows_returned, truncated, create_time, terminal_hash) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                    + "UTC_TIMESTAMP(6), ?)";
    private static final String UPDATE_TERMINAL_LOG =
            "UPDATE audit_log SET user_id = COALESCE(user_id, ?), "
                    + "platform_id = COALESCE(platform_id, ?), "
                    + "datasource_id = COALESCE(datasource_id, ?), "
                    + "completed_at = ?, result = ?, verdict = ?, error_code = ?, "
                    + "duration_ms = ?, queue_ms = ?, rows_returned = ?, truncated = ?, "
                    + "sql_masked = COALESCE(?, sql_masked), "
                    + "sql_fingerprint = COALESCE(?, sql_fingerprint), "
                    + "sql_raw_cipher = COALESCE(?, sql_raw_cipher), "
                    + "key_id = COALESCE(?, key_id), terminal_hash = ? "
                    + "WHERE request_id = ? AND result = 'RUNNING'";
    private static final String SELECT_TERMINAL =
            "SELECT result, verdict, error_code, completed_at FROM audit_log WHERE request_id = ?";
    private static final String SELECT_STALE =
            "SELECT l.request_id, l.user_id, l.platform_id, l.tool, l.datasource_id, l.started_at "
                    + "FROM audit_log l "
                    + "WHERE l.result = 'RUNNING' AND l.started_at <= ? "
                    + "AND NOT EXISTS (SELECT 1 FROM execution_lease e "
                    + "WHERE e.request_id = l.request_id "
                    + "AND e.state IN ('QUEUED', 'RUNNING', 'CANCELLING') "
                    + "AND e.lease_until > UTC_TIMESTAMP(6)) "
                    + "ORDER BY l.started_at, l.request_id LIMIT ?";

    private final AuditTransaction transaction;

    public AuditRepository(DataSource primaryMetadata) {
        this.transaction =
                new AuditTransaction(Objects.requireNonNull(primaryMetadata, "primaryMetadata"));
    }

    /**
     * Persists the minimal START or accepts an identical replay; a changed payload is a conflict.
     */
    public void insertStart(AuditStart start) {
        if (start == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        AuditPayloads.Payload payload = AuditPayloads.start(start);
        transaction.execute(
                connection -> {
                    boolean inserted =
                            insertOutbox(connection, start.requestId(), "START", 0, payload);
                    if (!inserted) {
                        byte[] existing = existingHash(connection, start.requestId(), "START");
                        if (existing == null) {
                            // A duplicate event_id without this request's START: internal mismatch.
                            throw new DomainException(ErrorCode.INTERNAL_ERROR);
                        }
                        if (!AuditPayloads.sameHash(payload.hash(), existing)) {
                            throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                        }
                    }
                    materializeStart(connection, start);
                    return null;
                });
    }

    /**
     * Persists the single terminal outcome and returns only after commit. A replayed outcome
     * returns the stored state without overwriting it; a conflicting outcome is reported as such
     * while the first durable ending remains authoritative.
     */
    public TerminalState submitTerminal(AuditTerminal terminal) {
        if (terminal == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        AuditPayloads.Payload payload = AuditPayloads.terminal(terminal);
        return transaction.execute(
                connection -> {
                    boolean inserted =
                            insertOutbox(connection, terminal.requestId(), "TERMINAL", 1, payload);
                    if (!inserted) {
                        return replayTerminal(connection, terminal, payload);
                    }
                    return persistTerminal(connection, terminal, payload.hash());
                });
    }

    /**
     * Recovers START-only rows as UNKNOWN when the cutoff has passed and no execution lease is
     * still active. This is a recovery primitive; it must not be called for in-flight work.
     */
    public int markUnknownIfStale(Instant staleBefore, int limit) {
        if (staleBefore == null || limit < 1 || limit > 1000) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        List<AuditTerminal> stale =
                transaction.execute(connection -> selectStale(connection, staleBefore, limit));
        int recovered = 0;
        for (AuditTerminal terminal : stale) {
            TerminalState state = submitTerminal(terminal);
            if (!state.alreadyExisted() && !state.conflict()) {
                recovered++;
            }
        }
        return recovered;
    }

    /**
     * Confirms the durable START row for a request exists. The tool path calls this after the guard
     * passed and before a business connection is borrowed, so an admission that silently
     * disappeared can never proceed to execution.
     */
    public boolean confirmStart(UUID requestId) {
        if (requestId == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return transaction.execute(
                connection -> {
                    try (PreparedStatement statement =
                            AuditTransaction.statement(
                                    connection,
                                    "SELECT request_id FROM audit_log WHERE request_id = ?")) {
                        statement.setString(1, requestId.toString());
                        try (ResultSet result = statement.executeQuery()) {
                            return result.next();
                        }
                    }
                });
    }

    private static boolean insertOutbox(
            Connection connection,
            UUID requestId,
            String eventKind,
            int seq,
            AuditPayloads.Payload payload)
            throws SQLException {
        try (PreparedStatement statement = AuditTransaction.statement(connection, INSERT_OUTBOX)) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, requestId.toString());
            statement.setString(3, eventKind);
            statement.setInt(4, seq);
            statement.setBytes(5, payload.bytes());
            statement.setBytes(6, payload.hash());
            return statement.executeUpdate() == 1;
        } catch (SQLException error) {
            if (AuditTransaction.duplicateKey(error)) {
                return false;
            }
            throw error;
        }
    }

    private static byte[] existingHash(Connection connection, UUID requestId, String eventKind)
            throws SQLException {
        try (PreparedStatement statement =
                AuditTransaction.statement(connection, SELECT_OUTBOX_HASH)) {
            statement.setString(1, requestId.toString());
            statement.setString(2, eventKind);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getBytes(1) : null;
            }
        }
    }

    private static void materializeStart(Connection connection, AuditStart start)
            throws SQLException {
        try (PreparedStatement statement =
                AuditTransaction.statement(connection, INSERT_START_LOG)) {
            statement.setString(1, start.requestId().toString());
            setNullableLong(statement, 2, start.userId());
            setNullableLong(statement, 3, start.platformId());
            statement.setString(4, start.tool());
            statement.setObject(5, utc(start.startedAt()));
            if (start.rawLength() == null) {
                statement.setNull(6, java.sql.Types.INTEGER);
            } else {
                statement.setInt(6, start.rawLength());
            }
            statement.executeUpdate();
        } catch (SQLException error) {
            if (!AuditTransaction.duplicateKey(error)) {
                throw error;
            }
            try (PreparedStatement statement =
                    AuditTransaction.statement(connection, FILL_START_LOG)) {
                setNullableLong(statement, 1, start.userId());
                setNullableLong(statement, 2, start.platformId());
                if (start.rawLength() == null) {
                    statement.setNull(3, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(3, start.rawLength());
                }
                statement.setString(4, start.requestId().toString());
                statement.executeUpdate();
            }
        }
    }

    private static TerminalState replayTerminal(
            Connection connection, AuditTerminal terminal, AuditPayloads.Payload payload)
            throws SQLException {
        byte[] existing = existingHash(connection, terminal.requestId(), "TERMINAL");
        if (existing == null) {
            // The duplicate event_id belongs to no row for this request: internal corruption.
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        TerminalState state = readTerminal(connection, terminal.requestId());
        if (state == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        if (state.result() == AuditTerminal.Result.RUNNING) {
            if (!AuditPayloads.sameHash(payload.hash(), existing)) {
                // Another ending exists but has not been materialized; never overwrite it blind.
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return persistTerminal(connection, terminal, payload.hash());
        }
        return new TerminalState(
                state.requestId(),
                state.result(),
                state.verdict(),
                state.errorCode(),
                state.completedAt(),
                true,
                !AuditPayloads.sameHash(payload.hash(), existing));
    }

    private static TerminalState persistTerminal(
            Connection connection, AuditTerminal terminal, byte[] payloadHash) throws SQLException {
        Instant completedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        boolean inserted;
        try (PreparedStatement statement =
                AuditTransaction.statement(connection, INSERT_TERMINAL_LOG)) {
            statement.setString(1, terminal.requestId().toString());
            setNullableLong(statement, 2, terminal.userId());
            setNullableLong(statement, 3, terminal.platformId());
            statement.setString(4, terminal.tool());
            setNullableLong(statement, 5, terminal.datasourceId());
            statement.setObject(6, utc(terminal.startedAt()));
            statement.setObject(7, utc(completedAt));
            statement.setString(8, terminal.result().name());
            statement.setString(9, terminal.verdict().name());
            statement.setString(10, terminal.errorCode());
            statement.setString(11, terminal.maskedSql());
            statement.setBytes(12, terminal.sqlFingerprint());
            statement.setBytes(13, terminal.encryptedRaw());
            statement.setString(14, terminal.keyId());
            setNullableLong(statement, 15, terminal.durationMs());
            if (terminal.queueMs() == null) {
                statement.setNull(16, java.sql.Types.INTEGER);
            } else {
                statement.setInt(16, terminal.queueMs());
            }
            if (terminal.rowsReturned() == null) {
                statement.setNull(17, java.sql.Types.INTEGER);
            } else {
                statement.setInt(17, terminal.rowsReturned());
            }
            statement.setBoolean(18, terminal.truncated());
            statement.setBytes(19, payloadHash);
            inserted = statement.executeUpdate() == 1;
        } catch (SQLException error) {
            if (!AuditTransaction.duplicateKey(error)) {
                throw error;
            }
            inserted = false;
        }
        if (inserted) {
            return new TerminalState(
                    terminal.requestId(),
                    terminal.result(),
                    terminal.verdict(),
                    terminal.errorCode(),
                    completedAt,
                    false,
                    false);
        }
        int updated;
        try (PreparedStatement statement =
                AuditTransaction.statement(connection, UPDATE_TERMINAL_LOG)) {
            setNullableLong(statement, 1, terminal.userId());
            setNullableLong(statement, 2, terminal.platformId());
            setNullableLong(statement, 3, terminal.datasourceId());
            statement.setObject(4, utc(completedAt));
            statement.setString(5, terminal.result().name());
            statement.setString(6, terminal.verdict().name());
            statement.setString(7, terminal.errorCode());
            setNullableLong(statement, 8, terminal.durationMs());
            if (terminal.queueMs() == null) {
                statement.setNull(9, java.sql.Types.INTEGER);
            } else {
                statement.setInt(9, terminal.queueMs());
            }
            if (terminal.rowsReturned() == null) {
                statement.setNull(10, java.sql.Types.INTEGER);
            } else {
                statement.setInt(10, terminal.rowsReturned());
            }
            statement.setBoolean(11, terminal.truncated());
            statement.setString(12, terminal.maskedSql());
            statement.setBytes(13, terminal.sqlFingerprint());
            statement.setBytes(14, terminal.encryptedRaw());
            statement.setString(15, terminal.keyId());
            statement.setBytes(16, payloadHash);
            statement.setString(17, terminal.requestId().toString());
            updated = statement.executeUpdate();
        }
        TerminalState state = readTerminal(connection, terminal.requestId());
        if (state == null) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        boolean alreadyExisted = updated == 0;
        return new TerminalState(
                state.requestId(),
                state.result(),
                state.verdict(),
                state.errorCode(),
                state.completedAt(),
                alreadyExisted,
                alreadyExisted
                        && (state.result() != terminal.result()
                                || !Objects.equals(state.errorCode(), terminal.errorCode())));
    }

    private static TerminalState readTerminal(Connection connection, UUID requestId)
            throws SQLException {
        try (PreparedStatement statement =
                AuditTransaction.statement(connection, SELECT_TERMINAL)) {
            statement.setString(1, requestId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                LocalDateTime completedAt = result.getObject(4, LocalDateTime.class);
                return new TerminalState(
                        requestId,
                        AuditTerminal.Result.valueOf(result.getString(1)),
                        AuditTerminal.Verdict.valueOf(result.getString(2)),
                        result.getString(3),
                        completedAt == null ? null : completedAt.toInstant(ZoneOffset.UTC),
                        false,
                        false);
            }
        }
    }

    private static List<AuditTerminal> selectStale(
            Connection connection, Instant staleBefore, int limit) throws SQLException {
        List<AuditTerminal> stale = new ArrayList<>();
        try (PreparedStatement statement = AuditTransaction.statement(connection, SELECT_STALE)) {
            statement.setObject(1, utc(staleBefore));
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    stale.add(
                            new AuditTerminal(
                                    UUID.fromString(result.getString(1)),
                                    AuditTerminal.Result.UNKNOWN,
                                    AuditTerminal.Verdict.UNKNOWN,
                                    "EXECUTION_OUTCOME_UNKNOWN",
                                    null,
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    result.getString(4),
                                    nullableLong(result, 2),
                                    nullableLong(result, 3),
                                    nullableLong(result, 5),
                                    result.getObject(6, LocalDateTime.class)
                                            .toInstant(ZoneOffset.UTC)));
                }
            }
        }
        return stale;
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static Long nullableLong(ResultSet result, int index) throws SQLException {
        long value = result.getLong(index);
        return result.wasNull() ? null : value;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * A persisted ending. {@code alreadyExisted} means this call replayed or lost a race; {@code
     * conflict} means the durable ending differs from the submitted payload.
     */
    public record TerminalState(
            UUID requestId,
            AuditTerminal.Result result,
            AuditTerminal.Verdict verdict,
            String errorCode,
            Instant completedAt,
            boolean alreadyExisted,
            boolean conflict) {}
}
