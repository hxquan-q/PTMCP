package local.ptmcp.persistence.audit;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Dedicated primary-metadata write transaction for audit admission and termination. It never joins
 * an ambient transaction, never retries a commit and never lets SQL or driver text escape.
 */
final class AuditTransaction {
    private static final int STATEMENT_SECONDS = 5;
    private final DataSource primaryMetadata;

    AuditTransaction(DataSource primaryMetadata) {
        this.primaryMetadata = Objects.requireNonNull(primaryMetadata, "primaryMetadata");
    }

    <T> T execute(Work<T> work) {
        if (work == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Connection connection = null;
        boolean transactionStarted = false;
        boolean commitAttempted = false;
        boolean committed = false;
        DomainException failure = null;
        T result = null;
        try {
            connection = primaryMetadata.getConnection();
            configurePrimaryWrite(connection);
            connection.setAutoCommit(false);
            transactionStarted = true;
            result = work.apply(connection);
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
                    // The durable outcome is already decided; cleanup failure is not a replay.
                    try {
                        connection.abort(Runnable::run);
                    } catch (SQLException | RuntimeException ignored) {
                        // No infrastructure text crosses this seam.
                    }
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return result;
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
        try (PreparedStatement statement = statement(connection, "SET time_zone = '+00:00'")) {
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                statement(connection, "SET SESSION innodb_lock_wait_timeout = 5")) {
            statement.executeUpdate();
        }
    }

    static PreparedStatement statement(Connection connection, String sql) throws SQLException {
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

    static boolean duplicateKey(SQLException error) {
        return error.getErrorCode() == 1062 && "23000".equals(error.getSQLState());
    }

    @FunctionalInterface
    interface Work<T> {
        T apply(Connection connection) throws SQLException;
    }
}
