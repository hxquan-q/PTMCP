package local.ptmcp.persistence.schema;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED state machine over {@code metadata_sync_job}. Lease
 * claim and expiry takeover are single-row atomic transitions; the unique running-guard index
 * guarantees at most one RUNNING job per datasource.
 */
public final class MetadataSyncJobStore {
    private static final int STATEMENT_SECONDS = 5;
    private static final long MAX_LEASE_SECONDS = 300;

    private final DataSource primaryMetadata;

    public MetadataSyncJobStore(DataSource primaryMetadata) {
        this.primaryMetadata = Objects.requireNonNull(primaryMetadata, "primaryMetadata");
    }

    public long enqueue(long datasourceId, long requestedBy, long configVersion) {
        if (datasourceId <= 0 || requestedBy <= 0 || configVersion <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        LocalDateTime now = nowUtc();
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            try (PreparedStatement statement =
                    statement(
                            connection,
                            "INSERT INTO metadata_sync_job (datasource_id, requested_by, config_version, "
                                    + "state, create_time, update_time) VALUES (?, ?, ?, 'QUEUED', ?, ?)")) {
                statement.setLong(1, datasourceId);
                statement.setLong(2, requestedBy);
                statement.setLong(3, configVersion);
                statement.setObject(4, now);
                statement.setObject(5, now);
                if (statement.executeUpdate() != 1) {
                    throw new DomainException(ErrorCode.INTERNAL_ERROR);
                }
            }
            return lastId(connection);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Atomic QUEUED→RUNNING claim; empty when no queued job exists. */
    public long claimQueued(long datasourceId, String leaseOwner, int leaseSeconds) {
        requireLease(leaseOwner, leaseSeconds);
        LocalDateTime now = nowUtc();
        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            connection.setAutoCommit(false);
            long jobId;
            try (PreparedStatement select =
                    statement(
                            connection,
                            "SELECT id FROM metadata_sync_job "
                                    + "WHERE datasource_id = ? AND state = 'QUEUED' "
                                    + "ORDER BY id LIMIT 1 FOR UPDATE")) {
                select.setLong(1, datasourceId);
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        return -1;
                    }
                    jobId = result.getLong(1);
                }
            }
            try (PreparedStatement update =
                    statement(
                            connection,
                            "UPDATE metadata_sync_job SET state = 'RUNNING', lease_owner = ?, "
                                    + "lease_until = ?, started_at = ?, update_time = ? "
                                    + "WHERE id = ? AND state = 'QUEUED'")) {
                statementBindLease(update, leaseOwner, leaseUntil, now, jobId, true);
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return -1;
                }
            }
            connection.commit();
            return jobId;
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Expiry takeover. Allowed only when the previous RUNNING job provably published nothing: its
     * {@code snapshot_id} is unset and no snapshot was captured after the job started. A lost
     * post-publish job finalization is recovered to SUCCEEDED instead of taken over.
     */
    public TakeOver takeOverExpired(long jobId, String leaseOwner, int leaseSeconds) {
        requireLease(leaseOwner, leaseSeconds);
        LocalDateTime now = nowUtc();
        LocalDateTime leaseUntil = now.plusSeconds(leaseSeconds);
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            connection.setAutoCommit(false);
            long datasourceId;
            LocalDateTime startedAt;
            try (PreparedStatement select =
                    statement(
                            connection,
                            "SELECT datasource_id, started_at FROM metadata_sync_job "
                                    + "WHERE id = ? AND state = 'RUNNING' AND lease_until < ? "
                                    + "AND snapshot_id IS NULL FOR UPDATE")) {
                select.setLong(1, jobId);
                select.setObject(2, now);
                try (ResultSet result = select.executeQuery()) {
                    if (!result.next()) {
                        connection.rollback();
                        return TakeOver.NOT_TAKEN;
                    }
                    datasourceId = result.getLong(1);
                    startedAt = result.getObject(2, LocalDateTime.class);
                }
            }
            boolean published = publishedAfter(connection, datasourceId, startedAt);
            if (published) {
                try (PreparedStatement finish =
                        statement(
                                connection,
                                "UPDATE metadata_sync_job SET state = 'SUCCEEDED', finished_at = ?, "
                                        + "snapshot_id = (SELECT active_snapshot_id FROM datasource WHERE id = ?), "
                                        + "update_time = ? WHERE id = ? AND state = 'RUNNING'")) {
                    finish.setObject(1, now);
                    finish.setLong(2, datasourceId);
                    finish.setObject(3, now);
                    finish.setLong(4, jobId);
                    finish.executeUpdate();
                }
                connection.commit();
                return TakeOver.RECOVERED_PUBLISHED;
            }
            try (PreparedStatement update =
                    statement(
                            connection,
                            "UPDATE metadata_sync_job SET lease_owner = ?, lease_until = ?, "
                                    + "update_time = ? WHERE id = ? AND state = 'RUNNING'")) {
                statementBindLease(update, leaseOwner, leaseUntil, now, jobId, false);
                if (update.executeUpdate() != 1) {
                    connection.rollback();
                    return TakeOver.NOT_TAKEN;
                }
            }
            connection.commit();
            return TakeOver.TAKEN;
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public boolean heartbeat(long jobId, String leaseOwner, int leaseSeconds) {
        requireLease(leaseOwner, leaseSeconds);
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            try (PreparedStatement statement =
                    statement(
                            connection,
                            "UPDATE metadata_sync_job SET lease_until = ?, update_time = ? "
                                    + "WHERE id = ? AND state = 'RUNNING' AND lease_owner = ?")) {
                statement.setObject(1, nowUtc().plusSeconds(leaseSeconds));
                statement.setObject(2, nowUtc());
                statement.setLong(3, jobId);
                statement.setString(4, leaseOwner);
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException error) {
            return false;
        }
    }

    public void finish(
            long jobId, String leaseOwner, Outcome outcome, Long snapshotId, String errorCode) {
        requireLease(leaseOwner, 1);
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            try (PreparedStatement statement =
                    statement(
                            connection,
                            "UPDATE metadata_sync_job SET state = ?, finished_at = ?, snapshot_id = ?, "
                                    + "error_code = ?, lease_owner = NULL, lease_until = NULL, update_time = ? "
                                    + "WHERE id = ? AND state = 'RUNNING' AND lease_owner = ?")) {
                statement.setString(1, outcome.name());
                statement.setObject(2, nowUtc());
                if (snapshotId == null) {
                    statement.setNull(3, java.sql.Types.BIGINT);
                } else {
                    statement.setLong(3, snapshotId);
                }
                statement.setString(4, errorCode);
                statement.setObject(5, nowUtc());
                statement.setLong(6, jobId);
                statement.setString(7, leaseOwner);
                if (statement.executeUpdate() != 1) {
                    throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Cancel from QUEUED only; a RUNNING job keeps its lease and cannot be raced away. */
    public boolean cancelQueued(long jobId, long datasourceId) {
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            try (PreparedStatement statement =
                    statement(
                            connection,
                            "UPDATE metadata_sync_job SET state = 'CANCELLED', finished_at = ?, "
                                    + "update_time = ? WHERE id = ? AND datasource_id = ? AND state = 'QUEUED'")) {
                statement.setObject(1, nowUtc());
                statement.setObject(2, nowUtc());
                statement.setLong(3, jobId);
                statement.setLong(4, datasourceId);
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public record JobRow(
            long id,
            long datasourceId,
            long configVersion,
            String state,
            String leaseOwner,
            Long snapshotId) {}

    public JobRow get(long jobId) {
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            try (PreparedStatement statement =
                    statement(
                            connection,
                            "SELECT id, datasource_id, config_version, state, lease_owner, snapshot_id "
                                    + "FROM metadata_sync_job WHERE id = ?")) {
                statement.setLong(1, jobId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                    }
                    long snapshotId = result.getLong(6);
                    Long snapshot = result.wasNull() ? null : snapshotId;
                    return new JobRow(
                            result.getLong(1),
                            result.getLong(2),
                            result.getLong(3),
                            result.getString(4),
                            result.getString(5),
                            snapshot);
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static boolean publishedAfter(
            Connection connection, long datasourceId, LocalDateTime startedAt) throws SQLException {
        if (startedAt == null) {
            return false;
        }
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT COUNT(*) FROM schema_snapshot "
                                + "WHERE datasource_id = ? AND status = 'PUBLISHED' AND captured_at > ?")) {
            statement.setLong(1, datasourceId);
            statement.setObject(2, startedAt);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getLong(1) > 0;
            }
        }
    }

    /** Terminal job states written by {@link #finish}. */
    public enum Outcome {
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    /** Takeover result of {@link #takeOverExpired(long, String, int)}. */
    public enum TakeOver {
        TAKEN,
        NOT_TAKEN,
        RECOVERED_PUBLISHED
    }

    private static void statementBindLease(
            PreparedStatement update,
            String leaseOwner,
            LocalDateTime leaseUntil,
            LocalDateTime now,
            long jobId,
            boolean claimColumns)
            throws SQLException {
        update.setString(1, leaseOwner);
        update.setObject(2, leaseUntil);
        update.setObject(3, now);
        if (claimColumns) {
            update.setObject(4, now);
            update.setLong(5, jobId);
        } else {
            update.setLong(4, jobId);
        }
    }

    private static void requireLease(String leaseOwner, int leaseSeconds) {
        if (leaseOwner == null
                || leaseOwner.isBlank()
                || leaseOwner.length() > 128
                || leaseSeconds < 1
                || leaseSeconds > MAX_LEASE_SECONDS) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static long lastId(Connection connection) throws SQLException {
        try (PreparedStatement statement = statement(connection, "SELECT LAST_INSERT_ID()");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return result.getLong(1);
        }
    }

    private static void configure(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        connection.setReadOnly(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setNetworkTimeout(Runnable::run, 20_000);
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
                // Preserve the original error.
            }
            throw error;
        }
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}
