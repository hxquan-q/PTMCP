package local.ptmcp.guard.rewrite;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceKey;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.scope.ColumnBinder;
import local.ptmcp.guard.scope.ColumnBindingResult;
import local.ptmcp.guard.scope.ColumnDef;
import local.ptmcp.guard.scope.RelationColumns;
import local.ptmcp.guard.scope.ScopeTableBinder;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Select;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class RewriteTestSupport {
    static final ResourceNamespace PG =
            new ResourceNamespace(new ResourceId(1), DatabaseEngine.POSTGRESQL, "erp", "sales");
    static final ResourceNamespace MYSQL =
            new ResourceNamespace(new ResourceId(2), DatabaseEngine.MYSQL, "shop", "");

    final Map<ResourceKey, ResourceRef> catalog = new LinkedHashMap<>();
    final Map<ResourceRef, List<ColumnDef>> columns = new HashMap<>();
    final ResourceRef orders;
    final ResourceRef users;
    final ResourceRef salary;

    RewriteTestSupport() {
        orders = add("orders", 11, idAndCustomer());
        users = add("users", 12, idAndName());
        salary = add("salary", 13, idAndAmount());
    }

    private ResourceRef add(String name, long objectId, List<ColumnDef> defs) {
        addNamespace(PG, name, objectId, defs);
        addNamespace(MYSQL, name, objectId + 100, defs);
        return catalog.get(new ResourceKey(PG, name));
    }

    private void addNamespace(
            ResourceNamespace namespace, String name, long objectId, List<ColumnDef> defs) {
        ResourceKey key = new ResourceKey(namespace, name);
        ResourceRef ref = new ResourceRef(key, new ResourceId(objectId), 1, 1, 7);
        catalog.put(key, ref);
        columns.put(ref, defs);
    }

    private static List<ColumnDef> idAndCustomer() {
        return List.of(
                new ColumnDef(0, "id", "BIGINT", false),
                new ColumnDef(1, "customer_id", "BIGINT", true));
    }

    private static List<ColumnDef> idAndName() {
        return List.of(
                new ColumnDef(0, "id", "BIGINT", false), new ColumnDef(1, "name", "VARCHAR", true));
    }

    private static List<ColumnDef> idAndAmount() {
        return List.of(
                new ColumnDef(0, "id", "BIGINT", false),
                new ColumnDef(1, "amount", "DECIMAL", true));
    }

    ScopeTableBinder.RelationCatalog catalogLookup() {
        return key -> Optional.ofNullable(catalog.get(key));
    }

    RelationColumns columnLookup() {
        return ref -> columns.getOrDefault(ref, List.of());
    }

    Bound bind(String sql, ResourceNamespace namespace) throws Exception {
        Select select = (Select) CCJSqlParserUtil.parse(sql);
        ScopeTableBinder.BindingResult relations =
                ScopeTableBinder.bind(select, namespace, catalogLookup());
        ColumnBindingResult bindings = ColumnBinder.bind(select, relations, columnLookup());
        return new Bound(select, bindings);
    }

    RewrittenSql rewrite(String sql, ResourceNamespace namespace) throws Exception {
        Bound bound = bind(sql, namespace);
        return QualifiedRewriter.rewrite(bound.select(), bound.bindings());
    }

    record Bound(Select select, ColumnBindingResult bindings) {}
}
