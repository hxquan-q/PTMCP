package local.ptmcp.guard.rewrite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceKey;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.scope.ScopeTableBinder;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class LimitRewriterTest {
    private static final ResourceNamespace NAMESPACE =
            new ResourceNamespace(new ResourceId(1), DatabaseEngine.POSTGRESQL, "erp", "sales");

    private static Select parse(String sql) throws Exception {
        return (Select) CCJSqlParserUtil.parse(sql);
    }

    private static String rewrittenSql(String sql, int maxRows) throws Exception {
        return LimitRewriter.rewrite(parse(sql), maxRows);
    }

    @ParameterizedTest(name = "{0} with max_rows {2} -> LIMIT {3}")
    @CsvSource(
            delimiter = '|',
            value = {
                "SELECT * FROM orders | 500 | 500",
                "SELECT * FROM orders | 50 | 50",
                "SELECT * FROM orders LIMIT 10 | 500 | 10",
                "SELECT * FROM orders LIMIT 1000 | 50 | 50",
                "SELECT * FROM orders LIMIT 10 OFFSET 5 | 500 | 10",
                "SELECT * FROM orders LIMIT 5, 10 | 500 | 10"
            })
    void appliesTheTightestOuterLimit(String sql, int maxRows, long expectedLimit)
            throws Exception {
        String rewritten = rewrittenSql(sql, maxRows);
        Select reparsed = parse(rewritten);

        assertEquals(
                expectedLimit,
                ((net.sf.jsqlparser.expression.LongValue) reparsed.getLimit().getRowCount())
                        .getValue(),
                rewritten);
    }

    @Test
    void convertsFetchFirstRowsOnlyIntoAnEquivalentLimit() throws Exception {
        String rewritten = rewrittenSql("SELECT * FROM orders FETCH FIRST 7 ROWS ONLY", 500);
        Select reparsed = parse(rewritten);

        assertEquals(
                7,
                ((net.sf.jsqlparser.expression.LongValue) reparsed.getLimit().getRowCount())
                        .getValue());
        assertEquals(null, reparsed.getFetch());
    }

    @Test
    void rejectsRequestsAboveTheServerMaximumInsteadOfClamping() throws Exception {
        DomainException error =
                assertThrows(
                        DomainException.class, () -> rewrittenSql("SELECT * FROM orders", 501));
        assertEquals(ErrorCode.SQL_BUDGET_EXCEEDED, error.errorCode());

        DomainException zero =
                assertThrows(DomainException.class, () -> rewrittenSql("SELECT * FROM orders", 0));
        assertEquals(ErrorCode.SQL_BUDGET_EXCEEDED, zero.errorCode());
    }

    @Test
    void rejectsNonConstantOrUnboundedOuterLimits() {
        rejected("SELECT * FROM orders LIMIT 1 + 1", ErrorCode.SQL_UNSUPPORTED);
        rejected("SELECT * FROM orders LIMIT ALL", ErrorCode.SQL_UNSUPPORTED);
        rejected("SELECT * FROM orders OFFSET 1 + 1", ErrorCode.SQL_UNSUPPORTED);
    }

    @Test
    void rewrittenSqlKeepsTheSamePhysicalRelationsWhenReparsedAndBound() throws Exception {
        ResourceKey key = new ResourceKey(NAMESPACE, "orders");
        Map<ResourceKey, ResourceRef> catalog = new LinkedHashMap<>();
        catalog.put(key, new ResourceRef(key, new ResourceId(11), 1, 1, 7));
        ScopeTableBinder.RelationCatalog lookup =
                candidate -> Optional.ofNullable(catalog.get(candidate));

        Select original = parse("SELECT * FROM orders WHERE id > 1");
        String rewritten = LimitRewriter.rewrite(original, 25);
        ScopeTableBinder.BindingResult before = ScopeTableBinder.bind(original, NAMESPACE, lookup);
        ScopeTableBinder.BindingResult after =
                ScopeTableBinder.bind(parse(rewritten), NAMESPACE, lookup);

        assertEquals(before.physicalRefs(), after.physicalRefs());
        assertEquals(
                List.of("orders"),
                after.physicalRefs().stream().map(ref -> ref.key().objectName()).toList());
    }

    private static void rejected(String sql, ErrorCode expected) {
        rejected(sql, expected, sql);
    }

    private static void rejected(String sql, ErrorCode expected, String label) {
        DomainException error =
                assertThrows(
                        DomainException.class, () -> LimitRewriter.rewrite(parse(sql), 500), sql);
        assertEquals(expected, error.errorCode(), sql);
    }
}
