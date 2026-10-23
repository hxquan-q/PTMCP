package local.ptmcp.guard.lexing;

import local.ptmcp.common.resource.DatabaseEngine;

/**
 * Immutable lexical settings supplied from verified server configuration, never guessed from SQL.
 * This value does not verify a server: later connection checkout and parser adapters MUST check
 * that their dialect/modes match it. No such integration is provided by lexical precheck.
 */
public final class SqlLexicalPolicy {
    public static final int MAX_SQL_BYTES = 64 * 1024;

    private final DatabaseEngine engine;
    private final boolean ansiQuotes;
    private final boolean noBackslashEscapes;
    private final boolean standardConformingStrings;
    private final int maxSqlBytes;

    private SqlLexicalPolicy(
            DatabaseEngine engine,
            boolean ansiQuotes,
            boolean noBackslashEscapes,
            boolean standardConformingStrings,
            int maxSqlBytes) {
        if (maxSqlBytes < 1 || maxSqlBytes > MAX_SQL_BYTES) {
            throw new IllegalArgumentException("SQL byte budget must be between 1 and 65536");
        }
        this.engine = engine;
        this.ansiQuotes = ansiQuotes;
        this.noBackslashEscapes = noBackslashEscapes;
        this.standardConformingStrings = standardConformingStrings;
        this.maxSqlBytes = maxSqlBytes;
    }

    public static SqlLexicalPolicy mysql(
            boolean ansiQuotes, boolean noBackslashEscapes, int maxBytes) {
        return new SqlLexicalPolicy(
                DatabaseEngine.MYSQL, ansiQuotes, noBackslashEscapes, false, maxBytes);
    }

    public static SqlLexicalPolicy mysql(boolean ansiQuotes, boolean noBackslashEscapes) {
        return mysql(ansiQuotes, noBackslashEscapes, MAX_SQL_BYTES);
    }

    public static SqlLexicalPolicy postgresql(boolean standardConformingStrings, int maxBytes) {
        return new SqlLexicalPolicy(
                DatabaseEngine.POSTGRESQL, false, false, standardConformingStrings, maxBytes);
    }

    public static SqlLexicalPolicy postgresql(boolean standardConformingStrings) {
        return postgresql(standardConformingStrings, MAX_SQL_BYTES);
    }

    public DatabaseEngine engine() {
        return engine;
    }

    public boolean ansiQuotes() {
        return ansiQuotes;
    }

    public boolean noBackslashEscapes() {
        return noBackslashEscapes;
    }

    public boolean standardConformingStrings() {
        return standardConformingStrings;
    }

    public int maxSqlBytes() {
        return maxSqlBytes;
    }
}
