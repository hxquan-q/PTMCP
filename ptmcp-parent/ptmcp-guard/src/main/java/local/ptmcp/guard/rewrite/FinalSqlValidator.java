package local.ptmcp.guard.rewrite;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.DialectIdentifiers;
import local.ptmcp.guard.ast.SqlAllowlistGate;
import local.ptmcp.guard.ast.SqlStatementGate;
import local.ptmcp.guard.budget.BoundedParser;
import local.ptmcp.guard.scope.ColumnBinder;
import local.ptmcp.guard.scope.ColumnBindingResult;
import local.ptmcp.guard.scope.RelationColumns;
import local.ptmcp.guard.scope.ScopeTableBinder;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Select;

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
            DialectIdentifiers.normalizeMysqlRelations(select);
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

    private static DomainException failed() {
        return new DomainException(ErrorCode.REWRITE_VALIDATION_FAILED);
    }
}
