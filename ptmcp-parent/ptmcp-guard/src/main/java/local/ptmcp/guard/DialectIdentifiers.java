package local.ptmcp.guard;

import local.ptmcp.common.resource.DatabaseEngine;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

import java.util.List;

/**
 * Dialect-aware identifier spelling helpers shared by the binder and the final validator. The
 * parser keeps identifier quotes in {@code getName()} spellings, while snapshot names are stored
 * unquoted; comparisons and lookup keys must strip exactly one dialect quote layer without
 * otherwise rewriting the spelling.
 */
public final class DialectIdentifiers {
    private DialectIdentifiers() {}

    /** Strips one layer of the dialect's identifier quotes; absent or unmatched quotes stay. */
    public static String unquote(DatabaseEngine engine, String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        char quote = engine == DatabaseEngine.MYSQL ? '`' : '"';
        return strip(name, quote);
    }

    /**
     * Column references carry no reliable dialect context at their use sites; a matching outer pair
     * of either dialect quote character is treated as one quoting layer. The other dialect's quote
     * character cannot reach this path as an identifier (MySQL parses it as a string literal and
     * PostgreSQL rejects backticks lexically).
     */
    public static String unquoteAny(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        String backtick = strip(name, '`');
        return backtick != name ? backtick : strip(name, '"');
    }

    private static String strip(String name, char quote) {
        if (name.charAt(0) != quote || name.charAt(name.length() - 1) != quote) {
            return name;
        }
        String doubled = String.valueOf(quote) + quote;
        return name.substring(1, name.length() - 1).replace(doubled, String.valueOf(quote));
    }

    /**
     * Two-part {@code database.table} names parse into {@code schemaName}; for MySQL the first part
     * is the catalog, so the re-bound AST must expose it as such. This mutates only the freshly
     * parsed tree in memory, never the SQL text handed to an executor.
     */
    public static void normalizeMysqlRelations(Select select) {
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
                for (var order : plain.getOrderByElements()) {
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
}
