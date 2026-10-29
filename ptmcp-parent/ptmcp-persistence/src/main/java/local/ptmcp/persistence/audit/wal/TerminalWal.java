package local.ptmcp.persistence.audit.wal;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.AuditTerminal;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Durable, append-only writer for encrypted terminal events. Every append ends in {@link
 * FileChannel#force(boolean)}; a failed fsync surfaces as {@code AUDIT_UNAVAILABLE} and the caller
 * must stop treating this writer as usable — bytes that did not reach the channel may be recovered
 * by replay, bytes that were never fsynced never happened.
 *
 * <p>The directory has one OS file-lock owner. Active segments end in {@code .open}; only sealed
 * {@code .wal} segments may be replayed. A new owner seals abandoned files after acquiring the
 * exclusive lock. Headers identify the writer and the data key; frame bodies are encrypted.
 */
public final class TerminalWal implements AutoCloseable {
    static final long DEFAULT_MAX_SEGMENT_BYTES = 64L * 1024 * 1024;
    private static final Pattern SEGMENT_FILE =
            Pattern.compile("wal-(\\d{6})-.+\\.(?:wal|open)(?:\\..+)?");

    private final Path directory;
    private final WalDirectoryOwner owner;
    private final String gatewayInstanceId;
    private final String bootId;
    private final WalKeyProvider keys;
    private final boolean forceOnAppend;
    private final long maxSegmentBytes;
    private FileChannel channel;
    private Path activeSegment;
    private WalKey segmentKey;
    private long channelSize;
    private int segmentIndex;
    private long framesInSegment;
    private boolean poisoned;
    private boolean closed;

    public TerminalWal(
            Path directory, String gatewayInstanceId, String bootId, WalKeyProvider keys) {
        this(directory, gatewayInstanceId, bootId, keys, true, DEFAULT_MAX_SEGMENT_BYTES);
    }

    TerminalWal(
            Path directory,
            String gatewayInstanceId,
            String bootId,
            WalKeyProvider keys,
            boolean forceOnAppend,
            long maxSegmentBytes) {
        if (gatewayInstanceId == null
                || gatewayInstanceId.isBlank()
                || gatewayInstanceId.length() > 128
                || bootId == null
                || !bootId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || gatewayInstanceId.getBytes(StandardCharsets.UTF_8).length > 128) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (maxSegmentBytes < 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        this.gatewayInstanceId = gatewayInstanceId;
        this.bootId = bootId;
        this.keys = Objects.requireNonNull(keys, "keys");
        this.forceOnAppend = forceOnAppend;
        this.maxSegmentBytes = maxSegmentBytes;
        this.owner = WalDirectoryOwner.acquire(Objects.requireNonNull(directory, "directory"));
        this.directory = owner.directory();
        try {
            owner.recoverAbandonedSegments();
            this.segmentIndex = highestExistingSegment(this.directory);
        } catch (RuntimeException failure) {
            owner.close();
            throw failure;
        }
    }

    /** Encodes, encrypts and fsyncs one terminal event. */
    public synchronized void append(AuditTerminal terminal) {
        Objects.requireNonNull(terminal, "terminal");
        if (poisoned || closed) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        try {
            owner.requireHeld();
            WalKey current = keys.current();
            if (channel == null || !current.equals(segmentKey)) {
                roll();
            }
            byte[] bytes = encode(terminal);
            if (framesInSegment > 0 && channelSize + bytes.length > maxSegmentBytes) {
                roll();
                bytes = encode(terminal);
            }
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channelSize += bytes.length;
            if (forceOnAppend) {
                channel.force(true);
            }
            framesInSegment++;
        } catch (IOException error) {
            poisoned = true;
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (channel != null) {
                if (poisoned) {
                    channel.close();
                    channel = null;
                } else {
                    seal();
                }
            }
        } catch (IOException error) {
            poisoned = true;
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        } finally {
            owner.close();
        }
    }

    /** Online recovery shares this directory's owner and never reads the current active segment. */
    public synchronized WalReplayer.ReplayReport replayClosed(WalReplayer.TerminalSink sink) {
        if (closed || poisoned) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        owner.requireHeld();
        return new WalReplayer().replayOwned(owner, keys, sink);
    }

    /** Makes the current fsynced segment eligible for recovery without closing the directory. */
    public synchronized void sealForRecovery() {
        if (closed || poisoned) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        try {
            seal();
        } catch (IOException failure) {
            poisoned = true;
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    private byte[] encode(AuditTerminal terminal) {
        WalFormat.Frame frame =
                new WalFormat.Frame(
                        WalFormat.KIND_TERMINAL,
                        UUID.randomUUID(),
                        terminal.requestId(),
                        Math.toIntExact(framesInSegment),
                        WalPayload.encode(terminal));
        return WalFormat.encodeFrame(frame, segmentKey, WalFormat.newNonce());
    }

    Path directory() {
        return directory;
    }

    private void roll() throws IOException {
        seal();
        segmentKey = keys.current();
        for (int attempt = 0; attempt < 2; attempt++) {
            segmentIndex++;
            if (segmentIndex > 999999) {
                throw new IOException("WAL segment sequence exhausted");
            }
            Path file = directory.resolve(segmentName(segmentIndex));
            try {
                channel =
                        FileChannel.open(
                                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                activeSegment = file;
                break;
            } catch (FileAlreadyExistsException race) {
                if (attempt == 1) {
                    throw race;
                }
            }
        }
        byte[] header = segmentHeader();
        ByteBuffer buffer = ByteBuffer.wrap(header);
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        channel.force(true);
        WalDirectoryOwner.sync(directory);
        channelSize = header.length;
        framesInSegment = 0;
    }

    private byte[] segmentHeader() {
        byte[] instance = gatewayInstanceId.getBytes(StandardCharsets.UTF_8);
        byte[] boot = bootId.getBytes(StandardCharsets.UTF_8);
        byte[] keyId = segmentKey.keyId().getBytes(StandardCharsets.UTF_8);
        UUID segmentId = UUID.randomUUID();
        ByteBuffer header =
                ByteBuffer.allocate(
                        WalFormat.MAGIC.length
                                + 1
                                + 16
                                + 1
                                + instance.length
                                + 1
                                + boot.length
                                + 8
                                + 1
                                + keyId.length);
        header.put(WalFormat.MAGIC);
        header.put(WalFormat.SCHEMA_VERSION);
        header.putLong(segmentId.getMostSignificantBits());
        header.putLong(segmentId.getLeastSignificantBits());
        header.put((byte) instance.length);
        header.put(instance);
        header.put((byte) boot.length);
        header.put(boot);
        header.putLong(System.currentTimeMillis());
        header.put((byte) keyId.length);
        header.put(keyId);
        return header.array();
    }

    private String segmentName(int index) {
        return String.format("wal-%06d-%s.open", index, bootId);
    }

    private void seal() throws IOException {
        if (channel == null) {
            return;
        }
        channel.force(true);
        channel.close();
        channel = null;
        String name = activeSegment.getFileName().toString();
        Path target = activeSegment.resolveSibling(name.substring(0, name.length() - 5) + ".wal");
        if (Files.exists(target)) {
            throw new IOException("WAL segment collision");
        }
        Files.move(activeSegment, target, StandardCopyOption.ATOMIC_MOVE);
        WalDirectoryOwner.sync(directory);
        activeSegment = null;
    }

    private static int highestExistingSegment(Path directory) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            int highest = 0;
            for (Path file : files) {
                Matcher match = SEGMENT_FILE.matcher(file.getFileName().toString());
                if (match.matches()) {
                    highest = Math.max(highest, Integer.parseInt(match.group(1)));
                }
            }
            return highest;
        } catch (IOException error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }
}
