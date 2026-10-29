package local.ptmcp.persistence.audit.wal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.AuditTerminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Real file locks: a live writer cannot share its directory with another owner or replay. */
class WalOwnershipTest {
    private final WalKeyProvider keys = () -> new WalKey("ownership-test", new byte[32]);

    @TempDir Path directory;

    @Test
    void failedSameJvmContenderCannotReleaseTheOriginalNativeLock() throws Exception {
        try (TerminalWal first =
                new TerminalWal(directory, "instance", UUID.randomUUID().toString(), keys)) {
            assertEquals(23, probeAnotherJvm());
            assertThrows(
                    DomainException.class,
                    () -> new TerminalWal(directory, "other", UUID.randomUUID().toString(), keys));
            assertEquals(23, probeAnotherJvm());
            first.append(terminal());
        }
        assertEquals(0, probeAnotherJvm());
    }

    private int probeAnotherJvm() throws Exception {
        String executable =
                System.getProperty("os.name", "").toLowerCase().contains("win")
                        ? "java.exe"
                        : "java";
        Process child =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", executable)
                                        .toString(),
                                "-cp",
                                System.getProperty("java.class.path"),
                                WalLockChild.class.getName(),
                                directory.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "lock probe timed out");
            return child.exitValue();
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void secondWriterCannotAcquireLiveDirectoryButCanAcquireAfterClose() {
        try (TerminalWal first =
                new TerminalWal(directory, "instance", UUID.randomUUID().toString(), keys)) {
            assertThrows(
                    DomainException.class,
                    () -> {
                        try (TerminalWal ignored =
                                new TerminalWal(
                                        directory, "other", UUID.randomUUID().toString(), keys)) {
                            // Closing the candidate also detects implementations that silently
                            // share ownership.
                        }
                    });
            first.append(terminal());
        }
        assertDoesNotThrow(
                () -> {
                    try (TerminalWal next =
                            new TerminalWal(
                                    directory, "instance", UUID.randomUUID().toString(), keys)) {
                        next.append(terminal());
                    }
                });
    }

    @Test
    void offlineReplayCannotReadOrCheckpointAnActiveWriter() {
        try (TerminalWal writer =
                new TerminalWal(directory, "instance", UUID.randomUUID().toString(), keys)) {
            writer.append(terminal());
            assertThrows(
                    DomainException.class,
                    () ->
                            new WalReplayer()
                                    .replay(
                                            directory,
                                            keys,
                                            value -> {
                                                throw new AssertionError(
                                                        "live segment must never reach the sink");
                                            }));
            writer.append(terminal());
        }
    }

    @Test
    void closedWriterCannotReopenItsChannelOrReacquireOwnershipByAppending() {
        TerminalWal writer =
                new TerminalWal(directory, "instance", UUID.randomUUID().toString(), keys);
        writer.append(terminal());
        writer.close();
        assertThrows(DomainException.class, () -> writer.append(terminal()));
        assertDoesNotThrow(writer::close);
    }

    @Test
    void onlineRecoveryOnlyReadsSealedSegmentsAndLeavesTheWriterUsable() {
        List<UUID> admitted = new ArrayList<>();
        WalReplayer.TerminalSink sink =
                value -> {
                    admitted.add(value.requestId());
                    return new AuditRepository.TerminalState(
                            value.requestId(),
                            value.result(),
                            value.verdict(),
                            null,
                            Instant.now(),
                            false,
                            false);
                };
        AuditTerminal first = terminal();
        AuditTerminal second = terminal();
        try (TerminalWal writer =
                new TerminalWal(directory, "instance", "online-boot", keys, true, 1)) {
            writer.append(first);
            writer.append(second);
            assertEquals(1, writer.replayClosed(sink).framesIngested());
            assertEquals(List.of(first.requestId()), admitted);
            assertEquals(0, writer.replayClosed(sink).framesIngested());
            writer.sealForRecovery();
            assertEquals(1, writer.replayClosed(sink).framesIngested());
            assertEquals(List.of(first.requestId(), second.requestId()), admitted);
            writer.append(terminal());
        }
    }

    private static AuditTerminal terminal() {
        return new AuditTerminal(
                UUID.randomUUID(),
                AuditTerminal.Result.SUCCESS,
                AuditTerminal.Verdict.ALLOW,
                null,
                1L,
                0,
                1,
                false,
                null,
                null,
                null,
                null,
                "execute_readonly_sql",
                1L,
                1L,
                1L,
                Instant.now());
    }
}
