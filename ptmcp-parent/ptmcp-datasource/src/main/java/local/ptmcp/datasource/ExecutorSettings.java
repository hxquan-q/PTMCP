package local.ptmcp.datasource;

/** Execution budgets from the timeout contract. */
public record ExecutorSettings(
        long sqlTimeoutMillis, long cancelTimeoutMillis, long cleanupTimeoutMillis) {
    private static final long MAX_SQL_TIMEOUT_MILLIS = 60_000;
    private static final long MAX_CANCEL_TIMEOUT_MILLIS = 10_000;
    private static final long MAX_CLEANUP_TIMEOUT_MILLIS = 10_000;

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
    }

    public static ExecutorSettings defaults() {
        return new ExecutorSettings(15_000, 3_000, 2_000);
    }
}
