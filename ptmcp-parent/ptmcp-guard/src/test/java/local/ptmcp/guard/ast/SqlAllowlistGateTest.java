package local.ptmcp.guard.ast;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlAllowlistGateTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT COUNT(*), SUM(amount), MIN(amount), MAX(amount), AVG(amount) FROM orders",
                "SELECT LOWER(name), UPPER(name), LENGTH(name) FROM users",
                "SELECT COALESCE(a, b), NULLIF(a, b), ABS(x), ROUND(x, 2) FROM orders",
                "SELECT ROW_NUMBER() OVER (ORDER BY id) AS rn, RANK() OVER (ORDER BY id) AS r FROM orders",
                "SELECT CAST(amount AS DECIMAL(18, 2)) FROM orders",
                "SELECT CAST(name AS VARCHAR(64)) FROM users",
                "SELECT id FROM orders o JOIN users u ON o.customer_id = u.id"
            })
    void acceptsReviewedFunctionsAndBasicCastTypes(String sql) {
        assertDoesNotThrow(() -> SqlAllowlistGate.check(sql));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT sleep(10)",
                "SELECT pg_sleep(10)",
                "SELECT load_file('/etc/passwd')",
                "SELECT pg_read_file('/etc/passwd')",
                "SELECT nextval('orders_id_seq')",
                "SELECT CONCAT(a, b) FROM orders",
                "SELECT GROUP_CONCAT(name) FROM users",
                "SELECT public.custom_fn()",
                "SELECT mydb.custom_fn()",
                "SELECT (SELECT sleep(1)) FROM orders",
                "SELECT * FROM (SELECT load_file('/tmp/x') AS v) nested"
            })
    void rejectsUnlistedAndPathQualifiedFunctionsAnywhere(String sql) {
        rejected(sql, ErrorCode.FUNCTION_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT CAST(amount AS public.leaky_type) FROM orders",
                "SELECT CAST(name AS uuid) FROM users",
                "SELECT CAST(name AS mydb.custom_type) FROM users"
            })
    void rejectsNonBasicOrPathQualifiedCastTargets(String sql) {
        rejected(sql, ErrorCode.TYPE_NOT_ALLOWED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM mysql.user",
                "SELECT * FROM information_schema.tables",
                "SELECT * FROM performance_schema.threads",
                "SELECT * FROM sys.session",
                "SELECT * FROM pg_catalog.pg_class",
                "SELECT * FROM pg_authid",
                "SELECT * FROM sales.pg_statistic",
                "SELECT * FROM orders o JOIN mysql.user u ON 1 = 1"
            })
    void rejectsSystemCatalogsSchemasAndReservedRelations(String sql) {
        rejected(sql, ErrorCode.TABLE_NOT_AUTHORIZED);
    }

    @Test
    void rejectsParserFailuresWithoutLeakingTheInput() {
        DomainException error =
                assertThrows(DomainException.class, () -> SqlAllowlistGate.check("SELECT )("));

        assertEquals(ErrorCode.SQL_SYNTAX_INVALID, error.errorCode());
        assertEquals("SQL_SYNTAX_INVALID", error.getMessage());
    }

    @Test
    void rejectsNonSelectStatementsBeforeAnyAllowlistWork() {
        rejected("UPDATE orders SET amount = 1", ErrorCode.SQL_NOT_READONLY);
        rejected("SELECT sleep(1); SELECT 1", ErrorCode.SQL_NOT_READONLY);
    }

    private static void rejected(String sql, ErrorCode expected) {
        DomainException error =
                assertThrows(DomainException.class, () -> SqlAllowlistGate.check(sql), sql);
        assertEquals(expected, error.errorCode(), sql);
        assertEquals(expected.name(), error.getMessage(), sql);
    }
}
