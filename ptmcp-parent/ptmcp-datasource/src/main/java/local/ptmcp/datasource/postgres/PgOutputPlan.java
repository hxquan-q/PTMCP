package local.ptmcp.datasource.postgres;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.datasource.postgres.PgReadOnlyResult.LogicalType;

import java.util.List;
import java.util.Objects;

/**
 * The expected output projection that travels with an approved request (datasource.md §6: the guard
 * derives the plan before a connection is borrowed). The executor verifies the live {@code
 * ResultSetMetaData} against this plan before reading any cell; a mismatch means the statement and
 * the plan disagree — the execution terminates with {@code RESULT_TYPE_UNSUPPORTED} and the trace
 * records the drift instead of reading values of unknown width or kind.
 */
public record PgOutputPlan(List<ExpectedColumn> columns) {
    public record ExpectedColumn(String label, LogicalType logicalType) {
        public ExpectedColumn {
            Objects.requireNonNull(label, "label");
            if (label.isBlank()) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            Objects.requireNonNull(logicalType, "logicalType");
        }
    }

    public PgOutputPlan {
        Objects.requireNonNull(columns, "columns");
        if (columns.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        columns = List.copyOf(columns);
    }

    public static PgOutputPlan of(ExpectedColumn... columns) {
        return new PgOutputPlan(List.of(columns));
    }
}
