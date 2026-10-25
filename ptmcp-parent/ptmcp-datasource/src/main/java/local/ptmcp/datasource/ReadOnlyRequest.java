package local.ptmcp.datasource;

/**
 * A closed execution input. The factory is package-private on purpose: only the ApprovedQuery
 * adapter that lives in this package after T07-c may create one, and no module outside can execute
 * an arbitrary SQL string.
 */
public final class ReadOnlyRequest {
    public static final int MAX_ROWS_LIMIT = 500;
    private static final int MAX_SQL_CHARS = 65_536;

    /** The only output projection this executor can produce; anything else must be rejected. */
    public enum OutputKind {
        ROWS
    }

    private final String sql;
    private final int maxRows;
    private final OutputKind outputKind;

    private ReadOnlyRequest(String sql, int maxRows, OutputKind outputKind) {
        this.sql = sql;
        this.maxRows = maxRows;
        this.outputKind = outputKind;
    }

    /** Only the post-T07-c ApprovedQuery adapter in this package may call this factory. */
    static ReadOnlyRequest of(String sql, int maxRows) {
        return of(sql, maxRows, OutputKind.ROWS);
    }

    static ReadOnlyRequest of(String sql, int maxRows, OutputKind outputKind) {
        if (outputKind != OutputKind.ROWS) {
            // Unknown or unsupported output projections can never be executed by this engine.
            throw new IllegalArgumentException("unsupported output kind");
        }
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
        return new ReadOnlyRequest(sql, maxRows, outputKind);
    }

    String sql() {
        return sql;
    }

    int maxRows() {
        return maxRows;
    }

    OutputKind outputKind() {
        return outputKind;
    }
}
