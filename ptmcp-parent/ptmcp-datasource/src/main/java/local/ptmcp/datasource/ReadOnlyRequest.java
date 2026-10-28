package local.ptmcp.datasource;

import local.ptmcp.common.execution.ApprovedSqlPlan;

import java.time.Clock;

/**
 * A closed execution input. The factory is package-private on purpose: only the ApprovedQuery
 * adapter that lives in this package after T07-c may create one, and no module outside can execute
 * an arbitrary SQL string.
 */
public final class ReadOnlyRequest {
    public static final int MAX_ROWS_LIMIT = 500;
    private static final int MAX_SQL_CHARS = 65_536;

    private final String sql;
    private final int maxRows;
    private final long configVersion;
    private final Runnable revalidation;

    private ReadOnlyRequest(String sql, int maxRows) {
        this(sql, maxRows, 0, () -> {});
    }

    private ReadOnlyRequest(String sql, int maxRows, long configVersion, Runnable revalidation) {
        this.sql = sql;
        this.maxRows = maxRows;
        this.configVersion = configVersion;
        this.revalidation = revalidation;
    }

    /** Internal protocol-test input; business execution must use {@link #approved}. */
    static ReadOnlyRequest of(String sql, int maxRows) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        if (sql.length() > MAX_SQL_CHARS) {
            throw new IllegalArgumentException("sql exceeds the character budget");
        }
        if (sql.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("sql contains a NUL character");
        }
        if (maxRows < 1 || maxRows > MAX_ROWS_LIMIT) {
            throw new IllegalArgumentException("maxRows must be between 1 and " + MAX_ROWS_LIMIT);
        }
        return new ReadOnlyRequest(sql, maxRows);
    }

    /** Only the approved-plan adapter may bind a business request to a configuration. */
    static ReadOnlyRequest approved(ApprovedSqlPlan plan, Clock clock) {
        if (plan.configVersion() <= 0) {
            throw new IllegalArgumentException("configVersion must be positive");
        }
        ReadOnlyRequest validated = of(plan.finalSql(), plan.effectiveLimit());
        return new ReadOnlyRequest(
                validated.sql,
                validated.maxRows,
                plan.configVersion(),
                () -> ApprovedPlanVerifier.verify(plan, clock));
    }

    void beforeExecution() {
        revalidation.run();
    }

    long configVersion() {
        return configVersion;
    }

    String sql() {
        return sql;
    }

    int maxRows() {
        return maxRows;
    }
}
