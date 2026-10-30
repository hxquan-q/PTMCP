package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.connection.ConnectionProfile;
import local.ptmcp.persistence.SecurityChangeTransaction;
import local.ptmcp.persistence.SecurityEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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
    private final SecurityChangeTransaction changes;

    public DatasourceStore(DataSource primary) {
        this.primary = primary;
        this.changes = new SecurityChangeTransaction(primary);
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
            LocalDateTime updatedAt,
            String health) {}

    public record CandidateRow(
            long id,
            long datasourceId,
            long baseConfigVersion,
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
            String probeScope,
            String state,
            LocalDateTime checkedAt,
            LocalDateTime validUntil,
            String errorCode,
            long requestedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            boolean live) {}

    public record Page(List<Row> items, Long nextCursor) {}

    public record DeleteResult(boolean deleted, long id, long version) {}

    public record RecordedTest(long candidateId, long jobId) {}

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
                        "SELECT d.id, d.code, d.name, d.engine, d.host, d.port, d.database_name, "
                                + "d.username, d.credential_cipher, d.key_id, d.tls_mode, d.pool_max, "
                                + "d.status, d.config_version, d.resource_version, d.active_snapshot_id, "
                                + "d.last_sync_at, d.create_time, d.update_time, "
                                + HEALTH
                                + " FROM datasource d WHERE d.status <> 'DELETED'");
        List<Object> parameters = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            if (keyword.length() > 128) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            sql.append(" AND (d.code LIKE ? OR d.name LIKE ?)");
            String pattern =
                    "%"
                            + keyword.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                            + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (engine != null && !engine.isBlank()) {
            sql.append(" AND d.engine = ?");
            parameters.add(engine);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND d.status = ?");
            parameters.add(status);
        }
        if (afterId != null) {
            sql.append(" AND d.id < ?");
            parameters.add(afterId);
        }
        sql.append(" ORDER BY d.id DESC LIMIT ?");
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
                                connection,
                                SELECT_WITH_HEALTH + " WHERE d.id = ? AND d.status <> 'DELETED'")) {
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
        return update(id, expectedVersion, fields, sealed, keyId, actorId, null);
    }

    public Row update(
            long id,
            long expectedVersion,
            Fields fields,
            byte[] sealed,
            String keyId,
            long actorId,
            String requestId) {
        validate(fields);
        AdminSql.requireActor(actorId);
        if (expectedVersion < 1
                || expectedVersion == Long.MAX_VALUE
                || (sealed != null && (sealed.length == 0 || keyId == null))) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        LocalDateTime now = AdminSql.nowUtc();
        UUID commandId;
        try {
            commandId = requestId == null ? UUID.randomUUID() : UUID.fromString(requestId);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        SecurityEvent event =
                new SecurityEvent(
                        commandId,
                        SecurityEvent.Kind.ACL_CHANGED,
                        new SecurityEvent.NumericId(id),
                        expectedVersion + 1);
        AtomicReference<Row> updated = new AtomicReference<>();
        changes.execute(
                event,
                connection -> {
                    Row current = getForUpdate(connection, id);
                    if (!current.code().equals(fields.code())
                            || !current.engine().equals(fields.engine())) {
                        throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                    }
                    if (current.configVersion() != expectedVersion) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                    boolean targetChanged =
                            !current.host().equalsIgnoreCase(fields.host().trim())
                                    || current.port() != fields.port()
                                    || !current.databaseName().equals(fields.databaseName());
                    long nextConfig = expectedVersion + 1;
                    long nextResource =
                            current.resourceVersion()
                                    + (targetChanged || "DISABLED".equals(fields.status()) ? 1 : 0);
                    try (PreparedStatement statement =
                            AdminSql.statement(
                                    connection,
                                    "UPDATE datasource SET name = ?, host = ?, port = ?, database_name = ?, "
                                            + "username = ?, tls_mode = ?, pool_max = ?, status = ?, config_version = ?, resource_version = ?, "
                                            + "credential_cipher = COALESCE(?, credential_cipher), key_id = COALESCE(?, key_id), "
                                            + "active_snapshot_id = CASE WHEN ? THEN NULL ELSE active_snapshot_id END, "
                                            + "last_sync_at = CASE WHEN ? THEN NULL ELSE last_sync_at END, update_time = ?, updated_by = ? "
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
                        statement.setBytes(11, sealed);
                        statement.setString(12, sealed == null ? null : keyId);
                        statement.setBoolean(13, targetChanged);
                        statement.setBoolean(14, targetChanged);
                        statement.setObject(15, now);
                        statement.setLong(16, actorId);
                        statement.setLong(17, id);
                        statement.setLong(18, expectedVersion);
                        if (statement.executeUpdate() != 1)
                            throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                    if (targetChanged) DatasourceTargetIsolation.retire(connection, id, actorId);
                    logUpdate(
                            connection,
                            commandId,
                            actorId,
                            current,
                            nextConfig,
                            nextResource,
                            targetChanged,
                            fields.status());
                    updated.set(
                            new Row(
                                    id,
                                    current.code(),
                                    fields.name(),
                                    current.engine(),
                                    fields.host().trim().toLowerCase(Locale.ROOT),
                                    fields.port(),
                                    fields.databaseName(),
                                    fields.username(),
                                    sealed == null ? current.credentialCipher() : sealed,
                                    sealed == null ? current.keyId() : keyId,
                                    fields.tlsMode(),
                                    fields.poolMax(),
                                    fields.status(),
                                    nextConfig,
                                    nextResource,
                                    targetChanged ? null : current.activeSnapshotId(),
                                    targetChanged ? null : current.lastSyncAt(),
                                    current.createdAt(),
                                    now,
                                    "UNKNOWN"));
                });
        // The receipt confirms commit. Returning the captured value avoids an extra metadata read
        // failing after commit and misleading a caller into replaying an already-applied command.
        return updated.get();
    }

    /**
     * Records a probe result and its TEST job in one transaction. Draft credentials are sealed in
     * the candidate row only; the datasource row is never touched by a probe.
     */
    public RecordedTest recordTest(
            DatasourceAdminService.DraftTarget target,
            boolean succeeded,
            long actorId,
            String requestId) {
        AdminSql.requireActor(actorId);
        if (target == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Fields fields = target.fields();
        validate(fields);
        byte[] sealed = target.sealed();
        String keyId = target.keyId();
        if (succeeded && (sealed == null || keyId == null)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        UUID commandId;
        try {
            commandId = requestId == null ? UUID.randomUUID() : UUID.fromString(requestId);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = AdminSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                long candidateId;
                try (PreparedStatement statement =
                        AdminSql.generatedKeys(
                                connection,
                                "INSERT INTO datasource_candidate (datasource_id, base_config_version, "
                                        + "code, name, engine, host, port, database_name, username, "
                                        + "credential_cipher, key_id, tls_mode, pool_max, "
                                        + "datasource_status, probe_scope, state, checked_at, valid_until, "
                                        + "error_code, requested_by, create_time, update_time) "
                                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                                        + "UTC_TIMESTAMP(6), DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE), "
                                        + "?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
                    statement.setLong(1, target.datasourceId());
                    statement.setLong(2, target.configVersion());
                    statement.setString(3, fields.code());
                    statement.setString(4, fields.name());
                    statement.setString(5, fields.engine());
                    statement.setString(6, fields.host());
                    statement.setInt(7, fields.port());
                    statement.setString(8, fields.databaseName());
                    statement.setString(9, fields.username());
                    statement.setBytes(10, succeeded ? sealed : null);
                    statement.setString(11, succeeded ? keyId : null);
                    statement.setString(12, fields.tlsMode());
                    statement.setInt(13, fields.poolMax());
                    statement.setString(14, fields.status());
                    statement.setString(15, target.draft() ? "DRAFT_CANDIDATE" : "ACTIVE_PROBE");
                    statement.setString(16, succeeded ? "VERIFIED" : "FAILED");
                    statement.setString(17, succeeded ? null : ErrorCode.DRIVER_ERROR.name());
                    statement.setLong(18, actorId);
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.INTERNAL_ERROR);
                    }
                    candidateId = AdminSql.lastId(connection, statement);
                }
                long jobId;
                try (PreparedStatement statement =
                        AdminSql.generatedKeys(
                                connection,
                                "INSERT INTO metadata_sync_job (datasource_id, job_type, requested_by, "
                                        + "config_version, candidate_id, state, started_at, finished_at, "
                                        + "error_code, create_time, update_time) "
                                        + "VALUES (?, 'TEST', ?, ?, ?, ?, UTC_TIMESTAMP(6), "
                                        + "UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
                    statement.setLong(1, target.datasourceId());
                    statement.setLong(2, actorId);
                    statement.setLong(3, target.configVersion());
                    statement.setLong(4, candidateId);
                    statement.setString(5, succeeded ? "SUCCEEDED" : "FAILED");
                    statement.setString(6, succeeded ? null : ErrorCode.DRIVER_ERROR.name());
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.INTERNAL_ERROR);
                    }
                    jobId = AdminSql.lastId(connection, statement);
                }
                logCandidateTest(connection, commandId, actorId, target, candidateId, succeeded);
                connection.commit();
                return new RecordedTest(candidateId, jobId);
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
    }

    /**
     * Atomically promotes a verified draft candidate. The datasource row is locked first, then the
     * candidate; a stale, expired or already-published candidate cannot overwrite newer config.
     */
    public Row publishCandidate(
            long id, long candidateId, long expectedVersion, long actorId, String requestId) {
        AdminSql.requireActor(actorId);
        if (id <= 0 || candidateId <= 0 || expectedVersion < 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        UUID commandId;
        try {
            commandId = requestId == null ? UUID.randomUUID() : UUID.fromString(requestId);
        } catch (IllegalArgumentException invalid) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        SecurityEvent event =
                new SecurityEvent(
                        commandId,
                        SecurityEvent.Kind.ACL_CHANGED,
                        new SecurityEvent.NumericId(id),
                        expectedVersion + 1);
        AtomicReference<Row> published = new AtomicReference<>();
        changes.execute(
                event,
                connection -> {
                    Row current = getForUpdate(connection, id);
                    if (current.configVersion() != expectedVersion) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                    CandidateRow candidate = candidateForUpdate(connection, id, candidateId);
                    if (!"DRAFT_CANDIDATE".equals(candidate.probeScope())
                            || !"VERIFIED".equals(candidate.state())
                            || !candidate.live()
                            || candidate.credentialCipher() == null) {
                        throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                    }
                    if (candidate.baseConfigVersion() != expectedVersion) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                    Fields fields =
                            new Fields(
                                    candidate.code(),
                                    candidate.name(),
                                    candidate.engine(),
                                    candidate.host(),
                                    candidate.port(),
                                    candidate.databaseName(),
                                    candidate.username(),
                                    candidate.tlsMode(),
                                    candidate.poolMax(),
                                    candidate.status());
                    validate(fields);
                    if (!current.code().equals(candidate.code())
                            || !current.engine().equals(candidate.engine())
                            || !current.status().equals(candidate.status())) {
                        throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                    }
                    boolean targetChanged =
                            !current.host().equalsIgnoreCase(candidate.host())
                                    || current.port() != candidate.port()
                                    || !current.databaseName().equals(candidate.databaseName());
                    long nextConfig = expectedVersion + 1;
                    long nextResource =
                            current.resourceVersion()
                                    + (targetChanged || "DISABLED".equals(candidate.status())
                                            ? 1
                                            : 0);
                    try (PreparedStatement statement =
                            AdminSql.statement(
                                    connection,
                                    "UPDATE datasource SET name = ?, host = ?, port = ?, "
                                            + "database_name = ?, username = ?, tls_mode = ?, pool_max = ?, "
                                            + "credential_cipher = ?, key_id = ?, config_version = ?, "
                                            + "resource_version = ?, "
                                            + "active_snapshot_id = CASE WHEN ? THEN NULL ELSE active_snapshot_id END, "
                                            + "last_sync_at = CASE WHEN ? THEN NULL ELSE last_sync_at END, "
                                            + "update_time = UTC_TIMESTAMP(6), updated_by = ? "
                                            + "WHERE id = ? AND config_version = ? AND status <> 'DELETED'")) {
                        statement.setString(1, candidate.name());
                        statement.setString(2, candidate.host());
                        statement.setInt(3, candidate.port());
                        statement.setString(4, candidate.databaseName());
                        statement.setString(5, candidate.username());
                        statement.setString(6, candidate.tlsMode());
                        statement.setInt(7, candidate.poolMax());
                        statement.setBytes(8, candidate.credentialCipher());
                        statement.setString(9, candidate.keyId());
                        statement.setLong(10, nextConfig);
                        statement.setLong(11, nextResource);
                        statement.setBoolean(12, targetChanged);
                        statement.setBoolean(13, targetChanged);
                        statement.setLong(14, actorId);
                        statement.setLong(15, id);
                        statement.setLong(16, expectedVersion);
                        if (statement.executeUpdate() != 1) {
                            throw new DomainException(ErrorCode.VERSION_CONFLICT);
                        }
                    }
                    if (targetChanged) {
                        DatasourceTargetIsolation.retire(connection, id, actorId);
                    }
                    try (PreparedStatement statement =
                            AdminSql.statement(
                                    connection,
                                    "UPDATE datasource_candidate SET state = 'PUBLISHED', "
                                            + "update_time = UTC_TIMESTAMP(6) WHERE id = ? "
                                            + "AND datasource_id = ? AND state = 'VERIFIED'")) {
                        statement.setLong(1, candidateId);
                        statement.setLong(2, id);
                        if (statement.executeUpdate() != 1) {
                            throw new DomainException(ErrorCode.VERSION_CONFLICT);
                        }
                    }
                    logPublish(
                            connection,
                            commandId,
                            actorId,
                            current,
                            candidateId,
                            nextConfig,
                            nextResource,
                            targetChanged);
                    published.set(
                            new Row(
                                    id,
                                    candidate.code(),
                                    candidate.name(),
                                    candidate.engine(),
                                    candidate.host(),
                                    candidate.port(),
                                    candidate.databaseName(),
                                    candidate.username(),
                                    candidate.credentialCipher(),
                                    candidate.keyId(),
                                    candidate.tlsMode(),
                                    candidate.poolMax(),
                                    candidate.status(),
                                    nextConfig,
                                    nextResource,
                                    targetChanged ? null : current.activeSnapshotId(),
                                    targetChanged ? null : current.lastSyncAt(),
                                    current.createdAt(),
                                    AdminSql.nowUtc(),
                                    "UNKNOWN"));
                });
        return published.get();
    }

    private static void logCandidateTest(
            Connection connection,
            UUID commandId,
            long actorId,
            DatasourceAdminService.DraftTarget target,
            long candidateId,
            boolean succeeded)
            throws SQLException {
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "INSERT INTO admin_operation_log (actor_id,request_id,action,aggregate_type,"
                                + "aggregate_id,before_masked,after_masked,occurred_at,create_time) "
                                + "VALUES (?,?,'DATASOURCE_CANDIDATE_TEST','DATASOURCE',?,NULL,"
                                + "JSON_OBJECT('candidateId',?,'baseConfigVersion',?,"
                                + "'probeScope',?,'state',?),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
            statement.setLong(1, actorId);
            statement.setString(2, commandId.toString());
            statement.setString(3, Long.toString(target.datasourceId()));
            statement.setLong(4, candidateId);
            statement.setLong(5, target.configVersion());
            statement.setString(6, target.draft() ? "DRAFT_CANDIDATE" : "ACTIVE_PROBE");
            statement.setString(7, succeeded ? "VERIFIED" : "FAILED");
            statement.executeUpdate();
        }
    }

    private static void logPublish(
            Connection connection,
            UUID commandId,
            long actorId,
            Row before,
            long candidateId,
            long nextConfig,
            long nextResource,
            boolean targetChanged)
            throws SQLException {
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "INSERT INTO admin_operation_log (actor_id,request_id,action,aggregate_type,"
                                + "aggregate_id,before_masked,after_masked,occurred_at,create_time) "
                                + "VALUES (?,?,'DATASOURCE_CANDIDATE_PUBLISH','DATASOURCE',?,"
                                + "JSON_OBJECT('configVersion',?,'resourceVersion',?),"
                                + "JSON_OBJECT('candidateId',?,'configVersion',?,"
                                + "'resourceVersion',?,'targetChanged',?),"
                                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
            statement.setLong(1, actorId);
            statement.setString(2, commandId.toString());
            statement.setString(3, Long.toString(before.id()));
            statement.setLong(4, before.configVersion());
            statement.setLong(5, before.resourceVersion());
            statement.setLong(6, candidateId);
            statement.setLong(7, nextConfig);
            statement.setLong(8, nextResource);
            statement.setBoolean(9, targetChanged);
            statement.executeUpdate();
        }
    }

    private static void logUpdate(
            Connection connection,
            UUID commandId,
            long actorId,
            Row before,
            long nextConfig,
            long nextResource,
            boolean targetChanged,
            String status)
            throws SQLException {
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "INSERT INTO admin_operation_log (actor_id,request_id,action,aggregate_type,aggregate_id,before_masked,after_masked,occurred_at,create_time) "
                                + "VALUES (?,?,'DATASOURCE_UPDATE','DATASOURCE',?,"
                                + "JSON_OBJECT('configVersion',?,'resourceVersion',?,'status',?),"
                                + "JSON_OBJECT('configVersion',?,'resourceVersion',?,'status',?,'targetChanged',?),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
            statement.setLong(1, actorId);
            statement.setString(2, commandId.toString());
            statement.setString(3, Long.toString(before.id()));
            statement.setLong(4, before.configVersion());
            statement.setLong(5, before.resourceVersion());
            statement.setString(6, before.status());
            statement.setLong(7, nextConfig);
            statement.setLong(8, nextResource);
            statement.setString(9, status);
            statement.setBoolean(10, targetChanged);
            statement.executeUpdate();
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

    private static final String COLUMNS =
            "d.id, d.code, d.name, d.engine, d.host, d.port, d.database_name, d.username, "
                    + "d.credential_cipher, d.key_id, d.tls_mode, d.pool_max, d.status, "
                    + "d.config_version, d.resource_version, d.active_snapshot_id, d.last_sync_at, "
                    + "d.create_time, d.update_time";

    /**
     * Health is evidence from the latest unexpired active probe for the live version and target.
     * Draft probes never count, so a broken candidate cannot make the active source look healthy.
     */
    private static final String HEALTH =
            "COALESCE((SELECT CASE c.state WHEN 'VERIFIED' THEN 'HEALTHY' "
                    + "WHEN 'FAILED' THEN 'UNHEALTHY' ELSE 'UNKNOWN' END "
                    + "FROM datasource_candidate c WHERE c.datasource_id = d.id "
                    + "AND c.probe_scope = 'ACTIVE_PROBE' "
                    + "AND c.base_config_version = d.config_version "
                    + "AND d.status = 'ACTIVE' AND c.valid_until > UTC_TIMESTAMP(6) "
                    + "AND c.host = d.host AND c.port = d.port "
                    + "AND c.database_name = d.database_name AND c.username = d.username "
                    + "ORDER BY c.checked_at DESC, c.id DESC LIMIT 1), 'UNKNOWN')";

    private static final String SELECT_WITH_HEALTH =
            "SELECT " + COLUMNS + ", " + HEALTH + " FROM datasource d";

    // FOR UPDATE cannot safely combine the health subquery with an outer lock, so mutation reads
    // use a constant. The caller re-reads the row after commit when it needs derived health.
    private static final String SELECT_ONE = "SELECT " + COLUMNS + ", 'UNKNOWN' FROM datasource d";

    private Row getForUpdate(Connection connection, long id) throws SQLException {
        return getForUpdate(connection, id, false);
    }

    private Row getForUpdate(Connection connection, long id, boolean includeDeleted)
            throws SQLException {
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        SELECT_ONE
                                + " WHERE d.id = ?"
                                + (includeDeleted ? "" : " AND d.status <> 'DELETED'")
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

    private static CandidateRow candidateForUpdate(
            Connection connection, long datasourceId, long candidateId) throws SQLException {
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "SELECT id, datasource_id, base_config_version, code, name, engine, host, "
                                + "port, database_name, username, credential_cipher, key_id, tls_mode, "
                                + "pool_max, datasource_status, probe_scope, state, checked_at, "
                                + "valid_until, error_code, requested_by, create_time, update_time, "
                                + "valid_until > UTC_TIMESTAMP(6) AS live "
                                + "FROM datasource_candidate WHERE id = ? AND datasource_id = ? "
                                + "FOR UPDATE")) {
            statement.setLong(1, candidateId);
            statement.setLong(2, datasourceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return new CandidateRow(
                        result.getLong(1),
                        result.getLong(2),
                        result.getLong(3),
                        result.getString(4),
                        result.getString(5),
                        result.getString(6),
                        result.getString(7),
                        result.getInt(8),
                        result.getString(9),
                        result.getString(10),
                        result.getBytes(11),
                        result.getString(12),
                        result.getString(13),
                        result.getInt(14),
                        result.getString(15),
                        result.getString(16),
                        result.getString(17),
                        result.getObject(18, LocalDateTime.class),
                        result.getObject(19, LocalDateTime.class),
                        result.getString(20),
                        result.getLong(21),
                        result.getObject(22, LocalDateTime.class),
                        result.getObject(23, LocalDateTime.class),
                        result.getBoolean(24));
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
                result.getObject(19, LocalDateTime.class),
                result.getString(20));
    }
}
