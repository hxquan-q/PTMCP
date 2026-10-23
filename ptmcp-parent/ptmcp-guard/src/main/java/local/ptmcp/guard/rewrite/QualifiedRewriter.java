package local.ptmcp.guard.rewrite;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.scope.ColumnBinder;
import local.ptmcp.guard.scope.ColumnBindingResult;
import local.ptmcp.guard.scope.RangeBinding;
import local.ptmcp.guard.scope.ScopeColumns;

import net.sf.jsqlparser.expression.Alias;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces physical range names with their snapshot-qualified form and synchronises every qualified
 * column and table-star reference to the range alias.
 *
 * <p>Physical ranges keep a user alias when one exists; otherwise they receive a statement-unique
 * {@code __ptmcp_r<n>} alias so column references never repeat a catalog/schema path. CTE and
 * derived ranges are never turned into physical tables. Unqualified columns and output aliases are
 * left alone: their binding is preserved by the range lookup on re-parse, and rewriting them would
 * risk mixing the output-alias and range-alias naming domains.
 *
 * <p>This walks the AST in the same scope order as {@link ColumnBinder}; a structural mismatch with
 * the supplied binding result is rejected as {@link ErrorCode#REWRITE_VALIDATION_FAILED}. The
 * result is NOT an approved query and must be re-parsed and validated by {@link FinalSqlValidator}.
 */
public final class QualifiedRewriter {
    public static final String INTERNAL_ALIAS_PREFIX = "__ptmcp_r";

    private QualifiedRewriter() {}

    public static RewrittenSql rewrite(Select select, ColumnBindingResult bindings) {
        if (select == null || bindings == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Rewriter rewriter = new Rewriter(select, bindings);
        rewriter.rewriteTop(select);
        return new RewrittenSql(select.toString(), RewritePlan.from(bindings));
    }

    private static DomainException failed() {
        return new DomainException(ErrorCode.REWRITE_VALIDATION_FAILED);
    }

    private record Resolved(RangeBinding range, String rewrittenAlias) {}

    private static final class Frame {
        private final ScopeColumns model;
        private final Frame parent;
        private final Map<String, RangeBinding> byAlias = new LinkedHashMap<>();
        private final Map<String, String> rewritten = new HashMap<>();
        private int nextRange;

        Frame(ScopeColumns model, Frame parent) {
            this.model = model;
            this.parent = parent;
            for (RangeBinding range : model.ranges()) {
                byAlias.put(range.alias(), range);
            }
        }

        RangeBinding takeRange() {
            if (nextRange >= model.ranges().size()) {
                throw failed();
            }
            return model.ranges().get(nextRange++);
        }

        boolean rangesConsumed() {
            return nextRange == model.ranges().size();
        }

        void rewrittenAlias(RangeBinding range, String alias) {
            rewritten.put(range.alias(), alias);
        }

        String rewrittenAlias(RangeBinding range) {
            String alias = rewritten.get(range.alias());
            if (alias == null) {
                throw failed();
            }
            return alias;
        }

        RangeBinding byAlias(String alias) {
            return byAlias.get(alias);
        }
    }

    private static final class Rewriter {
        private final Map<Integer, ScopeColumns> scopeModels = new HashMap<>();
        private final Set<String> reservedNames = new HashSet<>();
        private int nextScopeId = 1;
        private int nextAliasId = 1;

        Rewriter(Select root, ColumnBindingResult bindings) {
            for (ScopeColumns scope : bindings.scopes()) {
                scopeModels.put(scope.scopeId(), scope);
            }
            collectReserved(root);
        }

        void rewriteTop(Select root) {
            walkSelect(root, null);
            if (nextScopeId - 1 != scopeModels.size()) {
                throw failed();
            }
        }

        private Frame enter(Frame parent) {
            ScopeColumns model = scopeModels.get(nextScopeId++);
            if (model == null) {
                throw failed();
            }
            return new Frame(model, parent);
        }

        private void walkSelect(Select select, Frame parent) {
            if (select == null) {
                throw failed();
            }
            List<WithItem<?>> withItems = select.getWithItemsList();
            if (withItems != null) {
                for (WithItem<?> item : withItems) {
                    if (item.isRecursive() || item.getSelect() == null) {
                        throw failed();
                    }
                    walkSelect(item.getSelect(), parent);
                }
            }
            walkBody(select, parent);
        }

        private void walkBody(Select select, Frame parent) {
            if (select instanceof PlainSelect plain) {
                walkPlain(plain, parent);
            } else if (select instanceof ParenthesedSelect parenthesed) {
                walkSelect(parenthesed.getSelect(), parent);
            } else if (select instanceof SetOperationList operations) {
                Frame aggregate = enter(parent);
                for (Select branch : operations.getSelects()) {
                    walkSelect(branch, parent);
                }
                if (!aggregate.rangesConsumed()) {
                    throw failed();
                }
            } else {
                throw failed();
            }
        }

        private void walkPlain(PlainSelect plain, Frame parent) {
            Frame frame = enter(parent);
            if (plain.getFromItem() != null) {
                walkFromItem(plain.getFromItem(), frame);
            }
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    if (join.getRightItem() != null) {
                        walkFromItem(join.getRightItem(), frame);
                    }
                }
            }

            List<SelectItem<?>> items = plain.getSelectItems();
            if (items == null || items.isEmpty()) {
                throw failed();
            }
            for (SelectItem<?> item : items) {
                Expression expression = item.getExpression();
                if (expression instanceof AllTableColumns star) {
                    rewriteStar(star, frame);
                } else if (!(expression instanceof AllColumns)) {
                    rewriteExpression(expression, frame);
                }
            }
            rewriteExpression(plain.getWhere(), frame);
            rewriteExpression(plain.getHaving(), frame);
            GroupByElement groupBy = plain.getGroupBy();
            if (groupBy != null && groupBy.getGroupByExpressionList() != null) {
                for (Expression expression : groupBy.getGroupByExpressionList()) {
                    rewriteExpression(expression, frame);
                }
            }
            if (plain.getOrderByElements() != null) {
                for (OrderByElement order : plain.getOrderByElements()) {
                    rewriteExpression(order.getExpression(), frame);
                }
            }
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    if (join.getOnExpressions() != null) {
                        for (Expression expression : join.getOnExpressions()) {
                            rewriteExpression(expression, frame);
                        }
                    }
                }
            }
            if (!frame.rangesConsumed()) {
                throw failed();
            }
        }

        private void walkFromItem(FromItem item, Frame frame) {
            if (item instanceof Table table) {
                rewriteTableRange(table, frame);
                return;
            }
            if (item instanceof ParenthesedSelect parenthesed) {
                walkSelect(parenthesed.getSelect(), frame);
                RangeBinding range = frame.takeRange();
                String alias =
                        parenthesed.getAlias() == null ? null : parenthesed.getAlias().getName();
                if (alias == null
                        || alias.isEmpty()
                        || range.kind() != ColumnBinder.RangeKind.DERIVED
                        || !range.alias().equals(alias)) {
                    throw failed();
                }
                frame.rewrittenAlias(range, range.alias());
                return;
            }
            if (item instanceof ParenthesedFromItem parenthesed) {
                if (parenthesed.getAlias() != null) {
                    throw failed();
                }
                if (parenthesed.getFromItem() != null) {
                    walkFromItem(parenthesed.getFromItem(), frame);
                }
                if (parenthesed.getJoins() != null) {
                    for (Join join : parenthesed.getJoins()) {
                        if (join.getRightItem() != null) {
                            walkFromItem(join.getRightItem(), frame);
                        }
                    }
                }
                return;
            }
            throw failed();
        }

        private void rewriteTableRange(Table table, Frame frame) {
            RangeBinding range = frame.takeRange();
            if (!range.alias().equals(aliasName(table))) {
                throw failed();
            }
            if (range.kind() != ColumnBinder.RangeKind.PHYSICAL
                    && range.kind() != ColumnBinder.RangeKind.CTE) {
                throw failed();
            }
            if (range.kind() == ColumnBinder.RangeKind.CTE) {
                if (table.getDatabaseName() != null || table.getSchemaName() != null) {
                    throw failed();
                }
                frame.rewrittenAlias(range, range.alias());
                return;
            }
            qualify(table, range.resource());
            String alias = existingAlias(table);
            if (alias == null) {
                alias = generateAlias();
                table.setAlias(new Alias(alias, true));
            }
            frame.rewrittenAlias(range, alias);
        }

        private void rewriteStar(AllTableColumns star, Frame frame) {
            Resolved resolved = resolve(star.getTable(), frame);
            star.setTable(new Table(resolved.rewrittenAlias()));
        }

        private void rewriteExpression(Expression expression, Frame frame) {
            if (expression != null) {
                expression.accept(new RewriteVisitor(frame), null);
            }
        }

        private Resolved resolve(Table qualifier, Frame frame) {
            String name = qualifier == null ? null : qualifier.getName();
            if (name == null || name.isEmpty()) {
                throw failed();
            }
            boolean pathQualified =
                    qualifier.getDatabaseName() != null || qualifier.getSchemaName() != null;
            for (Frame current = frame; current != null; current = current.parent) {
                if (!pathQualified) {
                    RangeBinding range = current.byAlias(name);
                    if (range != null) {
                        return new Resolved(range, current.rewrittenAlias(range));
                    }
                    continue;
                }
                List<RangeBinding> matches =
                        current.model.ranges().stream()
                                .filter(range -> matchesPath(range, qualifier))
                                .toList();
                if (matches.size() > 1) {
                    throw failed();
                }
                if (matches.size() == 1) {
                    RangeBinding range = matches.get(0);
                    return new Resolved(range, current.rewrittenAlias(range));
                }
            }
            throw failed();
        }

        private final class RewriteVisitor extends ExpressionVisitorAdapter<Void> {
            private final Frame frame;

            RewriteVisitor(Frame frame) {
                this.frame = frame;
            }

            @Override
            public <S> Void visit(Column column, S context) {
                Table qualifier = column.getTable();
                if (qualifier == null
                        || qualifier.getName() == null
                        || qualifier.getName().isEmpty()) {
                    return null;
                }
                column.setTable(new Table(resolve(qualifier, frame).rewrittenAlias()));
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect select, S context) {
                walkSelect(select, frame);
                return null;
            }

            @Override
            public <S> Void visit(Select select, S context) {
                walkSelect(select, frame);
                return null;
            }

            @Override
            public <S> Void visit(AllColumns columns, S context) {
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

        private void qualify(Table table, ResourceRef resource) {
            ResourceNamespace namespace = resource.key().namespace();
            String schema =
                    switch (namespace.engine()) {
                        case MYSQL -> namespace.catalog();
                        case POSTGRESQL -> namespace.schema();
                    };
            table.setDatabaseName(null);
            table.setSchemaName(schema);
            table.setName(resource.key().objectName());
        }

        private String generateAlias() {
            while (true) {
                String candidate = INTERNAL_ALIAS_PREFIX + nextAliasId++;
                if (reservedNames.add(candidate)) {
                    return candidate;
                }
            }
        }

        private void collectReserved(Select select) {
            if (select == null) {
                return;
            }
            List<WithItem<?>> withItems = select.getWithItemsList();
            if (withItems != null) {
                for (WithItem<?> item : withItems) {
                    if (item.getAliasName() != null) {
                        reservedNames.add(item.getAliasName());
                    }
                    collectReserved(item.getSelect());
                }
            }
            if (select instanceof PlainSelect plain) {
                collectFromItem(plain.getFromItem());
                if (plain.getJoins() != null) {
                    for (Join join : plain.getJoins()) {
                        collectFromItem(join.getRightItem());
                    }
                }
                ExpressionVisitorAdapter<Void> expressions =
                        new ExpressionVisitorAdapter<Void>() {
                            @Override
                            public <S> Void visit(ParenthesedSelect nested, S context) {
                                collectReserved(nested);
                                return null;
                            }

                            @Override
                            public <S> Void visit(Select nested, S context) {
                                collectReserved(nested);
                                return null;
                            }
                        };
                for (SelectItem<?> item : plain.getSelectItems()) {
                    accept(item.getExpression(), expressions);
                }
                accept(plain.getWhere(), expressions);
                accept(plain.getHaving(), expressions);
                if (plain.getGroupBy() != null
                        && plain.getGroupBy().getGroupByExpressionList() != null) {
                    for (Expression expression : plain.getGroupBy().getGroupByExpressionList()) {
                        accept(expression, expressions);
                    }
                }
                if (plain.getOrderByElements() != null) {
                    for (OrderByElement order : plain.getOrderByElements()) {
                        accept(order.getExpression(), expressions);
                    }
                }
                if (plain.getJoins() != null) {
                    for (Join join : plain.getJoins()) {
                        if (join.getOnExpressions() != null) {
                            for (Expression expression : join.getOnExpressions()) {
                                accept(expression, expressions);
                            }
                        }
                    }
                }
            } else if (select instanceof ParenthesedSelect parenthesed) {
                collectReserved(parenthesed.getSelect());
            } else if (select instanceof SetOperationList operations) {
                for (Select branch : operations.getSelects()) {
                    collectReserved(branch);
                }
            }
        }

        private void collectFromItem(FromItem item) {
            if (item == null) {
                return;
            }
            if (item instanceof Table table) {
                if (table.getName() != null) {
                    reservedNames.add(table.getName());
                }
                String alias = existingAlias(table);
                if (alias != null) {
                    reservedNames.add(alias);
                }
                return;
            }
            if (item instanceof ParenthesedSelect parenthesed) {
                String alias =
                        parenthesed.getAlias() == null ? null : parenthesed.getAlias().getName();
                if (alias != null) {
                    reservedNames.add(alias);
                }
                collectReserved(parenthesed);
                return;
            }
            if (item instanceof ParenthesedFromItem parenthesed) {
                String alias =
                        parenthesed.getAlias() == null ? null : parenthesed.getAlias().getName();
                if (alias != null) {
                    reservedNames.add(alias);
                }
                collectFromItem(parenthesed.getFromItem());
                if (parenthesed.getJoins() != null) {
                    for (Join join : parenthesed.getJoins()) {
                        collectFromItem(join.getRightItem());
                    }
                }
            }
        }

        private static void accept(Expression expression, ExpressionVisitorAdapter<Void> visitor) {
            if (expression != null) {
                expression.accept(visitor, null);
            }
        }
    }

    private static String existingAlias(Table table) {
        String alias = table.getAlias() == null ? null : table.getAlias().getName();
        return alias == null || alias.isEmpty() ? null : alias;
    }

    private static String aliasName(Table table) {
        String alias = existingAlias(table);
        if (alias != null) {
            return alias;
        }
        String name = table.getName();
        if (name == null || name.isEmpty()) {
            throw failed();
        }
        return name;
    }

    private static boolean matchesPath(RangeBinding range, Table table) {
        if (range.resource() == null || !range.alias().equals(table.getName())) {
            return false;
        }
        ResourceNamespace namespace = range.resource().key().namespace();
        if (table.getDatabaseName() != null
                && !table.getDatabaseName().equals(namespace.catalog())) {
            return false;
        }
        return table.getSchemaName() == null || table.getSchemaName().equals(namespace.schema());
    }
}
