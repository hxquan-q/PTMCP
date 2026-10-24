package local.ptmcp.persistence.audit.wal;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.AuditTerminal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Recovers fsynced terminal events into the audit repository after a crash. Segments are replayed
 * in creation order; an incomplete tail is truncated at the last fully verified frame, a
 * structurally or cryptographically broken frame in the middle quarantines the whole segment
 * instead of silently skipping it, and a segment is checkpointed only after its frames were durably
 * admitted by the repository.
 */
public final class WalReplayer {
    private static final String DONE_SUFFIX = ".done";
    private static final String QUARANTINE_SUFFIX = ".quarantined";

    /** Admits one decoded terminal; implementations must return only after durable commit. */
    public interface TerminalSink {
        AuditRepository.TerminalState ingest(AuditTerminal terminal);
    }

    /**
     * Replay outcome. {@code framesDuplicated} counts terminals the repository already held; {@code
     * truncatedBytes} counts the torn tail cut off the last segment.
     */
    public record ReplayReport(
            int segmentsReplayed,
            int segmentsSkipped,
            int segmentsQuarantined,
            int framesIngested,
            int framesDuplicated,
            long truncatedBytes,
            List<Path> quarantined) {}

    public ReplayReport replay(Path directory, WalKeyProvider keys, TerminalSink sink) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(sink, "sink");
        WalKey current = keys.current();
        Function<String, WalKey> byId = keyId -> current.keyId().equals(keyId) ? current : null;
        List<Path> segments = listSegments(directory);
        int replayed = 0;
        int skipped = 0;
        int quarantinedCount = 0;
        int ingested = 0;
        int duplicated = 0;
        long truncatedBytes = 0;
        List<Path> quarantined = new ArrayList<>();
        for (Path segment : segments) {
            Path done = directory.resolve(segment.getFileName().toString() + DONE_SUFFIX);
            if (Files.exists(done)) {
                skipped++;
                continue;
            }
            byte[] file;
            try {
                file = Files.readAllBytes(segment);
            } catch (IOException error) {
                throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
            }
            ByteBuffer buffer = ByteBuffer.wrap(file);
            try {
                WalFormat.decodeSegmentHeader(buffer);
            } catch (WalFormat.CorruptFrameException corrupt) {
                quarantine(segment);
                quarantinedCount++;
                quarantined.add(segment);
                continue;
            }
            long validEnd = buffer.position();
            boolean broken = false;
            while (buffer.hasRemaining()) {
                int frameStart = buffer.position();
                if (buffer.remaining() < 4) {
                    break;
                }
                int total = buffer.getInt(frameStart);
                if (total <= 0 || frameStart + 4 + total > file.length) {
                    break;
                }
                WalFormat.Frame frame;
                try {
                    frame = WalFormat.decodeFrame(buffer, byId);
                } catch (WalFormat.CorruptFrameException corrupt) {
                    broken = true;
                    break;
                }
                if (!WalFormat.KIND_TERMINAL.equals(frame.eventKind())) {
                    broken = true;
                    break;
                }
                AuditTerminal terminal;
                try {
                    terminal = WalPayload.decode(WalPayload.SCHEMA_VERSION, frame.payload());
                } catch (RuntimeException corrupt) {
                    broken = true;
                    break;
                }
                AuditRepository.TerminalState state = sink.ingest(terminal);
                if (state.alreadyExisted()) {
                    duplicated++;
                } else {
                    ingested++;
                }
                validEnd = buffer.position();
            }
            if (broken) {
                quarantine(segment);
                quarantinedCount++;
                quarantined.add(segment);
                continue;
            }
            if (validEnd < file.length) {
                truncate(segment, validEnd);
                truncatedBytes += file.length - validEnd;
            }
            checkpoint(done);
            deleteQuietly(segment);
            replayed++;
        }
        return new ReplayReport(
                replayed,
                skipped,
                quarantinedCount,
                ingested,
                duplicated,
                truncatedBytes,
                List.copyOf(quarantined));
    }

    private static List<Path> listSegments(Path directory) {
        List<Path> segments = new ArrayList<>();
        try (DirectoryStream<Path> files =
                Files.newDirectoryStream(directory, "wal-??????-*.wal")) {
            for (Path file : files) {
                segments.add(file);
            }
        } catch (IOException error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        segments.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return segments;
    }

    private static void quarantine(Path segment) {
        Path target = segment.resolveSibling(segment.getFileName().toString() + QUARANTINE_SUFFIX);
        try {
            Files.move(segment, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    private static void truncate(Path segment, long validEnd) {
        try (FileChannel channel =
                FileChannel.open(segment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            channel.truncate(validEnd);
            channel.force(true);
        } catch (IOException error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    private static void checkpoint(Path done) {
        try {
            Files.createFile(done);
        } catch (IOException error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    private static void deleteQuietly(Path segment) {
        try {
            Files.deleteIfExists(segment);
        } catch (IOException ignored) {
            // The .done marker already prevents double ingestion; a locked file is retried later.
        }
    }
}
