package local.ptmcp.persistence.schema;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Loads the active PUBLISHED snapshot of one datasource in one short read-only transaction: the
 * datasource identity row, its ACTIVE namespaces and the snapshot's objects and columns. A missing,
 * inactive or expired state yields an unusable result so callers fail closed with {@code
 * SCHEMA_STALE}; STAGING snapshots and retired generations are never visible. Identifier spellings
 * are returned unchanged as data and are never re-normalized here.
 */
public final class PublishedSnapshotReader {
    private static final int STATEMENT_SECONDS = 5;

    private final DataSource primaryMetadata;

    public PublishedSnapshotReader(DataSource primaryMetadata) {
        this.primaryMetadata = Objects.requireNonNull(primaryMetadata, "primaryMetadata");
    }

    /** One datasource's publishable state; {@code snapshotId} is null when nothing is usable. */
    public record Published(
            long datasourceId,
            Long snapshotId,
            long configVersion,
            long resourceVersion,
            String engine,
            String host,
            int port,
            String databaseName,
            String username,
            byte[] credentialCipher,
            String keyId,
            int poolMax,
            List<Namespace> namespaces) {
        public Published {
            if (datasourceId <= 0 || configVersion <= 0 || resourceVersion <= 0 || poolMax < 1) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            namespaces = List.copyOf(namespaces);
            credentialCipher = credentialCipher == null ? null : credentialCipher.clone();
        }

        public boolean usable() {
            return snapshotId != null && !namespaces.isEmpty();
        }
    }

    public record Namespace(
            long id,
            String catalogRaw,
            String catalogNorm,
            String schemaRaw,
            String schemaNorm,
            long generation,
            List<TableObject> objects) {
        public Namespace {
            if (id <= 0 || generation <= 0) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            objects = List.copyOf(objects);
        }
    }

    public record TableObject(
            long id,
            String objectRaw,
            String objectNorm,
            boolean quoted,
            String kind,
            long generation,
            long objectVersion,
            String tableComment,
            List<Column> columns) {
        public TableObject {
            if (id <= 0 || generation <= 0 || objectVersion <= 0) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            columns = List.copyOf(columns);
        }
    }

    public record Column(
            int ordinal,
            String columnRaw,
            String columnNorm,
            String dataType,
            int jdbcType,
            boolean nullable,
            Integer precision,
            Integer scale,
            Long maxLength,
            String comment,
            boolean primaryKey,
            String foreignKeyJson) {}

    /**
     * The identity of one usable datasource: an ACTIVE row pinned to a PUBLISHED, unexpired
     * snapshot. Connection coordinates and credentials are deliberately absent; discovery callers
     * never need them and must not receive them.
     */
    public record UsableDatasource(
            long datasourceId, String code, String name, String engine, String databaseName) {
        public UsableDatasource {
            if (datasourceId <= 0) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
        }
    }

    /** Administrative text, never instructions. Ownership/audit identities are not exposed. */
    public record DictionaryEntry(
            String title, String description, long version, boolean descriptionTruncated) {}

    /**
     * Reads only the server-selected objects after the caller has authorized the entire batch.
     * Dictionary keys must still exist in the same published snapshot. Descriptions are bounded at
     * the database boundary; the gateway applies its smaller UTF-8 and aggregate output budgets.
     */
    public Map<Long, Map<String, DictionaryEntry>> loadDictionaries(
            Published expected, Set<Long> authorizedObjectIds) {
        Objects.requireNonNull(expected, "expected");
        List<Long> ids = authorizedObjectIds.stream().sorted().toList();
        Set<Long> snapshotIds =
                expected.namespaces().stream()
                        .flatMap(namespace -> namespace.objects().stream())
                        .map(TableObject::id)
                        .collect(java.util.stream.Collectors.toSet());
        if (!expected.usable()
                || ids.isEmpty()
                || ids.size() > 20
                || !snapshotIds.containsAll(ids)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = primaryMetadata.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                Head current = readHead(connection, expected.datasourceId());
                if (current == null || current.snapshotId() != expected.snapshotId()) {
                    throw new DomainException(ErrorCode.SCHEMA_STALE);
                }
                if (current.configVersion() != expected.configVersion()
                        || current.resourceVersion() != expected.resourceVersion()) {
                    throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
                }
                String placeholders =
                        String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
                String sql =
                        "SELECT bd.object_id, bd.column_norm, bd.title,"
                                + " LEFT(bd.description, 2048), bd.version, CHAR_LENGTH(bd.description) > 2048"
                                + " FROM business_dictionary bd JOIN snapshot_object so ON so.object_id = bd.object_id"
                                + " JOIN resource_object ro ON ro.id = bd.object_id AND ro.status = 'ACTIVE'"
                                + " JOIN resource_namespace rn ON rn.id = ro.namespace_id AND rn.status = 'ACTIVE'"
                                + " LEFT JOIN resource_column rc ON rc.snapshot_id = so.snapshot_id"
                                + " AND rc.object_id = bd.object_id AND rc.column_norm = bd.column_norm"
                                + " WHERE so.snapshot_id = ? AND rn.datasource_id = ? AND bd.object_id IN ("
                                + placeholders
                                + ") AND (bd.column_norm = '' OR rc.column_norm IS NOT NULL)"
                                + " ORDER BY bd.object_id, bd.column_norm";
                Map<Long, Map<String, DictionaryEntry>> dictionaries = new LinkedHashMap<>();
                try (PreparedStatement query = statement(connection, sql)) {
                    query.setLong(1, expected.snapshotId());
                    query.setLong(2, expected.datasourceId());
                    for (int index = 0; index < ids.size(); index++) {
                        query.setLong(index + 3, ids.get(index));
                    }
                    try (ResultSet rows = query.executeQuery()) {
                        while (rows.next()) {
                            dictionaries
                                    .computeIfAbsent(
                                            rows.getLong(1), ignored -> new LinkedHashMap<>())
                                    .put(
                                            rows.getString(2),
                                            new DictionaryEntry(
                                                    rows.getString(3),
                                                    rows.getString(4),
                                                    rows.getLong(5),
                                                    rows.getBoolean(6)));
                        }
                    }
                }
                Map<Long, Map<String, DictionaryEntry>> immutable = new LinkedHashMap<>();
                dictionaries.forEach((key, value) -> immutable.put(key, Map.copyOf(value)));
                return Map.copyOf(immutable);
            } finally {
                connection.rollback();
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public Published load(long datasourceId) {
        if (datasourceId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = primaryMetadata.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try {
                return load(connection, datasourceId);
            } finally {
                connection.rollback();
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Heads of every usable datasource in id order. Usability mirrors {@link #load(long)} exactly:
     * an ACTIVE datasource whose active snapshot exists, matches the current config version, is
     * PUBLISHED and has not expired. Anything else (drafts, retired pins, expired snapshots) is
     * invisible to discovery.
     */
    public List<UsableDatasource> listUsable() {
        try (Connection connection = primaryMetadata.getConnection()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (PreparedStatement statement =
                            statement(
                                    connection,
                                    "SELECT ds.id, ds.code, ds.name, ds.engine, ds.database_name"
                                            + " FROM datasource ds"
                                            + " JOIN schema_snapshot ss ON ss.id = ds.active_snapshot_id"
                                            + " WHERE ds.status = 'ACTIVE' AND ss.datasource_id = ds.id"
                                            + " AND ss.config_version = ds.config_version"
                                            + " AND ss.status = 'PUBLISHED'"
                                            + " AND ss.expires_at IS NOT NULL"
                                            + " AND ss.expires_at > UTC_TIMESTAMP(6)"
                                            + " ORDER BY ds.id");
                    ResultSet rows = statement.executeQuery()) {
                List<UsableDatasource> usable = new ArrayList<>();
                while (rows.next()) {
                    usable.add(
                            new UsableDatasource(
                                    rows.getLong(1),
                                    rows.getString(2),
                                    rows.getString(3),
                                    rows.getString(4),
                                    rows.getString(5)));
                }
                return List.copyOf(usable);
            } finally {
                connection.rollback();
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static Published load(Connection connection, long datasourceId) throws SQLException {
        Head head = readHead(connection, datasourceId);
        if (head == null) {
            return unusable(datasourceId);
        }
        Map<Long, NamespaceDraft> namespaces = new LinkedHashMap<>();
        Map<Long, ObjectDraft> objects = new LinkedHashMap<>();
        readNamespaces(connection, datasourceId, namespaces);
        readObjects(connection, head.snapshotId(), namespaces, objects);
        readColumns(connection, head.snapshotId(), objects);
        List<Namespace> published = new ArrayList<>(namespaces.size());
        for (NamespaceDraft draft : namespaces.values()) {
            List<TableObject> objectList = new ArrayList<>(draft.objects.size());
            for (ObjectDraft object : draft.objects) {
                objectList.add(object.toRecord());
            }
            published.add(draft.toRecord(objectList));
        }
        return new Published(
                datasourceId,
                head.snapshotId(),
                head.configVersion(),
                head.resourceVersion(),
                head.engine(),
                head.host(),
                head.port(),
                head.databaseName(),
                head.username(),
                head.credentialCipher(),
                head.keyId(),
                head.poolMax(),
                published);
    }

    private static Published unusable(long datasourceId) {
        return new Published(datasourceId, null, 1, 1, "", "", 0, "", "", null, "", 1, List.of());
    }

    private record Head(
            long snapshotId,
            long configVersion,
            long resourceVersion,
            String engine,
            String host,
            int port,
            String databaseName,
            String username,
            byte[] credentialCipher,
            String keyId,
            int poolMax) {}

    private static final class NamespaceDraft {
        final long id;
        final String catalogRaw;
        final String catalogNorm;
        final String schemaRaw;
        final String schemaNorm;
        final long generation;
        final List<ObjectDraft> objects = new ArrayList<>();

        NamespaceDraft(
                long id,
                String catalogRaw,
                String catalogNorm,
                String schemaRaw,
                String schemaNorm,
                long generation) {
            this.id = id;
            this.catalogRaw = catalogRaw;
            this.catalogNorm = catalogNorm;
            this.schemaRaw = schemaRaw;
            this.schemaNorm = schemaNorm;
            this.generation = generation;
        }

        Namespace toRecord(List<TableObject> objectList) {
            return new Namespace(
                    id, catalogRaw, catalogNorm, schemaRaw, schemaNorm, generation, objectList);
        }
    }

    private static final class ObjectDraft {
        final long id;
        final String objectRaw;
        final String objectNorm;
        final boolean quoted;
        final String kind;
        final long generation;
        final long objectVersion;
        final String tableComment;
        final List<Column> columns = new ArrayList<>();

        ObjectDraft(
                long id,
                String objectRaw,
                String objectNorm,
                boolean quoted,
                String kind,
                long generation,
                long objectVersion,
                String tableComment) {
            this.id = id;
            this.objectRaw = objectRaw;
            this.objectNorm = objectNorm;
            this.quoted = quoted;
            this.kind = kind;
            this.generation = generation;
            this.objectVersion = objectVersion;
            this.tableComment = tableComment;
        }

        TableObject toRecord() {
            return new TableObject(
                    id,
                    objectRaw,
                    objectNorm,
                    quoted,
                    kind,
                    generation,
                    objectVersion,
                    tableComment,
                    List.copyOf(columns));
        }
    }

    private static Head readHead(Connection connection, long datasourceId) throws SQLException {
        long snapshotId;
        String engine;
        String host;
        int port;
        String databaseName;
        String username;
        byte[] credentialCipher;
        String keyId;
        int poolMax;
        long configVersion;
        long resourceVersion;
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT engine, host, port, database_name, username, credential_cipher,"
                                + " key_id, pool_max, config_version, resource_version,"
                                + " active_snapshot_id FROM datasource WHERE id = ? AND status ="
                                + " 'ACTIVE'")) {
            statement.setLong(1, datasourceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                engine = result.getString(1);
                host = result.getString(2);
                port = result.getInt(3);
                databaseName = result.getString(4);
                username = result.getString(5);
                credentialCipher = result.getBytes(6);
                keyId = result.getString(7);
                poolMax = result.getInt(8);
                configVersion = result.getLong(9);
                resourceVersion = result.getLong(10);
                snapshotId = result.getLong(11);
                if (result.wasNull()) {
                    return null;
                }
            }
        }
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT status, expires_at FROM schema_snapshot WHERE id = ?"
                                + " AND datasource_id = ? AND config_version = ?")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, datasourceId);
            statement.setLong(3, configVersion);
            try (ResultSet result = statement.executeQuery()) {
                LocalDateTime expiresAt =
                        result.next() ? result.getObject(2, LocalDateTime.class) : null;
                if (expiresAt == null
                        || !"PUBLISHED".equals(result.getString(1))
                        || !expiresAt.isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
                    return null;
                }
            }
        }
        return new Head(
                snapshotId,
                configVersion,
                resourceVersion,
                engine,
                host,
                port,
                databaseName,
                username,
                credentialCipher,
                keyId,
                poolMax);
    }

    private static void readNamespaces(
            Connection connection, long datasourceId, Map<Long, NamespaceDraft> namespaces)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT id, catalog_raw, catalog_norm, schema_raw, schema_norm, generation"
                                + " FROM resource_namespace WHERE datasource_id = ?"
                                + " AND status = 'ACTIVE' ORDER BY id")) {
            statement.setLong(1, datasourceId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong(1);
                    namespaces.put(
                            id,
                            new NamespaceDraft(
                                    id,
                                    result.getString(2),
                                    result.getString(3),
                                    result.getString(4),
                                    result.getString(5),
                                    result.getLong(6)));
                }
            }
        }
    }

    private static void readObjects(
            Connection connection,
            long snapshotId,
            Map<Long, NamespaceDraft> namespaces,
            Map<Long, ObjectDraft> objects)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT ro.namespace_id, ro.id, ro.object_raw, ro.object_norm, ro.quoted,"
                                + " ro.kind, ro.generation, so.object_version, so.table_comment"
                                + " FROM snapshot_object so JOIN resource_object ro ON ro.id ="
                                + " so.object_id WHERE so.snapshot_id = ? AND ro.status = 'ACTIVE'"
                                + " ORDER BY ro.id")) {
            statement.setLong(1, snapshotId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    NamespaceDraft namespace = namespaces.get(result.getLong(1));
                    if (namespace == null) {
                        continue;
                    }
                    ObjectDraft draft =
                            new ObjectDraft(
                                    result.getLong(2),
                                    result.getString(3),
                                    result.getString(4),
                                    result.getInt(5) == 1,
                                    result.getString(6),
                                    result.getLong(7),
                                    result.getLong(8),
                                    result.getString(9));
                    namespace.objects.add(draft);
                    objects.put(draft.id, draft);
                }
            }
        }
    }

    private static void readColumns(
            Connection connection, long snapshotId, Map<Long, ObjectDraft> objects)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT object_id, ordinal, column_raw, column_norm, data_type, jdbc_type,"
                                + " nullable, precision_value, scale_value, max_length, comment,"
                                + " is_primary_key, foreign_key"
                                + " FROM resource_column WHERE snapshot_id = ?"
                                + " ORDER BY object_id, ordinal")) {
            statement.setLong(1, snapshotId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ObjectDraft draft = objects.get(result.getLong(1));
                    if (draft == null) {
                        continue;
                    }
                    draft.columns.add(
                            new Column(
                                    result.getInt(2),
                                    result.getString(3),
                                    result.getString(4),
                                    result.getString(5),
                                    result.getInt(6),
                                    result.getInt(7) == 1,
                                    nullableInt(result, 8),
                                    nullableInt(result, 9),
                                    nullableLong(result, 10),
                                    result.getString(11),
                                    result.getInt(12) == 1,
                                    result.getString(13)));
                }
            }
        }
    }

    private static Integer nullableInt(ResultSet result, int index) throws SQLException {
        int value = result.getInt(index);
        return result.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet result, int index) throws SQLException {
        long value = result.getLong(index);
        return result.wasNull() ? null : value;
    }

    private static PreparedStatement statement(Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            statement.setQueryTimeout(STATEMENT_SECONDS);
            return statement;
        } catch (SQLException | RuntimeException error) {
            try {
                statement.close();
            } catch (SQLException | RuntimeException ignored) {
                // Preserve the original failure.
            }
            throw error;
        }
    }
}
