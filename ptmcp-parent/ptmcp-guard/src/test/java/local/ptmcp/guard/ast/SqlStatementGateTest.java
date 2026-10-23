package local.ptmcp.guard.ast;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SqlStatementGateTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT 1",
                "SELECT id, name FROM orders WHERE id > 10 ORDER BY id LIMIT 10",
                "SELECT o.id, COUNT(*) FROM orders o JOIN users u ON o.customer_id = u.id GROUP BY o.id HAVING COUNT(*) > 1",
                "SELECT * FROM (SELECT id FROM orders) derived",
                "WITH recent AS (SELECT id FROM orders) SELECT * FROM recent",
                "SELECT 1 UNION ALL SELECT 2",
                "(SELECT 1) UNION (SELECT 2)",
                "SELECT a.id FROM orders a WHERE EXISTS (SELECT 1 FROM users u WHERE u.id = a.customer_id)"
            })
    void acceptsSingleReadOnlySelectShapes(String sql) {
        assertDoesNotThrow(() -> SqlStatementGate.check(sql));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "UPDATE orders SET amount = 0",
                "DELETE FROM orders",
                "INSERT INTO orders (id) VALUES (1)",
                "CREATE TABLE probe (id INT)",
                "DROP TABLE orders",
                "TRUNCATE TABLE orders",
                "CALL cleanup()",
                "SELECT 1; SELECT 2",
                "SELECT 1; ",
                ""
            })
    void rejectsNonSelectWritesAndMultipleStatements(String sql) {
        if (sql.equals("SELECT 1; ")) {
            // A single trailing terminator plus whitespace is a single statement.
            assertDoesNotThrow(() -> SqlStatementGate.check(sql));
            return;
        }
        rejected(sql, sql.isEmpty() ? ErrorCode.SQL_SYNTAX_INVALID : ErrorCode.SQL_NOT_READONLY);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "EXPLAIN SELECT 1",
                "EXPLAIN ANALYZE SELECT 1",
                "SHOW TABLES",
                "SHOW COLUMNS FROM orders"
            })
    void rejectsMetadataStatementsThatHaveDedicatedTools(String sql) {
        rejected(sql, ErrorCode.SQL_UNSUPPORTED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "WITH RECURSIVE r AS (SELECT 1 UNION ALL SELECT 2) SELECT * FROM r",
                "SELECT * FROM orders FOR UPDATE",
                "SELECT * FROM orders FOR SHARE",
                "SELECT * FROM orders INTO OUTFILE '/tmp/leak'",
                "SELECT * FROM orders LIMIT 1 FOR UPDATE"
            })
    void rejectsLockingRecursiveAndIntoVariants(String sql) {
        rejected(sql, ErrorCode.SQL_UNSUPPORTED);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                // Constructs the locked parser version cannot parse are rejected as invalid syntax,
                // not silently accepted: LOCK IN SHARE MODE and MySQL variable INTO.
                "SELECT * FROM orders LOCK IN SHARE MODE",
                "SELECT id INTO @v FROM orders"
            })
    void rejectsUnparsedDialectConstructsAsInvalidSyntax(String sql) {
        rejected(sql, ErrorCode.SQL_SYNTAX_INVALID);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "SELECT * FROM (SELECT id FROM orders FOR UPDATE) locked",
                "SELECT id FROM orders WHERE id IN (SELECT id FROM users FOR UPDATE)",
                "SELECT (SELECT 1 FROM orders FOR UPDATE LIMIT 1) AS nested",
                "WITH x AS (SELECT * FROM orders FOR UPDATE) SELECT * FROM x"
            })
    void rejectsLockingAnywhereInNestedSelects(String sql) {
        rejected(sql, ErrorCode.SQL_UNSUPPORTED);
    }

    @Test
    void rejectsParserFailuresWithoutLeakingInput() {
        DomainException unbalanced =
                assertThrows(DomainException.class, () -> SqlStatementGate.check("SELECT 'oops"));
        DomainException nonsense =
                assertThrows(
                        DomainException.class, () -> SqlStatementGate.check("SELECT FROM WHERE ("));

        assertEquals(ErrorCode.SQL_SYNTAX_INVALID, unbalanced.errorCode());
        assertEquals(ErrorCode.SQL_SYNTAX_INVALID, nonsense.errorCode());
        assertEquals("SQL_SYNTAX_INVALID", unbalanced.getMessage());
    }

    private static void rejected(String sql, ErrorCode expected) {
        DomainException error =
                assertThrows(DomainException.class, () -> SqlStatementGate.check(sql));
        assertEquals(expected, error.errorCode(), sql);
        assertEquals(expected.name(), error.getMessage(), sql);
    }
}
