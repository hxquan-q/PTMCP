package local.ptmcp.guard.scope;

/** One column from a published schema snapshot; ordinal is the snapshot position. */
public record ColumnDef(int ordinal, String name, String databaseType, boolean nullable) {
    public ColumnDef {
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("column name is required");
        }
        if (databaseType == null || databaseType.isEmpty()) {
            throw new IllegalArgumentException("database type is required");
        }
    }
}
