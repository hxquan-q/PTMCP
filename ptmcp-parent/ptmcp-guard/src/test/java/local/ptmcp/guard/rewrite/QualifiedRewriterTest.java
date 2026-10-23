package local.ptmcp.guard.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.guard.scope.ColumnBinder;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

import org.junit.jupiter.api.Test;

import java.util.List;

class QualifiedRewriterTest {
    private static final ErrorCode REWRITE = ErrorCode.REWRITE_VALIDATION_FAILED;

    private final RewriteTestSupport support = new RewriteTestSupport();

    @Test
    void qualifiesUnaliasedRangesAndSynchronisesEveryQualifiedColumn() throws Exception {
        RewrittenSql result =
                support.rewrite(
                        "SELECT orders.id FROM orders WHERE orders.id > 0", RewriteTestSupport.PG);

        assertEquals(
                "SELECT __ptmcp_r1.id FROM sales.orders AS __ptmcp_r1 WHERE __ptmcp_r1.id > 0",
                result.text());
        assertEquals(List.of(support.orders), result.plan().physicalRelations());
        assertEquals(List.of("id"), result.plan().outputNames());
        assertEquals(2, result.plan().columns().size());
        assertTrue(
                result.plan().columns().stream()
                        .allMatch(
                                column ->
                                        column.kind() == ColumnBinder.RangeKind.PHYSICAL
                                                && column.resource().equals(support.orders)
                                                && column.columnName().equals("id")
                                                && column.ordinal() == 0),
                "both references keep the physical origin of orders.id");
    }

    @Test
    void keepsExplicitAliasesAndCatalogSchemaColumnPaths() throws Exception {
        RewrittenSql path =
                support.rewrite("SELECT sales.orders.id FROM sales.orders", RewriteTestSupport.PG);
        assertEquals("SELECT __ptmcp_r1.id FROM sales.orders AS __ptmcp_r1", path.text());

        RewrittenSql selfJoin =
                support.rewrite(
                        "SELECT a.id, b.customer_id FROM orders a JOIN orders b ON a.id = b.id",
                        RewriteTestSupport.PG);
        assertEquals(
                "SELECT a.id, b.customer_id FROM sales.orders a JOIN sales.orders b ON a.id = b.id",
                selfJoin.text());
        assertEquals(2, selfJoin.plan().physicalRelations().size());
    }

    @Test
    void rewritesTableStarToTheRangeAlias() throws Exception {
        RewrittenSql result =
                support.rewrite(
                        "SELECT s.* FROM salary s WHERE s.amount > 0", RewriteTestSupport.PG);

        assertEquals("SELECT s.* FROM sales.salary s WHERE s.amount > 0", result.text());
        assertEquals(2, result.plan().outputNames().size());
        assertEquals("id", result.plan().outputNames().get(0));
        assertEquals("amount", result.plan().outputNames().get(1));
    }

    @Test
    void generatesAliasesThatAvoidExistingUserNames() throws Exception {
        RewrittenSql result =
                support.rewrite(
                        "SELECT users.id, __ptmcp_r1.name FROM users JOIN users __ptmcp_r1 "
                                + "ON users.id = __ptmcp_r1.id",
                        RewriteTestSupport.PG);

        assertEquals(
                "SELECT __ptmcp_r2.id, __ptmcp_r1.name "
                        + "FROM sales.users AS __ptmcp_r2 JOIN sales.users __ptmcp_r1 "
                        + "ON __ptmcp_r2.id = __ptmcp_r1.id",
                result.text());
    }

    @Test
    void neverTurnsCteOrDerivedNamesIntoPhysicalTables() throws Exception {
        RewrittenSql cte =
                support.rewrite(
                        "WITH recent AS (SELECT o.id FROM orders o) SELECT recent.id FROM recent",
                        RewriteTestSupport.PG);
        assertEquals(
                "WITH recent AS (SELECT o.id FROM sales.orders o) "
                        + "SELECT recent.id FROM recent",
                cte.text());

        RewrittenSql derived =
                support.rewrite(
                        "SELECT d.customer_id FROM (SELECT o.customer_id FROM orders o) d",
                        RewriteTestSupport.PG);
        assertEquals(
                "SELECT d.customer_id FROM (SELECT o.customer_id FROM sales.orders o) d",
                derived.text());
    }

    @Test
    void mysqlRendersDatabaseQualifiedRelationsAndRewrittenColumns() throws Exception {
        RewrittenSql result =
                support.rewrite(
                        "SELECT orders.id FROM orders WHERE orders.id > 0",
                        RewriteTestSupport.MYSQL);

        assertEquals(
                "SELECT __ptmcp_r1.id FROM shop.orders AS __ptmcp_r1 WHERE __ptmcp_r1.id > 0",
                result.text());
    }

    @Test
    void rewritesWindowOrderingReferences() throws Exception {
        RewrittenSql result =
                support.rewrite(
                        "SELECT ROW_NUMBER() OVER (ORDER BY orders.id) FROM orders",
                        RewriteTestSupport.PG);

        assertEquals(
                "SELECT ROW_NUMBER() OVER (ORDER BY __ptmcp_r1.id) "
                        + "FROM sales.orders AS __ptmcp_r1",
                result.text());
    }

    @Test
    void rejectsBindingResultsThatDoNotMatchTheStatement() throws Exception {
        RewriteTestSupport.Bound bound =
                support.bind("SELECT a.id FROM orders a", RewriteTestSupport.PG);
        Select other = (Select) CCJSqlParserUtil.parse("SELECT b.id FROM orders b");

        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () -> QualifiedRewriter.rewrite(other, bound.bindings()));
        assertEquals(REWRITE, failure.errorCode());
    }
}
