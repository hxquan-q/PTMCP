package local.ptmcp.gateway.mcp;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;

import java.util.regex.Pattern;

/**
 * Best-effort literal masker for the audit trail's {@code sql_masked} column: strip literal values
 * (strings, numbers, hex) while keeping the statement structure readable. masking is deliberately
 * conservative — it only runs on SQL that parses before and after the rewrite, and returns {@code
 * null} whenever anything is off (parse failure, masked form no longer parses). The sealed
 * ciphertext stays the authoritative readable form; the masked line is a convenience, never a
 * security boundary.
 */
public final class SqlLiteralMasker {
    /**
     * Single-quoted string literal with '' escaping; MySQL backslash escapes fail the re-parse and
     * drop the mask.
     */
    private static final Pattern STRING_LITERAL = Pattern.compile("'([^']|'')*'");

    private static final Pattern HEX_LITERAL = Pattern.compile("\\b0x[0-9a-fA-F]+\\b");
    private static final Pattern NUMERIC_LITERAL =
            Pattern.compile("\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b");

    private SqlLiteralMasker() {}

    /**
     * @return the masked SQL, or {@code null} when the input does not parse or the masked form
     *     stops parsing (the caller then leaves {@code sql_masked} empty).
     */
    public static String mask(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        try {
            CCJSqlParserUtil.parse(sql);
        } catch (Exception failure) {
            return null;
        }
        String masked = STRING_LITERAL.matcher(sql).replaceAll("?");
        masked = HEX_LITERAL.matcher(masked).replaceAll("?");
        masked = NUMERIC_LITERAL.matcher(masked).replaceAll("?");
        if (masked.equals(sql)) {
            return sql; // No literals: the statement itself is the masked form.
        }
        try {
            CCJSqlParserUtil.parse(masked);
        } catch (Exception failure) {
            return null;
        }
        return masked;
    }
}
