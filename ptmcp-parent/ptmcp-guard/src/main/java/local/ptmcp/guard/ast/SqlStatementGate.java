package local.ptmcp.guard.ast;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.ExpressionVisitor;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.ShowColumnsStatement;
import net.sf.jsqlparser.statement.ShowStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.show.ShowIndexStatement;
import net.sf.jsqlparser.statement.show.ShowTablesStatement;

import java.util.List;

/**
 * Statement-shape gate over a parsed AST: exactly one SELECT statement, no locking, no SELECT INTO,
 * no recursive CTE and no dialect hints/procedural modifiers.
 *
 * <p>Passing means the statement kind and these structural features are allowed; it does NOT prove
 * read-only expressions, a function/type allowlist, resource binding, permissions or lexical
 * approval. Callers MUST run the lexical precheck, the expression/type whitelist and the binder
 * before any execution, and no ApprovedQuery or connection is produced here.
 */
public final class SqlStatementGate {
    private SqlStatementGate() {}

    public static void check(String sql) {
        Statements statements;
        try {
            statements = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException error) {
            throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
        } catch (RuntimeException error) {
            // Parser internals may throw unchecked errors on adversarial input; deny, never leak.
            throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
        }
        if (statements == null || statements.isEmpty()) {
            throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
        }
        if (statements.size() != 1) {
            throw rejection(ErrorCode.SQL_NOT_READONLY);
        }
        Statement statement = statements.get(0);
        if (statement instanceof ExplainStatement
                || statement instanceof ShowStatement
                || statement instanceof ShowColumnsStatement
                || statement instanceof ShowTablesStatement
                || statement instanceof ShowIndexStatement) {
            throw rejection(ErrorCode.SQL_UNSUPPORTED);
        }
        if (!(statement instanceof Select select)) {
            throw rejection(ErrorCode.SQL_NOT_READONLY);
        }
        if (select instanceof Values) {
            throw rejection(ErrorCode.SQL_UNSUPPORTED);
        }
        new Checker().walk(select);
    }

    private static DomainException rejection(ErrorCode code) {
        return new DomainException(code);
    }

    private static final class Checker extends SelectVisitorAdapter<Void> {
        private ExpressionVisitorAdapter<Void> expressions;

        @Override
        public ExpressionVisitor<Void> getExpressionVisitor() {
            if (expressions == null) {
                expressions = new ExpressionVisitorAdapter<>(this);
            }
            return expressions;
        }

        void walk(Select select) {
            select.accept(this, null);
        }

        private void checkCommonSelect(Select select) {
            List<WithItem<?>> withItems = select.getWithItemsList();
            if (withItems != null) {
                for (WithItem<?> item : withItems) {
                    if (item.isRecursive()) {
                        throw rejection(ErrorCode.SQL_UNSUPPORTED);
                    }
                }
            }
            if (select.getForMode() != null
                    || select.getForUpdate() != null
                    || select.getForUpdateTables() != null && !select.getForUpdateTables().isEmpty()
                    || select.getWait() != null
                    || select.isSkipLocked()
                    || select.getOption() != null
                    || select.getIsolation() != null
                    || select.getMySqlProcedureAnalyse() != null
                    || select.isOracleSiblings()
                    || select.getInterpolate() != null
                    || select.getPivot() != null
                    || select.getUnPivot() != null
                    || select.getLimitBy() != null) {
                throw rejection(ErrorCode.SQL_UNSUPPORTED);
            }
        }

        private void checkFrom(Select owner) {
            if (!(owner instanceof PlainSelect plain)) {
                return;
            }
            List<FromItem> fromItems = new java.util.ArrayList<>();
            if (plain.getFromItem() != null) {
                fromItems.add(plain.getFromItem());
            }
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    if (join.getRightItem() != null) {
                        fromItems.add(join.getRightItem());
                    }
                }
            }
            for (FromItem from : fromItems) {
                if (from instanceof Table) {
                    continue;
                }
                if (from instanceof Select nested) {
                    walk(nested);
                    continue;
                }
                if (from instanceof ParenthesedFromItem parenthesed) {
                    List<FromItem> nested = new java.util.ArrayList<>();
                    nested.add(parenthesed.getFromItem());
                    if (parenthesed.getJoins() != null) {
                        for (Join join : parenthesed.getJoins()) {
                            nested.add(join.getRightItem());
                        }
                    }
                    checkFromItems(nested);
                    continue;
                }
                // Lateral subselects, table functions and unknown FROM kinds are rejected.
                throw rejection(ErrorCode.SQL_UNSUPPORTED);
            }
        }

        private void checkFromItems(List<FromItem> items) {
            for (FromItem from : items) {
                if (from == null || from instanceof Table) {
                    continue;
                }
                if (from instanceof Select nested) {
                    walk(nested);
                } else if (from instanceof ParenthesedFromItem parenthesed) {
                    List<FromItem> nested = new java.util.ArrayList<>();
                    nested.add(parenthesed.getFromItem());
                    if (parenthesed.getJoins() != null) {
                        for (Join join : parenthesed.getJoins()) {
                            nested.add(join.getRightItem());
                        }
                    }
                    checkFromItems(nested);
                } else {
                    throw rejection(ErrorCode.SQL_UNSUPPORTED);
                }
            }
        }

        @Override
        public <S> Void visit(PlainSelect plainSelect, S context) {
            checkCommonSelect(plainSelect);
            if (plainSelect.getOracleHint() != null
                    || plainSelect.getMySqlHintStraightJoin()
                    || plainSelect.getMySqlSqlCalcFoundRows()
                    || plainSelect.getMySqlSqlCacheFlag() != null
                    || plainSelect.getTop() != null
                    || plainSelect.getFirst() != null
                    || plainSelect.getSkip() != null
                    || plainSelect.getIntoTempTable() != null
                    || plainSelect.getMySqlSelectIntoClause() != null
                    || plainSelect.getIntoTables() != null
                            && !plainSelect.getIntoTables().isEmpty()) {
                throw rejection(ErrorCode.SQL_UNSUPPORTED);
            }
            checkFrom(plainSelect);
            return super.visit(plainSelect, context);
        }

        @Override
        public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
            checkCommonSelect(parenthesedSelect);
            return super.visit(parenthesedSelect, context);
        }

        @Override
        public <S> Void visit(SetOperationList setOperationList, S context) {
            checkCommonSelect(setOperationList);
            return super.visit(setOperationList, context);
        }

        @Override
        public <S> Void visit(Values values, S context) {
            throw rejection(ErrorCode.SQL_UNSUPPORTED);
        }
    }
}
