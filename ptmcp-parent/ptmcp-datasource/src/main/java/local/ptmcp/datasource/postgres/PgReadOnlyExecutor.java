package local.ptmcp.datasource.postgres;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.datasource.ExecutionStage;
import local.ptmcp.datasource.ExecutionState;
import local.ptmcp.datasource.ExecutionTrace;
import local.ptmcp.datasource.ReadOnlyCancellation;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import javax.sql.DataSource;

/**
 * Executes an already-approved PostgreSQL request inside a read-only transaction. The protocol is
 * ordered exactly as the datasource contract requires: clean checkout, read-only flag, manual
 * transaction, explicit BEGIN READ ONLY, transaction-local search path and timeouts, verification,
 * execution, then rollback and session-baseline restoration. Setup failures never return the
 * connection to the pool.
 *
 * <p>This class exposes no string-SQL entry point; the request factory is package-private. The
 * supplied DataSource must lend clean primary-business connections with auto-commit on and
 * read-only off. Cancellation is observed cooperatively between rows; a cancelled execution is
 * evicted rather than returned.
 */
public final class PgReadOnlyExecutor {
    public static final int FETCH_SIZE = 100;
    public static final long MAX_RESPONSE_BYTES = 1_048_576;
    private static final String SESSION_STATE_SQL =
            "SELECT current_setting('search_path'), "
                    + "(SELECT setting FROM pg_settings WHERE name = 'statement_timeout'), "
                    + "(SELECT setting FROM pg_settings WHERE name = 'lock_timeout'), "
                    + "current_setting('transaction_read_only'), "
                    + "current_setting('default_transaction_read_only')";

    private final DataSource dataSource;
    private final PgExecutorSettings settings;
    private final long responseBudgetBytes;
    private final Consumer<ExecutionTrace> observer;

    public PgReadOnlyExecutor(DataSource dataSource) {
        this(dataSource, PgExecutorSettings.defaults(), MAX_RESPONSE_BYTES, trace -> {});
    }

    public PgReadOnlyExecutor(DataSource dataSource, PgExecutorSettings settings) {
        this(dataSource, settings, MAX_RESPONSE_BYTES, trace -> {});
    }

    public PgReadOnlyExecutor(
            DataSource dataSource, PgExecutorSettings settings, Consumer<ExecutionTrace> observer) {
        this(dataSource, settings, MAX_RESPONSE_BYTES, observer);
    }

    PgReadOnlyExecutor(
            DataSource dataSource,
            PgExecutorSettings settings,
            long responseBudgetBytes,
            Consumer<ExecutionTrace> observer) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (responseBudgetBytes < 1 || responseBudgetBytes > MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("responseBudgetBytes is out of range");
        }
        this.responseBudgetBytes = responseBudgetBytes;
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    public PgReadOnlyResult execute(
            long poolGeneration, PgReadOnlyRequest request, ReadOnlyCancellation cancellation) {
        return execute(poolGeneration, request, null, cancellation);
    }

    /**
     * Executes with an output plan: the live metadata is verified against the plan before any cell
     * is read. A mismatch terminates the execution as RESULT_TYPE_UNSUPPORTED, marks the trace
     * {@code VERSION_DRIFT} and evicts the connection (datasource.md §6).
     */
    public PgReadOnlyResult execute(
            long poolGeneration,
            PgReadOnlyRequest request,
            PgOutputPlan plan,
            ReadOnlyCancellation cancellation) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(cancellation, "cancellation");
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.CONNECTION_POOL_UNAVAILABLE);
        }
        Trace trace = new Trace(poolGeneration);
        trace.record(ExecutionStage.CHECKOUT, ExecutionState.ACQUIRED);
        return run(connection, request, plan, cancellation, trace);
    }

    private PgReadOnlyResult run(
            Connection connection,
            PgReadOnlyRequest request,
            PgOutputPlan plan,
            ReadOnlyCancellation cancellation,
            Trace trace) {
        DomainException failure = null;
        ResultData data = null;
        SessionState baseline = null;
        boolean configured = false;
        boolean interrupted = false;
        boolean drift = false;
        try {
            validateBorrowed(connection);
            baseline = sessionState(connection);
            configure(connection);
            configured = true;
            trace.record(ExecutionStage.CONFIGURE, ExecutionState.CONFIGURED);
            if (cancellation.isCancelled()) {
                trace.record(ExecutionStage.CANCEL, ExecutionState.CANCELLING);
                failure = new DomainException(ErrorCode.CLIENT_CANCELLED);
            } else {
                trace.record(ExecutionStage.EXECUTE, ExecutionState.EXECUTING);
                try {
                    data = read(connection, request, plan, cancellation);
                    trace.record(ExecutionStage.DRAIN, ExecutionState.DRAINING);
                } catch (SQLException readFailure) {
                    if (readFailure instanceof PlanDriftException) {
                        drift = true;
                        failure = new DomainException(ErrorCode.RESULT_TYPE_UNSUPPORTED);
                    } else {
                        DomainException mapped = mapExecutionFailure(readFailure, cancellation);
                        if (mapped.errorCode() == ErrorCode.CLIENT_CANCELLED
                                || mapped.errorCode() == ErrorCode.QUERY_TIMEOUT) {
                            trace.record(ExecutionStage.CANCEL, ExecutionState.CANCELLING);
                            interrupted = true;
                        } else {
                            trace.record(ExecutionStage.DRAIN, ExecutionState.DRAINING);
                        }
                        failure = mapped;
                    }
                } catch (DomainException domainFailure) {
                    trace.record(ExecutionStage.DRAIN, ExecutionState.DRAINING);
                    failure = domainFailure;
                }
            }
        } catch (SQLException setupFailure) {
            failure = new DomainException(ErrorCode.DRIVER_ERROR);
        } catch (DomainException domainFailure) {
            failure = domainFailure;
        }

        if (configured && failure == null && cancellation.isCancelled()) {
            failure = new DomainException(ErrorCode.CLIENT_CANCELLED);
            interrupted = true;
        }

        ExecutionState finalState;
        if (drift) {
            /*
             * The live result diverged from the approved output plan. The execution terminated
             * before any cell was read; the connection is not trusted and the drift is what the
             * trace records (datasource.md §6 version drift).
             */
            evict(connection);
            trace.record(ExecutionStage.EVICT, ExecutionState.VERSION_DRIFT);
            finalState = ExecutionState.VERSION_DRIFT;
        } else if (!configured) {
            evict(connection);
            trace.record(ExecutionStage.EVICT, ExecutionState.EVICTED);
            finalState = ExecutionState.EVICTED;
            if (failure == null) {
                failure = new DomainException(ErrorCode.DRIVER_ERROR);
            }
        } else if (interrupted) {
            evict(connection);
            trace.record(ExecutionStage.EVICT, ExecutionState.EVICTED);
            finalState = ExecutionState.EVICTED;
        } else {
            trace.record(ExecutionStage.CLEAN, ExecutionState.CLEANING);
            if (cleanup(connection, baseline)) {
                trace.record(ExecutionStage.RETURN, ExecutionState.RETURNED);
                finalState = ExecutionState.RETURNED;
            } else {
                evict(connection);
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
        return new PgReadOnlyResult(
                data.columns(),
                data.rows(),
                data.truncations(),
                data.rowLimitReached(),
                data.responseLimitReached(),
                finished);
    }

    private static void validateBorrowed(Connection connection) {
        try {
            if (!connection.getAutoCommit() || connection.isReadOnly()) {
                throw new DomainException(ErrorCode.CLEANUP_FAILED);
            }
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("postgresql")) {
                throw new DomainException(ErrorCode.DIALECT_CONTEXT_INVALID);
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DIALECT_CONTEXT_INVALID);
        }
    }

    private void configure(Connection connection) throws SQLException {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute("BEGIN READ ONLY");
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL search_path TO pg_catalog");
            statement.execute(
                    "SET LOCAL statement_timeout = '" + settings.sqlTimeoutMillis() + "ms'");
            statement.execute("SET LOCAL lock_timeout = '" + settings.lockTimeoutMillis() + "ms'");
        }
        SessionState configuredState = sessionState(connection);
        if (!"pg_catalog".equals(configuredState.searchPath())
                || !Long.toString(settings.sqlTimeoutMillis())
                        .equals(configuredState.statementTimeout())
                || !Long.toString(settings.lockTimeoutMillis())
                        .equals(configuredState.lockTimeout())
                || !"on".equals(configuredState.transactionReadOnly())) {
            throw new DomainException(ErrorCode.DRIVER_ERROR);
        }
    }

    private static SessionState sessionState(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(SESSION_STATE_SQL)) {
            if (!result.next()) {
                throw new SQLException("session state unavailable");
            }
            return new SessionState(
                    result.getString(1),
                    result.getString(2),
                    result.getString(3),
                    result.getString(4),
                    result.getString(5));
        }
    }

    private ResultData read(
            Connection connection,
            PgReadOnlyRequest request,
            PgOutputPlan plan,
            ReadOnlyCancellation cancellation)
            throws SQLException {
        List<PgReadOnlyResult.Column> columns;
        Map<Integer, Set<Integer>> baseColumns;
        List<List<Object>> rows = new ArrayList<>();
        List<PgReadOnlyResult.CellTruncation> truncations = new ArrayList<>();
        boolean rowLimitReached = false;
        boolean responseLimitReached = false;
        try (Statement statement =
                connection.createStatement(
                        ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
            statement.setFetchSize(FETCH_SIZE);
            statement.setMaxRows(request.maxRows());
            try (ResultSet resultSet = statement.executeQuery(request.sql())) {
                ResultSetMetaData metadata = resultSet.getMetaData();
                columns = ColumnProjection.columns(metadata);
                verifyAgainstPlan(metadata, columns, plan);
                baseColumns = ColumnProjection.referencedBaseColumns(metadata, columns.size());
                long used = columnMetadataBytes(columns);
                if (used > responseBudgetBytes) {
                    throw new DomainException(ErrorCode.RESULT_METADATA_TOO_LARGE);
                }
                while (true) {
                    if (cancellation.isCancelled()) {
                        throw new CancelledQueryException();
                    }
                    if (!resultSet.next()) {
                        break;
                    }
                    List<Object> row = new ArrayList<>(columns.size());
                    List<PgReadOnlyResult.CellTruncation> rowTruncations = new ArrayList<>();
                    for (int index = 0; index < columns.size(); index++) {
                        ColumnProjection.CellValue cell =
                                ColumnProjection.read(resultSet, index + 1, columns.get(index));
                        row.add(cell.value());
                        if (cell.truncated()) {
                            rowTruncations.add(
                                    new PgReadOnlyResult.CellTruncation(
                                            rows.size(),
                                            index,
                                            cell.originalBytes(),
                                            cell.returnedBytes()));
                        }
                    }
                    long rowBytes = rowBytes(row, rowTruncations.size());
                    if (used + rowBytes > responseBudgetBytes) {
                        responseLimitReached = true;
                        break;
                    }
                    rows.add(row);
                    truncations.addAll(rowTruncations);
                    used += rowBytes;
                    if (rows.size() >= request.maxRows()) {
                        rowLimitReached = true;
                        break;
                    }
                }
            }
        }
        // The portal must be closed before another statement can run on this connection.
        ColumnProjection.rejectDomainColumns(connection, baseColumns);
        return new ResultData(columns, rows, truncations, rowLimitReached, responseLimitReached);
    }

    /**
     * The live result shape must equal the approved plan: same column count, same labels, and every
     * column's derived logical type equal to the planned one. Anything else is a version drift —
     * the execution stops before a single cell is read.
     */
    private static void verifyAgainstPlan(
            java.sql.ResultSetMetaData metadata,
            List<PgReadOnlyResult.Column> columns,
            PgOutputPlan plan)
            throws SQLException {
        if (plan == null) {
            return;
        }
        if (metadata.getColumnCount() != plan.columns().size()) {
            throw new PlanDriftException();
        }
        for (int index = 0; index < plan.columns().size(); index++) {
            PgOutputPlan.ExpectedColumn expected = plan.columns().get(index);
            PgReadOnlyResult.Column actual = columns.get(index);
            if (!expected.label().equals(actual.label())
                    || expected.logicalType() != actual.logicalType()) {
                throw new PlanDriftException();
            }
        }
    }

    private static final class PlanDriftException extends SQLException {
        private static final long serialVersionUID = 1L;

        PlanDriftException() {
            super("result metadata diverged from the output plan", "PTMCP", 0);
        }
    }

    private static long columnMetadataBytes(List<PgReadOnlyResult.Column> columns) {
        long bytes = 2;
        for (PgReadOnlyResult.Column column : columns) {
            bytes +=
                    80L
                            + ColumnProjection.cellWireBytes(column.label())
                            + ColumnProjection.cellWireBytes(column.databaseType())
                            + column.logicalType().name().length();
            if (column.encoding() != null) {
                bytes += column.encoding().name().length();
            }
        }
        return bytes;
    }

    private static long rowBytes(List<Object> row, int newTruncations) {
        long bytes = 3;
        for (Object cell : row) {
            bytes += ColumnProjection.cellWireBytes(cell) + 1L;
        }
        return bytes + newTruncations * 96L;
    }

    private static DomainException mapExecutionFailure(
            SQLException failure, ReadOnlyCancellation cancellation) {
        if (cancellation.isCancelled() || failure instanceof CancelledQueryException) {
            return new DomainException(ErrorCode.CLIENT_CANCELLED);
        }
        if (failure instanceof SQLTimeoutException) {
            return new DomainException(ErrorCode.QUERY_TIMEOUT);
        }
        String state = failure.getSQLState();
        // No driver-side cancel timer is installed, so 57014 here is the server statement_timeout.
        if ("57014".equals(state)) {
            return new DomainException(ErrorCode.QUERY_TIMEOUT);
        }
        if ("55P03".equals(state)) {
            return new DomainException(ErrorCode.QUERY_TIMEOUT);
        }
        if ("25006".equals(state)) {
            return new DomainException(ErrorCode.SQL_NOT_READONLY);
        }
        return new DomainException(ErrorCode.DRIVER_ERROR);
    }

    private static boolean cleanup(Connection connection, SessionState baseline) {
        try {
            connection.rollback();
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            if (connection.isReadOnly()) {
                connection.setReadOnly(false);
            }
            if (!baseline.equals(sessionState(connection))) {
                return false;
            }
            connection.close();
            return true;
        } catch (SQLException failure) {
            return false;
        }
    }

    private static void evict(Connection connection) {
        try {
            connection.abort(Runnable::run);
        } catch (SQLException ignored) {
            // The physical close below is the fallback; the connection is never returned.
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A failed close still leaves the connection outside the live generation.
        }
    }

    private record SessionState(
            String searchPath,
            String statementTimeout,
            String lockTimeout,
            String transactionReadOnly,
            String defaultTransactionReadOnly) {}

    private record ResultData(
            List<PgReadOnlyResult.Column> columns,
            List<List<Object>> rows,
            List<PgReadOnlyResult.CellTruncation> truncations,
            boolean rowLimitReached,
            boolean responseLimitReached) {}

    private static final class CancelledQueryException extends SQLException {
        private static final long serialVersionUID = 1L;

        CancelledQueryException() {
            super("cancelled", "57014");
        }
    }

    private static final class Trace {
        private final long poolGeneration;
        private final List<ExecutionTrace.Entry> entries = new ArrayList<>();
        private long lastNanos = System.nanoTime();

        Trace(long poolGeneration) {
            this.poolGeneration = poolGeneration;
        }

        void record(ExecutionStage stage, ExecutionState state) {
            long now = System.nanoTime();
            entries.add(
                    new ExecutionTrace.Entry(
                            stage,
                            state,
                            java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(now - lastNanos)));
            lastNanos = now;
        }

        ExecutionTrace finish(ExecutionState finalState) {
            return new ExecutionTrace(poolGeneration, entries, finalState);
        }
    }
}
