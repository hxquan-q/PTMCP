package local.ptmcp.datasource.postgres;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Positive PostgreSQL type allowlist plus bounded cell extraction. Unknown names, user-defined
 * types, arrays and ranges are rejected during metadata projection, before any row is read, so an
 * empty result cannot bypass the check.
 */
final class ColumnProjection {
    static final int MAX_CELL_BYTES = 4_096;
    static final int MAX_BINARY_BYTES = MAX_CELL_BYTES / 4 * 3;

    private ColumnProjection() {}

    static List<PgReadOnlyResult.Column> columns(ResultSetMetaData metadata) throws SQLException {
        int count = metadata.getColumnCount();
        List<PgReadOnlyResult.Column> columns = new ArrayList<>(count);
        for (int index = 1; index <= count; index++) {
            String typeName = metadata.getColumnTypeName(index);
            String normalized = typeName == null ? "" : typeName.toLowerCase(Locale.ROOT);
            PgReadOnlyResult.LogicalType logicalType = logicalType(normalized);
            columns.add(
                    new PgReadOnlyResult.Column(
                            index - 1,
                            metadata.getColumnLabel(index) == null
                                    ? ""
                                    : metadata.getColumnLabel(index),
                            typeName == null ? "" : typeName,
                            logicalType,
                            encoding(logicalType),
                            metadata.isNullable(index) != ResultSetMetaData.columnNoNulls,
                            metadata.getColumnType(index)));
        }
        return columns;
    }

    static CellValue read(ResultSet resultSet, int index, PgReadOnlyResult.Column column)
            throws SQLException {
        return switch (column.logicalType()) {
            case STRING, JSON -> text(resultSet, index);
            case BINARY -> binary(resultSet, index);
            case INTEGER -> integer(resultSet, index, column.databaseType());
            case DECIMAL -> decimal(resultSet, index);
            case FLOAT -> floating(resultSet, index, column.databaseType());
            case BOOLEAN -> bool(resultSet, index);
            case DATE -> date(resultSet, index);
            case TIME -> time(resultSet, index, column.databaseType());
            case LOCAL_DATETIME -> localDateTime(resultSet, index);
            case INSTANT -> instant(resultSet, index);
        };
    }

    /**
     * Base relation/attribute pairs referenced by the result. PostgreSQL reports a domain column's
     * base OID on the wire, so the catalog identity is the only runtime signal; expressions carry
     * no base identity and are covered by the guard output plan.
     */
    static Map<Integer, Set<Integer>> referencedBaseColumns(ResultSetMetaData metadata, int count) {
        Object[] fields = resultFields(metadata);
        if (fields.length < count) {
            throw new DomainException(ErrorCode.DRIVER_ERROR);
        }
        Map<Integer, Set<Integer>> referenced = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            int tableOid = fieldInt(fields[index], "getTableOid");
            int position = fieldInt(fields[index], "getPositionInTable");
            if (tableOid > 0 && position > 0) {
                referenced.computeIfAbsent(tableOid, ignored -> new HashSet<>()).add(position);
            }
        }
        return referenced;
    }

    static void rejectDomainColumns(Connection connection, Map<Integer, Set<Integer>> referenced)
            throws SQLException {
        if (referenced.isEmpty()) {
            return;
        }
        StringBuilder sql =
                new StringBuilder(
                        "SELECT a.attrelid, a.attnum FROM pg_catalog.pg_attribute a "
                                + "JOIN pg_catalog.pg_type t ON t.oid = a.atttypid "
                                + "WHERE t.typtype = 'd' AND a.attrelid IN (");
        StringJoiner tableOids = new StringJoiner(", ");
        for (int tableOid : referenced.keySet()) {
            tableOids.add(Integer.toString(tableOid));
        }
        sql.append(tableOids).append(')');
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql.toString())) {
            while (result.next()) {
                Set<Integer> positions = referenced.get(result.getInt(1));
                if (positions != null && positions.contains(result.getInt(2))) {
                    throw new DomainException(ErrorCode.RESULT_TYPE_UNSUPPORTED);
                }
            }
        }
    }

    private static int fieldInt(Object fieldValue, String accessor) {
        try {
            return (Integer) fieldValue.getClass().getMethod(accessor).invoke(fieldValue);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new DomainException(ErrorCode.DRIVER_ERROR);
        }
    }

    private static Object[] resultFields(ResultSetMetaData metadata) {
        Object[] fields;
        try {
            Field field = metadata.getClass().getDeclaredField("fields");
            field.setAccessible(true);
            fields = (Object[]) field.get(metadata);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            throw new DomainException(ErrorCode.DRIVER_ERROR);
        }
        if (fields == null) {
            throw new DomainException(ErrorCode.DRIVER_ERROR);
        }
        return fields;
    }

    static long cellWireBytes(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof Boolean flag) {
            return flag ? 4 : 5;
        }
        if (value instanceof Integer || value instanceof Float || value instanceof Double) {
            return value.toString().length();
        }
        String text = (String) value;
        return jsonEscapedUtf8Length(text) + 2;
    }

    private static PgReadOnlyResult.LogicalType logicalType(String typeName) {
        return switch (typeName) {
            case "text", "varchar", "bpchar", "name" -> PgReadOnlyResult.LogicalType.STRING;
            case "int2", "int4", "int8" -> PgReadOnlyResult.LogicalType.INTEGER;
            case "numeric" -> PgReadOnlyResult.LogicalType.DECIMAL;
            case "float4", "float8" -> PgReadOnlyResult.LogicalType.FLOAT;
            case "bool" -> PgReadOnlyResult.LogicalType.BOOLEAN;
            case "date" -> PgReadOnlyResult.LogicalType.DATE;
            case "time", "timetz" -> PgReadOnlyResult.LogicalType.TIME;
            case "timestamp" -> PgReadOnlyResult.LogicalType.LOCAL_DATETIME;
            case "timestamptz" -> PgReadOnlyResult.LogicalType.INSTANT;
            case "bytea" -> PgReadOnlyResult.LogicalType.BINARY;
            case "json", "jsonb" -> PgReadOnlyResult.LogicalType.JSON;
            default -> throw new DomainException(ErrorCode.RESULT_TYPE_UNSUPPORTED);
        };
    }

    private static PgReadOnlyResult.Encoding encoding(PgReadOnlyResult.LogicalType logicalType) {
        return switch (logicalType) {
            case BINARY -> PgReadOnlyResult.Encoding.BASE64;
            case JSON -> PgReadOnlyResult.Encoding.JSON_TEXT;
            default -> null;
        };
    }

    private static CellValue text(ResultSet resultSet, int index) throws SQLException {
        try (Reader reader = resultSet.getCharacterStream(index)) {
            if (reader == null) {
                return CellValue.nullValue();
            }
            char[] buffer = new char[MAX_CELL_BYTES + 2];
            int total = 0;
            while (total < buffer.length) {
                int read = reader.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            StringBuilder prefix = new StringBuilder(MAX_CELL_BYTES);
            int bytes = 0;
            int position = 0;
            boolean truncated = false;
            while (position < total) {
                char current = buffer[position];
                int codePoint;
                int width;
                if (Character.isHighSurrogate(current)
                        && position + 1 < total
                        && Character.isLowSurrogate(buffer[position + 1])) {
                    codePoint = Character.toCodePoint(current, buffer[position + 1]);
                    width = 2;
                } else if (Character.isSurrogate(current)) {
                    codePoint = 0xFFFD;
                    width = 1;
                } else {
                    codePoint = current;
                    width = 1;
                }
                int codePointBytes = utf8Length(codePoint);
                if (bytes + codePointBytes > MAX_CELL_BYTES) {
                    truncated = true;
                    break;
                }
                prefix.appendCodePoint(codePoint);
                bytes += codePointBytes;
                position += width;
            }
            if (!truncated && position < total) {
                truncated = true;
            }
            if (!truncated && total == buffer.length) {
                truncated = reader.read() >= 0;
            }
            return new CellValue(prefix.toString(), null, bytes, truncated);
        } catch (java.io.IOException failure) {
            throw new SQLException("character stream failed", failure);
        }
    }

    private static CellValue binary(ResultSet resultSet, int index) throws SQLException {
        try (InputStream stream = resultSet.getBinaryStream(index)) {
            if (stream == null) {
                return CellValue.nullValue();
            }
            byte[] buffer = new byte[MAX_BINARY_BYTES + 1];
            int total = 0;
            while (total < buffer.length) {
                int read = stream.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    break;
                }
                total += read;
            }
            int prefixLength = Math.min(total, MAX_BINARY_BYTES);
            boolean truncated = total > MAX_BINARY_BYTES || stream.read() >= 0;
            byte[] prefix = java.util.Arrays.copyOf(buffer, prefixLength);
            return new CellValue(
                    Base64.getEncoder().encodeToString(prefix), null, prefixLength, truncated);
        } catch (java.io.IOException failure) {
            throw new SQLException("binary stream failed", failure);
        }
    }

    private static CellValue integer(ResultSet resultSet, int index, String databaseType)
            throws SQLException {
        long value = resultSet.getLong(index);
        if (resultSet.wasNull()) {
            return CellValue.nullValue();
        }
        if ("int8".equalsIgnoreCase(databaseType)) {
            return new CellValue(Long.toString(value), null, 0, false);
        }
        return new CellValue((int) value, null, 0, false);
    }

    private static CellValue decimal(ResultSet resultSet, int index) throws SQLException {
        BigDecimal value = resultSet.getBigDecimal(index);
        if (value == null) {
            return CellValue.nullValue();
        }
        long estimated =
                (long) value.precision()
                        + Math.max(value.scale(), 0)
                        + Math.max(-value.scale(), 0)
                        + 2;
        if (estimated > MAX_CELL_BYTES) {
            throw new DomainException(ErrorCode.RESULT_TYPE_UNSUPPORTED);
        }
        String text = value.toPlainString();
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_CELL_BYTES) {
            throw new DomainException(ErrorCode.RESULT_TYPE_UNSUPPORTED);
        }
        return new CellValue(text, null, 0, false);
    }

    private static CellValue floating(ResultSet resultSet, int index, String databaseType)
            throws SQLException {
        if ("float4".equalsIgnoreCase(databaseType)) {
            float value = resultSet.getFloat(index);
            if (resultSet.wasNull()) {
                return CellValue.nullValue();
            }
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return new CellValue(Float.toString(value), null, 0, false);
            }
            return new CellValue(value, null, 0, false);
        }
        double value = resultSet.getDouble(index);
        if (resultSet.wasNull()) {
            return CellValue.nullValue();
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return new CellValue(Double.toString(value), null, 0, false);
        }
        return new CellValue(value, null, 0, false);
    }

    private static CellValue bool(ResultSet resultSet, int index) throws SQLException {
        boolean value = resultSet.getBoolean(index);
        if (resultSet.wasNull()) {
            return CellValue.nullValue();
        }
        return new CellValue(value, null, 0, false);
    }

    private static CellValue date(ResultSet resultSet, int index) throws SQLException {
        LocalDate value = resultSet.getObject(index, LocalDate.class);
        return value == null
                ? CellValue.nullValue()
                : new CellValue(value.toString(), null, 0, false);
    }

    private static CellValue time(ResultSet resultSet, int index, String databaseType)
            throws SQLException {
        if ("timetz".equalsIgnoreCase(databaseType)) {
            OffsetTime value = resultSet.getObject(index, OffsetTime.class);
            return value == null
                    ? CellValue.nullValue()
                    : new CellValue(value.toString(), null, 0, false);
        }
        LocalTime value = resultSet.getObject(index, LocalTime.class);
        return value == null
                ? CellValue.nullValue()
                : new CellValue(value.toString(), null, 0, false);
    }

    private static CellValue localDateTime(ResultSet resultSet, int index) throws SQLException {
        LocalDateTime value = resultSet.getObject(index, LocalDateTime.class);
        return value == null
                ? CellValue.nullValue()
                : new CellValue(value.toString().replace('T', ' '), null, 0, false);
    }

    private static CellValue instant(ResultSet resultSet, int index) throws SQLException {
        OffsetDateTime value = resultSet.getObject(index, OffsetDateTime.class);
        return value == null
                ? CellValue.nullValue()
                : new CellValue(value.toInstant().toString(), null, 0, false);
    }

    private static long jsonEscapedUtf8Length(String value) {
        long bytes = 0;
        for (int index = 0; index < value.length(); ) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            if (codePoint == '"'
                    || codePoint == '\\'
                    || codePoint == '\b'
                    || codePoint == '\f'
                    || codePoint == '\n'
                    || codePoint == '\r'
                    || codePoint == '\t') {
                bytes += 2;
            } else if (codePoint < 0x20) {
                bytes += 6;
            } else {
                bytes += utf8Length(codePoint);
            }
        }
        return bytes;
    }

    private static int utf8Length(int codePoint) {
        if (codePoint <= 0x7F) {
            return 1;
        }
        if (codePoint <= 0x7FF) {
            return 2;
        }
        if (codePoint <= 0xFFFF) {
            return 3;
        }
        return 4;
    }

    record CellValue(Object value, Integer originalBytes, int returnedBytes, boolean truncated) {
        static CellValue nullValue() {
            return new CellValue(null, null, 0, false);
        }
    }
}
