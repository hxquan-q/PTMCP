package local.ptmcp.datasource;

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

    private ReadOnlyRequest(String sql, int maxRows) {
        this.sql = sql;
        this.maxRows = maxRows;
    }

    /** Only the post-T07-c ApprovedQuery adapter in this package may call this factory. */
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

    String sql() {
        return sql;
    }

    int maxRows() {
        return maxRows;
    }
}
