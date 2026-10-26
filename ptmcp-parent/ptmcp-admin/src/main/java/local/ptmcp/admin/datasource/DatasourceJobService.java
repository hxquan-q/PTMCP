package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.datasource.metadata.MysqlMetadataCollector;
import local.ptmcp.datasource.metadata.PgMetadataCollector;
import local.ptmcp.persistence.schema.MetadataCollector;
import local.ptmcp.persistence.schema.MetadataConnectionOpener;
import local.ptmcp.persistence.schema.MetadataSyncJobRunner;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Connectivity probes and schema syncs. TEST runs the built-in probe inline and records a terminal
 * TEST job; SYNC enqueues a QUEUED job and hands it to the single-threaded runner so one admin
 * replica never runs two syncs at once.
 */
public final class DatasourceJobService implements AutoCloseable {
    private static final int LEASE_SECONDS = 120;
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final DatasourceStore store;
    private final DatasourceJobStore jobs;
    private final MetadataSyncJobRunner runner;
    private final DatasourceConnectionFactory connections;
    private final DatasourceAdminService admin;
    private final ExecutorService executor;

    public DatasourceJobService(
            DatasourceStore store,
            DatasourceJobStore jobs,
            MetadataSyncJobRunner runner,
            DatasourceConnectionFactory connections,
            DatasourceAdminService admin) {
        this.store = Objects.requireNonNull(store, "store");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.runner = Objects.requireNonNull(runner, "runner");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.admin = Objects.requireNonNull(admin, "admin");
        this.executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new LinkedBlockingQueue<>(),
                        runnable -> {
                            Thread thread = new Thread(runnable, "ptmcp-admin-schema-sync");
                            thread.setDaemon(true);
                            return thread;
                        });
    }

    public record Progress(int processed, Integer total) {}

    public record JobView(
            String id,
            String type,
            String datasourceId,
            String state,
            Progress progress,
            Instant startedAt,
            Instant finishedAt,
            String errorCode,
            String snapshotId) {}

    public JobView test(long datasourceId, DatasourceAdminService.Write draft, long actorId) {
        DatasourceAdminService.DraftTarget target = admin.resolveTarget(datasourceId, draft);
        boolean succeeded;
        try (Connection connection = connections.open(target.spec())) {
            connection.setReadOnly(true);
            succeeded = connection.isValid(5);
        } catch (Exception failure) {
            // Probe failures are recorded as job state only; driver stacks never reach the caller.
            succeeded = false;
        }
        return toView(jobs.recordTest(datasourceId, actorId, target.configVersion(), succeeded));
    }

    public JobView sync(long datasourceId, long actorId) {
        DatasourceStore.Row row = store.get(datasourceId);
        if (!"ACTIVE".equals(row.status())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        DatasourceJobStore.Job queued =
                jobs.enqueueSync(datasourceId, actorId, row.configVersion());
        submit(datasourceId, row.engine());
        return toView(queued);
    }

    public JobView get(long jobId) {
        return toView(jobs.get(jobId));
    }

    private void submit(long datasourceId, String engine) {
        MetadataCollector collector = collectorFor(engine);
        MetadataConnectionOpener opener =
                id -> connections.open(store.connectionSpec(store.get(id)));
        String owner = "admin:" + UUID.randomUUID();
        executor.submit(
                () -> {
                    try {
                        runner.runQueued(datasourceId, owner, LEASE_SECONDS, collector);
                    } catch (RuntimeException ignored) {
                        // The job row keeps the failure; the runner finalizes whatever it owns.
                    }
                });
    }

    private static MetadataCollector collectorFor(String engine) {
        return switch (engine) {
            case "MYSQL" -> new MysqlMetadataCollector();
            case "POSTGRESQL" -> new PgMetadataCollector();
            default -> throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        };
    }

    private static JobView toView(DatasourceJobStore.Job job) {
        return new JobView(
                Long.toString(job.id()),
                job.type(),
                Long.toString(job.datasourceId()),
                job.state(),
                new Progress(job.processed(), null),
                toInstant(job.startedAt()),
                toInstant(job.finishedAt()),
                job.errorCode(),
                job.snapshotId() == null ? null : Long.toString(job.snapshotId()));
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(UTC);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
