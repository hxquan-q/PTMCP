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
 * <p>Segments are plain files named {@code wal-%06d-{bootId}.wal} so that lexicographic order is
 * creation order. A plaintext segment header identifies the writer and the data key; only frame
 * bodies are encrypted.
 */
public final class TerminalWal implements AutoCloseable {
    static final long DEFAULT_MAX_SEGMENT_BYTES = 64L * 1024 * 1024;
    private static final Pattern SEGMENT_FILE = Pattern.compile("wal-(\\d{6})-.+\\.wal");

    private final Path directory;
    private final String gatewayInstanceId;
    private final String bootId;
    private final WalKeyProvider keys;
    private final boolean forceOnAppend;
    private final long maxSegmentBytes;
    private FileChannel channel;
    private long channelSize;
    private int segmentIndex;
    private long framesInSegment;
    private boolean poisoned;

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
                || bootId.isBlank()
                || bootId.length() > 128) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (maxSegmentBytes < 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        this.directory = Objects.requireNonNull(directory, "directory");
        this.gatewayInstanceId = gatewayInstanceId;
        this.bootId = bootId;
        this.keys = Objects.requireNonNull(keys, "keys");
        this.forceOnAppend = forceOnAppend;
        this.maxSegmentBytes = maxSegmentBytes;
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        this.segmentIndex = highestExistingSegment(directory);
    }

    /** Encodes, encrypts and fsyncs one terminal event. */
    public synchronized void append(AuditTerminal terminal) {
        Objects.requireNonNull(terminal, "terminal");
        if (poisoned) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        WalFormat.Frame frame =
                new WalFormat.Frame(
                        WalFormat.KIND_TERMINAL,
                        UUID.randomUUID(),
                        terminal.requestId(),
                        (int) (framesInSegment & 0x7FFFFFFF),
                        WalPayload.encode(terminal));
        byte[] bytes = WalFormat.encodeFrame(frame, keys.current(), WalFormat.newNonce());
        try {
            if (channel == null || channelSize + bytes.length > maxSegmentBytes) {
                roll();
            }
            channelSize += channel.write(ByteBuffer.wrap(bytes));
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
        if (poisoned) {
            return;
        }
        try {
            if (channel != null) {
                channel.close();
                channel = null;
            }
        } catch (IOException error) {
            poisoned = true;
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    Path directory() {
        return directory;
    }

    private void roll() throws IOException {
        if (channel != null) {
            channel.close();
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            segmentIndex++;
            Path file = directory.resolve(segmentName(segmentIndex));
            try {
                channel =
                        FileChannel.open(
                                file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
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
        channelSize = header.length;
        framesInSegment = 0;
    }

    private byte[] segmentHeader() {
        byte[] instance = gatewayInstanceId.getBytes(StandardCharsets.UTF_8);
        byte[] boot = bootId.getBytes(StandardCharsets.UTF_8);
        byte[] keyId = keys.current().keyId().getBytes(StandardCharsets.UTF_8);
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
        return String.format("wal-%06d-%s.wal", index, bootId);
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
