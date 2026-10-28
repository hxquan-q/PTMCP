package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.connection.ConnectionProfile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.sql.DataSource;

/** Persistence for the {@code datasource} registry and its references. */
public final class DatasourceStore {
    private static final Set<String> ENGINES = Set.of("MYSQL", "POSTGRESQL");
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "DISABLED");
    private static final int MAX_CODE = 64;
    private static final int MAX_NAME = 128;
    private static final int MAX_DATABASE = 128;
    private static final int MAX_USERNAME = 128;

    private final DataSource primary;

    public DatasourceStore(DataSource primary) {
        this.primary = primary;
    }

    public record Fields(
            String code,
            String name,
            String engine,
            String host,
            int port,
            String databaseName,
            String username,
            String tlsMode,
            int poolMax,
            String status) {}

    public record Row(
            long id,
            String code,
            String name,
            String engine,
            String host,
            int port,
            String databaseName,
            String username,
            byte[] credentialCipher,
            String keyId,
            String tlsMode,
            int poolMax,
            String status,
            long configVersion,
            long resourceVersion,
            Long activeSnapshotId,
            LocalDateTime lastSyncAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {}

    public record Page(List<Row> items, Long nextCursor) {}

    public record DeleteResult(boolean deleted, long id, long version) {}

    public static void validate(Fields fields) {
        if (fields == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        AdminSql.requireText(fields.code(), MAX_CODE);
        AdminSql.requireText(fields.name(), MAX_NAME);
        if (fields.code().length() < 1
                || fields.name().length() < 1
                || fields.host() == null
                || fields.port() < 1
                || fields.port() > 65535
                || fields.databaseName() == null
                || !fields.databaseName().matches("[A-Za-z0-9_$.-]{1,128}")
                || fields.databaseName().length() > MAX_DATABASE
                || fields.username() == null
                || fields.username().isBlank()
                || fields.username().length() > MAX_USERNAME
                || !ENGINES.contains(fields.engine())
                || !"VERIFY_FULL".equals(fields.tlsMode())
                || fields.poolMax() < 1
                || fields.poolMax() > 8
                || !STATUSES.contains(fields.status())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        new ConnectionProfile(
                DatabaseEngine.valueOf(fields.engine()),
                fields.host(),
                fields.port(),
                fields.databaseName(),
                fields.username());
    }

    public Page list(String keyword, String engine, String status, Long afterId, int pageSize) {
        if (pageSize < 1 || pageSize > 100) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (engine != null && !engine.isBlank() && !ENGINES.contains(engine)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (status != null && !status.isBlank() && !STATUSES.contains(status)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (afterId != null && afterId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, code, name, engine, host, port, database_name, username, "
                                + "credential_cipher, key_id, tls_mode, pool_max, status, "
                                + "config_version, resource_version, active_snapshot_id, last_sync_at, "
                                + "create_time, update_time FROM datasource "
                                + "WHERE status <> 'DELETED'");
        List<Object> parameters = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            if (keyword.length() > 128) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            sql.append(" AND (code LIKE ? OR name LIKE ?)");
            String pattern =
                    "%"
                            + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                            + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (engine != null && !engine.isBlank()) {
            sql.append(" AND engine = ?");
            parameters.add(engine);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ?");
            parameters.add(status);
        }
        if (afterId != null) {
            sql.append(" AND id < ?");
            parameters.add(afterId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        parameters.add(pageSize + 1);
        try (Connection connection = AdminSql.configure(primary);
                PreparedStatement statement = AdminSql.statement(connection, sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            List<Row> rows = new ArrayList<>();
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(map(result));
                }
            }
            if (rows.size() <= pageSize) {
                return new Page(rows, null);
            }
            List<Row> page = rows.subList(0, pageSize);
            return new Page(List.copyOf(page), page.getLast().id());
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public Row get(long id) {
        if (id <= 0) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        try (Connection connection = AdminSql.configure(primary);
                PreparedStatement statement =
                        AdminSql.statement(
                                connection, SELECT_ONE + " WHERE id = ? AND status <> 'DELETED'")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return map(result);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public Row create(Fields fields, byte[] sealed, String keyId, long actorId) {
        validate(fields);
        AdminSql.requireActor(actorId);
        if (sealed == null || sealed.length == 0 || keyId == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        LocalDateTime now = AdminSql.nowUtc();
        try (Connection connection = AdminSql.configure(primary);
                PreparedStatement statement =
                        AdminSql.generatedKeys(
                                connection,
                                "INSERT INTO datasource (code, name, engine, host, port, database_name, "
                                        + "username, credential_cipher, key_id, tls_mode, pool_max, status, "
                                        + "config_version, resource_version, create_time, update_time, "
                                        + "created_by, updated_by) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, 1, ?, ?, ?, ?)")) {
            bindFields(statement, fields);
            statement.setBytes(8, sealed);
            statement.setString(9, keyId);
            statement.setObject(13, now);
            statement.setObject(14, now);
            statement.setLong(15, actorId);
            statement.setLong(16, actorId);
            if (statement.executeUpdate() != 1) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return get(AdminSql.lastId(connection, statement));
        } catch (SQLException error) {
            if ("23000".equals(error.getSQLState())) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public Row update(
            long id,
            long expectedVersion,
            Fields fields,
            byte[] sealed,
            String keyId,
            long actorId) {
        validate(fields);
        AdminSql.requireActor(actorId);
        if (expectedVersion < 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        LocalDateTime now = AdminSql.nowUtc();
        try (Connection connection = AdminSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                Row current = getForUpdate(connection, id);
                if (!current.code().equals(fields.code())
                        || !current.engine().equals(fields.engine())) {
                    throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                }
                if (current.configVersion() != expectedVersion) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                long nextConfig = current.configVersion() + 1;
                long nextResource =
                        current.resourceVersion() + ("DISABLED".equals(fields.status()) ? 1 : 0);
                try (PreparedStatement statement =
                        AdminSql.statement(
                                connection,
                                "UPDATE datasource SET name = ?, host = ?, port = ?, database_name = ?, "
                                        + "username = ?, tls_mode = ?, pool_max = ?, status = ?, "
                                        + "config_version = ?, resource_version = ?, "
                                        + "credential_cipher = COALESCE(?, credential_cipher), "
                                        + "key_id = COALESCE(?, key_id), update_time = ?, updated_by = ? "
                                        + "WHERE id = ? AND config_version = ? AND status <> 'DELETED'")) {
                    statement.setString(1, fields.name());
                    statement.setString(2, fields.host().trim().toLowerCase(Locale.ROOT));
                    statement.setInt(3, fields.port());
                    statement.setString(4, fields.databaseName());
                    statement.setString(5, fields.username());
                    statement.setString(6, fields.tlsMode());
                    statement.setInt(7, fields.poolMax());
                    statement.setString(8, fields.status());
                    statement.setLong(9, nextConfig);
                    statement.setLong(10, nextResource);
                    if (sealed == null) {
                        statement.setNull(11, java.sql.Types.BLOB);
                        statement.setNull(12, java.sql.Types.VARCHAR);
                    } else {
                        statement.setBytes(11, sealed);
                        statement.setString(12, keyId);
                    }
                    statement.setObject(13, now);
                    statement.setLong(14, actorId);
                    statement.setLong(15, id);
                    statement.setLong(16, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                }
                connection.commit();
                return get(id);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Preserve the original failure.
                }
                if (failure instanceof SQLException sql) {
                    throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public DeleteResult delete(long id, long expectedVersion, long actorId) {
        AdminSql.requireActor(actorId);
        if (expectedVersion < 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = AdminSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                Row current = getForUpdate(connection, id, true);
                if ("DELETED".equals(current.status())) {
                    connection.rollback();
                    return new DeleteResult(true, id, current.configVersion());
                }
                if (current.configVersion() != expectedVersion) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                if (hasActiveGrants(connection, id)) {
                    throw new DomainException(ErrorCode.REFERENCES_EXIST);
                }
                try (PreparedStatement statement =
                        AdminSql.statement(
                                connection,
                                "UPDATE datasource SET status = 'DELETED', config_version = config_version + 1, "
                                        + "resource_version = resource_version + 1, update_time = ?, "
                                        + "updated_by = ? WHERE id = ? AND config_version = ?")) {
                    statement.setObject(1, AdminSql.nowUtc());
                    statement.setLong(2, actorId);
                    statement.setLong(3, id);
                    statement.setLong(4, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                }
                connection.commit();
                return new DeleteResult(true, id, expectedVersion + 1);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Preserve the original failure.
                }
                if (failure instanceof SQLException sql) {
                    throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public boolean hasActiveGrants(long datasourceId) {
        try (Connection connection = AdminSql.configure(primary)) {
            return hasActiveGrants(connection, datasourceId);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public DatasourceConnectionFactory.Spec connectionSpec(Row row) {
        return new DatasourceConnectionFactory.Spec(
                row.engine(),
                row.host(),
                row.port(),
                row.databaseName(),
                row.username(),
                row.credentialCipher(),
                row.keyId());
    }

    private static final String SELECT_ONE =
            "SELECT id, code, name, engine, host, port, database_name, username, "
                    + "credential_cipher, key_id, tls_mode, pool_max, status, config_version, "
                    + "resource_version, active_snapshot_id, last_sync_at, create_time, update_time "
                    + "FROM datasource";

    private Row getForUpdate(Connection connection, long id) throws SQLException {
        return getForUpdate(connection, id, false);
    }

    private Row getForUpdate(Connection connection, long id, boolean includeDeleted)
            throws SQLException {
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        SELECT_ONE
                                + " WHERE id = ?"
                                + (includeDeleted ? "" : " AND status <> 'DELETED'")
                                + " FOR UPDATE")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return map(result);
            }
        }
    }

    private static boolean hasActiveGrants(Connection connection, long datasourceId)
            throws SQLException {
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "SELECT 1 FROM acl_grant g WHERE g.status = 'ACTIVE' AND ("
                                + "(g.resource_type = 'DATASOURCE' AND g.resource_id = ?) OR "
                                + "(g.resource_type = 'NAMESPACE' AND g.resource_id IN "
                                + "(SELECT id FROM resource_namespace WHERE datasource_id = ?)) OR "
                                + "(g.resource_type = 'OBJECT' AND g.resource_id IN "
                                + "(SELECT o.id FROM resource_object o JOIN resource_namespace n "
                                + "ON n.id = o.namespace_id WHERE n.datasource_id = ?))) LIMIT 1")) {
            statement.setLong(1, datasourceId);
            statement.setLong(2, datasourceId);
            statement.setLong(3, datasourceId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void bindFields(PreparedStatement statement, Fields fields) throws SQLException {
        statement.setString(1, fields.code());
        statement.setString(2, fields.name());
        statement.setString(3, fields.engine());
        statement.setString(4, fields.host().trim().toLowerCase(Locale.ROOT));
        statement.setInt(5, fields.port());
        statement.setString(6, fields.databaseName());
        statement.setString(7, fields.username());
        statement.setString(10, fields.tlsMode());
        statement.setInt(11, fields.poolMax());
        statement.setString(12, fields.status());
    }

    private static Row map(ResultSet result) throws SQLException {
        long snapshot = result.getLong(16);
        Long activeSnapshot = result.wasNull() ? null : snapshot;
        LocalDateTime lastSync = result.getObject(17, LocalDateTime.class);
        return new Row(
                result.getLong(1),
                result.getString(2),
                result.getString(3),
                result.getString(4),
                result.getString(5),
                result.getInt(6),
                result.getString(7),
                result.getString(8),
                result.getBytes(9),
                result.getString(10),
                result.getString(11),
                result.getInt(12),
                result.getString(13),
                result.getLong(14),
                result.getLong(15),
                activeSnapshot,
                lastSync,
                result.getObject(18, LocalDateTime.class),
                result.getObject(19, LocalDateTime.class));
    }
}
