package local.ptmcp.persistence.audit;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.wal.RetiredBoot;

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
                    + "result, verdict, error_code, sql_masked, sql_fingerprint, sql_raw_cipher, "
                    + "sql_final_cipher, key_id, "
                    + "duration_ms, queue_ms, rows_returned, truncated, create_time, terminal_hash) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
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
                    + "sql_final_cipher = COALESCE(?, sql_final_cipher), "
                    + "key_id = COALESCE(?, key_id), terminal_hash = ? "
                    + "WHERE request_id = ? AND result = 'RUNNING'";
    private static final String SELECT_TERMINAL =
            "SELECT result, verdict, error_code, completed_at FROM audit_log WHERE request_id = ?";
    private static final String SELECT_STALE =
            "SELECT l.request_id, l.user_id, l.platform_id, l.tool, "
                    + "COALESCE(l.datasource_id, e.datasource_id), l.started_at "
                    + "FROM audit_log l JOIN execution_lease e ON e.request_id = l.request_id "
                    + "WHERE l.result = 'RUNNING' AND e.gateway_instance_id = ? AND e.boot_id = ? "
                    + "AND e.state IN ('QUEUED', 'RUNNING', 'CANCELLING') "
                    + "AND e.lease_until <= UTC_TIMESTAMP(6) "
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
        insertStart(start, null);
    }

    /** START and its process lease are committed together, before any business connection. */
    public void insertStart(AuditStart start, ExecutionOwner owner) {
        if (start == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (owner != null && start.userId() == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        AuditPayloads.Payload payload = AuditPayloads.start(start);
        transaction.execute(
                connection -> {
                    if (owner != null) {
                        lockBoot(connection, owner, false);
                    }
                    lockAuditRow(connection, start.requestId());
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
                    if (owner != null) {
                        insertLease(connection, start, owner);
                    }
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
        return transaction.execute(connection -> submitTerminal(connection, terminal, payload));
    }

    private static TerminalState submitTerminal(
            Connection connection, AuditTerminal terminal, AuditPayloads.Payload payload)
            throws SQLException {
        lockAuditRow(connection, terminal.requestId());
        boolean inserted = insertOutbox(connection, terminal.requestId(), "TERMINAL", 1, payload);
        TerminalState state =
                inserted
                        ? persistTerminal(connection, terminal, payload.hash())
                        : replayTerminal(connection, terminal, payload);
        try (PreparedStatement statement =
                AuditTransaction.statement(
                        connection,
                        "UPDATE execution_lease SET state = 'TERMINAL', version = version + 1, "
                                + "update_time = UTC_TIMESTAMP(6) WHERE request_id = ? AND state <> 'TERMINAL'")) {
            statement.setString(1, terminal.requestId().toString());
            statement.executeUpdate();
        }
        return state;
    }

    /**
     * Compatibility entry point: a cutoff alone cannot prove a process is dead. It deliberately
     * does no recovery. The directory-owner proof is required by markUnknownForRetiredBoot.
     */
    public int markUnknownIfStale(Instant staleBefore, int limit) {
        if (staleBefore == null || limit < 1 || limit > 1000) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return 0;
    }

    /** Only a new owner of the same persistent directory can recover a retired boot. */
    public int markUnknownForRetiredBoot(RetiredBoot proof, int limit) {
        return recoverRetiredBoot(proof, limit).recoveredCount();
    }

    /** The result becomes usable for filesystem cleanup only after a confirmed commit. */
    public record BootRecovery(int recoveredCount, boolean collectable) {}

    public BootRecovery recoverRetiredBoot(RetiredBoot proof, int limit) {
        if (proof == null || limit < 1 || limit > 1000) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        ExecutionOwner retired = proof.identity();
        return transaction.execute(
                connection -> {
                    proof.requireHeld();
                    lockBoot(connection, retired, true);
                    int recovered = 0;
                    for (AuditTerminal terminal : selectStale(connection, retired, limit)) {
                        if (!lockAuditRow(connection, terminal.requestId())) {
                            continue;
                        }
                        try (PreparedStatement statement =
                                AuditTransaction.statement(
                                        connection,
                                        "SELECT request_id FROM execution_lease WHERE request_id = ? "
                                                + "AND gateway_instance_id = ? AND boot_id = ? "
                                                + "AND state IN ('QUEUED', 'RUNNING', 'CANCELLING') "
                                                + "AND lease_until <= UTC_TIMESTAMP(6) FOR UPDATE")) {
                            statement.setString(1, terminal.requestId().toString());
                            statement.setString(2, retired.instanceId());
                            statement.setString(3, retired.bootId());
                            try (ResultSet result = statement.executeQuery()) {
                                if (!result.next()) {
                                    continue;
                                }
                            }
                        }
                        proof.requireHeld();
                        TerminalState state =
                                submitTerminal(
                                        connection, terminal, AuditPayloads.terminal(terminal));
                        if (!state.alreadyExisted() && !state.conflict()) {
                            recovered++;
                        }
                    }
                    proof.requireHeld();
                    return new BootRecovery(recovered, noPendingLeases(connection, retired));
                });
    }

    public void heartbeat(ExecutionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        transaction.execute(
                connection -> {
                    lockBoot(connection, owner, false);
                    try (PreparedStatement statement =
                            AuditTransaction.statement(
                                    connection,
                                    "UPDATE execution_lease SET heartbeat_at = UTC_TIMESTAMP(6), "
                                            + "lease_until = TIMESTAMPADD(SECOND, 90, UTC_TIMESTAMP(6)), "
                                            + "version = version + 1, update_time = UTC_TIMESTAMP(6) "
                                            + "WHERE gateway_instance_id = ? AND boot_id = ? "
                                            + "AND state IN ('QUEUED', 'RUNNING', 'CANCELLING')")) {
                        statement.setString(1, owner.instanceId());
                        statement.setString(2, owner.bootId());
                        statement.executeUpdate();
                    }
                    return null;
                });
    }

    public boolean confirmExecution(UUID requestId, long datasourceId, ExecutionOwner owner) {
        if (requestId == null || datasourceId <= 0 || owner == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return transaction.execute(
                connection -> {
                    lockBoot(connection, owner, false);
                    if (!lockAuditRow(connection, requestId)) {
                        return false;
                    }
                    try (PreparedStatement statement =
                            AuditTransaction.statement(
                                    connection,
                                    "UPDATE execution_lease SET datasource_id = ?, state = 'RUNNING', "
                                            + "execution_started_at = COALESCE(execution_started_at, UTC_TIMESTAMP(6)), "
                                            + "heartbeat_at = UTC_TIMESTAMP(6), "
                                            + "lease_until = TIMESTAMPADD(SECOND, 90, UTC_TIMESTAMP(6)), "
                                            + "version = version + 1, update_time = UTC_TIMESTAMP(6) "
                                            + "WHERE request_id = ? AND gateway_instance_id = ? AND boot_id = ? "
                                            + "AND state IN ('QUEUED', 'RUNNING')")) {
                        statement.setLong(1, datasourceId);
                        statement.setString(2, requestId.toString());
                        statement.setString(3, owner.instanceId());
                        statement.setString(4, owner.bootId());
                        return statement.executeUpdate() == 1;
                    }
                });
    }

    /** Owner writes and retirement serialize here, before audit/outbox/lease locks. */
    private static void lockBoot(Connection connection, ExecutionOwner owner, boolean retire)
            throws SQLException {
        try (PreparedStatement statement =
                AuditTransaction.statement(
                        connection,
                        "INSERT INTO audit_boot_owner (gateway_instance_id, boot_id, state, "
                                + "create_time, update_time) VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)) "
                                + "ON DUPLICATE KEY UPDATE boot_id = boot_id")) {
            statement.setString(1, owner.instanceId());
            statement.setString(2, owner.bootId());
            statement.setString(3, retire ? "RETIRED" : "OPEN");
            statement.executeUpdate();
        }
        String state;
        try (PreparedStatement statement =
                AuditTransaction.statement(
                        connection,
                        "SELECT state FROM audit_boot_owner WHERE gateway_instance_id = ? AND boot_id = ? FOR UPDATE")) {
            statement.setString(1, owner.instanceId());
            statement.setString(2, owner.bootId());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
                }
                state = result.getString(1);
            }
        }
        if (retire && "OPEN".equals(state)) {
            try (PreparedStatement statement =
                    AuditTransaction.statement(
                            connection,
                            "UPDATE audit_boot_owner SET state = 'RETIRED', update_time = UTC_TIMESTAMP(6) "
                                    + "WHERE gateway_instance_id = ? AND boot_id = ? AND state = 'OPEN'")) {
                statement.setString(1, owner.instanceId());
                statement.setString(2, owner.bootId());
                if (statement.executeUpdate() != 1) {
                    throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
                }
            }
        } else if (!((retire && "RETIRED".equals(state)) || (!retire && "OPEN".equals(state)))) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    private static boolean noPendingLeases(Connection connection, ExecutionOwner owner)
            throws SQLException {
        try (PreparedStatement statement =
                AuditTransaction.statement(
                        connection,
                        "SELECT request_id FROM execution_lease WHERE gateway_instance_id = ? AND boot_id = ? "
                                + "AND state <> 'TERMINAL' LIMIT 1")) {
            statement.setString(1, owner.instanceId());
            statement.setString(2, owner.bootId());
            try (ResultSet result = statement.executeQuery()) {
                return !result.next();
            }
        }
    }

    private static boolean lockAuditRow(Connection connection, UUID requestId) throws SQLException {
        try (PreparedStatement statement =
                AuditTransaction.statement(
                        connection,
                        "SELECT result FROM audit_log WHERE request_id = ? FOR UPDATE")) {
            statement.setString(1, requestId.toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && "RUNNING".equals(result.getString(1));
            }
        }
    }

    private static void insertLease(Connection connection, AuditStart start, ExecutionOwner owner)
            throws SQLException {
        try (PreparedStatement statement =
                AuditTransaction.statement(
                        connection,
                        "INSERT INTO execution_lease (request_id, user_id, gateway_instance_id, boot_id, "
                                + "state, heartbeat_at, lease_until, create_time, update_time) "
                                + "VALUES (?, ?, ?, ?, 'QUEUED', UTC_TIMESTAMP(6), "
                                + "TIMESTAMPADD(SECOND, 90, UTC_TIMESTAMP(6)), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
            statement.setString(1, start.requestId().toString());
            statement.setLong(2, start.userId());
            statement.setString(3, owner.instanceId());
            statement.setString(4, owner.bootId());
            statement.executeUpdate();
        } catch (SQLException failure) {
            if (!AuditTransaction.duplicateKey(failure)) {
                throw failure;
            }
            try (PreparedStatement existing =
                    AuditTransaction.statement(
                            connection,
                            "SELECT gateway_instance_id, boot_id FROM execution_lease WHERE request_id = ? FOR UPDATE")) {
                existing.setString(1, start.requestId().toString());
                try (ResultSet result = existing.executeQuery()) {
                    if (!result.next()
                            || !owner.instanceId().equals(result.getString(1))
                            || !owner.bootId().equals(result.getString(2))) {
                        throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
                    }
                }
            }
        }
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
            statement.setBytes(14, terminal.encryptedFinal());
            statement.setString(15, terminal.keyId());
            setNullableLong(statement, 16, terminal.durationMs());
            if (terminal.queueMs() == null) {
                statement.setNull(17, java.sql.Types.INTEGER);
            } else {
                statement.setInt(17, terminal.queueMs());
            }
            if (terminal.rowsReturned() == null) {
                statement.setNull(18, java.sql.Types.INTEGER);
            } else {
                statement.setInt(18, terminal.rowsReturned());
            }
            statement.setBoolean(19, terminal.truncated());
            statement.setBytes(20, payloadHash);
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
            statement.setBytes(15, terminal.encryptedFinal());
            statement.setString(16, terminal.keyId());
            statement.setBytes(17, payloadHash);
            statement.setString(18, terminal.requestId().toString());
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
            Connection connection, ExecutionOwner retired, int limit) throws SQLException {
        List<AuditTerminal> stale = new ArrayList<>();
        try (PreparedStatement statement = AuditTransaction.statement(connection, SELECT_STALE)) {
            statement.setString(1, retired.instanceId());
            statement.setString(2, retired.bootId());
            statement.setInt(3, limit);
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
