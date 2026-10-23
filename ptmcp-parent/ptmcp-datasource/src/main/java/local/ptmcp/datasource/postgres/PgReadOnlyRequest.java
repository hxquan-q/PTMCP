package local.ptmcp.datasource.postgres;

/**
 * A closed PostgreSQL execution input. The factory is package-private on purpose: only an
 * ApprovedQuery adapter that lives in this package may create one, and no module outside can
 * execute an arbitrary SQL string.
 */
public final class PgReadOnlyRequest {
    public static final int MAX_ROWS_LIMIT = 500;
    private static final int MAX_SQL_CHARS = 65_536;

    private final String sql;
    private final int maxRows;

    private PgReadOnlyRequest(String sql, int maxRows) {
        this.sql = sql;
        this.maxRows = maxRows;
    }

    /** Only a package-local ApprovedQuery adapter may call this factory. */
    static PgReadOnlyRequest of(String sql, int maxRows) {
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
        return new PgReadOnlyRequest(sql, maxRows);
    }

    String sql() {
        return sql;
    }

    int maxRows() {
        return maxRows;
    }
}
