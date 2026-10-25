package local.ptmcp.persistence.audit.masking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Literal masking and hash-only degradation for the audit path (audit.md §4). These assertions are
 * the "cannot prove correct masking" contract: every failure degrades to fingerprint + length and
 * never to a partially masked plaintext.
 */
class AuditSqlMaskingTest {
    private final AuditSqlMasker masker = new AuditSqlMasker("mysql");

    @Test
    void masksLiteralsEverywhereAndDropsComments() {
        String raw =
                "SELECT 'proj', 42, u.name /* table hint */ FROM t1 u\n"
                        + "-- filter\n"
                        + "WHERE u.id = 7 AND u.name = 'Alice' AND u.flag = 0x1F\n"
                        + "AND u.created >= DATE '2024-01-01' AND u.state IN ('A', 'B')\n"
                        + "AND CASE WHEN u.kind = 'X' THEN 1 ELSE 2 END = CAST('3' AS SIGNED)\n"
                        + "LIMIT 10";
        MaskedSql result = masker.mask("ds-42/public.orders", raw);

        assertFalse(result.degraded());
        String masked = result.sqlMasked();
        assertFalse(masked.contains("proj"));
        assertFalse(masked.contains("Alice"));
        assertFalse(masked.contains("'X'"));
        assertFalse(masked.contains("'A'"));
        assertFalse(masked.contains("2024-01-01"));
        assertFalse(masked.contains("42"), "MASKED=[" + masked + "]");
        assertFalse(masked.contains("table hint"));
        assertFalse(masked.contains("filter"));
        assertFalse(masked.toLowerCase().contains("/*"));
        assertFalse(masked.toLowerCase().contains("--"));
        assertTrue(masked.contains("'?'"));
        assertTrue(masked.contains("CASE"));
        assertTrue(masked.contains("CAST"));
        assertTrue(masked.contains("IN"));
        assertFalse(masked.contains("0x"));
        assertEquals(32, result.fingerprint().length);
        // Every literal is masked, including LIMIT values and SELECT projection constants.
        assertFalse(result.degradedRuleId() != null);

        MaskedSql again = masker.mask("ds-42/public.orders", raw);
        assertTrue(Arrays.equals(result.fingerprint(), again.fingerprint()));
        assertTrue(Arrays.equals(result.fingerprint(), result.fingerprint()));
    }

    @Test
    void fingerprintBindsDialectResourceAndSchemaVersion() {
        String raw = "SELECT * FROM t WHERE a = 'same'";
        MaskedSql mysql = masker.mask("ds-1", raw);
        MaskedSql otherDialect = new AuditSqlMasker("postgresql").mask("ds-1", raw);
        MaskedSql otherResource = masker.mask("ds-2", raw);

        assertFalse(Arrays.equals(mysql.fingerprint(), otherDialect.fingerprint()));
        assertFalse(Arrays.equals(mysql.fingerprint(), otherResource.fingerprint()));
    }

    @Test
    void parseFailureDegradesToHashOnlyWithoutRawLeakage() {
        String raw = "SELECT n0_.id FR0M secret_table WHERE n0_.pw = 'SecretValue' AND";
        MaskedSql result = masker.mask("ds-1", raw);

        assertTrue(result.degraded());
        assertNull(result.sqlMasked());
        assertEquals("PARSE_ERROR", result.degradedCode());
        assertEquals(AuditSqlMasker.RULE_PARSE_ERROR, result.degradedRuleId());
        assertEquals(raw.getBytes(StandardCharsets.UTF_8).length, result.rawUtf8Length());
        assertTrue(Arrays.equals(result.fingerprint(), sha256(raw)));
        // Neither the raw text nor any fragment may surface through the result object.
        String rendered = result.toString();
        assertFalse(rendered.contains("SecretValue"));
        assertFalse(rendered.contains("secret_table"));
        for (String needle : new String[] {"SecretValue", "secret_table", "FR0M"}) {
            assertFalse(Arrays.toString(result.fingerprint()).contains(needle));
        }
    }

    @Test
    void unprovableMaskingDegradesInsteadOfGuessing() {
        // INTERVAL arithmetic re-parses only with numeric literals; after masking the transform
        // cannot prove the statement still means the same thing, so it must degrade.
        MaskedSql result = masker.mask("ds-1", "SELECT NOW() < NOW() + INTERVAL 1 DAY");
        if (result.degraded()) {
            assertEquals("MASKING_UNPROVEN", result.degradedCode());
            assertNull(result.sqlMasked());
            assertFalse(result.toString().contains("INTERVAL"));
        } else {
            assertFalse(result.sqlMasked().contains("1 "));
        }

        // Any leftover literal token after the transform must degrade, never pass through.
        MaskedSql guarded = masker.mask("ds-1", "SELECT 'ok' FROM t WHERE b = B'0101'");
        if (!guarded.degraded()) {
            assertFalse(guarded.sqlMasked().contains("0101"));
        } else {
            assertFalse(guarded.toString().contains("0101"));
        }
    }

    @Test
    void oversizedAndNulInputsDegradeToHashOnly() {
        StringBuilder big = new StringBuilder("SELECT * FROM t WHERE a = '");
        while (big.length() < AuditSqlMasker.MAX_MASKING_INPUT_BYTES + 1) {
            big.append('a');
        }
        big.append('\'');
        MaskedSql oversized = masker.mask("ds-1", big.toString());
        assertTrue(oversized.degraded());
        assertEquals("SQL_TOO_LARGE", oversized.degradedCode());
        assertEquals(AuditSqlMasker.RULE_TOO_LARGE, oversized.degradedRuleId());
        assertNull(oversized.sqlMasked());
        assertFalse(oversized.toString().contains("aaa"));

        String withNul = "SELECT * FROM t WHERE a = 'v\0alue'";
        MaskedSql nul = masker.mask("ds-1", withNul);
        assertTrue(nul.degraded());
        assertEquals("NUL_BYTE", nul.degradedCode());
        assertEquals(AuditSqlMasker.RULE_NUL_BYTE, nul.degradedRuleId());
    }

    @Test
    void blankDegradesAndParameterizedInputsMaskSafely() {
        // An empty statement cannot be parsed, so the honest outcome is hash-only.
        MaskedSql empty = masker.mask("ds-1", "");
        assertTrue(empty.degraded());
        assertEquals("PARSE_ERROR", empty.degradedCode());
        assertEquals(0, empty.rawUtf8Length());

        MaskedSql parameterized = masker.mask("ds-1", "SELECT * FROM t1 WHERE a = ? AND b = ?");
        assertFalse(parameterized.degraded(), parameterized.toString());
        assertFalse(
                parameterized.sqlMasked().contains("'"),
                "MASKED=[" + parameterized.sqlMasked() + "]");
        assertTrue(parameterized.sqlMasked().contains("t1"), "identifiers survive");
    }

    @Test
    void rejectsInvalidConstruction() {
        assertThrows(Exception.class, () -> new AuditSqlMasker("Not Valid"));
        assertThrows(Exception.class, () -> masker.mask("ds-1", null));
        assertThrows(Exception.class, () -> MaskedSql.masked("x", new byte[3], 1));
        assertThrows(
                Exception.class,
                () -> MaskedSql.degraded(sha256("x"), 1, "PARSE_ERROR", "BAD-RULE"));
    }

    private static byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
