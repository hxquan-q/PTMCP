package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Masking must remove literal values, keep structure, and fail conservative (null) on doubt. */
class SqlLiteralMaskerTest {
    @Test
    void numericAndStringLiteralsBecomePlaceholders() {
        assertEquals(
                "SELECT id FROM orders WHERE id = ? AND amount > ?",
                SqlLiteralMasker.mask("SELECT id FROM orders WHERE id = 1 AND amount > 12.5"));
        assertEquals(
                "SELECT id FROM t WHERE name = ?",
                SqlLiteralMasker.mask("SELECT id FROM t WHERE name = '张三'"));
    }

    @Test
    void escapedQuotesAndHexAndScientificNotationAreMasked() {
        String masked =
                SqlLiteralMasker.mask("SELECT 'it''s' FROM t WHERE h = 0xDEADBEEF AND x = 1.5e3");
        assertEquals("SELECT ? FROM t WHERE h = ? AND x = ?", masked);
    }

    @Test
    void structureKeywordsAndIdentifiersSurvive() {
        String sql =
                "SELECT COUNT(*) AS total FROM schema_a.orders o JOIN sys_user u"
                        + " ON u.id = o.owner WHERE o.note LIKE '%a%' GROUP BY o.owner"
                        + " ORDER BY total DESC LIMIT 10";
        String masked = SqlLiteralMasker.mask(sql);
        // Literals are gone (COUNT(*) star, LIKE pattern, LIMIT), structure words stay.
        assertFalse(masked.contains("'"));
        assertTrue(masked.contains("COUNT(*)"));
        assertTrue(masked.contains("GROUP BY"));
    }

    @Test
    void statementsWithoutLiteralsComeBackUnchanged() {
        String sql = "SELECT id FROM orders";
        assertEquals(sql, SqlLiteralMasker.mask(sql));
    }

    @Test
    void unparsableInputYieldsNull() {
        assertNull(SqlLiteralMasker.mask("SELEXT id FROM nowhere"));
        assertNull(SqlLiteralMasker.mask(null));
        assertNull(SqlLiteralMasker.mask("   "));
    }

    @Test
    void maskedFormThatStopsParsingYieldsNullInsteadOfGarbage() {
        // The backslash escape breaks the single-quote pattern, the masked form no longer parses
        // and the masker refuses rather than emitting a broken statement.
        assertNull(SqlLiteralMasker.mask("SELECT * FROM t WHERE note = 'it\\'s' AND id = 1"));
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition);
    }

    private static void assertFalse(boolean condition) {
        org.junit.jupiter.api.Assertions.assertFalse(condition);
    }
}
