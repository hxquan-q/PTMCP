package local.ptmcp.guard.scope;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceKey;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;

import net.sf.jsqlparser.expression.ExpressionVisitor;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves the physical relations a parsed query actually reads, using an explicit scope model.
 *
 * <p>Non-recursive CTE names are introduced only after their own definition body is bound, so
 * {@code WITH salary AS (SELECT * FROM salary) ...} binds the physical {@code salary} inside the
 * definition and the CTE reference outside it. Derived-table aliases are recorded as derived, never
 * as a reason to remove a physical relation. UNION branches get independent scopes, and correlated
 * subqueries inherit the CTE environment only.
 *
 * <p>This reports relations; it does not resolve columns, decide authorization, rewrite SQL or
 * approve execution. Unknown or malformed names are reported as unresolved, never silently dropped.
 */
public final class ScopeTableBinder {
    private ScopeTableBinder() {}

    /**
     * Binds the query against a server-side catalog. Duplicate physical references are preserved in
     * encounter order so later budget checks can count relation accesses, not just distinct tables.
     */
    public static BindingResult bind(
            Select select, ResourceNamespace defaultNamespace, RelationCatalog catalog) {
        if (select == null || defaultNamespace == null || catalog == null) {
            throw new NullPointerException("select, namespace and catalog are required");
        }
        Collector collector = new Collector(select, defaultNamespace, catalog);
        collector.bindTop();
        return new BindingResult(collector.physical, collector.unresolved);
    }

    /** Immutable, order-preserving binding result. */
    public record BindingResult(List<ResourceRef> physicalRefs, List<ResourceKey> unresolved) {
        public BindingResult {
            physicalRefs = List.copyOf(physicalRefs);
            unresolved = List.copyOf(unresolved);
        }
    }

    /** Server-side snapshot lookup; the guard never queries a business database. */
    @FunctionalInterface
    public interface RelationCatalog {
        Optional<ResourceRef> find(ResourceKey key);
    }

    private static final class Collector {
        private final Select root;
        private final ResourceNamespace defaultNamespace;
        private final RelationCatalog catalog;
        private final List<ResourceRef> physical = new ArrayList<>();
        private final List<ResourceKey> unresolved = new ArrayList<>();

        Collector(Select root, ResourceNamespace defaultNamespace, RelationCatalog catalog) {
            this.root = root;
            this.defaultNamespace = defaultNamespace;
            this.catalog = catalog;
        }

        void bindTop() {
            bindAny(root, Map.of());
        }

        /**
         * Introduces CTE names for this query block only after binding each definition body, so a
         * definition never sees its own name. Nested blocks repeat the same rule.
         */
        private void bindAny(Select select, Map<String, Boolean> environment) {
            Map<String, Boolean> visible = environment;
            List<WithItem<?>> withItems = select.getWithItemsList();
            if (withItems != null && !withItems.isEmpty()) {
                visible = new LinkedHashMap<>(environment);
                for (WithItem<?> item : withItems) {
                    String name = item.getAliasName();
                    // Self/forward references bind to the physical relation instead of looping.
                    bindAny(item.getSelect(), visible);
                    if (name != null) {
                        visible.put(name, Boolean.TRUE);
                    }
                }
            }
            bindBody(select, visible);
        }

        private void bindBody(Select select, Map<String, Boolean> environment) {
            if (select instanceof PlainSelect plain) {
                bindFromItems(plain, environment);
            } else if (select instanceof ParenthesedSelect parenthesed) {
                bindAny(parenthesed.getSelect(), environment);
            } else if (select instanceof SetOperationList operations) {
                for (Select branch : operations.getSelects()) {
                    bindAny(branch, environment);
                }
            }
        }

        private void bindFromItems(PlainSelect plain, Map<String, Boolean> environment) {
            List<FromItem> items = new ArrayList<>();
            if (plain.getFromItem() != null) {
                items.add(plain.getFromItem());
            }
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    if (join.getRightItem() != null) {
                        items.add(join.getRightItem());
                    }
                }
            }
            for (FromItem item : items) {
                bindFromItem(item, environment);
            }
            // Subqueries may hide physical reads; walk expressions with the same CTE environment.
            ExpressionVisitor<Void> expressions = expressionVisitor(environment);
            plain.getSelectItems().forEach(item -> accept(item.getExpression(), expressions));
            accept(plain.getWhere(), expressions);
            accept(plain.getHaving(), expressions);
            if (plain.getGroupBy() != null) {
                accept(plain.getGroupBy().getGroupByExpressionList(), expressions);
            }
            if (plain.getOrderByElements() != null) {
                plain.getOrderByElements()
                        .forEach(order -> accept(order.getExpression(), expressions));
            }
            if (plain.getJoins() != null) {
                plain.getJoins().forEach(join -> accept(join.getOnExpressions(), expressions));
            }
        }

        private void bindFromItem(FromItem item, Map<String, Boolean> environment) {
            if (item instanceof Table table) {
                bindTable(table, environment);
            } else if (item instanceof Select nested) {
                bindAny(nested, environment);
            } else if (item instanceof ParenthesedFromItem parenthesed) {
                List<FromItem> items = new ArrayList<>();
                items.add(parenthesed.getFromItem());
                if (parenthesed.getJoins() != null) {
                    parenthesed.getJoins().forEach(join -> items.add(join.getRightItem()));
                }
                items.forEach(nested -> bindFromItem(nested, environment));
            }
        }

        private void bindTable(Table table, Map<String, Boolean> environment) {
            String catalogName = table.getDatabaseName();
            String schemaName = table.getSchemaName();
            boolean qualified = catalogName != null || schemaName != null;
            String name = table.getName();
            if (!qualified && name != null && environment.containsKey(name)) {
                // CTE reference: no physical relation is read here.
                return;
            }
            ResourceKey key;
            try {
                key =
                        new ResourceKey(
                                namespaceFor(catalogName, schemaName), name == null ? "" : name);
            } catch (IllegalArgumentException invalidName) {
                // Malformed or dialect-inconsistent names are reported, not silently skipped.
                unresolved.add(new ResourceKey(defaultNamespace, name == null ? "" : name));
                return;
            }
            Optional<ResourceRef> reference = catalog.find(key);
            if (reference.isPresent()) {
                physical.add(reference.get());
            } else {
                unresolved.add(key);
            }
        }

        private ResourceNamespace namespaceFor(String catalogName, String schemaName) {
            ResourceId datasourceId = defaultNamespace.datasourceId();
            DatabaseEngine engine = defaultNamespace.engine();
            String catalog = catalogName == null ? defaultNamespace.catalog() : catalogName;
            String schema = schemaName == null ? defaultNamespace.schema() : schemaName;
            return new ResourceNamespace(datasourceId, engine, catalog, schema);
        }

        private ExpressionVisitor<Void> expressionVisitor(Map<String, Boolean> environment) {
            ExpressionVisitorAdapter<Void> visitor =
                    new ExpressionVisitorAdapter<Void>() {
                        @Override
                        public <S> Void visit(Select select, S context) {
                            bindAny(select, environment);
                            return null;
                        }
                    };
            return visitor;
        }

        private static void accept(Iterable<?> expressions, ExpressionVisitor<Void> visitor) {
            if (expressions == null) {
                return;
            }
            for (Object expression : expressions) {
                if (expression instanceof net.sf.jsqlparser.expression.Expression candidate) {
                    candidate.accept(visitor, null);
                }
            }
        }

        private static void accept(
                net.sf.jsqlparser.expression.Expression expression,
                ExpressionVisitor<Void> visitor) {
            if (expression != null) {
                expression.accept(visitor, null);
            }
        }

        private static void accept(
                net.sf.jsqlparser.expression.operators.relational.ExpressionList<?> expressions,
                ExpressionVisitor<Void> visitor) {
            if (expressions == null) {
                return;
            }
            expressions.getExpressions().forEach(expression -> accept(expression, visitor));
        }
    }
}
