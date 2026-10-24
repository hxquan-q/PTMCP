package local.ptmcp.admin.metadata;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.schema.SchemaSnapshotStore;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Admin metadata catalog over the active published snapshot. Detail and dictionary writes only see
 * the columns of that snapshot; dictionary rows are annotations and never touch database DDL.
 */
public final class MetadataCatalogService {
    private static final int MAX_PAGE = 100;
    private static final int DEFAULT_PAGE = 20;
    private static final Set<String> KINDS = Set.of("TABLE", "VIEW");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final DataSource primary;
    private final SchemaSnapshotStore snapshots;

    public MetadataCatalogService(DataSource primary, SchemaSnapshotStore snapshots) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    public record Item(
            String id,
            String namespaceId,
            String databaseName,
            String schemaName,
            String objectName,
            String objectType,
            String tableComment,
            String generation,
            String snapshotId,
            String snapshotVersion) {}

    public record Page(List<Item> items, String nextCursor) {}

    public record ColumnView(
            int ordinal,
            String name,
            String databaseType,
            int jdbcType,
            boolean nullable,
            Integer precision,
            Integer scale,
            Long maxLength,
            String comment,
            boolean primaryKey,
            Object foreignKey) {}

    public record DictionaryColumn(String columnName, String title, String description) {}

    public record Dictionary(
            String title,
            String description,
            String ownerUserId,
            String version,
            List<DictionaryColumn> columns) {}

    public record Detail(
            String id,
            String namespaceId,
            String databaseName,
            String schemaName,
            String objectName,
            String objectType,
            String tableComment,
            String physicalIdentity,
            String generation,
            String snapshotId,
            String snapshotVersion,
            List<ColumnView> columns,
            Dictionary dictionary) {}

    public record DictionaryColumnWrite(String columnName, String title, String description) {}

    public record DictionaryWrite(
            String title,
            String description,
            Long ownerUserId,
            List<DictionaryColumnWrite> columns) {}

    public Page list(
            long datasourceId,
            Long namespaceId,
            String keyword,
            String kind,
            String cursor,
            Integer pageSize) {
        if (datasourceId <= 0 || (namespaceId != null && namespaceId <= 0)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        int size = pageSize == null ? DEFAULT_PAGE : pageSize;
        if (size < 1 || size > MAX_PAGE) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (kind != null && !kind.isBlank() && !KINDS.contains(kind)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (keyword != null && keyword.length() > 128) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Cursor after = decodeCursor(cursor);
        SchemaSnapshotStore.ActiveSnapshot active =
                snapshots.requireUsableActiveSnapshot(datasourceId);
        StringBuilder sql =
                new StringBuilder(
                        "SELECT o.id, n.id, n.catalog_raw, n.schema_raw, o.object_raw, o.kind, "
                                + "so.table_comment, o.generation, o.object_norm "
                                + "FROM snapshot_object so "
                                + "JOIN resource_object o ON o.id = so.object_id "
                                + "JOIN resource_namespace n ON n.id = o.namespace_id "
                                + "WHERE so.snapshot_id = ? AND n.datasource_id = ? "
                                + "AND o.status = 'ACTIVE' AND n.status = 'ACTIVE'");
        List<Object> parameters = new ArrayList<>();
        parameters.add(active.snapshotId());
        parameters.add(datasourceId);
        if (namespaceId != null) {
            sql.append(" AND n.id = ?");
            parameters.add(namespaceId);
        }
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND o.kind = ?");
            parameters.add(kind);
        }
        if (keyword != null && !keyword.isBlank()) {
            String pattern =
                    "%"
                            + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                            + "%";
            sql.append(" AND (o.object_raw LIKE ? OR so.table_comment LIKE ?)");
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (after != null) {
            sql.append(" AND (o.object_norm > ? OR (o.object_norm = ? AND o.id > ?))");
            parameters.add(after.objectNorm());
            parameters.add(after.objectNorm());
            parameters.add(after.objectId());
        }
        sql.append(" ORDER BY o.object_norm ASC, o.id ASC LIMIT ?");
        parameters.add(size + 1);
        try (Connection connection = configure(primary, true);
                PreparedStatement statement = statement(connection, sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            List<Item> rows = new ArrayList<>();
            List<Cursor> cursors = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong(1);
                    cursors.add(new Cursor(result.getString(9), id));
                    rows.add(
                            new Item(
                                    Long.toString(id),
                                    Long.toString(result.getLong(2)),
                                    result.getString(3),
                                    result.getString(4),
                                    result.getString(5),
                                    result.getString(6),
                                    result.getString(7),
                                    Long.toString(result.getLong(8)),
                                    Long.toString(active.snapshotId()),
                                    Long.toString(active.resourceVersion())));
                }
            }
            if (rows.size() <= size) {
                return new Page(rows, null);
            }
            List<Item> page = rows.subList(0, size);
            return new Page(List.copyOf(page), encodeCursor(cursors.get(size - 1)));
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public Detail detail(long objectId) {
        try (Connection connection = configure(primary, true)) {
            ObjectRow row = objectRow(connection, objectId);
            SchemaSnapshotStore.ActiveSnapshot active =
                    snapshots.requireUsableActiveSnapshot(row.datasourceId());
            SnapshotObject snapshot = snapshotObject(connection, active.snapshotId(), objectId);
            List<ColumnView> columns = columns(connection, active.snapshotId(), objectId);
            Dictionary dictionary = dictionary(connection, objectId, columns);
            return new Detail(
                    Long.toString(row.id()),
                    Long.toString(row.namespaceId()),
                    row.databaseName(),
                    row.schemaName(),
                    row.objectName(),
                    row.kind(),
                    snapshot.tableComment(),
                    row.physicalIdentity(),
                    Long.toString(row.generation()),
                    Long.toString(active.snapshotId()),
                    Long.toString(active.resourceVersion()),
                    columns,
                    dictionary);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public Detail replaceDictionary(
            long objectId, long expectedVersion, DictionaryWrite write, long actorId) {
        if (actorId <= 0 || expectedVersion < 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        ValidDictionary validated = validateDictionary(write);
        try (Connection connection = configure(primary, false)) {
            connection.setAutoCommit(false);
            try {
                ObjectRow row = objectRow(connection, objectId);
                SchemaSnapshotStore.ActiveSnapshot active =
                        snapshots.requireUsableActiveSnapshot(row.datasourceId());
                snapshotObject(connection, active.snapshotId(), objectId);
                Map<String, ColumnName> columns =
                        columnNames(connection, active.snapshotId(), objectId);
                long currentVersion = dictionaryVersion(connection, objectId);
                if (currentVersion != expectedVersion) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                List<ValidatedColumn> insertColumns = new ArrayList<>();
                for (ValidDictionaryColumn column : validated.columns()) {
                    ColumnName match = resolveColumn(columns, column.columnName());
                    if (match == null) {
                        throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                    }
                    insertColumns.add(new ValidatedColumn(match.columnNorm(), column.column()));
                }
                long nextVersion = expectedVersion + 1;
                try (PreparedStatement delete =
                        statement(
                                connection,
                                "DELETE FROM business_dictionary WHERE object_id = ?")) {
                    delete.setLong(1, objectId);
                    delete.executeUpdate();
                }
                LocalDateTime now = LocalDateTime.now(UTC);
                insertDictionaryRow(
                        connection,
                        objectId,
                        "",
                        validated.title(),
                        validated.description(),
                        validated.ownerUserId(),
                        nextVersion,
                        now,
                        actorId);
                for (ValidatedColumn column : insertColumns) {
                    insertDictionaryRow(
                            connection,
                            objectId,
                            column.columnNorm(),
                            column.column().title(),
                            column.column().description(),
                            validated.ownerUserId(),
                            nextVersion,
                            now,
                            actorId);
                }
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Preserve the original failure.
                }
                if (failure instanceof SQLException) {
                    throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return detail(objectId);
    }

    private static ObjectRow objectRow(Connection connection, long objectId) throws SQLException {
        if (objectId <= 0) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT o.id, o.namespace_id, n.datasource_id, n.catalog_raw, n.schema_raw, "
                                + "o.object_raw, o.kind, o.generation, o.physical_identity, o.status "
                                + "FROM resource_object o "
                                + "JOIN resource_namespace n ON n.id = o.namespace_id "
                                + "WHERE o.id = ?")) {
            statement.setLong(1, objectId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"ACTIVE".equals(result.getString(10))) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return new ObjectRow(
                        result.getLong(1),
                        result.getLong(2),
                        result.getLong(3),
                        result.getString(4),
                        result.getString(5),
                        result.getString(6),
                        result.getString(7),
                        result.getLong(8),
                        result.getString(9));
            }
        }
    }

    private static SnapshotObject snapshotObject(
            Connection connection, long snapshotId, long objectId) throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT table_comment FROM snapshot_object "
                                + "WHERE snapshot_id = ? AND object_id = ?")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return new SnapshotObject(result.getString(1));
            }
        }
    }

    private static List<ColumnView> columns(Connection connection, long snapshotId, long objectId)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT ordinal, column_raw, data_type, jdbc_type, nullable, "
                                + "precision_value, scale_value, max_length, comment, is_primary_key, "
                                + "foreign_key FROM resource_column "
                                + "WHERE snapshot_id = ? AND object_id = ? ORDER BY ordinal")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            List<ColumnView> columns = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long maxLength = result.getLong(8);
                    Long length = result.wasNull() ? null : maxLength;
                    int precision = result.getInt(6);
                    Integer precisionValue = result.wasNull() ? null : precision;
                    int scale = result.getInt(7);
                    Integer scaleValue = result.wasNull() ? null : scale;
                    columns.add(
                            new ColumnView(
                                    result.getInt(1),
                                    result.getString(2),
                                    result.getString(3),
                                    result.getInt(4),
                                    result.getBoolean(5),
                                    precisionValue,
                                    scaleValue,
                                    length,
                                    result.getString(9),
                                    result.getBoolean(10),
                                    parseJson(result.getString(11))));
                }
            }
            return columns;
        }
    }

    private static Dictionary dictionary(
            Connection connection, long objectId, List<ColumnView> columns) throws SQLException {
        Set<String> visible = new java.util.HashSet<>();
        for (ColumnView column : columns) {
            visible.add(column.name().toLowerCase(Locale.ROOT));
        }
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT column_norm, title, description, owner_user_id, version "
                                + "FROM business_dictionary WHERE object_id = ? "
                                + "ORDER BY column_norm")) {
            statement.setLong(1, objectId);
            String title = null;
            String description = null;
            String owner = null;
            long version = 1;
            List<DictionaryColumn> columnRows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    version = Math.max(version, result.getLong(5));
                    String norm = result.getString(1);
                    long ownerId = result.getLong(4);
                    String ownerValue = result.wasNull() ? null : Long.toString(ownerId);
                    if (norm.isEmpty()) {
                        title = result.getString(2);
                        description = result.getString(3);
                        owner = ownerValue;
                    } else {
                        String display = displayName(columns, norm);
                        if (visible.contains(norm.toLowerCase(Locale.ROOT))) {
                            columnRows.add(
                                    new DictionaryColumn(
                                            display, result.getString(2), result.getString(3)));
                        }
                    }
                }
            }
            return new Dictionary(title, description, owner, Long.toString(version), columnRows);
        }
    }

    private static String displayName(List<ColumnView> columns, String columnNorm) {
        for (ColumnView column : columns) {
            if (column.name().equalsIgnoreCase(columnNorm)) {
                return column.name();
            }
        }
        return columnNorm;
    }

    private static long dictionaryVersion(Connection connection, long objectId)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT COALESCE(MAX(version), 1) FROM business_dictionary WHERE object_id = ?")) {
            statement.setLong(1, objectId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static Map<String, ColumnName> columnNames(
            Connection connection, long snapshotId, long objectId) throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT column_norm, column_raw FROM resource_column "
                                + "WHERE snapshot_id = ? AND object_id = ?")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            Map<String, ColumnName> names = new LinkedHashMap<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String norm = result.getString(1);
                    String raw = result.getString(2);
                    names.putIfAbsent(norm, new ColumnName(raw, norm));
                    names.putIfAbsent(raw, new ColumnName(raw, norm));
                    names.putIfAbsent(raw.toLowerCase(Locale.ROOT), new ColumnName(raw, norm));
                }
            }
            return names;
        }
    }

    private static ColumnName resolveColumn(Map<String, ColumnName> columns, String requested) {
        ColumnName exact = columns.get(requested);
        if (exact != null) {
            return exact;
        }
        return columns.get(requested.toLowerCase(Locale.ROOT));
    }

    private static void insertDictionaryRow(
            Connection connection,
            long objectId,
            String columnNorm,
            String title,
            String description,
            Long ownerUserId,
            long version,
            LocalDateTime now,
            long actorId)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "INSERT INTO business_dictionary (object_id, column_norm, title, description, "
                                + "owner_user_id, version, create_time, update_time, created_by, updated_by) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, objectId);
            statement.setString(2, columnNorm);
            statement.setString(3, title);
            statement.setString(4, description);
            if (ownerUserId == null) {
                statement.setNull(5, java.sql.Types.BIGINT);
            } else {
                statement.setLong(5, ownerUserId);
            }
            statement.setLong(6, version);
            statement.setObject(7, now);
            statement.setObject(8, now);
            statement.setLong(9, actorId);
            statement.setLong(10, actorId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("dictionary insert");
            }
        }
    }

    private static ValidDictionary validateDictionary(DictionaryWrite write) {
        if (write == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String title = plainText(write.title(), 128, true);
        String description = plainText(write.description(), 8000, false);
        if (write.ownerUserId() != null && write.ownerUserId() <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        List<DictionaryColumnWrite> requested =
                write.columns() == null ? List.of() : write.columns();
        if (requested.size() > 500) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        List<ValidDictionaryColumn> columns = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (DictionaryColumnWrite column : requested) {
            if (column == null || column.columnName() == null || column.columnName().isBlank()) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            String name = column.columnName().trim();
            if (name.length() > 128 || !seen.add(name.toLowerCase(Locale.ROOT))) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            columns.add(
                    new ValidDictionaryColumn(
                            name,
                            new ValidDictionaryColumnValue(
                                    plainText(column.title(), 128, true),
                                    plainText(column.description(), 8000, false))));
        }
        return new ValidDictionary(title, description, write.ownerUserId(), columns);
    }

    /** Rich text is disabled in phase one, so angle brackets are always rejected. */
    private static String plainText(String value, int max, boolean required) {
        if (value == null || value.isEmpty()) {
            if (required) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            return "";
        }
        if (value.length() > max || value.indexOf('<') >= 0 || value.indexOf('>') >= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)
                    && character != '\n'
                    && character != '\r'
                    && character != '\t') {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        return value;
    }

    private static Object parseJson(String json) {
        return json == null || json.isBlank() ? null : JSON.readValue(json, Object.class);
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('\n');
            if (separator <= 0 || separator == decoded.length() - 1) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            long id = Long.parseLong(decoded.substring(separator + 1));
            if (id <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            return new Cursor(decoded.substring(0, separator), id);
        } catch (IllegalArgumentException error) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static String encodeCursor(Cursor cursor) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        (cursor.objectNorm() + "\n" + cursor.objectId())
                                .getBytes(StandardCharsets.UTF_8));
    }

    private static Connection configure(DataSource dataSource, boolean readOnly)
            throws SQLException {
        Connection connection = dataSource.getConnection();
        connection.setReadOnly(readOnly);
        connection.setAutoCommit(true);
        return connection;
    }

    private static PreparedStatement statement(Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(5);
        return statement;
    }

    private record Cursor(String objectNorm, long objectId) {}

    private record ColumnName(String raw, String columnNorm) {}

    private record SnapshotObject(String tableComment) {}

    private record ObjectRow(
            long id,
            long namespaceId,
            long datasourceId,
            String databaseName,
            String schemaName,
            String objectName,
            String kind,
            long generation,
            String physicalIdentity) {}

    private record ValidDictionaryColumnValue(String title, String description) {}

    private record ValidDictionaryColumn(String columnName, ValidDictionaryColumnValue column) {}

    private record ValidatedColumn(String columnNorm, ValidDictionaryColumnValue column) {}

    private record ValidDictionary(
            String title,
            String description,
            Long ownerUserId,
            List<ValidDictionaryColumn> columns) {}
}
