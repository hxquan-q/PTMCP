package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

import javax.sql.DataSource;

/**
 * Admin-side job view over {@code metadata_sync_job}. Connectivity probes are recorded as already
 * finished TEST rows: temporary draft credentials are never persisted or queued.
 */
public final class DatasourceJobStore {
    private final DataSource primary;

    public DatasourceJobStore(DataSource primary) {
        this.primary = primary;
    }

    public record Job(
            long id,
            String type,
            long datasourceId,
            String state,
            long configVersion,
            LocalDateTime startedAt,
            LocalDateTime finishedAt,
            String errorCode,
            Long snapshotId,
            int processed) {}

    public Job enqueueSync(long datasourceId, long actorId, long configVersion) {
        AdminSql.requireActor(actorId);
        if (datasourceId <= 0 || configVersion <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = AdminSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                String status = null;
                long currentVersion = 0;
                try (PreparedStatement statement =
                        AdminSql.statement(
                                connection,
                                "SELECT status, config_version FROM datasource "
                                        + "WHERE id = ? AND status <> 'DELETED' FOR UPDATE")) {
                    statement.setLong(1, datasourceId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                        }
                        status = result.getString(1);
                        currentVersion = result.getLong(2);
                    }
                }
                if (!"ACTIVE".equals(status)) {
                    throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                }
                if (currentVersion != configVersion) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
                try (PreparedStatement statement =
                        AdminSql.statement(
                                connection,
                                "SELECT 1 FROM metadata_sync_job WHERE datasource_id = ? "
                                        + "AND state IN ('QUEUED', 'RUNNING') LIMIT 1")) {
                    statement.setLong(1, datasourceId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            throw new DomainException(ErrorCode.SYNC_IN_PROGRESS);
                        }
                    }
                }
                long jobId;
                try (PreparedStatement statement =
                        AdminSql.generatedKeys(
                                connection,
                                "INSERT INTO metadata_sync_job (datasource_id, job_type, requested_by, "
                                        + "config_version, state, create_time, update_time) "
                                        + "VALUES (?, 'SYNC', ?, ?, 'QUEUED', ?, ?)")) {
                    statement.setLong(1, datasourceId);
                    statement.setLong(2, actorId);
                    statement.setLong(3, configVersion);
                    statement.setObject(4, AdminSql.nowUtc());
                    statement.setObject(5, AdminSql.nowUtc());
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.INTERNAL_ERROR);
                    }
                    jobId = AdminSql.lastId(connection, statement);
                }
                connection.commit();
                return get(jobId);
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

    public Job recordTest(long datasourceId, long actorId, long configVersion, boolean succeeded) {
        AdminSql.requireActor(actorId);
        if (datasourceId <= 0 || configVersion <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        LocalDateTime now = AdminSql.nowUtc();
        try (Connection connection = AdminSql.configure(primary);
                PreparedStatement statement =
                        AdminSql.generatedKeys(
                                connection,
                                "INSERT INTO metadata_sync_job (datasource_id, job_type, requested_by, "
                                        + "config_version, state, started_at, finished_at, error_code, "
                                        + "create_time, update_time) "
                                        + "VALUES (?, 'TEST', ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, datasourceId);
            statement.setLong(2, actorId);
            statement.setLong(3, configVersion);
            statement.setString(4, succeeded ? "SUCCEEDED" : "FAILED");
            statement.setObject(5, now);
            statement.setObject(6, now);
            if (succeeded) {
                statement.setNull(7, java.sql.Types.VARCHAR);
            } else {
                statement.setString(7, ErrorCode.DRIVER_ERROR.name());
            }
            statement.setObject(8, now);
            statement.setObject(9, now);
            if (statement.executeUpdate() != 1) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return get(AdminSql.lastId(connection, statement));
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public Job get(long jobId) {
        if (jobId <= 0) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        try (Connection connection = AdminSql.configure(primary);
                PreparedStatement statement =
                        AdminSql.statement(
                                connection,
                                "SELECT j.id, j.job_type, j.datasource_id, j.state, j.config_version, "
                                        + "j.started_at, j.finished_at, j.error_code, j.snapshot_id, "
                                        + "CASE WHEN j.state = 'SUCCEEDED' AND j.job_type = 'TEST' THEN 1 "
                                        + "WHEN j.state = 'SUCCEEDED' THEN COALESCE(ss.object_count, 0) "
                                        + "ELSE 0 END AS processed "
                                        + "FROM metadata_sync_job j JOIN datasource d "
                                        + "ON d.id = j.datasource_id "
                                        + "LEFT JOIN schema_snapshot ss ON ss.id = j.snapshot_id "
                                        + "WHERE j.id = ? AND d.status <> 'DELETED'")) {
            statement.setLong(1, jobId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                long snapshot = result.getLong(9);
                Long snapshotId = result.wasNull() ? null : snapshot;
                return new Job(
                        result.getLong(1),
                        result.getString(2),
                        result.getLong(3),
                        result.getString(4),
                        result.getLong(5),
                        result.getObject(6, LocalDateTime.class),
                        result.getObject(7, LocalDateTime.class),
                        result.getString(8),
                        snapshotId,
                        result.getInt(10));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
