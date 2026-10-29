package local.ptmcp.gateway.mcp;

import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.wal.TerminalWal;
import local.ptmcp.persistence.audit.wal.WalReplayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Recovers terminal events before reconciling retired processes; never executes business SQL. */
final class AuditRecoveryWorker implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AuditRecoveryWorker.class);
    private final AuditRepository repository;
    private final TerminalWal wal;
    private final long maxBytes;
    private final long minFreeBytes;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    task -> {
                        Thread thread = new Thread(task, "ptmcp-audit-recovery");
                        thread.setDaemon(true);
                        return thread;
                    });

    AuditRecoveryWorker(AuditRepository repository, TerminalWal wal, long maxBytes, long minFreeBytes) {
        this.repository = repository;
        this.wal = wal;
        this.maxBytes = maxBytes;
        this.minFreeBytes = minFreeBytes;
    }

    void start() {
        recover();
        scheduler.scheduleWithFixedDelay(this::recover, 10, 10, TimeUnit.SECONDS);
    }

    void recover() {
        try {
            repository.heartbeat(wal.executionOwner());
            wal.sealForRecovery();
            WalReplayer.ReplayReport report = wal.replayClosed(repository::submitTerminal);
            wal.verifyRecoveryCapacity(maxBytes, minFreeBytes);
            wal.recoverRetiredBoots(proof -> repository.markUnknownForRetiredBoot(proof, 100));
            wal.recoveryAvailable(true);
            if (report.truncatedBytes() > 0) {
                LOG.warn("Audit WAL torn tail recovered; bytes={}", report.truncatedBytes());
            }
        } catch (RuntimeException failure) {
            wal.recoveryAvailable(false);
            // Driver errors, paths, SQL and payloads must never enter recovery logs.
            LOG.warn("Audit recovery unavailable; new tool admission is suspended");
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warn("Audit recovery worker did not stop within its shutdown budget");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
