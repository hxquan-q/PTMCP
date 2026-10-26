package local.ptmcp.gateway.mcp;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.datasource.ReadOnlyResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps one bounded executor result into the MCP column/cell contract: string-safe numbers,
 * booleans, normalized date-time text and a 4KiB per-cell UTF-8 budget that cuts on codepoint
 * boundaries. Types this encoder cannot prove bounded and faithful (binary families, unknown types)
 * are rejected before any row is returned. The overall response budget lives in the tool service,
 * which measures the final serialized body.
 */
final class ToolResultEncoder {
    static final int CELL_BUDGET_BYTES = 4_096;

    private ToolResultEncoder() {}

    record Encoded(
            List<Map<String, Object>> columns,
            List<List<Object>> rows,
            List<Map<String, Object>> cellTruncations,
            boolean rowLimitReached) {}

    static Encoded encode(ReadOnlyResult result, boolean limitReached) {
        List<ColumnSpec> specs = new ArrayList<>(result.columns().size());
        List<Map<String, Object>> columns = new ArrayList<>(specs.size());
        for (int index = 0; index < result.columns().size(); index++) {
            ColumnSpec spec = ColumnSpec.of(result.columns().get(index));
            specs.add(spec);
            columns.add(spec.descriptor(index));
        }
        List<List<Object>> rows = new ArrayList<>(result.rows().size());
        List<Map<String, Object>> cellTruncations = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < result.rows().size(); rowIndex++) {
            List<String> raw = result.rows().get(rowIndex);
            List<Object> row = new ArrayList<>(raw.size());
            for (int columnIndex = 0;
                    columnIndex < raw.size() && columnIndex < specs.size();
                    columnIndex++) {
                Cell cell = encodeCell(specs.get(columnIndex), raw.get(columnIndex));
                if (cell.truncated()) {
                    cellTruncations.add(
                            Map.of(
                                    "rowIndex",
                                    rowIndex,
                                    "columnIndex",
                                    columnIndex,
                                    "reason",
                                    "CELL_LIMIT",
                                    "originalBytes",
                                    cell.originalBytes(),
                                    "returnedBytes",
                                    cell.returnedBytes()));
                }
                row.add(cell.value());
            }
            rows.add(row);
        }
        return new Encoded(columns, List.copyOf(rows), List.copyOf(cellTruncations), limitReached);
    }

    private record Cell(Object value, boolean truncated, int originalBytes, int returnedBytes) {
        static Cell plain(Object value) {
            return new Cell(value, false, 0, 0);
        }
    }

    private static Cell encodeCell(ColumnSpec spec, String raw) {
        if (raw == null) {
            return Cell.plain(null);
        }
        if (spec.stringCells()) {
            // BIGINT keeps its text form: JSON numbers lose precision in common clients.
            return textCell(raw);
        }
        return switch (spec.logicalType()) {
            case "BOOLEAN" -> booleanCell(raw);
            case "INTEGER" -> integerCell(raw);
            case "FLOAT" -> floatCell(raw);
            case "LOCAL_DATETIME" -> textCell(normalizeDatetime(raw, false));
            case "INSTANT" -> textCell(normalizeDatetime(raw, true));
            default -> textCell(raw);
        };
    }

    private static Cell booleanCell(String raw) {
        if ("1".equals(raw) || "true".equalsIgnoreCase(raw)) {
            return Cell.plain(Boolean.TRUE);
        }
        if ("0".equals(raw) || "false".equalsIgnoreCase(raw)) {
            return Cell.plain(Boolean.FALSE);
        }
        return Cell.plain(raw);
    }

    private static Cell integerCell(String raw) {
        try {
            long value = Long.parseLong(raw);
            if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                return Cell.plain((int) value);
            }
            return Cell.plain(raw);
        } catch (NumberFormatException overflow) {
            return Cell.plain(raw);
        }
    }

    private static Cell floatCell(String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return Cell.plain(raw);
            }
            return Cell.plain(value);
        } catch (NumberFormatException overflow) {
            return Cell.plain(raw);
        }
    }

    /**
     * Converts the driver's {@code yyyy-MM-dd HH:mm:ss[.fraction]} text to the contract's {@code
     * yyyy-MM-ddTHH:mm:ss[.fraction]} shape, appending {@code Z} for instants. Values that do not
     * match the expected shape are returned unchanged rather than mangled.
     */
    private static String normalizeDatetime(String raw, boolean instant) {
        String value = raw;
        if (value.length() >= 19
                && value.charAt(4) == '-'
                && value.charAt(7) == '-'
                && value.charAt(10) == ' '
                && value.charAt(13) == ':'
                && value.charAt(16) == ':') {
            value = value.substring(0, 10) + 'T' + value.substring(11);
        }
        if (instant && value.length() >= 19 && !value.endsWith("Z") && !value.contains("+")) {
            value = value + 'Z';
        }
        return value;
    }

    private static Cell textCell(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= CELL_BUDGET_BYTES) {
            return Cell.plain(raw);
        }
        // Cut on a codepoint boundary so a surrogate pair or multi-byte sequence never splits.
        int cut = CELL_BUDGET_BYTES;
        while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
            cut--;
        }
        String prefix = new String(bytes, 0, cut, StandardCharsets.UTF_8);
        return new Cell(prefix, true, bytes.length, cut);
    }

    private record ColumnSpec(
            String name,
            String databaseType,
            String logicalType,
            boolean nullable,
            String encoding,
            boolean stringCells) {
        Map<String, Object> descriptor(int index) {
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("index", index);
            descriptor.put("name", name);
            descriptor.put("databaseType", databaseType);
            descriptor.put("logicalType", logicalType);
            descriptor.put("nullable", nullable);
            descriptor.put("encoding", encoding);
            return descriptor;
        }

        static ColumnSpec of(ReadOnlyResult.Column column) {
            String typeName = column.typeName() == null ? "" : column.typeName().toUpperCase();
            String logical;
            String encoding = null;
            boolean stringCells = false;
            switch (column.jdbcType()) {
                case java.sql.Types.BOOLEAN -> logical = "BOOLEAN";
                case java.sql.Types.BIT -> {
                    if (!typeName.equals("BOOLEAN") && !typeName.equals("BOOL")) {
                        // Multi-bit BIT values cannot be proven faithful through text decoding.
                        throw new DomainException(ErrorCode.RESULT_TYPE_UNSUPPORTED);
                    }
                    logical = "BOOLEAN";
                }
                case java.sql.Types.TINYINT,
                        java.sql.Types.SMALLINT,
                        java.sql.Types.INTEGER,
                        java.sql.Types.BIGINT ->
                        logical = "INTEGER";
                case java.sql.Types.DECIMAL, java.sql.Types.NUMERIC -> logical = "DECIMAL";
                case java.sql.Types.FLOAT, java.sql.Types.REAL, java.sql.Types.DOUBLE ->
                        logical = "FLOAT";
                case java.sql.Types.DATE -> logical = typeName.equals("YEAR") ? "INTEGER" : "DATE";
                case java.sql.Types.TIME -> logical = "TIME";
                case java.sql.Types.TIMESTAMP ->
                        logical = typeName.equals("DATETIME") ? "LOCAL_DATETIME" : "INSTANT";
                case java.sql.Types.CHAR,
                        java.sql.Types.VARCHAR,
                        java.sql.Types.LONGVARCHAR,
                        java.sql.Types.NCHAR,
                        java.sql.Types.NVARCHAR,
                        java.sql.Types.LONGNVARCHAR -> {
                    if (typeName.equals("JSON")) {
                        logical = "JSON";
                        encoding = "JSON_TEXT";
                    } else {
                        logical = "STRING";
                    }
                }
                default -> throw new DomainException(ErrorCode.RESULT_TYPE_UNSUPPORTED);
            }
            return new ColumnSpec(
                    column.label(),
                    column.typeName(),
                    logical,
                    column.nullable(),
                    encoding,
                    stringCells);
        }
    }
}
