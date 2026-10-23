package local.ptmcp.guard.scope;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.FunctionAllColumns;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Binds every column reference to a concrete range and expands stars from snapshot columns.
 *
 * <p>Ranges are built from the same traversal order as {@link ScopeTableBinder}, so physical
 * references are consumed once each, including duplicates. Unqualified columns bind only when the
 * current block has exactly one candidate; otherwise the lookup follows the correlated parent
 * chain. Qualified references resolve aliases first and explicit catalog/schema paths only when the
 * range has no alias. CTE and derived ranges keep physical provenance in {@code sources} and are
 * never rewritten into physical tables here.
 *
 * <p>This does not authorize resources, rewrite SQL or approve execution.
 */
public final class ColumnBinder {
    private ColumnBinder() {}

    public static ColumnBindingResult bind(
            Select select,
            ScopeTableBinder.BindingResult relations,
            RelationColumns columnCatalog) {
        if (select == null || relations == null || columnCatalog == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!relations.unresolved().isEmpty()) {
            // Unknown relations are rejected before any column decision can be made.
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new Binder(relations, columnCatalog).bindTop(select);
    }

    /** The kind of range a column was resolved against. */
    public enum RangeKind {
        PHYSICAL,
        CTE,
        DERIVED
    }

    private record CteDef(List<OutputColumn> outputs) {
        CteDef {
            outputs = List.copyOf(outputs);
        }
    }

    private record ResolvedColumn(ColumnBinding usage, List<ColumnBinding> sources) {
        ResolvedColumn {
            sources = List.copyOf(sources);
        }
    }

    private static final class Scope {
        private final int id;
        private final Scope parent;
        private final Map<String, RangeBinding> ranges = new LinkedHashMap<>();
        private final List<ColumnBinding> bindings = new ArrayList<>();
        private final boolean primary;
        private List<OutputColumn> outputs = List.of();

        Scope(int id, Scope parent, boolean primary) {
            this.id = id;
            this.parent = parent;
            this.primary = primary;
        }
    }

    private static final class Binder {
        private final List<ResourceRef> remainingRelations;
        private final RelationColumns columnCatalog;
        private final List<ScopeColumns> scopes = new ArrayList<>();
        private final List<ColumnBinding> allBindings = new ArrayList<>();
        private ScopeColumns primaryScope;
        private int nextScopeId = 1;

        Binder(ScopeTableBinder.BindingResult relations, RelationColumns columnCatalog) {
            this.remainingRelations = new ArrayList<>(relations.physicalRefs());
            this.columnCatalog = columnCatalog;
        }

        ColumnBindingResult bindTop(Select select) {
            bindAny(select, null, Map.of(), true);
            if (!remainingRelations.isEmpty()) {
                // A relation reported by the scope binder was not consumed: internal mismatch.
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return new ColumnBindingResult(
                    primaryScope, List.copyOf(scopes), List.copyOf(allBindings));
        }

        private Scope bindAny(
                Select select, Scope parent, Map<String, CteDef> inherited, boolean primary) {
            if (select == null) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            Map<String, CteDef> environment = new LinkedHashMap<>(inherited);
            List<WithItem<?>> withItems = select.getWithItemsList();
            if (withItems != null) {
                for (WithItem<?> item : withItems) {
                    if (item.isRecursive()) {
                        throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
                    }
                    String name = item.getAliasName();
                    if (name == null || name.isEmpty() || environment.containsKey(name)) {
                        throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                    }
                    Select definition = item.getSelect();
                    if (definition == null) {
                        throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
                    }
                    Scope definitionScope = bindAny(definition, parent, environment, false);
                    environment.put(
                            name,
                            new CteDef(applyExplicitColumnNames(item, definitionScope.outputs)));
                }
            }
            return bindBody(select, parent, environment, primary);
        }

        private Scope bindBody(
                Select select, Scope parent, Map<String, CteDef> environment, boolean primary) {
            if (select instanceof PlainSelect plain) {
                return bindPlainSelect(plain, parent, environment, primary);
            }
            if (select instanceof ParenthesedSelect parenthesed) {
                return bindAny(parenthesed.getSelect(), parent, environment, primary);
            }
            if (select instanceof SetOperationList operations) {
                return bindSetOperations(operations, parent, environment, primary);
            }
            throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
        }

        private Scope bindPlainSelect(
                PlainSelect plain, Scope parent, Map<String, CteDef> environment, boolean primary) {
            Scope scope = newScope(parent, primary);
            bindFromItems(plain, scope, environment);

            List<OutputColumn> outputs = new ArrayList<>();
            List<SelectItem<?>> items = plain.getSelectItems();
            if (items == null || items.isEmpty()) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            for (int index = 0; index < items.size(); index++) {
                SelectItem<?> item = items.get(index);
                Expression expression = item.getExpression();
                if (expression instanceof AllTableColumns tableStar) {
                    outputs.addAll(expandTableStar(tableStar, scope, outputs.size()));
                } else if (expression instanceof AllColumns) {
                    outputs.addAll(expandAll(scope, outputs.size()));
                } else {
                    List<ColumnBinding> sources =
                            bindExpression(expression, scope, environment, false);
                    outputs.add(
                            new OutputColumn(
                                    outputs.size(),
                                    outputName(item, expression, outputs.size()),
                                    sources));
                }
            }
            scope.outputs = List.copyOf(outputs);
            bindClauses(plain, scope, environment);
            return finish(scope);
        }

        private Scope bindSetOperations(
                SetOperationList operations,
                Scope parent,
                Map<String, CteDef> environment,
                boolean primary) {
            Scope aggregate = newScope(parent, primary);
            List<Scope> branches = new ArrayList<>();
            for (Select branch : operations.getSelects()) {
                branches.add(bindAny(branch, parent, environment, false));
            }
            int arity = branches.get(0).outputs.size();
            for (Scope branch : branches) {
                if (branch.outputs.size() != arity) {
                    throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                }
            }
            List<OutputColumn> outputs = new ArrayList<>();
            for (int index = 0; index < arity; index++) {
                List<ColumnBinding> sources = new ArrayList<>();
                for (Scope branch : branches) {
                    sources.addAll(branch.outputs.get(index).sources());
                }
                outputs.add(
                        new OutputColumn(
                                index, branches.get(0).outputs.get(index).name(), sources));
            }
            aggregate.outputs = List.copyOf(outputs);
            return finish(aggregate);
        }

        private Scope newScope(Scope parent, boolean primary) {
            return new Scope(nextScopeId++, parent, primary);
        }

        private Scope finish(Scope scope) {
            ScopeColumns columns =
                    new ScopeColumns(
                            scope.id,
                            scope.parent == null ? null : scope.parent.id,
                            List.copyOf(scope.ranges.values()),
                            scope.outputs,
                            List.copyOf(scope.bindings));
            scopes.add(columns);
            if (scope.primary) {
                primaryScope = columns;
            }
            return scope;
        }

        private void bindFromItems(
                PlainSelect plain, Scope scope, Map<String, CteDef> environment) {
            if (plain.getFromItem() != null) {
                bindFromItem(plain.getFromItem(), scope, environment);
            }
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    if (join.getRightItem() != null) {
                        bindFromItem(join.getRightItem(), scope, environment);
                    }
                }
            }
        }

        private void bindFromItem(FromItem item, Scope scope, Map<String, CteDef> environment) {
            if (item instanceof Table table) {
                String name = table.getName();
                boolean qualified =
                        table.getDatabaseName() != null || table.getSchemaName() != null;
                CteDef cte = !qualified && name != null ? environment.get(name) : null;
                if (cte != null) {
                    addRange(scope, cteRange(table, cte));
                } else {
                    addRange(scope, physicalRange(table));
                }
                return;
            }
            if (item instanceof ParenthesedSelect parenthesed) {
                addRange(scope, derivedRange(parenthesed, environment));
                return;
            }
            if (item instanceof ParenthesedFromItem parenthesed) {
                if (parenthesed.getAlias() != null) {
                    throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
                }
                if (parenthesed.getFromItem() != null) {
                    bindFromItem(parenthesed.getFromItem(), scope, environment);
                }
                if (parenthesed.getJoins() != null) {
                    for (Join join : parenthesed.getJoins()) {
                        if (join.getRightItem() != null) {
                            bindFromItem(join.getRightItem(), scope, environment);
                        }
                    }
                }
                return;
            }
            throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
        }

        private RangeBinding physicalRange(Table table) {
            ResourceRef resource = takePhysicalRef(table);
            String alias = aliasName(table);
            List<ColumnDef> columns = columnCatalog.columns(resource);
            if (columns == null || columns.isEmpty()) {
                throw new DomainException(ErrorCode.SCHEMA_STALE);
            }
            List<ColumnBinding> sources = new ArrayList<>();
            for (ColumnDef column : columns) {
                sources.add(
                        new ColumnBinding(
                                alias,
                                RangeKind.PHYSICAL,
                                resource,
                                column.name(),
                                column.ordinal()));
            }
            return new RangeBinding(
                    alias, RangeKind.PHYSICAL, resource, List.copyOf(columns), sources);
        }

        private ResourceRef takePhysicalRef(Table table) {
            for (int index = 0; index < remainingRelations.size(); index++) {
                ResourceRef candidate = remainingRelations.get(index);
                if (!candidate.key().objectName().equals(table.getName())) {
                    continue;
                }
                ResourceNamespace namespace = candidate.key().namespace();
                if (table.getDatabaseName() != null
                        && !table.getDatabaseName().equals(namespace.catalog())) {
                    continue;
                }
                if (table.getSchemaName() != null
                        && !table.getSchemaName().equals(namespace.schema())) {
                    continue;
                }
                remainingRelations.remove(index);
                return candidate;
            }
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }

        private RangeBinding derivedRange(
                ParenthesedSelect parenthesed, Map<String, CteDef> environment) {
            String alias = parenthesed.getAlias() == null ? null : parenthesed.getAlias().getName();
            if (alias == null || alias.isEmpty()) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            // Derived tables cannot correlate to sibling or outer ranges without LATERAL.
            Scope child = bindAny(parenthesed.getSelect(), null, environment, false);
            List<ColumnDef> columns = new ArrayList<>();
            List<ColumnBinding> sources = new ArrayList<>();
            for (int index = 0; index < child.outputs.size(); index++) {
                OutputColumn output = child.outputs.get(index);
                columns.add(new ColumnDef(index, output.name(), "UNKNOWN", true));
                sources.addAll(output.sources());
            }
            return new RangeBinding(alias, RangeKind.DERIVED, null, columns, sources);
        }

        private RangeBinding cteRange(Table table, CteDef cte) {
            String alias = aliasName(table);
            List<ColumnDef> columns = new ArrayList<>();
            List<ColumnBinding> sources = new ArrayList<>();
            for (int index = 0; index < cte.outputs().size(); index++) {
                OutputColumn output = cte.outputs().get(index);
                columns.add(new ColumnDef(index, output.name(), "UNKNOWN", true));
                sources.addAll(output.sources());
            }
            return new RangeBinding(alias, RangeKind.CTE, null, columns, sources);
        }

        private void addRange(Scope scope, RangeBinding range) {
            if (scope.ranges.putIfAbsent(range.alias(), range) != null) {
                throw new DomainException(ErrorCode.RESOURCE_AMBIGUOUS);
            }
        }

        private List<OutputColumn> expandAll(Scope scope, int startOrdinal) {
            if (scope.ranges.isEmpty()) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            List<OutputColumn> outputs = new ArrayList<>();
            for (RangeBinding range : scope.ranges.values()) {
                outputs.addAll(expandRange(range, scope, startOrdinal + outputs.size()));
            }
            return outputs;
        }

        private List<OutputColumn> expandTableStar(
                AllTableColumns star, Scope scope, int startOrdinal) {
            RangeBinding range = resolveRange(star.getTable(), scope);
            if (range == null) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            return expandRange(range, scope, startOrdinal);
        }

        private List<OutputColumn> expandRange(RangeBinding range, Scope scope, int startOrdinal) {
            List<OutputColumn> outputs = new ArrayList<>();
            for (int index = 0; index < range.columns().size(); index++) {
                ColumnDef column = range.columns().get(index);
                ColumnBinding usage =
                        new ColumnBinding(
                                range.alias(),
                                range.kind(),
                                range.resource(),
                                column.name(),
                                column.ordinal());
                scope.bindings.add(usage);
                allBindings.add(usage);
                outputs.add(
                        new OutputColumn(
                                startOrdinal + index,
                                column.name(),
                                List.of(range.sources().get(index))));
            }
            return outputs;
        }

        private void bindClauses(PlainSelect plain, Scope scope, Map<String, CteDef> environment) {
            bindExpression(plain.getWhere(), scope, environment, false);
            bindExpression(plain.getHaving(), scope, environment, true);
            GroupByElement groupBy = plain.getGroupBy();
            if (groupBy != null && groupBy.getGroupByExpressionList() != null) {
                for (Expression expression : groupBy.getGroupByExpressionList()) {
                    bindExpression(expression, scope, environment, true);
                }
            }
            if (plain.getOrderByElements() != null) {
                for (OrderByElement order : plain.getOrderByElements()) {
                    bindExpression(order.getExpression(), scope, environment, true);
                }
            }
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    if (join.getOnExpressions() != null) {
                        for (Expression expression : join.getOnExpressions()) {
                            bindExpression(expression, scope, environment, false);
                        }
                    }
                }
            }
        }

        private List<ColumnBinding> bindExpression(
                Expression expression,
                Scope scope,
                Map<String, CteDef> environment,
                boolean allowOutputAlias) {
            if (expression == null) {
                return List.of();
            }
            BindingVisitor visitor = new BindingVisitor(scope, environment, allowOutputAlias);
            expression.accept(visitor, null);
            return List.copyOf(visitor.sources);
        }

        private RangeBinding resolveRange(Table table, Scope scope) {
            String name = table.getName();
            if (name == null || name.isEmpty()) {
                return null;
            }
            boolean qualified = table.getDatabaseName() != null || table.getSchemaName() != null;
            for (Scope current = scope; current != null; current = current.parent) {
                if (!qualified) {
                    RangeBinding alias = current.ranges.get(name);
                    if (alias != null) {
                        return alias;
                    }
                    continue;
                }
                List<RangeBinding> matches =
                        current.ranges.values().stream()
                                .filter(range -> matchesPath(range, table))
                                .toList();
                if (matches.size() == 1) {
                    return matches.get(0);
                }
                if (matches.size() > 1) {
                    throw new DomainException(ErrorCode.RESOURCE_AMBIGUOUS);
                }
            }
            return null;
        }

        private boolean matchesPath(RangeBinding range, Table table) {
            if (range.resource() == null || !range.alias().equals(table.getName())) {
                return false;
            }
            ResourceNamespace namespace = range.resource().key().namespace();
            if (table.getDatabaseName() != null
                    && !table.getDatabaseName().equals(namespace.catalog())) {
                return false;
            }
            return table.getSchemaName() == null
                    || table.getSchemaName().equals(namespace.schema());
        }

        private ResolvedColumn resolveColumn(Column column, Scope scope, boolean allowOutputAlias) {
            Table qualifier = column.getTable();
            String tableName = qualifier == null ? null : qualifier.getName();
            if (tableName == null || tableName.isEmpty()) {
                return resolveUnqualified(column.getColumnName(), scope, allowOutputAlias);
            }
            RangeBinding range = resolveRange(qualifier, scope);
            if (range == null) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            return bindToRange(range, column.getColumnName());
        }

        private ResolvedColumn resolveUnqualified(
                String columnName, Scope scope, boolean allowOutputAlias) {
            for (Scope current = scope; current != null; current = current.parent) {
                List<RangeBinding> candidates =
                        current.ranges.values().stream()
                                .filter(range -> indexOf(range.columns(), columnName) >= 0)
                                .toList();
                if (candidates.size() > 1) {
                    throw new DomainException(ErrorCode.RESOURCE_AMBIGUOUS);
                }
                if (candidates.size() == 1) {
                    return bindToRange(candidates.get(0), columnName);
                }
            }
            if (allowOutputAlias) {
                for (Scope current = scope; current != null; current = current.parent) {
                    List<OutputColumn> candidates =
                            current.outputs.stream()
                                    .filter(output -> output.name().equals(columnName))
                                    .toList();
                    if (candidates.size() > 1) {
                        throw new DomainException(ErrorCode.RESOURCE_AMBIGUOUS);
                    }
                    if (candidates.size() == 1) {
                        return new ResolvedColumn(null, candidates.get(0).sources());
                    }
                }
            }
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }

        private ResolvedColumn bindToRange(RangeBinding range, String columnName) {
            int index = indexOf(range.columns(), columnName);
            if (index < 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            ColumnDef definition = range.columns().get(index);
            ColumnBinding usage =
                    new ColumnBinding(
                            range.alias(),
                            range.kind(),
                            range.resource(),
                            columnName,
                            definition.ordinal());
            List<ColumnBinding> sources =
                    range.kind() == RangeKind.PHYSICAL
                            ? List.of(usage)
                            : List.of(range.sources().get(index));
            return new ResolvedColumn(usage, sources);
        }

        private static int indexOf(List<ColumnDef> columns, String name) {
            for (int index = 0; index < columns.size(); index++) {
                if (columns.get(index).name().equals(name)) {
                    return index;
                }
            }
            return -1;
        }

        private static String aliasName(Table table) {
            String alias = table.getAlias() == null ? null : table.getAlias().getName();
            if (alias == null || alias.isEmpty()) {
                alias = table.getName();
            }
            if (alias == null || alias.isEmpty()) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            return alias;
        }

        private static String outputName(SelectItem<?> item, Expression expression, int ordinal) {
            if (item.getAlias() != null) {
                return item.getAlias().getName();
            }
            if (expression instanceof Column column) {
                return column.getColumnName();
            }
            return "__ptmcp_c" + (ordinal + 1);
        }

        private static List<OutputColumn> applyExplicitColumnNames(
                WithItem<?> item, List<OutputColumn> outputs) {
            List<SelectItem<?>> names = item.getWithItemList();
            if (names == null || names.isEmpty()) {
                return outputs;
            }
            if (names.size() != outputs.size()) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            Set<String> seen = new HashSet<>();
            List<OutputColumn> renamed = new ArrayList<>();
            for (int index = 0; index < names.size(); index++) {
                String name = explicitName(names.get(index));
                if (name == null || name.isEmpty() || !seen.add(name)) {
                    throw new DomainException(ErrorCode.RESOURCE_AMBIGUOUS);
                }
                OutputColumn original = outputs.get(index);
                renamed.add(new OutputColumn(index, name, original.sources()));
            }
            return List.copyOf(renamed);
        }

        private static String explicitName(SelectItem<?> item) {
            if (item.getAlias() != null) {
                return item.getAlias().getName();
            }
            if (item.getExpression() instanceof Column column) {
                return column.getColumnName();
            }
            return Objects.toString(item.getExpression(), null);
        }

        private final class BindingVisitor extends ExpressionVisitorAdapter<Void> {
            private final Scope scope;
            private final Map<String, CteDef> environment;
            private final boolean allowOutputAlias;
            private final List<ColumnBinding> sources = new ArrayList<>();

            BindingVisitor(Scope scope, Map<String, CteDef> environment, boolean allowOutputAlias) {
                this.scope = scope;
                this.environment = environment;
                this.allowOutputAlias = allowOutputAlias;
            }

            @Override
            public <S> Void visit(Column column, S context) {
                ResolvedColumn resolved = resolveColumn(column, scope, allowOutputAlias);
                if (resolved.usage() != null) {
                    scope.bindings.add(resolved.usage());
                    allBindings.add(resolved.usage());
                }
                sources.addAll(resolved.sources());
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect select, S context) {
                Scope child = bindAny(select, scope, environment, false);
                child.outputs.forEach(output -> sources.addAll(output.sources()));
                return null;
            }

            @Override
            public <S> Void visit(Select select, S context) {
                Scope child = bindAny(select, scope, environment, false);
                child.outputs.forEach(output -> sources.addAll(output.sources()));
                return null;
            }

            @Override
            public <S> Void visit(AllColumns columns, S context) {
                // COUNT(*) and similar aggregate stars carry no column reference.
                return null;
            }

            @Override
            public <S> Void visit(FunctionAllColumns columns, S context) {
                return null;
            }

            @Override
            public <S> Void visit(AllTableColumns columns, S context) {
                throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
            }
        }
    }
}
