package local.ptmcp.guard.rewrite;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.ast.SqlAllowlistGate;
import local.ptmcp.guard.ast.SqlStatementGate;
import local.ptmcp.guard.budget.BoundedParser;
import local.ptmcp.guard.scope.ColumnBinder;
import local.ptmcp.guard.scope.ColumnBindingResult;
import local.ptmcp.guard.scope.RelationColumns;
import local.ptmcp.guard.scope.ScopeTableBinder;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Re-parses rendered rewrite output and proves it still carries the expected plan.
 *
 * <p>Validation repeats the read-only/statement gate, the function/CAST/namespace allowlist, the
 * physical-relation multiset, column provenance, output column order and the exact outer row limit.
 * Physical range aliases are allowed to change; CTE/derived aliases are not. Any parse failure,
 * structural diff or limit mismatch is reported as {@link ErrorCode#REWRITE_VALIDATION_FAILED} and
 * the text stays unexecutable.
 */
public final class FinalSqlValidator {
    private FinalSqlValidator() {}

    public static void validate(
            String finalSql,
            RewritePlan expected,
            ResourceNamespace defaultNamespace,
            ScopeTableBinder.RelationCatalog relations,
            RelationColumns columns,
            int expectedOuterLimit) {
        if (finalSql == null
                || expected == null
                || defaultNamespace == null
                || relations == null
                || columns == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (expectedOuterLimit < 1 || expectedOuterLimit > LimitRewriter.MAX_ROWS) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Select select = parse(finalSql);
        if (defaultNamespace.engine() == DatabaseEngine.MYSQL) {
            normalizeMysqlRelations(select);
        }
        ColumnBindingResult bindings = bind(select, defaultNamespace, relations, columns);
        compare(expected, bindings);
        checkOuterLimit(select, expectedOuterLimit);
    }

    private static Select parse(String finalSql) {
        if (finalSql.getBytes(StandardCharsets.UTF_8).length > BoundedParser.MAX_SQL_BYTES) {
            throw failed();
        }
        try {
            SqlStatementGate.check(finalSql);
            SqlAllowlistGate.check(finalSql);
            Statement statement = CCJSqlParserUtil.parse(finalSql);
            if (statement instanceof Select select) {
                return select;
            }
        } catch (RuntimeException | JSQLParserException failure) {
            throw failed();
        }
        throw failed();
    }

    private static ColumnBindingResult bind(
            Select select,
            ResourceNamespace defaultNamespace,
            ScopeTableBinder.RelationCatalog relations,
            RelationColumns columns) {
        try {
            ScopeTableBinder.BindingResult bound =
                    ScopeTableBinder.bind(select, defaultNamespace, relations);
            return ColumnBinder.bind(select, bound, columns);
        } catch (RuntimeException failure) {
            throw failed();
        }
    }

    private static void compare(RewritePlan expected, ColumnBindingResult actual) {
        Map<ResourceRef, Long> expectedRelations = multiset(expected.physicalRelations());
        Map<ResourceRef, Long> actualRelations = multiset(RewritePlan.physicalRelations(actual));
        if (!expectedRelations.equals(actualRelations)) {
            throw failed();
        }
        List<RewritePlan.BoundColumn> actualColumns =
                actual.bindings().stream().map(RewritePlan.BoundColumn::from).toList();
        if (!expected.columns().equals(actualColumns)) {
            throw failed();
        }
        List<String> actualNames =
                actual.primaryScope().outputs().stream().map(output -> output.name()).toList();
        if (!expected.outputNames().equals(actualNames)) {
            throw failed();
        }
    }

    private static void checkOuterLimit(Select select, int expected) {
        Limit limit = select.getLimit();
        if (limit == null || limit.isLimitAll() || limit.isLimitNull()) {
            throw failed();
        }
        if (!(limit.getRowCount() instanceof LongValue rows) || rows.getValue() != expected) {
            throw failed();
        }
        if (limit.getByExpressions() != null
                || select.getFetch() != null
                || select.getLimitBy() != null) {
            throw failed();
        }
    }

    private static Map<ResourceRef, Long> multiset(List<ResourceRef> relations) {
        return relations.stream().collect(Collectors.groupingBy(ref -> ref, Collectors.counting()));
    }

    /**
     * Two-part {@code database.table} names parse into {@code schemaName}; for MySQL the first part
     * is the catalog, so the re-bound AST must expose it as such. This mutates only the freshly
     * parsed validation tree, never the SQL text handed to an executor.
     */
    private static void normalizeMysqlRelations(Select select) {
        List<WithItem<?>> withItems = select.getWithItemsList();
        if (withItems != null) {
            for (WithItem<?> item : withItems) {
                normalizeMysqlRelations(item.getSelect());
            }
        }
        if (select instanceof PlainSelect plain) {
            normalizeFromItem(plain.getFromItem());
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    normalizeFromItem(join.getRightItem());
                }
            }
            ExpressionVisitorAdapter<Void> expressions =
                    new ExpressionVisitorAdapter<Void>() {
                        @Override
                        public <S> Void visit(ParenthesedSelect nested, S context) {
                            normalizeMysqlRelations(nested);
                            return null;
                        }

                        @Override
                        public <S> Void visit(Select nested, S context) {
                            normalizeMysqlRelations(nested);
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
            normalizeMysqlRelations(parenthesed.getSelect());
        } else if (select instanceof SetOperationList operations) {
            for (Select branch : operations.getSelects()) {
                normalizeMysqlRelations(branch);
            }
        }
    }

    private static void normalizeFromItem(FromItem item) {
        if (item instanceof Table table) {
            if (table.getSchemaName() != null && table.getDatabaseName() == null) {
                table.setDatabaseName(table.getSchemaName());
                table.setSchemaName(null);
            }
            return;
        }
        if (item instanceof ParenthesedSelect parenthesed) {
            normalizeMysqlRelations(parenthesed.getSelect());
            return;
        }
        if (item instanceof ParenthesedFromItem parenthesed) {
            normalizeFromItem(parenthesed.getFromItem());
            if (parenthesed.getJoins() != null) {
                for (Join join : parenthesed.getJoins()) {
                    normalizeFromItem(join.getRightItem());
                }
            }
        }
    }

    private static void accept(Expression expression, ExpressionVisitorAdapter<Void> visitor) {
        if (expression != null) {
            expression.accept(visitor, null);
        }
    }

    private static DomainException failed() {
        return new DomainException(ErrorCode.REWRITE_VALIDATION_FAILED);
    }
}
