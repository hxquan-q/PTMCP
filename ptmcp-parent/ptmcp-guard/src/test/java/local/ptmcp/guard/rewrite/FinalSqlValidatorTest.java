package local.ptmcp.guard.rewrite;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.ArrayList;
import java.util.List;

class FinalSqlValidatorTest {
    private final RewriteTestSupport support = new RewriteTestSupport();

    private record Pipeline(String finalSql, RewritePlan plan) {}

    private Pipeline pipeline(String sql, int maxRows) throws Exception {
        RewriteTestSupport.Bound bound = support.bind(sql, RewriteTestSupport.PG);
        RewrittenSql rewritten = QualifiedRewriter.rewrite(bound.select(), bound.bindings());
        String finalSql = LimitRewriter.rewrite(bound.select(), maxRows);
        return new Pipeline(finalSql, rewritten.plan());
    }

    private void validate(String finalSql, RewritePlan plan, int expectedLimit) {
        FinalSqlValidator.validate(
                finalSql,
                plan,
                RewriteTestSupport.PG,
                support.catalogLookup(),
                support.columnLookup(),
                expectedLimit);
    }

    private static void assertRewriteFailure(Executable executable) {
        DomainException failure = assertThrows(DomainException.class, executable);
        assertEquals(ErrorCode.REWRITE_VALIDATION_FAILED, failure.errorCode());
    }

    @Test
    void acceptsTheEquivalentRewrittenStatement() throws Exception {
        Pipeline pipeline = pipeline("SELECT orders.id FROM orders WHERE orders.id > 0", 200);

        assertEquals(
                "SELECT __ptmcp_r1.id FROM sales.orders AS __ptmcp_r1 WHERE __ptmcp_r1.id > 0 LIMIT 200",
                pipeline.finalSql());
        assertDoesNotThrow(() -> validate(pipeline.finalSql(), pipeline.plan(), 200));
    }

    @Test
    void acceptsAQueryWithoutRelations() throws Exception {
        Pipeline pipeline = pipeline("SELECT 1", 5);

        assertEquals("SELECT 1 LIMIT 5", pipeline.finalSql());
        assertDoesNotThrow(() -> validate(pipeline.finalSql(), pipeline.plan(), 5));
    }

    @Test
    void validatesWindowOrderingAndTableStars() throws Exception {
        Pipeline window = pipeline("SELECT ROW_NUMBER() OVER (ORDER BY orders.id) FROM orders", 10);
        Pipeline star = pipeline("SELECT s.* FROM salary s", 10);

        assertDoesNotThrow(() -> validate(window.finalSql(), window.plan(), 10));
        assertDoesNotThrow(() -> validate(star.finalSql(), star.plan(), 10));
    }

    @Test
    void rejectsAPlanWithAMissingColumnBinding() throws Exception {
        Pipeline pipeline = pipeline("SELECT orders.id FROM orders WHERE orders.id > 0", 200);
        RewritePlan plan = pipeline.plan();
        RewritePlan tampered =
                new RewritePlan(
                        plan.physicalRelations(),
                        plan.columns().subList(0, plan.columns().size() - 1),
                        plan.outputNames());

        assertRewriteFailure(() -> validate(pipeline.finalSql(), tampered, 200));
    }

    @Test
    void rejectsAPlanWithAnExtraPhysicalRelation() throws Exception {
        Pipeline pipeline = pipeline("SELECT orders.id FROM orders", 200);
        RewritePlan plan = pipeline.plan();
        List<local.ptmcp.common.resource.ResourceRef> relations =
                new ArrayList<>(plan.physicalRelations());
        relations.add(support.orders);
        RewritePlan tampered = new RewritePlan(relations, plan.columns(), plan.outputNames());

        assertRewriteFailure(() -> validate(pipeline.finalSql(), tampered, 200));
    }

    @Test
    void rejectsAMissingOrWidenedOuterLimit() throws Exception {
        Pipeline pipeline = pipeline("SELECT orders.id FROM orders WHERE orders.id > 0", 200);
        String withoutLimit = pipeline.finalSql().replace(" LIMIT 200", "");
        String widened = pipeline.finalSql().replace("LIMIT 200", "LIMIT 500");

        assertRewriteFailure(() -> validate(withoutLimit, pipeline.plan(), 200));
        assertRewriteFailure(() -> validate(widened, pipeline.plan(), 200));
    }

    @Test
    void rejectsTextThatChangesTheColumnSources() throws Exception {
        Pipeline pipeline = pipeline("SELECT orders.id FROM orders WHERE orders.id > 0", 200);
        String tampered = pipeline.finalSql().replace("SELECT __ptmcp_r1.id", "SELECT 1");

        assertRewriteFailure(() -> validate(tampered, pipeline.plan(), 200));
    }

    @Test
    void rejectsUnsupportedOrNonReadOnlyText() throws Exception {
        RewritePlan plan = pipeline("SELECT orders.id FROM orders", 200).plan();
        String delete = "DELETE FROM sales.orders";
        String forbiddenFunction = "SELECT SLEEP(1) FROM sales.orders AS __ptmcp_r1 LIMIT 200";

        assertRewriteFailure(() -> validate(delete, plan, 200));
        assertRewriteFailure(() -> validate(forbiddenFunction, plan, 200));
    }

    @Test
    void validatesMysqlRewrittenText() throws Exception {
        RewriteTestSupport.Bound bound =
                support.bind("SELECT orders.id FROM orders", RewriteTestSupport.MYSQL);
        RewrittenSql rewritten = QualifiedRewriter.rewrite(bound.select(), bound.bindings());
        String finalSql = LimitRewriter.rewrite(bound.select(), 100);

        assertEquals("SELECT __ptmcp_r1.id FROM shop.orders AS __ptmcp_r1 LIMIT 100", finalSql);
        assertDoesNotThrow(
                () ->
                        FinalSqlValidator.validate(
                                finalSql,
                                rewritten.plan(),
                                RewriteTestSupport.MYSQL,
                                support.catalogLookup(),
                                support.columnLookup(),
                                100));
    }

    @Test
    void rejectsAnOutOfRangeExpectedLimit() throws Exception {
        Pipeline pipeline = pipeline("SELECT orders.id FROM orders", 200);

        assertThrows(
                DomainException.class, () -> validate(pipeline.finalSql(), pipeline.plan(), 0));
        assertThrows(
                DomainException.class, () -> validate(pipeline.finalSql(), pipeline.plan(), 501));
    }
}
