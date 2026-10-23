package local.ptmcp.persistence;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Package-only seam for trusted metadata repository commands, never an arbitrary-SQL application
 * interface. The dedicated DataSource must lend a fresh primary-metadata connection, not an ambient
 * transaction or a business connection. Every command locks the global row before repository rows.
 *
 * <p>Commands must use bounded DML on this connection, enforce their expected aggregate version and
 * follow db-design §7 lock ordering. They must not commit, roll back, close, change connection
 * state, execute DDL, retain the connection or call network/Redis. Only a confirmed receipt permits
 * external post-commit work. This is a trusted-code contract, not a sandbox for arbitrary
 * callbacks.
 */
final class SecurityChangeTransaction {
    private static final int STATEMENT_SECONDS = 5;
    private final DataSource primaryMetadata;

    SecurityChangeTransaction(DataSource primaryMetadata) {
        this.primaryMetadata = Objects.requireNonNull(primaryMetadata, "primaryMetadata");
    }

    Receipt execute(SecurityEvent event, Change change) {
        if (event == null || change == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitAttempted = false;
        boolean committed = false;
        boolean cleanupFailed = false;
        long epoch = 0;
        DomainException failure = null;
        try {
            connection = primaryMetadata.getConnection();
            configurePrimaryWrite(connection);
            connection.setAutoCommit(false);
            transactionStarted = true;
            epoch = nextEpochWithGlobalLock(connection);
            change.apply(connection);
            advanceEpoch(connection, epoch);
            insertEvent(connection, event, epoch);
            // From this point any failure can mean that the server committed but the ack was lost.
            commitAttempted = true;
            connection.commit();
            committed = true;
        } catch (SQLException error) {
            failure =
                    new DomainException(
                            commitAttempted
                                    ? ErrorCode.OPERATION_UNKNOWN
                                    : ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (RuntimeException error) {
            failure =
                    new DomainException(
                            commitAttempted
                                    ? ErrorCode.OPERATION_UNKNOWN
                                    : error instanceof DomainException domain
                                            ? domain.errorCode()
                                            : ErrorCode.INTERNAL_ERROR);
        } finally {
            if (connection != null) {
                // Also rolls back when a callback throws Error; such Errors are not turned into
                // success.
                if (!committed && transactionStarted) {
                    try {
                        connection.rollback();
                    } catch (SQLException | RuntimeException error) {
                        failure = new DomainException(ErrorCode.OPERATION_UNKNOWN);
                    }
                }
                try {
                    connection.close();
                } catch (SQLException | RuntimeException error) {
                    cleanupFailed = true;
                    // Best effort discard, not a retry and never a change to the commit outcome.
                    try {
                        connection.abort(Runnable::run);
                    } catch (SQLException | RuntimeException ignored) {
                        // No infrastructure text/cause/suppressed exceptions cross this seam.
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return new Receipt(event.eventId(), epoch, cleanupFailed);
    }

    private static void configurePrimaryWrite(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        connection.setReadOnly(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try (PreparedStatement statement =
                        statement(
                                connection, "SELECT @@global.read_only, @@global.super_read_only");
                ResultSet result = statement.executeQuery()) {
            if (!result.next() || result.getBoolean(1) || result.getBoolean(2)) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
        }
        // Fixed server-owned session settings. Dedicated pool connections intentionally use UTC.
        try (PreparedStatement statement = statement(connection, "SET time_zone = '+00:00'")) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                statement(connection, "SET SESSION innodb_lock_wait_timeout = 5")) {
            statement.executeUpdate();
        }
    }

    private static long nextEpochWithGlobalLock(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                        statement(
                                connection,
                                "SELECT policy_epoch FROM sys_security_state WHERE singleton_id = 1 FOR UPDATE");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            long previous = result.getLong(1);
            if (previous <= 0 || previous == Long.MAX_VALUE || result.next()) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            return previous + 1;
        }
    }

    private static void advanceEpoch(Connection connection, long epoch) throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "UPDATE sys_security_state SET policy_epoch = ?, update_time = UTC_TIMESTAMP(6) "
                                + "WHERE singleton_id = 1 AND policy_epoch = ?")) {
            statement.setLong(1, epoch);
            statement.setLong(2, epoch - 1);
            if (statement.executeUpdate() != 1) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
        }
    }

    private static void insertEvent(Connection connection, SecurityEvent event, long epoch)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "INSERT INTO sys_security_event (event_id, epoch, aggregate_type, aggregate_id, event_type, "
                                + "payload, occurred_at, create_time, update_time) "
                                + "VALUES (?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
            statement.setString(1, event.eventId().toString());
            statement.setLong(2, epoch);
            statement.setString(3, event.kind().aggregateType);
            statement.setString(4, event.aggregateId().text());
            statement.setString(5, event.kind().name());
            statement.setString(6, event.payload());
            if (statement.executeUpdate() != 1) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
        } catch (SQLException error) {
            if (error.getErrorCode() == 1062 && "23000".equals(error.getSQLState())) {
                throw new DomainException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            throw error;
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
                // Preserve the original error. execute() sanitizes all infrastructure failures.
            }
            throw error;
        }
    }

    @FunctionalInterface
    interface Change {
        void apply(Connection connection) throws SQLException;
    }

    /**
     * A confirmed commit; cleanupFailed requires operational attention, not replay of the command.
     */
    record Receipt(UUID eventId, long epoch, boolean cleanupFailed) {}
}
