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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Captures user schemas of the current PostgreSQL database through built-in parameterized
 * pg_catalog queries only. Relation OIDs are recorded as physical identity; domain columns keep
 * their domain type name so the guard whitelist rejects them before any execution.
 */
public final class PgMetadataCollector implements MetadataCollector {
    private static final String EXCLUDE_SYSTEM_SCHEMAS =
            "n.nspname <> 'information_schema' AND n.nspname NOT LIKE 'pg\\_%'";

    @Override
    public SnapshotModel collect(Connection connection) {
        try {
            connection.setReadOnly(true);
            String database = scalar(connection, "SELECT current_database()", "database");

            Map<String, TableCapture> relations = loadRelations(connection);
            loadColumns(connection, relations);
            loadPrimaryKeys(connection, relations);
            loadForeignKeys(connection, relations);
            loadViewDefinitions(connection, relations);

            Map<String, List<SnapshotModel.ObjectModel>> bySchema = new LinkedHashMap<>();
            for (Map.Entry<String, TableCapture> entry : relations.entrySet()) {
                TableCapture relation = entry.getValue();
                if (relation.columns.isEmpty()) {
                    continue;
                }
                String definition =
                        relation.viewDefinition == null
                                ? canonicalColumns(relation.columns)
                                : relation.viewDefinition
                                        + '\n'
                                        + canonicalColumns(relation.columns);
                SnapshotModel.ObjectModel object =
                        new SnapshotModel.ObjectModel(
                                relation.objectRaw,
                                relation.objectRaw,
                                isQuoted(relation.objectRaw),
                                relation.kind,
                                sha256(definition),
                                relation.physicalIdentity,
                                relation.tableComment,
                                relation.columns);
                bySchema.computeIfAbsent(relation.schemaRaw, key -> new ArrayList<>()).add(object);
            }
            List<SnapshotModel.NamespaceModel> namespaces = new ArrayList<>(bySchema.size());
            for (Map.Entry<String, List<SnapshotModel.ObjectModel>> entry : bySchema.entrySet()) {
                namespaces.add(
                        new SnapshotModel.NamespaceModel(
                                "", "", entry.getKey(), entry.getKey(), entry.getValue()));
            }
            if (namespaces.isEmpty()) {
                throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
            }
            return new SnapshotModel(database, "TRUE", namespaces);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DRIVER_ERROR);
        }
    }

    private Map<String, TableCapture> loadRelations(Connection connection) throws SQLException {
        Map<String, TableCapture> relations = new LinkedHashMap<>();
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT n.nspname, c.relname, c.relkind, c.oid::text "
                                        + "FROM pg_catalog.pg_class c "
                                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                                        + "WHERE "
                                        + EXCLUDE_SYSTEM_SCHEMAS
                                        + " AND c.relkind IN ('r', 'v') "
                                        + "ORDER BY n.nspname, c.relname");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                if (relations.size() >= SnapshotModel.MAX_OBJECTS_PER_NAMESPACE) {
                    throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
                }
                String schema = result.getString(1);
                String name = result.getString(2);
                checkIdentifier(schema);
                checkIdentifier(name);
                relations.put(
                        schema + '\u001f' + name,
                        new TableCapture(
                                "v".equals(result.getString(3))
                                        ? SnapshotModel.ObjectModel.KIND_VIEW
                                        : SnapshotModel.ObjectModel.KIND_TABLE,
                                schema,
                                name,
                                result.getString(4)));
            }
        }
        return relations;
    }

    private void loadColumns(Connection connection, Map<String, TableCapture> relations)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT n.nspname, c.relname, a.attname, a.attnum, t.typname, "
                                        + "a.attnotnull, a.atttypmod, "
                                        + "obj_description(c.oid, 'pg_class'), "
                                        + "col_description(c.oid, a.attnum) "
                                        + "FROM pg_catalog.pg_attribute a "
                                        + "JOIN pg_catalog.pg_class c ON c.oid = a.attrelid "
                                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                                        + "JOIN pg_catalog.pg_type t ON t.oid = a.atttypid "
                                        + "WHERE "
                                        + EXCLUDE_SYSTEM_SCHEMAS
                                        + " AND c.relkind IN ('r', 'v') "
                                        + "AND a.attnum > 0 AND NOT a.attisdropped "
                                        + "ORDER BY n.nspname, c.relname, a.attnum");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                TableCapture relation =
                        relations.get(result.getString(1) + '\u001f' + result.getString(2));
                if (relation == null) {
                    continue;
                }
                String name = result.getString(3);
                checkIdentifier(name);
                if (relation.columns.size() >= SnapshotModel.MAX_COLUMNS_PER_OBJECT) {
                    throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
                }
                // Domain columns keep their own type name; the guard whitelist rejects unknown
                // names before any execution, so domains never reach a projection silently.
                String dataType = typname(result);
                int atttypmod = result.getInt(7);
                Integer precision = null;
                Integer scale = null;
                Long maxLength = null;
                if ("varchar".equals(dataType) || "bpchar".equals(dataType)) {
                    if (atttypmod >= 4) {
                        maxLength = (long) (atttypmod - 4);
                    }
                } else if ("numeric".equals(dataType)) {
                    if (atttypmod >= 4) {
                        precision = (atttypmod - 4) >> 16;
                        scale = (atttypmod - 4) & 0xFFFF;
                    }
                } else if ("time".equals(dataType)
                        || "timetz".equals(dataType)
                        || "timestamp".equals(dataType)
                        || "timestamptz".equals(dataType)) {
                    // timestamp family stores the precision directly in typmod (no +4 header).
                    if (atttypmod >= 0) {
                        precision = atttypmod;
                    }
                }
                String comment = result.getString(9);
                if (relation.tableComment == null) {
                    relation.tableComment = result.getString(8);
                }
                relation.columns.add(
                        new SnapshotModel.ColumnModel(
                                result.getInt(4),
                                name,
                                name,
                                isQuoted(name),
                                dataType,
                                jdbcType(dataType),
                                !result.getBoolean(6),
                                precision,
                                scale,
                                maxLength,
                                comment == null ? null : comment,
                                false,
                                null));
            }
        }
    }

    private void loadPrimaryKeys(Connection connection, Map<String, TableCapture> relations)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT n.nspname, c.relname, a.attname "
                                        + "FROM pg_catalog.pg_constraint con "
                                        + "JOIN pg_catalog.pg_class c ON c.oid = con.conrelid "
                                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                                        + "JOIN LATERAL unnest(con.conkey) WITH ORDINALITY "
                                        + "AS k(attnum, ord) ON true "
                                        + "JOIN pg_catalog.pg_attribute a "
                                        + "ON a.attrelid = con.conrelid AND a.attnum = k.attnum "
                                        + "WHERE con.contype = 'p' "
                                        + "ORDER BY n.nspname, c.relname, k.ord");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                TableCapture relation =
                        relations.get(result.getString(1) + '\u001f' + result.getString(2));
                if (relation != null) {
                    relation.primaryKeys.add(result.getString(3));
                }
            }
        }
        for (TableCapture relation : relations.values()) {
            if (relation.primaryKeys.isEmpty()) {
                continue;
            }
            for (int index = 0; index < relation.columns.size(); index++) {
                SnapshotModel.ColumnModel column = relation.columns.get(index);
                if (relation.primaryKeys.contains(column.columnRaw())) {
                    relation.columns.set(index, withPrimaryKey(column));
                }
            }
        }
    }

    private SnapshotModel.ColumnModel withPrimaryKey(SnapshotModel.ColumnModel column) {
        return new SnapshotModel.ColumnModel(
                column.ordinal(),
                column.columnRaw(),
                column.columnNorm(),
                column.quoted(),
                column.dataType(),
                column.jdbcType(),
                column.nullable(),
                column.precision(),
                column.scale(),
                column.maxLength(),
                column.comment(),
                true,
                column.foreignKeyJson());
    }

    private void loadForeignKeys(Connection connection, Map<String, TableCapture> relations)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT sn.nspname, sc.relname, sa.attname, "
                                        + "tn.nspname, tc.relname, ta.attname "
                                        + "FROM pg_catalog.pg_constraint con "
                                        + "JOIN pg_catalog.pg_class sc ON sc.oid = con.conrelid "
                                        + "JOIN pg_catalog.pg_namespace sn ON sn.oid = sc.relnamespace "
                                        + "JOIN pg_catalog.pg_class tc ON tc.oid = con.confrelid "
                                        + "JOIN pg_catalog.pg_namespace tn ON tn.oid = tc.relnamespace "
                                        + "JOIN LATERAL unnest(con.conkey) WITH ORDINALITY AS sk(attnum, ord) "
                                        + "ON true "
                                        + "JOIN LATERAL unnest(con.confkey) WITH ORDINALITY AS tk(attnum, ord) "
                                        + "ON tk.ord = sk.ord "
                                        + "JOIN pg_catalog.pg_attribute sa "
                                        + "ON sa.attrelid = con.conrelid AND sa.attnum = sk.attnum "
                                        + "JOIN pg_catalog.pg_attribute ta "
                                        + "ON ta.attrelid = con.confrelid AND ta.attnum = tk.attnum "
                                        + "WHERE con.contype = 'f' "
                                        + "ORDER BY sn.nspname, sc.relname, sk.ord");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                TableCapture relation =
                        relations.get(result.getString(1) + '\u001f' + result.getString(2));
                if (relation == null) {
                    continue;
                }
                String referencedTable =
                        result.getString(4) + '.' + result.getString(5) + '.' + result.getString(6);
                for (int index = 0; index < relation.columns.size(); index++) {
                    SnapshotModel.ColumnModel column = relation.columns.get(index);
                    if (!column.columnRaw().equals(result.getString(3))) {
                        continue;
                    }
                    String json =
                            (column.foreignKeyJson() == null
                                            ? "["
                                            : column.foreignKeyJson()
                                                            .substring(
                                                                    0,
                                                                    column.foreignKeyJson().length()
                                                                            - 1)
                                                    + ",")
                                    + "{\"referencedTable\":\""
                                    + jsonEscape(referencedTable)
                                    + "\"}]";
                    relation.columns.set(
                            index,
                            new SnapshotModel.ColumnModel(
                                    column.ordinal(),
                                    column.columnRaw(),
                                    column.columnNorm(),
                                    column.quoted(),
                                    column.dataType(),
                                    column.jdbcType(),
                                    column.nullable(),
                                    column.precision(),
                                    column.scale(),
                                    column.maxLength(),
                                    column.comment(),
                                    column.primaryKey(),
                                    json));
                }
            }
        }
    }

    private void loadViewDefinitions(Connection connection, Map<String, TableCapture> relations)
            throws SQLException {
        try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT n.nspname, c.relname, pg_get_viewdef(c.oid, false) "
                                        + "FROM pg_catalog.pg_class c "
                                        + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                                        + "WHERE "
                                        + EXCLUDE_SYSTEM_SCHEMAS
                                        + " AND c.relkind = 'v'");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                TableCapture relation =
                        relations.get(result.getString(1) + '\u001f' + result.getString(2));
                if (relation != null) {
                    String definition = result.getString(3);
                    if (definition != null && !definition.isBlank()) {
                        relation.viewDefinition = definition;
                    }
                }
            }
        }
    }

    private static String typname(ResultSet result) throws SQLException {
        return result.getString(5);
    }

    private static String scalar(Connection connection, String sql, String what)
            throws SQLException {
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

    /** PG identifiers fold to lowercase unquoted; anything else in the catalog needs quoting. */
    private static boolean isQuoted(String identifier) {
        String lowered = identifier.toLowerCase(Locale.ROOT);
        return !identifier.equals(lowered) || !lowered.matches("[a-z_][a-z0-9_$]*");
    }

    private static int jdbcType(String dataType) {
        return switch (dataType) {
            case "int2" -> Types.SMALLINT;
            case "int4" -> Types.INTEGER;
            case "int8" -> Types.BIGINT;
            case "numeric" -> Types.DECIMAL;
            case "float4" -> Types.REAL;
            case "float8" -> Types.DOUBLE;
            case "bool" -> Types.BOOLEAN;
            case "varchar", "bpchar", "name" -> Types.VARCHAR;
            case "text", "json", "jsonb" -> Types.LONGVARCHAR;
            case "date" -> Types.DATE;
            case "time" -> Types.TIME;
            case "timetz" -> Types.TIME_WITH_TIMEZONE;
            case "timestamp" -> Types.TIMESTAMP;
            case "timestamptz" -> Types.TIMESTAMP_WITH_TIMEZONE;
            case "bytea" -> Types.BINARY;
            default -> Types.OTHER;
        };
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

    private static final class TableCapture {
        final String kind;
        final String schemaRaw;
        final String objectRaw;
        final String physicalIdentity;
        final List<SnapshotModel.ColumnModel> columns = new ArrayList<>();
        final List<String> primaryKeys = new ArrayList<>();
        String viewDefinition;
        String tableComment;

        TableCapture(String kind, String schemaRaw, String objectRaw, String physicalIdentity) {
            this.kind = kind;
            this.schemaRaw = schemaRaw;
            this.objectRaw = objectRaw;
            this.physicalIdentity = physicalIdentity;
        }
    }
}
