package local.ptmcp.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Executes an already-approved MySQL request inside a read-only transaction. It exposes no public
 * SQL-string entry point and never maps a driver message into an error response.
 */
public final class MysqlReadOnlyExecutor {
    public static final int FETCH_SIZE = 100;
    private static final String SAFE_SQL_MODE =
            "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,"
                    + "ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION";
    private static final String CHECK_BASELINE =
            "SELECT @@session.time_zone, @@session.max_execution_time, "
                    + "@@session.transaction_read_only, @@autocommit";

    private final DataSourcePoolRegistry registry;
    private final ExecutorSettings settings;
    private final Consumer<ExecutionTrace> observer;

    public MysqlReadOnlyExecutor(DataSourcePoolRegistry registry) {
        this(registry, ExecutorSettings.defaults(), trace -> {});
    }

    public MysqlReadOnlyExecutor(DataSourcePoolRegistry registry, ExecutorSettings settings) {
        this(registry, settings, trace -> {});
    }

    public MysqlReadOnlyExecutor(
            DataSourcePoolRegistry registry,
            ExecutorSettings settings,
            Consumer<ExecutionTrace> observer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public ReadOnlyResult execute(
            long datasourceId, ReadOnlyRequest request, ReadOnlyCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        PoolHandle handle =
                registry.current(datasourceId)
                        .orElseThrow(
                                () -> new DomainException(ErrorCode.CONNECTION_POOL_UNAVAILABLE));
        if (handle.key().engine() != DatabaseEngine.MYSQL) {
            throw new DomainException(ErrorCode.DIALECT_CONTEXT_INVALID);
        }
        Connection connection;
        try {
            connection = handle.dataSource().getConnection();
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.CONNECTION_POOL_UNAVAILABLE);
        }
        TraceRecorder trace = new TraceRecorder();
        trace.poolGeneration(handle.generation());
        trace.record(ExecutionStage.CHECKOUT, ExecutionState.ACQUIRED);
        return run(connection, handle, request, cancellation, trace);
    }

    private ReadOnlyResult run(
            Connection connection,
            PoolHandle handle,
            ReadOnlyRequest request,
            ReadOnlyCancellation cancellation,
            TraceRecorder trace) {
        DomainException failure = null;
        QueryData data = null;
        long executeStarted = 0;
        boolean interrupted = false;
        try {
            validateBorrowed(connection, handle.key());
            configure(connection);
            trace.record(ExecutionStage.CONFIGURE, ExecutionState.CONFIGURED);
            if (cancellation.isCancelled()) {
                trace.record(ExecutionStage.CANCEL, ExecutionState.CANCELLING);
                failure = new DomainException(ErrorCode.CLIENT_CANCELLED);
            } else {
                trace.record(ExecutionStage.EXECUTE, ExecutionState.EXECUTING);
                executeStarted = System.nanoTime();
                try {
                    data = read(connection, request, cancellation);
                    trace.record(ExecutionStage.DRAIN, ExecutionState.DRAINING);
                } catch (SQLException readFailure) {
                    DomainException mapped =
                            mapExecutionFailure(readFailure, cancellation, executeStarted);
                    if (mapped.errorCode() == ErrorCode.CLIENT_CANCELLED
                            || mapped.errorCode() == ErrorCode.QUERY_TIMEOUT) {
                        trace.record(ExecutionStage.CANCEL, ExecutionState.CANCELLING);
                        interrupted = true;
                    } else {
                        trace.record(ExecutionStage.DRAIN, ExecutionState.DRAINING);
                    }
                    failure = mapped;
                }
            }
        } catch (SQLException configureFailure) {
            failure = new DomainException(ErrorCode.DRIVER_ERROR);
        } catch (DomainException domainFailure) {
            failure = domainFailure;
        }

        if (cancellation.isTainted()) {
            interrupted = true;
            if (failure == null) {
                failure = new DomainException(ErrorCode.CLIENT_CANCELLED);
            }
        }
        ExecutionState last = trace.lastState();
        if (last != ExecutionState.DRAINING && last != ExecutionState.CANCELLING) {
            trace.record(ExecutionStage.DRAIN, ExecutionState.DRAINING);
        }
        trace.record(ExecutionStage.CLEAN, ExecutionState.CLEANING);
        ExecutionState finalState;
        if (interrupted) {
            /*
             * Connector/J cannot confirm that a killed cursor fetch quiesced, and its response
             * stream stays desynchronized after such an interrupt. The design forbids returning
             * that connection, so it is aborted and the primary timeout/cancel error is reported.
             */
            handle.evict(connection);
            trace.record(ExecutionStage.EVICT, ExecutionState.EVICTED);
            finalState = ExecutionState.EVICTED;
        } else {
            boolean clean = cleanup(connection);
            if (clean) {
                clean = close(connection);
            }
            if (clean) {
                trace.record(ExecutionStage.RETURN, ExecutionState.RETURNED);
                finalState = ExecutionState.RETURNED;
            } else {
                handle.evict(connection);
                trace.record(ExecutionStage.EVICT, ExecutionState.EVICTED);
                finalState = ExecutionState.EVICTED;
                failure = new DomainException(ErrorCode.CLEANUP_FAILED);
            }
        }
        ExecutionTrace finished = trace.finish(finalState);
        observer.accept(finished);
        if (failure != null) {
            throw failure;
        }
        return new ReadOnlyResult(data.columns(), data.rows(), data.limitReached(), finished);
    }

    private static void validateBorrowed(Connection connection, PoolKey key) {
        try {
            if (!connection.getAutoCommit() || connection.isReadOnly()) {
                throw new DomainException(ErrorCode.CLEANUP_FAILED);
            }
            String catalog = connection.getCatalog();
            if (catalog == null || !catalog.equals(key.database())) {
                throw new DomainException(ErrorCode.DIALECT_CONTEXT_INVALID);
            }
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
                throw new DomainException(ErrorCode.DIALECT_CONTEXT_INVALID);
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DIALECT_CONTEXT_INVALID);
        }
    }

    private void configure(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET SESSION max_execution_time = " + settings.sqlTimeoutMillis());
            statement.execute("SET time_zone = '+00:00'");
            statement.execute("SET SESSION sql_mode = '" + SAFE_SQL_MODE + "'");
            statement.execute("SET SESSION TRANSACTION READ ONLY");
        }
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("START TRANSACTION READ ONLY");
        }
    }

    private QueryData read(
            Connection connection, ReadOnlyRequest request, ReadOnlyCancellation cancellation)
            throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.setFetchSize(FETCH_SIZE);
            statement.setMaxRows(request.maxRows());
            cancellation.install(() -> cancelQuietly(statement));
            if (cancellation.isCancelled()) {
                throw new CancelledQueryException();
            }
            try (ResultSet resultSet = statement.executeQuery(request.sql())) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                List<ReadOnlyResult.Column> columns = new ArrayList<>(columnCount);
                for (int index = 1; index <= columnCount; index++) {
                    String label = metadata.getColumnLabel(index);
                    String typeName = metadata.getColumnTypeName(index);
                    columns.add(
                            new ReadOnlyResult.Column(
                                    label == null ? "" : label,
                                    typeName == null ? "" : typeName,
                                    metadata.getColumnType(index),
                                    metadata.isNullable(index)
                                            != java.sql.ResultSetMetaData.columnNoNulls));
                }
                List<List<String>> rows = new ArrayList<>();
                while (rows.size() < request.maxRows() && resultSet.next()) {
                    if (cancellation.isCancelled()) {
                        throw new CancelledQueryException();
                    }
                    List<String> row = new ArrayList<>(columnCount);
                    for (int index = 1; index <= columnCount; index++) {
                        row.add(resultSet.getString(index));
                    }
                    rows.add(row);
                }
                if (cancellation.isCancelled()) {
                    throw new CancelledQueryException();
                }
                return new QueryData(columns, rows, rows.size() >= request.maxRows());
            }
        } finally {
            cancellation.clear(settings.cancelTimeoutMillis());
        }
    }

    private DomainException mapExecutionFailure(
            SQLException failure, ReadOnlyCancellation cancellation, long executeStartedNanos) {
        if (cancellation.isCancelled()) {
            return new DomainException(ErrorCode.CLIENT_CANCELLED);
        }
        if (failure instanceof SQLTimeoutException) {
            return new DomainException(ErrorCode.QUERY_TIMEOUT);
        }
        int code = failure.getErrorCode();
        if (code == 3024) {
            return new DomainException(ErrorCode.QUERY_TIMEOUT);
        }
        long elapsedMillis = (System.nanoTime() - executeStartedNanos) / 1_000_000;
        if ((code == 1317 || "70100".equals(failure.getSQLState()))
                && elapsedMillis >= settings.sqlTimeoutMillis()) {
            return new DomainException(ErrorCode.QUERY_TIMEOUT);
        }
        if (code == 1792
                || "25006".equals(failure.getSQLState())
                || "S1009".equals(failure.getSQLState())) {
            return new DomainException(ErrorCode.SQL_NOT_READONLY);
        }
        return new DomainException(ErrorCode.DRIVER_ERROR);
    }

    private boolean cleanup(Connection connection) {
        try {
            connection.setNetworkTimeout(Runnable::run, (int) settings.cleanupTimeoutMillis());
            connection.rollback();
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            if (connection.isReadOnly()) {
                connection.setReadOnly(false);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION max_execution_time = 0");
                statement.execute("SET time_zone = '+00:00'");
                statement.execute("SET SESSION sql_mode = '" + SAFE_SQL_MODE + "'");
                statement.execute("SET SESSION TRANSACTION READ WRITE");
            }
            if (!baseline(connection)) {
                return false;
            }
            connection.setNetworkTimeout(Runnable::run, 0);
            return true;
        } catch (SQLException failure) {
            return false;
        }
    }

    private static boolean baseline(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(CHECK_BASELINE)) {
            if (!resultSet.next()) {
                return false;
            }
            return "+00:00".equals(resultSet.getString(1))
                    && resultSet.getLong(2) == 0
                    && resultSet.getInt(3) == 0
                    && resultSet.getInt(4) == 1;
        }
    }

    private static boolean close(Connection connection) {
        try {
            connection.close();
            return true;
        } catch (SQLException failure) {
            return false;
        }
    }

    private static void cancelQuietly(Statement statement) {
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // Cancellation is best effort; execution failure decides the final code.
        }
    }

    private record QueryData(
            List<ReadOnlyResult.Column> columns, List<List<String>> rows, boolean limitReached) {}

    private static final class CancelledQueryException extends SQLException {
        private static final long serialVersionUID = 1L;

        CancelledQueryException() {
            super("cancelled", "70100", 1317);
        }
    }
}
