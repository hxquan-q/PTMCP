package local.ptmcp.guard.budget;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

import org.junit.jupiter.api.Test;

class AstMetricsTest {
    private static Select parse(String sql) throws Exception {
        return (Select) CCJSqlParserUtil.parse(sql);
    }

    @Test
    void acceptsExactlySixtyFourLevelsAndRejectsSixtyFive() throws Exception {
        Select depth64 = parse("SELECT " + "(".repeat(61) + "1" + ")".repeat(61));
        Select depth65 = parse("SELECT " + "(".repeat(62) + "1" + ")".repeat(62));

        assertEquals(64, AstMetrics.maxDepth(depth64));
        assertEquals(65, AstMetrics.maxDepth(depth65));
        assertDoesNotThrow(() -> AstMetrics.requireWithinDepth(depth64, 64));

        DomainException error =
                assertThrows(
                        DomainException.class, () -> AstMetrics.requireWithinDepth(depth65, 64));
        assertEquals(ErrorCode.SQL_BUDGET_EXCEEDED, error.errorCode());
    }

    @Test
    void countsSubqueriesCteBodiesAndFunctionArguments() throws Exception {
        Select flat = parse("SELECT 1");
        Select nested =
                parse(
                        "WITH c AS (SELECT 1) SELECT abs((SELECT count(*) FROM "
                                + "(SELECT 1) t)) FROM (SELECT 2) d");

        assertEquals(3, AstMetrics.maxDepth(flat));
        org.junit.jupiter.api.Assertions.assertTrue(AstMetrics.maxDepth(nested) > 3);
    }
}
