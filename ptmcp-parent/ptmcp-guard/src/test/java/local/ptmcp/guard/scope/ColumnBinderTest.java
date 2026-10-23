package local.ptmcp.guard.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceKey;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class ColumnBinderTest {
    private static final ResourceNamespace NAMESPACE =
            new ResourceNamespace(new ResourceId(1), DatabaseEngine.POSTGRESQL, "erp", "sales");

    private final Map<ResourceKey, ResourceRef> catalog = new LinkedHashMap<>();
    private final ResourceRef orders;
    private final ResourceRef users;
    private final ResourceRef salary;

    ColumnBinderTest() {
        orders = add("orders", 11);
        users = add("users", 12);
        salary = add("salary", 13);
    }

    private ResourceRef add(String name, long objectId) {
        ResourceKey key = new ResourceKey(NAMESPACE, name);
        ResourceRef ref = new ResourceRef(key, new ResourceId(objectId), 1, 1, 7);
        catalog.put(key, ref);
        return ref;
    }

    private ColumnBindingResult bind(String sql) throws Exception {
        Select select = (Select) CCJSqlParserUtil.parse(sql);
        ScopeTableBinder.BindingResult relations =
                ScopeTableBinder.bind(
                        select, NAMESPACE, key -> Optional.ofNullable(catalog.get(key)));
        return ColumnBinder.bind(
                select,
                relations,
                ref ->
                        switch (ref.key().objectName()) {
                            case "orders" ->
                                    List.of(
                                            new ColumnDef(0, "id", "BIGINT", false),
                                            new ColumnDef(1, "customer_id", "BIGINT", true));
                            case "users" ->
                                    List.of(
                                            new ColumnDef(0, "id", "BIGINT", false),
                                            new ColumnDef(1, "name", "VARCHAR", true));
                            case "salary" ->
                                    List.of(
                                            new ColumnDef(0, "id", "BIGINT", false),
                                            new ColumnDef(1, "amount", "DECIMAL", true));
                            default -> List.of();
                        });
    }

    @Test
    void expandsTableStarIntoOrderedSnapshotColumnsBoundToThatAlias() throws Exception {
        ColumnBindingResult result = bind("SELECT s.* FROM salary s");

        assertEquals(2, result.primaryScope().outputs().size());
        assertEquals("id", result.primaryScope().outputs().get(0).name());
        assertEquals("amount", result.primaryScope().outputs().get(1).name());
        ColumnBinding id = result.primaryScope().outputs().get(0).sources().get(0);
        assertEquals("s", id.rangeAlias());
        assertEquals(ColumnBinder.RangeKind.PHYSICAL, id.kind());
        assertEquals(salary, id.resource());
        assertEquals("id", id.columnName());
        assertEquals(0, id.ordinal());
    }

    @Test
    void keepsSelfJoinAliasesDistinct() throws Exception {
        ColumnBindingResult result =
                bind("SELECT a.id, b.customer_id FROM orders a " + "JOIN orders b ON a.id = b.id");

        assertEquals(2, result.primaryScope().outputs().size());
        ColumnBinding first = result.primaryScope().outputs().get(0).sources().get(0);
        ColumnBinding second = result.primaryScope().outputs().get(1).sources().get(0);
        assertEquals("a", first.rangeAlias());
        assertEquals("id", first.columnName());
        assertEquals("b", second.rangeAlias());
        assertEquals("customer_id", second.columnName());
        assertTrue(
                result.primaryScope().bindings().stream()
                        .anyMatch(
                                binding ->
                                        binding.rangeAlias().equals("b")
                                                && binding.columnName().equals("id")),
                "the ON clause must bind b.id as well");
    }

    @Test
    void resolvesCatalogSchemaTableAndColumnPath() throws Exception {
        ColumnBindingResult result = bind("SELECT erp.sales.orders.id FROM erp.sales.orders");

        ColumnBinding binding = result.primaryScope().outputs().get(0).sources().get(0);
        assertEquals(orders, binding.resource());
        assertEquals("id", binding.columnName());
        assertEquals("orders", binding.rangeAlias());
    }

    @Test
    void resolvesCorrelatedOuterColumnFromTheParentScope() throws Exception {
        ColumnBindingResult result =
                bind(
                        "SELECT o.id FROM orders o WHERE EXISTS "
                                + "(SELECT 1 FROM users u WHERE u.id = o.id)");

        ScopeColumns subquery =
                result.scopes().stream()
                        .filter(
                                scope ->
                                        scope.ranges().stream()
                                                .anyMatch(range -> range.alias().equals("u")))
                        .findFirst()
                        .orElseThrow();
        assertTrue(
                subquery.bindings().stream()
                        .anyMatch(
                                binding ->
                                        binding.rangeAlias().equals("o")
                                                && binding.columnName().equals("id")
                                                && binding.resource().equals(orders)));
    }

    @Test
    void rejectsUnqualifiedDuplicateColumnsAsAmbiguous() throws Exception {
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () -> bind("SELECT id FROM orders o JOIN users u ON o.id = u.id"));

        assertEquals(ErrorCode.RESOURCE_AMBIGUOUS, error.errorCode());
    }

    @Test
    void rejectsAnUnknownStarAlias() throws Exception {
        DomainException error =
                assertThrows(DomainException.class, () -> bind("SELECT t.* FROM orders o"));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }

    @Test
    void preservesDuplicateOutputNamesByIndex() throws Exception {
        ColumnBindingResult result =
                bind("SELECT o.id, u.id FROM orders o JOIN users u ON o.id = u.id");

        assertEquals(
                List.of("id", "id"),
                result.primaryScope().outputs().stream().map(OutputColumn::name).toList());
        assertEquals(0, result.primaryScope().outputs().get(0).ordinal());
        assertEquals(1, result.primaryScope().outputs().get(1).ordinal());
        assertEquals("o", result.primaryScope().outputs().get(0).sources().get(0).rangeAlias());
        assertEquals("u", result.primaryScope().outputs().get(1).sources().get(0).rangeAlias());
    }

    @Test
    void tableStarOnlyExpandsTheNamedRange() throws Exception {
        ColumnBindingResult result = bind("SELECT o.* FROM orders o JOIN users u ON o.id = u.id");

        assertEquals(
                List.of("id", "customer_id"),
                result.primaryScope().outputs().stream().map(OutputColumn::name).toList());
    }

    @Test
    void cteReferenceIsNotTreatedAsAPhysicalTable() throws Exception {
        ColumnBindingResult result =
                bind("WITH x AS (SELECT o.id FROM orders o) SELECT x.id FROM x");

        assertEquals(1, result.primaryScope().ranges().size());
        RangeBinding range = result.primaryScope().ranges().get(0);
        assertEquals("x", range.alias());
        assertEquals(ColumnBinder.RangeKind.CTE, range.kind());
        assertNull(range.resource());
        ColumnBinding binding = result.primaryScope().outputs().get(0).sources().get(0);
        assertEquals(orders, binding.resource());
    }

    @Test
    void derivedTableOutputKeepsUnderlyingPhysicalSources() throws Exception {
        ColumnBindingResult result = bind("SELECT d.id FROM (SELECT o.id FROM orders o) d");

        RangeBinding range = result.primaryScope().ranges().get(0);
        assertEquals("d", range.alias());
        assertEquals(ColumnBinder.RangeKind.DERIVED, range.kind());
        assertNull(range.resource());
        assertEquals(orders, result.primaryScope().outputs().get(0).sources().get(0).resource());
    }

    @Test
    void rejectsQueriesWithUnresolvedRelationsBeforeBindingColumns() throws Exception {
        DomainException error =
                assertThrows(DomainException.class, () -> bind("SELECT missing.id FROM missing"));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }

    @Test
    void rejectsAMissingColumnOnAKnownAlias() throws Exception {
        DomainException error =
                assertThrows(
                        DomainException.class, () -> bind("SELECT o.missing_col FROM orders o"));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }
}
