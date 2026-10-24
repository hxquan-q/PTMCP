package local.ptmcp.persistence.schema;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Executes one schema sync job end to end: claim (or inherit after takeover), open the metadata
 * connection, collect, stage and publish the snapshot, then finalize the job. Any failure —
 * collection, staging, publish or a lost lease — never leaves a half-published snapshot behind.
 */
public final class MetadataSyncJobRunner {
    private final DataSource primaryMetadata;
    private final MetadataSyncJobStore jobStore;
    private final SchemaSnapshotStore snapshotStore;
    private final MetadataConnectionOpener opener;

    public MetadataSyncJobRunner(
            DataSource primaryMetadata,
            MetadataSyncJobStore jobStore,
            SchemaSnapshotStore snapshotStore,
            MetadataConnectionOpener opener) {
        this.primaryMetadata = Objects.requireNonNull(primaryMetadata, "primaryMetadata");
        this.jobStore = Objects.requireNonNull(jobStore, "jobStore");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /** Claims the oldest QUEUED job of the datasource and runs it; IDLE when nothing is queued. */
    public Outcome runQueued(
            long datasourceId, String leaseOwner, int leaseSeconds, MetadataCollector collector) {
        long jobId = jobStore.claimQueued(datasourceId, leaseOwner, leaseSeconds);
        if (jobId < 0) {
            return new Outcome(jobId, Outcome.Status.IDLE, null, null, null);
        }
        return execute(jobId, leaseOwner, leaseSeconds, collector);
    }

    /** Runs an already-owned RUNNING job (fresh claim or an expired-lease takeover). */
    public Outcome execute(
            long jobId, String leaseOwner, int leaseSeconds, MetadataCollector collector) {
        if (!jobStore.heartbeat(jobId, leaseOwner, leaseSeconds)) {
            return new Outcome(jobId, Outcome.Status.LEASE_LOST, null, null, null);
        }
        MetadataSyncJobStore.JobRow job = jobStore.get(jobId);
        if (!jobStore.heartbeat(jobId, leaseOwner, leaseSeconds)) {
            return new Outcome(jobId, Outcome.Status.LEASE_LOST, null, null, null);
        }
        if (!"ACTIVE".equals(datasourceStatus(job.datasourceId()))) {
            return fail(jobId, leaseOwner, -1, ErrorCode.DRIVER_ERROR);
        }
        long snapshotId = -1;
        try (Connection connection = opener.open(job.datasourceId())) {
            SnapshotModel model = collector.collect(connection);
            if (!jobStore.heartbeat(jobId, leaseOwner, leaseSeconds)) {
                return new Outcome(jobId, Outcome.Status.LEASE_LOST, null, null, null);
            }
            snapshotId = snapshotStore.stage(job.datasourceId(), model, nowUtc());
            long resourceVersion = snapshotStore.publish(snapshotId, job.configVersion());
            jobStore.finish(
                    jobId, leaseOwner, MetadataSyncJobStore.Outcome.SUCCEEDED, snapshotId, null);
            return new Outcome(jobId, Outcome.Status.SUCCEEDED, snapshotId, resourceVersion, null);
        } catch (DomainException error) {
            return fail(jobId, leaseOwner, snapshotId, error.errorCode());
        } catch (SQLException | RuntimeException error) {
            return fail(jobId, leaseOwner, snapshotId, ErrorCode.DRIVER_ERROR);
        }
    }

    private Outcome fail(long jobId, String leaseOwner, long snapshotId, ErrorCode errorCode) {
        if (snapshotId > 0) {
            try {
                snapshotStore.markSnapshotFailed(snapshotId);
            } catch (RuntimeException ignored) {
                // The job result carries the failure even if the staging row lingers.
            }
        }
        try {
            jobStore.finish(
                    jobId, leaseOwner, MetadataSyncJobStore.Outcome.FAILED, null, errorCode.name());
        } catch (RuntimeException ignored) {
            // A lost lease means the new owner finalizes; a published snapshot is recovered on
            // takeover.
        }
        return new Outcome(jobId, Outcome.Status.FAILED, null, null, errorCode.name());
    }

    private String datasourceStatus(long datasourceId) {
        try (Connection connection = primaryMetadata.getConnection()) {
            connection.setReadOnly(true);
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT status FROM datasource WHERE id = ?")) {
                statement.setQueryTimeout(5);
                statement.setLong(1, datasourceId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                    }
                    return result.getString(1);
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    /** Terminal report of one runner execution. */
    public record Outcome(
            long jobId, Status status, Long snapshotId, Long resourceVersion, String errorCode) {
        public enum Status {
            IDLE,
            SUCCEEDED,
            FAILED,
            LEASE_LOST
        }
    }
}
