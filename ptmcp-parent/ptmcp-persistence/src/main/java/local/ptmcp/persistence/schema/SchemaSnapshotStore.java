package local.ptmcp.persistence.schema;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.SecurityChangeTransaction;
import local.ptmcp.persistence.SecurityEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Stages and publishes schema snapshots. Staging writes one complete STAGING snapshot in a single
 * metadata transaction; publishing re-checks {@code config_version}, flips {@code
 * datasource.active_snapshot_id}, bumps {@code resource_version}, revokes grants of retired objects
 * and records the epoch event — all inside one {@link SecurityChangeTransaction} commit. A failed
 * publish never leaves a partially published snapshot.
 */
public final class SchemaSnapshotStore {
    private static final int STATEMENT_SECONDS = 5;
    private static final int MAX_PREVIOUS_OBJECTS = 20_000;

    private final DataSource primaryMetadata;
    private final SecurityChangeTransaction changes;

    public SchemaSnapshotStore(DataSource primaryMetadata) {
        this.primaryMetadata = Objects.requireNonNull(primaryMetadata, "primaryMetadata");
        this.changes = new SecurityChangeTransaction(primaryMetadata);
    }

    /** Writes one complete STAGING snapshot and returns its id. */
    public long stage(long datasourceId, SnapshotModel model, LocalDateTime capturedAt) {
        Objects.requireNonNull(model, "model");
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            connection.setAutoCommit(false);
            long snapshotId;
            try {
                snapshotId = stageInternal(connection, datasourceId, model, capturedAt);
                connection.commit();
            } catch (SQLException | RuntimeException | Error failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Original failure is reported.
                }
                if (failure instanceof SQLException sql) {
                    throw sql;
                }
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw (Error) failure;
            }
            return snapshotId;
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private long stageInternal(
            Connection connection, long datasourceId, SnapshotModel model, LocalDateTime capturedAt)
            throws SQLException {
        LocalDateTime now = nowUtc();
        // Serializes identity allocation and its publication baseline with config changes and
        // other captures. This transaction never subsequently takes the security-global lock.
        try (PreparedStatement lock =
                statement(connection, "SELECT id FROM datasource WHERE id = ? FOR UPDATE")) {
            lock.setLong(1, datasourceId);
            try (ResultSet result = lock.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
        Previous previous = loadPreviousPublished(connection, datasourceId);

        try (PreparedStatement insert =
                statement(
                        connection,
                        "INSERT INTO schema_snapshot (datasource_id, config_version, resource_version, "
                                + "status, captured_at, expires_at, object_count, checksum, create_time, update_time) "
                                + "SELECT id, config_version, resource_version, 'STAGING', ?, "
                                + "DATE_ADD(?, INTERVAL 1 DAY), 0, ?, ?, ? FROM datasource WHERE id = ?")) {
            insert.setObject(1, capturedAt);
            insert.setObject(2, capturedAt);
            insert.setBytes(3, model.checksum());
            insert.setObject(4, now);
            insert.setObject(5, now);
            insert.setLong(6, datasourceId);
            if (insert.executeUpdate() != 1) {
                throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
            }
        }
        long snapshotId = lastId(connection);

        int objectCount = 0;
        for (SnapshotModel.NamespaceModel namespace : model.namespaces()) {
            long namespaceId = resolveNamespace(connection, datasourceId, namespace, now);
            for (SnapshotModel.ObjectModel object : namespace.objects()) {
                long objectId = resolveObject(connection, namespaceId, object, capturedAt, now);
                ObjectVersion versioned = objectVersion(objectId, object, previous);
                insertSnapshotObject(connection, snapshotId, objectId, object, versioned, now);
                insertColumns(connection, snapshotId, objectId, object, now);
                objectCount++;
            }
        }
        try (PreparedStatement update =
                statement(
                        connection,
                        "UPDATE schema_snapshot SET object_count = ?, update_time = ? WHERE id = ?")) {
            update.setInt(1, objectCount);
            update.setObject(2, now);
            update.setLong(3, snapshotId);
            update.executeUpdate();
        }
        return snapshotId;
    }

    private Previous loadPreviousPublished(Connection connection, long datasourceId)
            throws SQLException {
        Map<Long, String> objectSignatures = new HashMap<>();
        Map<Long, Long> objectVersions = new HashMap<>();
        Map<Long, byte[]> objectDefinitions = new HashMap<>();
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT so.object_id, so.object_version, so.column_signature, so.definition_hash "
                                + "FROM datasource ds JOIN snapshot_object so "
                                + "ON so.snapshot_id = ds.active_snapshot_id "
                                + "WHERE ds.id = ? LIMIT "
                                + MAX_PREVIOUS_OBJECTS)) {
            statement.setLong(1, datasourceId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long objectId = result.getLong(1);
                    objectVersions.putIfAbsent(objectId, result.getLong(2));
                    objectSignatures.put(objectId, HexFormat.of().formatHex(result.getBytes(3)));
                    objectDefinitions.put(objectId, result.getBytes(4));
                }
            }
        }
        return new Previous(objectSignatures, objectVersions, objectDefinitions);
    }

    private record Previous(
            Map<Long, String> objectSignatures,
            Map<Long, Long> objectVersions,
            Map<Long, byte[]> objectDefinitions) {}

    private record ObjectVersion(String signature, long version) {}

    private ObjectVersion objectVersion(
            long objectId, SnapshotModel.ObjectModel object, Previous previous) {
        StringBuilder canonical = new StringBuilder(128);
        canonical.append(object.kind()).append('\n');
        for (SnapshotModel.ColumnModel column : object.columns()) {
            canonical
                    .append(column.ordinal())
                    .append('\t')
                    .append(column.columnNorm())
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
                    .append('\t')
                    .append(column.primaryKey())
                    .append('\t')
                    .append(column.foreignKeyJson())
                    .append('\n');
        }
        String signature = sha256Hex(canonical.toString());
        Long previousVersion = previous.objectVersions().get(objectId);
        if (previousVersion == null) {
            return new ObjectVersion(signature, 1);
        }
        // Same generation keeps its identity; the version only advances when the captured shape
        // differs from the version the object last published.
        long version =
                signature.equals(previous.objectSignatures().get(objectId))
                                && previous.objectDefinitions().get(objectId) != null
                                && MessageDigest.isEqual(
                                        object.definitionHash(),
                                        previous.objectDefinitions().get(objectId))
                        ? previousVersion
                        : previousVersion + 1;
        return new ObjectVersion(signature, version);
    }

    private long resolveNamespace(
            Connection connection,
            long datasourceId,
            SnapshotModel.NamespaceModel namespace,
            LocalDateTime now)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT id, generation, status FROM resource_namespace "
                                + "WHERE datasource_id = ? AND catalog_norm = ? AND schema_norm = ? "
                                + "ORDER BY (status = 'ACTIVE') DESC, generation DESC LIMIT 1 FOR UPDATE")) {
            statement.setLong(1, datasourceId);
            statement.setString(2, namespace.catalogNorm());
            statement.setString(3, namespace.schemaNorm());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    long id = result.getLong(1);
                    if ("ACTIVE".equals(result.getString(3))) {
                        return id;
                    }
                    // A namespace that previously went MISSING restarts at a new generation so
                    // old authorizations never silently apply to the recreated bucket.
                    return insertNamespace(
                            connection, datasourceId, namespace, result.getLong(2) + 1, now);
                }
            }
        }
        return insertNamespace(connection, datasourceId, namespace, 1, now);
    }

    private long insertNamespace(
            Connection connection,
            long datasourceId,
            SnapshotModel.NamespaceModel namespace,
            long generation,
            LocalDateTime now)
            throws SQLException {
        try (PreparedStatement insert =
                statement(
                        connection,
                        "INSERT INTO resource_namespace (datasource_id, catalog_raw, catalog_norm, "
                                + "schema_raw, schema_norm, generation, status, create_time, update_time) "
                                + "VALUES (?, ?, ?, ?, ?, ?, 'STAGING', ?, ?)")) {
            insert.setLong(1, datasourceId);
            insert.setString(2, namespace.catalogRaw());
            insert.setString(3, namespace.catalogNorm());
            insert.setString(4, namespace.schemaRaw());
            insert.setString(5, namespace.schemaNorm());
            insert.setLong(6, generation);
            insert.setObject(7, now);
            insert.setObject(8, now);
            insert.executeUpdate();
        }
        return lastId(connection);
    }

    private long resolveObject(
            Connection connection,
            long namespaceId,
            SnapshotModel.ObjectModel object,
            LocalDateTime capturedAt,
            LocalDateTime now)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT id, status FROM resource_object "
                                + "WHERE namespace_id = ? AND object_norm = ? "
                                + "ORDER BY (status = 'ACTIVE') DESC, generation DESC LIMIT 1 FOR UPDATE")) {
            statement.setLong(1, namespaceId);
            statement.setString(2, object.objectNorm());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    long id = result.getLong(1);
                    if ("ACTIVE".equals(result.getString(2))) {
                        return id;
                    }
                }
            }
        }
        // Recreated object: new identity row at a new generation; the old row keeps its history.
        try (PreparedStatement insert =
                statement(
                        connection,
                        "INSERT INTO resource_object (namespace_id, generation, object_raw, object_norm, "
                                + "quoted, kind, status, definition_hash, physical_identity, first_seen_at, "
                                + "last_seen_at, create_time, update_time) "
                                + "SELECT ?, COALESCE(MAX(generation), 0) + 1, ?, ?, ?, ?, 'STAGING', ?, ?, ?, ?, ?, ? "
                                + "FROM resource_object WHERE namespace_id = ?")) {
            insert.setLong(1, namespaceId);
            insert.setString(2, object.objectRaw());
            insert.setString(3, object.objectNorm());
            insert.setInt(4, object.quoted() ? 1 : 0);
            insert.setString(5, object.kind());
            insert.setBytes(6, object.definitionHash());
            setNullableString(insert, 7, object.physicalIdentity());
            insert.setObject(8, capturedAt);
            insert.setObject(9, capturedAt);
            insert.setObject(10, now);
            insert.setObject(11, now);
            insert.setLong(12, namespaceId);
            if (insert.executeUpdate() != 1) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
        }
        return lastId(connection);
    }

    private void insertSnapshotObject(
            Connection connection,
            long snapshotId,
            long objectId,
            SnapshotModel.ObjectModel object,
            ObjectVersion versioned,
            LocalDateTime now)
            throws SQLException {
        try (PreparedStatement insert =
                statement(
                        connection,
                        "INSERT INTO snapshot_object (snapshot_id, object_id, object_version, "
                                + "table_comment, column_signature, definition_hash, physical_identity, "
                                + "create_time, update_time) VALUES (?, ?, ?, ?, UNHEX(?), ?, ?, ?, ?)")) {
            insert.setLong(1, snapshotId);
            insert.setLong(2, objectId);
            insert.setLong(3, versioned.version());
            insert.setString(4, object.tableComment());
            insert.setString(5, versioned.signature());
            insert.setBytes(6, object.definitionHash());
            setNullableString(insert, 7, object.physicalIdentity());
            insert.setObject(8, now);
            insert.setObject(9, now);
            insert.executeUpdate();
        }
    }

    private void insertColumns(
            Connection connection,
            long snapshotId,
            long objectId,
            SnapshotModel.ObjectModel object,
            LocalDateTime now)
            throws SQLException {
        try (PreparedStatement insert =
                statement(
                        connection,
                        "INSERT INTO resource_column (snapshot_id, object_id, ordinal, column_raw, "
                                + "column_norm, quoted, data_type, jdbc_type, nullable, precision_value, "
                                + "scale_value, max_length, comment, is_primary_key, foreign_key, "
                                + "create_time, update_time) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            for (SnapshotModel.ColumnModel column : object.columns()) {
                insert.setLong(1, snapshotId);
                insert.setLong(2, objectId);
                insert.setInt(3, column.ordinal());
                insert.setString(4, column.columnRaw());
                insert.setString(5, column.columnNorm());
                insert.setInt(6, column.quoted() ? 1 : 0);
                insert.setString(7, column.dataType());
                insert.setInt(8, column.jdbcType());
                insert.setInt(9, column.nullable() ? 1 : 0);
                if (column.precision() == null) {
                    insert.setNull(10, Types.INTEGER);
                } else {
                    insert.setInt(10, column.precision());
                }
                if (column.scale() == null) {
                    insert.setNull(11, Types.INTEGER);
                } else {
                    insert.setInt(11, column.scale());
                }
                if (column.maxLength() == null) {
                    insert.setNull(12, Types.BIGINT);
                } else {
                    insert.setLong(12, column.maxLength());
                }
                if (column.comment() == null) {
                    insert.setNull(13, Types.VARCHAR);
                } else {
                    insert.setString(13, column.comment());
                }
                insert.setInt(14, column.primaryKey() ? 1 : 0);
                if (column.foreignKeyJson() == null) {
                    insert.setNull(15, Types.VARCHAR);
                } else {
                    insert.setString(15, column.foreignKeyJson());
                }
                insert.setObject(16, now);
                insert.setObject(17, now);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    /**
     * Publishes a STAGING snapshot: config_version re-check, atomic active switch, resource version
     * bump, retirement of the previous snapshot and revocation of grants that point at objects this
     * snapshot retired. Commits through {@link SecurityChangeTransaction} so the global epoch and
     * the ACL event land in the same transaction.
     */
    public long publish(long snapshotId, long expectedConfigVersion) {
        long datasourceId = datasourceOf(snapshotId);
        long currentResourceVersion = currentResourceVersion(datasourceId);
        long newResourceVersion = currentResourceVersion + 1;
        SecurityEvent event =
                new SecurityEvent(
                        UUID.randomUUID(),
                        SecurityEvent.Kind.ACL_CHANGED,
                        new SecurityEvent.NumericId(datasourceId),
                        newResourceVersion);
        changes.execute(
                event,
                connection -> {
                    long configVersion;
                    try (PreparedStatement lock =
                            statement(
                                    connection,
                                    "SELECT config_version, resource_version FROM datasource WHERE id = ? FOR UPDATE")) {
                        lock.setLong(1, datasourceId);
                        try (ResultSet result = lock.executeQuery()) {
                            if (!result.next()) {
                                throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                            }
                            configVersion = result.getLong(1);
                            if (result.getLong(2) != currentResourceVersion) {
                                throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
                            }
                        }
                    }
                    if (configVersion != expectedConfigVersion) {
                        throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
                    }
                    try (PreparedStatement publish =
                            statement(
                                    connection,
                                    "UPDATE schema_snapshot SET status = 'PUBLISHED', update_time = UTC_TIMESTAMP(6) "
                                            + "WHERE id = ? AND datasource_id = ? AND config_version = ? "
                                            + "AND resource_version = ? AND status = 'STAGING' "
                                            + "AND expires_at > UTC_TIMESTAMP(6)")) {
                        publish.setLong(1, snapshotId);
                        publish.setLong(2, datasourceId);
                        publish.setLong(3, expectedConfigVersion);
                        publish.setLong(4, currentResourceVersion);
                        if (publish.executeUpdate() != 1) {
                            throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
                        }
                    }
                    publishResources(connection, datasourceId, snapshotId);
                    try (PreparedStatement retire =
                            statement(
                                    connection,
                                    "UPDATE schema_snapshot SET status = 'RETIRED', update_time = UTC_TIMESTAMP(6) "
                                            + "WHERE datasource_id = ? AND status = 'PUBLISHED' AND id <> ?")) {
                        retire.setLong(1, datasourceId);
                        retire.setLong(2, snapshotId);
                        retire.executeUpdate();
                    }
                    try (PreparedStatement switchActive =
                            statement(
                                    connection,
                                    "UPDATE datasource SET active_snapshot_id = ?, "
                                            + "resource_version = resource_version + 1, last_sync_at = UTC_TIMESTAMP(6), "
                                            + "update_time = UTC_TIMESTAMP(6) "
                                            + "WHERE id = ? AND resource_version = ?")) {
                        switchActive.setLong(1, snapshotId);
                        switchActive.setLong(2, datasourceId);
                        switchActive.setLong(3, currentResourceVersion);
                        if (switchActive.executeUpdate() != 1) {
                            throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
                        }
                    }
                    revokeRetiredGrants(connection, datasourceId);
                });
        return newResourceVersion;
    }

    private static void publishResources(Connection connection, long datasourceId, long snapshotId)
            throws SQLException {
        try (PreparedStatement validate =
                statement(
                        connection,
                        "SELECT COUNT(*) FROM snapshot_object so "
                                + "JOIN resource_object ro ON ro.id = so.object_id "
                                + "JOIN resource_namespace ns ON ns.id = ro.namespace_id "
                                + "WHERE so.snapshot_id = ? AND (ns.datasource_id <> ? "
                                + "OR ro.status NOT IN ('ACTIVE', 'STAGING') "
                                + "OR ns.status NOT IN ('ACTIVE', 'STAGING') OR so.definition_hash IS NULL)")) {
            validate.setLong(1, snapshotId);
            validate.setLong(2, datasourceId);
            try (ResultSet result = validate.executeQuery()) {
                if (!result.next() || result.getLong(1) != 0) {
                    throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
                }
            }
        }
        try (PreparedStatement retire =
                statement(
                        connection,
                        "UPDATE resource_object ro JOIN resource_namespace ns ON ns.id = ro.namespace_id "
                                + "LEFT JOIN snapshot_object so ON so.object_id = ro.id AND so.snapshot_id = ? "
                                + "SET ro.status = 'MISSING', ro.update_time = UTC_TIMESTAMP(6) "
                                + "WHERE ns.datasource_id = ? AND ro.status = 'ACTIVE' AND so.object_id IS NULL")) {
            retire.setLong(1, snapshotId);
            retire.setLong(2, datasourceId);
            retire.executeUpdate();
        }
        try (PreparedStatement retire =
                statement(
                        connection,
                        "UPDATE resource_namespace ns SET ns.status = 'MISSING', ns.update_time = UTC_TIMESTAMP(6) "
                                + "WHERE ns.datasource_id = ? AND ns.status = 'ACTIVE' AND NOT EXISTS "
                                + "(SELECT 1 FROM snapshot_object so JOIN resource_object ro ON ro.id = so.object_id "
                                + "WHERE so.snapshot_id = ? AND ro.namespace_id = ns.id)")) {
            retire.setLong(1, datasourceId);
            retire.setLong(2, snapshotId);
            retire.executeUpdate();
        }
        try (PreparedStatement activate =
                statement(
                        connection,
                        "UPDATE resource_namespace ns JOIN resource_object ro ON ro.namespace_id = ns.id "
                                + "JOIN snapshot_object so ON so.object_id = ro.id AND so.snapshot_id = ? "
                                + "SET ns.status = 'ACTIVE', ns.update_time = UTC_TIMESTAMP(6) WHERE ns.datasource_id = ?")) {
            activate.setLong(1, snapshotId);
            activate.setLong(2, datasourceId);
            activate.executeUpdate();
        }
        try (PreparedStatement activate =
                statement(
                        connection,
                        "UPDATE resource_object ro JOIN snapshot_object so ON so.object_id = ro.id "
                                + "JOIN schema_snapshot ss ON ss.id = so.snapshot_id AND ss.id = ? "
                                + "SET ro.status = 'ACTIVE', ro.definition_hash = so.definition_hash, "
                                + "ro.physical_identity = so.physical_identity, ro.last_seen_at = ss.captured_at, "
                                + "ro.update_time = UTC_TIMESTAMP(6)")) {
            activate.setLong(1, snapshotId);
            activate.executeUpdate();
        }
    }

    private static void revokeRetiredGrants(Connection connection, long datasourceId)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "UPDATE acl_grant g "
                                + "JOIN resource_object ro ON g.resource_type = 'OBJECT' "
                                + "AND g.resource_id = ro.id "
                                + "JOIN resource_namespace ns ON ns.id = ro.namespace_id "
                                + "SET g.status = 'REVOKED', g.reason = 'SCHEMA_RETIRED', "
                                + "g.version = g.version + 1 "
                                + "WHERE ns.datasource_id = ? AND ro.status = 'MISSING' "
                                + "AND g.status = 'ACTIVE'")) {
            statement.setLong(1, datasourceId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                statement(
                        connection,
                        "UPDATE acl_grant g "
                                + "JOIN resource_namespace ns ON g.resource_type = 'NAMESPACE' "
                                + "AND g.resource_id = ns.id "
                                + "SET g.status = 'REVOKED', g.reason = 'SCHEMA_RETIRED', "
                                + "g.version = g.version + 1 "
                                + "WHERE ns.datasource_id = ? AND ns.status = 'MISSING' "
                                + "AND g.status = 'ACTIVE'")) {
            statement.setLong(1, datasourceId);
            statement.executeUpdate();
        }
    }

    /** Marks a staging snapshot FAILED; used when staging or publishing is abandoned. */
    public void markSnapshotFailed(long snapshotId) {
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            try (PreparedStatement statement =
                    statement(
                            connection,
                            "UPDATE schema_snapshot SET status = 'FAILED', update_time = UTC_TIMESTAMP(6) "
                                    + "WHERE id = ? AND status = 'STAGING'")) {
                statement.setLong(1, snapshotId);
                statement.executeUpdate();
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** The published snapshot for new queries; throws SCHEMA_STALE when unusable. */
    public ActiveSnapshot requireUsableActiveSnapshot(long datasourceId) {
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            try (PreparedStatement statement =
                    statement(
                            connection,
                            "SELECT ss.id, ss.config_version, ds.config_version, ss.resource_version, "
                                    + "ss.captured_at, ss.expires_at, ss.object_count "
                                    + "FROM datasource ds "
                                    + "JOIN schema_snapshot ss ON ss.id = ds.active_snapshot_id "
                                    + "AND ss.datasource_id = ds.id "
                                    + "WHERE ds.id = ? AND ds.status = 'ACTIVE' "
                                    + "AND ss.status = 'PUBLISHED'")) {
                statement.setLong(1, datasourceId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new DomainException(ErrorCode.SCHEMA_STALE);
                    }
                    LocalDateTime expiresAt = result.getObject(6, LocalDateTime.class);
                    if (expiresAt == null
                            || !expiresAt.isAfter(nowUtc())
                            || result.getLong(2) != result.getLong(3)) {
                        throw new DomainException(ErrorCode.SCHEMA_STALE);
                    }
                    return new ActiveSnapshot(
                            result.getLong(1),
                            datasourceId,
                            result.getLong(4),
                            result.getObject(5, LocalDateTime.class),
                            expiresAt,
                            result.getInt(7));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public record ActiveSnapshot(
            long snapshotId,
            long datasourceId,
            long resourceVersion,
            LocalDateTime capturedAt,
            LocalDateTime expiresAt,
            int objectCount) {}

    private long datasourceOf(long snapshotId) {
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            try (PreparedStatement statement =
                    statement(
                            connection, "SELECT datasource_id FROM schema_snapshot WHERE id = ?")) {
                statement.setLong(1, snapshotId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                    }
                    return result.getLong(1);
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private long currentResourceVersion(long datasourceId) {
        try (Connection connection = primaryMetadata.getConnection()) {
            configure(connection);
            try (PreparedStatement statement =
                    statement(connection, "SELECT resource_version FROM datasource WHERE id = ?")) {
                statement.setLong(1, datasourceId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                    }
                    return result.getLong(1);
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static String sha256Hex(String text) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static long lastId(Connection connection) throws SQLException {
        try (PreparedStatement statement = statement(connection, "SELECT LAST_INSERT_ID()");
                ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return result.getLong(1);
        }
    }

    private static void configure(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        connection.setReadOnly(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        connection.setNetworkTimeout(Runnable::run, 20_000);
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
                // Preserve the original error.
            }
            throw error;
        }
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
}
