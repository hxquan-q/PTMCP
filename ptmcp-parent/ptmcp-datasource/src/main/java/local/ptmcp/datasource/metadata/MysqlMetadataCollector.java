package local.ptmcp.datasource.metadata;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.schema.MetadataCollector;
import local.ptmcp.persistence.schema.SnapshotModel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Captures the current MySQL database through built-in parameterized information_schema queries
 * only. Identifiers and comments are data; nothing from the catalog is executed or interpreted.
 */
public final class MysqlMetadataCollector implements MetadataCollector {
    private static final int QUERY_SECONDS = 30;
    private static final Pattern SAFE_UNQUOTED = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");
    private static final java.util.Set<String> RESERVED_WORDS =
            java.util.Set.of(
                    "ORDER",
                    "GROUP",
                    "TABLE",
                    "SELECT",
                    "FROM",
                    "WHERE",
                    "JOIN",
                    "ON",
                    "AS",
                    "KEY",
                    "INDEX",
                    "VIEW",
                    "DROP",
                    "CREATE",
                    "INSERT",
                    "UPDATE",
                    "DELETE",
                    "LEFT",
                    "RIGHT",
                    "INNER",
                    "OUTER",
                    "UNION",
                    "ALL",
                    "DISTINCT",
                    "LIMIT",
                    "DESC",
                    "ASC",
                    "CASE",
                    "WHEN",
                    "THEN",
                    "ELSE",
                    "END",
                    "VALUES",
                    "SET",
                    "HAVING",
                    "USE",
                    "SHOW",
                    "COLUMN",
                    "DATABASE",
                    "SCHEMA",
                    "CONDITION",
                    "RANGE",
                    "ROWS",
                    "SYSTEM",
                    "USAGE",
                    "CUME_DIST",
                    "DEFAULT");

    @Override
    public SnapshotModel collect(Connection connection) {
        try {
            connection.setReadOnly(true);
            String database = scalarText(connection, "SELECT DATABASE()");
            // lower_case_table_names is GLOBAL-only in MySQL 8.4; reading it needs no privileges.
            int lctn =
                    Integer.parseInt(
                            scalarText(connection, "SELECT @@GLOBAL.lower_case_table_names"));
            boolean foldLowercase = lctn != 0;

            Map<String, TableCapture> tables = loadTables(connection);
            Map<String, List<String[]>> foreignKeys = loadForeignKeys(connection);
            loadColumns(connection, tables, foreignKeys, foldLowercase);
            Map<String, String> viewDefinitions = loadViewDefinitions(connection);

            List<SnapshotModel.ObjectModel> objects = new ArrayList<>(tables.size());
            for (Map.Entry<String, TableCapture> entry : tables.entrySet()) {
                TableCapture table = entry.getValue();
                List<SnapshotModel.ColumnModel> columns = table.columns;
                if (columns.isEmpty()) {
                    continue;
                }
                String definition;
                String viewText = viewDefinitions.get(entry.getKey());
                if (viewText != null) {
                    definition = viewText + '\n' + canonicalColumns(columns);
                } else {
                    definition = canonicalColumns(columns);
                }
                objects.add(
                        new SnapshotModel.ObjectModel(
                                entry.getKey(),
                                normalize(entry.getKey(), foldLowercase),
                                isQuoted(entry.getKey()),
                                "VIEW".equals(table.kind)
                                        ? SnapshotModel.ObjectModel.KIND_VIEW
                                        : SnapshotModel.ObjectModel.KIND_TABLE,
                                sha256(definition),
                                null,
                                table.comment,
                                columns));
            }
            if (objects.isEmpty()) {
                throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
            }
            SnapshotModel.NamespaceModel namespace =
                    new SnapshotModel.NamespaceModel(
                            database, normalize(database, foldLowercase), "", "", objects);
            return new SnapshotModel(
                    database, foldLowercase ? "TRUE" : "FALSE", List.of(namespace));
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DRIVER_ERROR);
        }
    }

    private Map<String, TableCapture> loadTables(Connection connection) throws SQLException {
        Map<String, TableCapture> tables = new LinkedHashMap<>();
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, ENGINE "
                                        + "FROM information_schema.TABLES "
                                        + "WHERE TABLE_SCHEMA = DATABASE() "
                                        + "AND TABLE_TYPE IN ('BASE TABLE', 'VIEW') ORDER BY TABLE_NAME");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String name = result.getString(1);
                checkIdentifier(name);
                if (tables.size() >= SnapshotModel.MAX_OBJECTS_PER_NAMESPACE) {
                    throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
                }
                String comment = result.getString(3);
                TableCapture capture =
                        new TableCapture(
                                "VIEW".equals(result.getString(2)) ? "VIEW" : "TABLE",
                                comment == null ? null : comment);
                tables.put(name, capture);
            }
        }
        return tables;
    }

    private void loadColumns(
            Connection connection,
            Map<String, TableCapture> tables,
            Map<String, List<String[]>> foreignKeys,
            boolean foldLowercase)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT TABLE_NAME, COLUMN_NAME, ORDINAL_POSITION, DATA_TYPE, "
                                        + "IS_NULLABLE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, "
                                        + "NUMERIC_SCALE, DATETIME_PRECISION, COLUMN_COMMENT, COLUMN_KEY "
                                        + "FROM information_schema.COLUMNS "
                                        + "WHERE TABLE_SCHEMA = DATABASE() "
                                        + "ORDER BY TABLE_NAME, ORDINAL_POSITION");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String tableName = result.getString(1);
                TableCapture table = tables.get(tableName);
                if (table == null) {
                    continue;
                }
                String name = result.getString(2);
                checkIdentifier(name);
                if (table.columns.size() >= SnapshotModel.MAX_COLUMNS_PER_OBJECT) {
                    throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
                }
                String dataType = result.getString(4);
                Long maxLength = getLong(result, 6);
                Integer precision = getInteger(result, 7);
                Integer scale = getInteger(result, 8);
                if (precision == null
                        && ("datetime".equals(dataType) || "timestamp".equals(dataType))) {
                    precision = getInteger(result, 9);
                }
                String comment = result.getString(10);
                String foreignKeyJson = foreignKeyJson(foreignKeys.get(tableName), name);
                table.columns.add(
                        new SnapshotModel.ColumnModel(
                                result.getInt(3),
                                name,
                                normalize(name, foldLowercase),
                                isQuoted(name),
                                dataType,
                                jdbcType(dataType),
                                !"NO".equals(result.getString(5)),
                                precision,
                                scale,
                                maxLength,
                                comment == null ? null : comment,
                                "PRI".equals(result.getString(11)),
                                foreignKeyJson));
            }
        }
    }

    private Map<String, List<String[]>> loadForeignKeys(Connection connection) throws SQLException {
        Map<String, List<String[]>> foreignKeys = new HashMap<>();
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT TABLE_NAME, COLUMN_NAME, REFERENCED_TABLE_NAME, "
                                        + "REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE "
                                        + "WHERE TABLE_SCHEMA = DATABASE() "
                                        + "AND REFERENCED_TABLE_NAME IS NOT NULL "
                                        + "ORDER BY TABLE_NAME, ORDINAL_POSITION");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                foreignKeys
                        .computeIfAbsent(result.getString(1), key -> new ArrayList<>())
                        .add(
                                new String[] {
                                    result.getString(2), result.getString(3), result.getString(4)
                                });
            }
        }
        return foreignKeys;
    }

    private Map<String, String> loadViewDefinitions(Connection connection) throws SQLException {
        Map<String, String> definitions = new HashMap<>();
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT TABLE_NAME, VIEW_DEFINITION FROM information_schema.VIEWS "
                                        + "WHERE TABLE_SCHEMA = DATABASE()");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String definition = result.getString(2);
                if (definition != null && !definition.isBlank()) {
                    definitions.put(result.getString(1), definition);
                }
            }
        }
        return definitions;
    }

    /** Compact JSON array of references for one column; null when the column has no foreign key. */
    private static String foreignKeyJson(List<String[]> references, String columnName) {
        if (references == null) {
            return null;
        }
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String[] reference : references) {
            if (!reference[0].equals(columnName)) {
                continue;
            }
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("{\"referencedTable\":\"")
                    .append(jsonEscape(reference[1]))
                    .append("\",\"referencedColumn\":\"")
                    .append(jsonEscape(reference[2]))
                    .append("\"}");
        }
        return json.append(']').toString();
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (char character : value.toCharArray()) {
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String scalarText(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new DomainException(ErrorCode.DRIVER_ERROR);
            }
            String value = result.getString(1);
            if (value == null || value.isBlank()) {
                throw new DomainException(ErrorCode.DRIVER_ERROR);
            }
            return value;
        }
    }

    private static void checkIdentifier(String identifier) {
        if (identifier == null
                || identifier.isBlank()
                || identifier.length() > SnapshotModel.MAX_IDENTIFIER_CHARS
                || identifier.indexOf('\u0000') >= 0) {
            throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
        }
    }

    private static String normalize(String identifier, boolean foldLowercase) {
        return foldLowercase ? identifier.toLowerCase(Locale.ROOT) : identifier;
    }

    private static boolean isQuoted(String identifier) {
        return !SAFE_UNQUOTED.matcher(identifier).matches()
                || RESERVED_WORDS.contains(identifier.toUpperCase(Locale.ROOT));
    }

    private static int jdbcType(String dataType) {
        return switch (dataType) {
            case "bigint" -> Types.BIGINT;
            case "int", "integer", "mediumint" -> Types.INTEGER;
            case "smallint" -> Types.SMALLINT;
            case "tinyint" -> Types.TINYINT;
            case "decimal", "numeric" -> Types.DECIMAL;
            case "float" -> Types.REAL;
            case "double" -> Types.DOUBLE;
            case "bit" -> Types.BIT;
            case "varchar", "enum", "set" -> Types.VARCHAR;
            case "char" -> Types.CHAR;
            case "tinytext", "text", "mediumtext", "longtext" -> Types.LONGVARCHAR;
            case "date" -> Types.DATE;
            case "datetime", "timestamp" -> Types.TIMESTAMP;
            case "time" -> Types.TIME;
            case "binary" -> Types.BINARY;
            case "varbinary" -> Types.VARBINARY;
            case "tinyblob", "blob", "mediumblob", "longblob" -> Types.BLOB;
            default -> Types.OTHER;
        };
    }

    private static Long getLong(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer getInteger(ResultSet result, int column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static String canonicalColumns(List<SnapshotModel.ColumnModel> columns) {
        StringBuilder text = new StringBuilder(128);
        for (SnapshotModel.ColumnModel column : columns) {
            text.append(column.ordinal())
                    .append('\t')
                    .append(column.columnRaw())
                    .append('\t')
                    .append(column.dataType())
                    .append('\t')
                    .append(column.jdbcType())
                    .append('\t')
                    .append(column.nullable())
                    .append('\t')
                    .append(column.precision())
                    .append('\t')
                    .append(column.scale())
                    .append('\t')
                    .append(column.maxLength())
                    .append('\n');
        }
        return text.toString();
    }

    private static byte[] sha256(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        return digest.digest(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class TableCapture {
        final String kind;
        final String comment;
        final List<SnapshotModel.ColumnModel> columns = new ArrayList<>();

        TableCapture(String kind, String comment) {
            this.kind = kind;
            this.comment = comment;
        }
    }
}
