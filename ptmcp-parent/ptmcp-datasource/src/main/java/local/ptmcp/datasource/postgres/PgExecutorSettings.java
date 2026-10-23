package local.ptmcp.datasource.postgres;

/**
 * PostgreSQL execution budgets. The lock timeout is a configured value and can never exceed the
 * statement budget.
 */
public record PgExecutorSettings(
        long sqlTimeoutMillis, long lockTimeoutMillis, long cleanupTimeoutMillis) {
    private static final long MAX_SQL_TIMEOUT_MILLIS = 60_000;
    private static final long MAX_CLEANUP_TIMEOUT_MILLIS = 10_000;

    public PgExecutorSettings {
        if (sqlTimeoutMillis < 1 || sqlTimeoutMillis > MAX_SQL_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("sqlTimeoutMillis is out of range");
        }
        if (lockTimeoutMillis < 1 || lockTimeoutMillis > sqlTimeoutMillis) {
            throw new IllegalArgumentException(
                    "lockTimeoutMillis must not exceed sqlTimeoutMillis");
        }
        if (cleanupTimeoutMillis < 1 || cleanupTimeoutMillis > MAX_CLEANUP_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException("cleanupTimeoutMillis is out of range");
        }
    }

    public static PgExecutorSettings defaults() {
        return new PgExecutorSettings(15_000, 5_000, 2_000);
    }
}
