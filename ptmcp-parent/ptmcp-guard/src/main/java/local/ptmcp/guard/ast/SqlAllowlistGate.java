package local.ptmcp.guard.ast;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;

import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed allowlist for functions, CAST target types and relation namespaces.
 *
 * <p>Any function outside the reviewed set is rejected, path-qualified functions are rejected, CAST
 * targets must be basic scalar types, and system catalogs/schemas (or reserved {@code pg_*} names)
 * are rejected. This is a positive list: unknown functions never fall through to acceptance.
 *
 * <p>It does NOT prove read-only semantics, output typing, relation existence, permissions or
 * ownership, and it does not yet dispatch on every unknown expression node; the scope binder and
 * expression whitelist stages must still traverse the tree explicitly. Passing grants nothing.
 */
public final class SqlAllowlistGate {
    private static final Set<String> ALLOWED_FUNCTIONS =
            Set.of(
                    "count",
                    "sum",
                    "min",
                    "max",
                    "avg",
                    "abs",
                    "round",
                    "lower",
                    "upper",
                    "length",
                    "coalesce",
                    "nullif",
                    "row_number",
                    "rank",
                    "dense_rank",
                    "extract");

    private static final Set<String> ALLOWED_CAST_TYPES =
            Set.of(
                    "char",
                    "varchar",
                    "text",
                    "smallint",
                    "int",
                    "integer",
                    "bigint",
                    "decimal",
                    "numeric",
                    "float",
                    "double",
                    "date",
                    "time",
                    "datetime",
                    "timestamp",
                    "boolean",
                    "json",
                    "jsonb");

    private static final Set<String> SYSTEM_NAMESPACES =
            Set.of(
                    "information_schema",
                    "mysql",
                    "performance_schema",
                    "sys",
                    "pg_catalog",
                    "pg_toast");

    private SqlAllowlistGate() {}

    public static void check(String sql) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException | RuntimeException error) {
            throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
        }
        if (statements == null || statements.size() != 1) {
            throw rejection(ErrorCode.SQL_NOT_READONLY);
        }
        Statement statement = statements.get(0);
        if (!(statement instanceof Select select)) {
            throw rejection(ErrorCode.SQL_NOT_READONLY);
        }
        Link link = new Link();
        Checker checker = new Checker(new GateExpressions(link));
        link.checker = checker;
        checker.walk(select);
    }

    private static DomainException rejection(ErrorCode code) {
        return new DomainException(code);
    }

    private static void checkFunction(Function function) {
        if (function.getMultipartName() == null
                || function.getMultipartName().size() != 1
                || function.getMultipartName().get(0) == null) {
            throw rejection(ErrorCode.FUNCTION_NOT_ALLOWED);
        }
        String name = function.getMultipartName().get(0).toLowerCase(Locale.ROOT);
        if (!ALLOWED_FUNCTIONS.contains(name)) {
            throw rejection(ErrorCode.FUNCTION_NOT_ALLOWED);
        }
    }

    private static void checkCast(CastExpression castExpression) {
        if (castExpression.getColDataType() == null
                || castExpression.getColDataType().getDataType() == null) {
            throw rejection(ErrorCode.TYPE_NOT_ALLOWED);
        }
        String declared = castExpression.getColDataType().getDataType();
        int arguments = declared.indexOf('(');
        String type = (arguments >= 0 ? declared.substring(0, arguments) : declared).trim();
        type = type.toLowerCase(Locale.ROOT);
        // Precision/length arguments are validated by the binder; the base type must be reviewed.
        if (type.contains(".") || !ALLOWED_CAST_TYPES.contains(type)) {
            throw rejection(ErrorCode.TYPE_NOT_ALLOWED);
        }
    }

    private static void checkTable(Table table) {
        // A two-part name binds its leading part as the schema, so both positions are checked.
        String catalog = table.getDatabaseName();
        String schema = table.getSchemaName();
        if (catalog != null && SYSTEM_NAMESPACES.contains(catalog.toLowerCase(Locale.ROOT))) {
            throw rejection(ErrorCode.TABLE_NOT_AUTHORIZED);
        }
        if (schema != null && SYSTEM_NAMESPACES.contains(schema.toLowerCase(Locale.ROOT))) {
            throw rejection(ErrorCode.TABLE_NOT_AUTHORIZED);
        }
        if (table.getName() != null && table.getName().toLowerCase(Locale.ROOT).startsWith("pg_")) {
            throw rejection(ErrorCode.TABLE_NOT_AUTHORIZED);
        }
    }

    private static final class Link {
        Checker checker;
    }

    private static final class GateExpressions extends ExpressionVisitorAdapter<Void> {
        private final Link link;

        GateExpressions(Link link) {
            this.link = link;
        }

        @Override
        public <S> Void visit(Function function, S context) {
            checkFunction(function);
            return super.visit(function, context);
        }

        @Override
        public <S> Void visit(CastExpression castExpression, S context) {
            checkCast(castExpression);
            return super.visit(castExpression, context);
        }

        @Override
        public <S> Void visit(Select select, S context) {
            return select.accept(link.checker, context);
        }
    }

    private static final class Checker extends SelectVisitorAdapter<Void> {
        Checker(GateExpressions expressions) {
            super(expressions);
        }

        void walk(Select select) {
            select.accept(this, null);
        }

        private void checkFromItems(java.util.List<FromItem> items) {
            for (FromItem from : items) {
                if (from instanceof Table table) {
                    checkTable(table);
                } else if (from instanceof Select nested) {
                    walk(nested);
                } else if (from instanceof ParenthesedFromItem parenthesed) {
                    java.util.List<FromItem> nested = new java.util.ArrayList<>();
                    nested.add(parenthesed.getFromItem());
                    if (parenthesed.getJoins() != null) {
                        for (Join join : parenthesed.getJoins()) {
                            nested.add(join.getRightItem());
                        }
                    }
                    checkFromItems(nested);
                } else if (from != null) {
                    throw rejection(ErrorCode.SQL_UNSUPPORTED);
                }
            }
        }

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            java.util.List<FromItem> items = new java.util.ArrayList<>();
            if (plainSelect.getFromItem() != null) {
                items.add(plainSelect.getFromItem());
            }
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    if (join.getRightItem() != null) {
                        items.add(join.getRightItem());
                    }
                }
            }
            checkFromItems(items);
            return super.visit(plainSelect, context);
        }
    }
}
