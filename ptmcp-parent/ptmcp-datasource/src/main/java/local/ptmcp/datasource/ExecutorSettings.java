package local.ptmcp.datasource;

/**
 * Execution budgets from the timeout contract: queue (pool checkout), connect, execute (server
 * side), read (wall clock over the fetch loop) and cleanup, plus the accumulated output budget in
 * bytes. Exceeding the read or output budget ends the execution as a controlled interrupt — the
 * connection is never returned after a mid-cursor stop.
 */
public record ExecutorSettings(
        long sqlTimeoutMillis,
        long cancelTimeoutMillis,
        long cleanupTimeoutMillis,
        long outputBudgetBytes,
        long readTimeoutMillis) {
    private static final long MAX_SQL_TIMEOUT_MILLIS = 60_000;
    private static final long MAX_CANCEL_TIMEOUT_MILLIS = 10_000;
    private static final long MAX_CLEANUP_TIMEOUT_MILLIS = 10_000;
    private static final long MAX_READ_TIMEOUT_MILLIS = 60_000;
    public static final long MIN_OUTPUT_BUDGET_BYTES = 64L * 1024;
    public static final long MAX_OUTPUT_BUDGET_BYTES = 8L * 1024 * 1024;
    private static final long DEFAULT_OUTPUT_BUDGET_BYTES = 1024L * 1024;

    public ExecutorSettings {
        if (sqlTimeoutMillis < 1 || sqlTimeoutMillis > MAX_SQL_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("sqlTimeoutMillis is out of range");
        }
        if (cancelTimeoutMillis < 1 || cancelTimeoutMillis > MAX_CANCEL_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("cancelTimeoutMillis is out of range");
        }
        if (cleanupTimeoutMillis < 1 || cleanupTimeoutMillis > MAX_CLEANUP_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("cleanupTimeoutMillis is out of range");
        }
        if (outputBudgetBytes < MIN_OUTPUT_BUDGET_BYTES
                || outputBudgetBytes > MAX_OUTPUT_BUDGET_BYTES) {
            throw new IllegalArgumentException("outputBudgetBytes is out of range");
        }
        if (readTimeoutMillis < 1 || readTimeoutMillis > MAX_READ_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("readTimeoutMillis is out of range");
        }
    }

    /**
     * The historical three-budget shape; read follows the SQL budget and output defaults to 1 MiB.
     */
    public ExecutorSettings(
            long sqlTimeoutMillis, long cancelTimeoutMillis, long cleanupTimeoutMillis) {
        this(
                sqlTimeoutMillis,
                cancelTimeoutMillis,
                cleanupTimeoutMillis,
                DEFAULT_OUTPUT_BUDGET_BYTES,
                Math.min(sqlTimeoutMillis, MAX_READ_TIMEOUT_MILLIS));
    }

    public static ExecutorSettings defaults() {
        return new ExecutorSettings(15_000, 3_000, 2_000);
    }
}
