package local.ptmcp.datasource.postgres;

import local.ptmcp.datasource.ExecutionTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded PostgreSQL result. Cells are JSON-compatible values: SQL NULL stays null, booleans stay
 * booleans, safe 32-bit integers stay numbers, and BIGINT/DECIMAL plus date/time/binary/JSON values
 * are strings according to the result contract.
 */
public record PgReadOnlyResult(
        List<Column> columns,
        List<List<Object>> rows,
        List<CellTruncation> cellTruncations,
        boolean rowLimitReached,
        boolean responseLimitReached,
        ExecutionTrace trace) {
    /** Logical output types from the datasource output contract. */
    public enum LogicalType {
        STRING,
        INTEGER,
        DECIMAL,
        FLOAT,
        BOOLEAN,
        DATE,
        TIME,
        LOCAL_DATETIME,
        INSTANT,
        BINARY,
        JSON
    }

    /** Non-null encodings only where the wire value differs from a plain JSON string. */
    public enum Encoding {
        BASE64,
        JSON_TEXT
    }

    public record Column(
            int index,
            String label,
            String databaseType,
            LogicalType logicalType,
            Encoding encoding,
            boolean nullable,
            int jdbcType) {
        public Column {
            if (index < 0) {
                throw new IllegalArgumentException("index must not be negative");
            }
            if (label == null || databaseType == null || logicalType == null) {
                throw new IllegalArgumentException(
                        "label, database type and logical type are required");
            }
        }
    }

    /**
     * One bounded cell. {@code originalBytes} is null when the full value cannot be measured
     * without materializing it. {@code returnedBytes} is the UTF-8 prefix length for text and the
     * decoded prefix length for binary.
     */
    public record CellTruncation(
            int rowIndex, int columnIndex, Integer originalBytes, int returnedBytes) {
        public CellTruncation {
            if (rowIndex < 0 || columnIndex < 0) {
                throw new IllegalArgumentException("row and column indexes must not be negative");
            }
            if (originalBytes != null && originalBytes < 0) {
                throw new IllegalArgumentException("originalBytes must not be negative");
            }
            if (returnedBytes < 0) {
                throw new IllegalArgumentException("returnedBytes must not be negative");
            }
        }
    }

    public PgReadOnlyResult {
        columns = List.copyOf(columns);
        List<List<Object>> rowCopy = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            rowCopy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = Collections.unmodifiableList(rowCopy);
        cellTruncations = List.copyOf(cellTruncations);
        if (trace == null) {
            throw new IllegalArgumentException("trace is required");
        }
    }

    public boolean truncated() {
        return rowLimitReached || responseLimitReached || !cellTruncations.isEmpty();
    }
}
