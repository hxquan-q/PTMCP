package local.ptmcp.guard.rewrite;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.select.Fetch;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.Offset;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;

import java.util.List;
import java.util.Locale;

/**
 * Applies the single outer row cap to a parsed SELECT and renders final SQL text.
 *
 * <p>The effective limit is {@code min(500, requestedMaxRows, existing outer limit)}; callers may
 * only tighten. An outer LIMIT/FETCH limited by a non-constant expression, {@code LIMIT ALL},
 * {@code FETCH ... WITH TIES} or percentage/row-count variants without a plain row count are
 * rejected. OFFSET is preserved and must be a non-negative constant; normalisation of {@code MySQL
 * LIMIT o,c} is handled by the parser's Limit fields.
 *
 * <p>The returned text is derived only from the parsed AST; it is NOT an approved query, does not
 * add qualification, and MUST be re-parsed and re-validated before execution.
 */
public final class LimitRewriter {
    public static final int MAX_ROWS = 500;

    private LimitRewriter() {}

    public static String rewrite(Select select, int requestedMaxRows) {
        if (select == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (requestedMaxRows < 1 || requestedMaxRows > MAX_ROWS) {
            // Over-limit requests are refused, not silently clamped.
            throw new DomainException(ErrorCode.SQL_BUDGET_EXCEEDED);
        }
        if (select instanceof SetOperationList) {
            // Re-parsing "SELECT ... UNION ... LIMIT n" attaches the limit to the last branch in
            // the
            // locked parser version, so an equivalent outer-bound transformation is not yet proven.
            throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
        }
        long effective = Math.min(MAX_ROWS, requestedMaxRows);
        Expression existingOffset = null;
        if (select.getLimit() != null) {
            effective = Math.min(effective, constantRows(select.getLimit()));
            existingOffset = select.getLimit().getOffset();
            if (select.getLimit().getByExpressions() != null) {
                throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
            }
        }
        if (select.getFetch() != null) {
            Fetch fetch = select.getFetch();
            List<String> parameters = fetch.getFetchParameters();
            boolean tiesOrPercent =
                    parameters != null
                            && parameters.stream()
                                    .map(value -> value.toUpperCase(Locale.ROOT))
                                    .anyMatch(
                                            value ->
                                                    value.contains("TIES")
                                                            || value.contains("PERCENT"));
            if (!fetch.isFetchParamFirst() || tiesOrPercent) {
                throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
            }
            effective = Math.min(effective, fetch.getRowCount());
            select.setFetch(null);
        }
        if (select.getLimitBy() != null) {
            throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
        }
        checkOffset(select.getOffset());
        if (existingOffset != null && !(existingOffset instanceof LongValue)) {
            throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
        }
        Limit limit = new Limit();
        limit.setRowCount(new LongValue(effective));
        limit.setOffset(existingOffset);
        select.setLimit(limit);
        return select.toString();
    }

    private static long constantRows(Limit limit) {
        if (limit.isLimitAll() || limit.isLimitNull()) {
            throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
        }
        if (limit.getRowCount() instanceof LongValue constant && constant.getValue() >= 0) {
            return constant.getValue();
        }
        // Arithmetic/parameterised/percent row counts have no provable outer bound.
        throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
    }

    private static void checkOffset(Offset offset) {
        if (offset == null) {
            return;
        }
        if (!(offset.getOffset() instanceof LongValue constant) || constant.getValue() < 0) {
            throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
        }
    }
}
