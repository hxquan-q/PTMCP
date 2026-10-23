package local.ptmcp.guard.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceKey;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** R1 scope cases: two conventional and four shadowing queries must keep the physical relations. */
class ScopeTableBinderTest {
    private static final ResourceNamespace NAMESPACE =
            new ResourceNamespace(new ResourceId(1), DatabaseEngine.POSTGRESQL, "erp", "sales");

    private final Map<ResourceKey, ResourceRef> catalog = new LinkedHashMap<>();

    ScopeTableBinderTest() {
        add("orders", 11);
        add("users", 12);
        add("salary", 13);
    }

    private void add(String name, long objectId) {
        ResourceKey key = new ResourceKey(NAMESPACE, name);
        catalog.put(key, new ResourceRef(key, new ResourceId(objectId), 1, 1, 7));
    }

    private ScopeTableBinder.BindingResult bind(String sql) throws Exception {
        Select select = (Select) CCJSqlParserUtil.parse(sql);
        return ScopeTableBinder.bind(
                select, NAMESPACE, key -> Optional.ofNullable(catalog.get(key)));
    }

    @ParameterizedTest(name = "R1 case {0}: {1}")
    @CsvSource(
            delimiter = '|',
            value = {
                "1 | WITH x AS (SELECT * FROM orders) SELECT * FROM x JOIN users u ON 1 = 1 | orders,users",
                "2 | WITH users AS (SELECT * FROM salary) SELECT * FROM users | salary",
                "3 | SELECT s.* FROM salary s CROSS JOIN (SELECT 1 AS n) salary | salary",
                "4 | SELECT * FROM salary WHERE EXISTS (WITH salary AS (SELECT 1 AS n) SELECT * FROM salary) | salary",
                "5 | WITH salary AS (SELECT * FROM salary) SELECT * FROM salary | salary",
                "6 | SELECT 1 FROM salary UNION ALL SELECT * FROM (SELECT 1 AS n) salary | salary"
            })
    void keepsPhysicalRelationsForEveryR1ScopeCase(int id, String sql, String expectedNames)
            throws Exception {
        ScopeTableBinder.BindingResult result = bind(sql);

        List<String> actual =
                result.physicalRefs().stream().map(ref -> ref.key().objectName()).toList();
        assertEquals(List.of(expectedNames.split(",")), actual, "case " + id);
        assertTrue(
                result.unresolved().isEmpty(), "case " + id + " unresolved=" + result.unresolved());
    }

    @Test
    void duplicatePhysicalAccessesAreCountedRatherThanDeduplicated() throws Exception {
        ScopeTableBinder.BindingResult result =
                bind("SELECT * FROM orders a JOIN orders b ON a.id = b.id");

        assertEquals(2, result.physicalRefs().size());
        assertEquals("orders", result.physicalRefs().get(0).key().objectName());
        assertEquals("orders", result.physicalRefs().get(1).key().objectName());
    }

    @Test
    void explicitSchemaOverridesTheDefaultNamespace() throws Exception {
        ResourceKey publicKey =
                new ResourceKey(
                        new ResourceNamespace(
                                new ResourceId(1), DatabaseEngine.POSTGRESQL, "erp", "public"),
                        "orders");
        catalog.put(publicKey, new ResourceRef(publicKey, new ResourceId(99), 2, 1, 8));

        ScopeTableBinder.BindingResult result = bind("SELECT * FROM public.orders");

        assertEquals(1, result.physicalRefs().size());
        assertEquals(new ResourceId(99), result.physicalRefs().get(0).objectId());
    }

    @Test
    void unknownPhysicalRelationsAreReportedInsteadOfDropped() throws Exception {
        ScopeTableBinder.BindingResult result = bind("SELECT * FROM missing_table");

        assertTrue(result.physicalRefs().isEmpty());
        assertEquals(List.of(new ResourceKey(NAMESPACE, "missing_table")), result.unresolved());
    }

    @Test
    void derivedAliasDoesNotShadowThePhysicalRelationInAnotherScope() throws Exception {
        ScopeTableBinder.BindingResult result =
                bind("SELECT * FROM (SELECT * FROM orders) salary JOIN salary s ON 1 = 1");

        assertEquals(
                List.of("orders", "salary"),
                result.physicalRefs().stream().map(ref -> ref.key().objectName()).toList());
    }
}
