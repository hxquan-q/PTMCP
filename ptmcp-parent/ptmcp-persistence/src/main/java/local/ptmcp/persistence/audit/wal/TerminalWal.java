package local.ptmcp.persistence.audit.wal;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.AuditTerminal;
import local.ptmcp.persistence.audit.ExecutionOwner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
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
            Pattern.compile("wal-(\\d{6,19})-.+\\.(?:wal|open)(?:\\..+)?");

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
    private long segmentIndex;
    private long framesInSegment;
    private boolean poisoned;
    private boolean closed;
    private volatile boolean recoveryAvailable;

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
        new ExecutionOwner(gatewayInstanceId, bootId);
        this.keys = Objects.requireNonNull(keys, "keys");
        this.forceOnAppend = forceOnAppend;
        this.maxSegmentBytes = maxSegmentBytes;
        this.owner = WalDirectoryOwner.acquire(Objects.requireNonNull(directory, "directory"));
        this.directory = owner.directory();
        try {
            owner.recoverAbandonedSegments();
            this.segmentIndex = highestExistingSegment(this.directory);
            persistBoot();
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

    public ExecutionOwner executionOwner() {
        owner.requireHeld();
        return new ExecutionOwner(gatewayInstanceId, bootId);
    }

    public void recoveryAvailable(boolean available) {
        recoveryAvailable = available;
    }

    public synchronized void requireAdmission() {
        owner.requireHeld();
        if (closed || poisoned || !recoveryAvailable) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    /** Quarantined evidence remains a blocking recovery incident until explicitly repaired. */
    public synchronized void verifyRecoveryCapacity(long maxBytes, long minFreeBytes) {
        owner.requireHeld();
        if (maxBytes < 1 || minFreeBytes < 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        long bytes = 0;
        int entries = 0;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (++entries > 10000
                        || name.endsWith(".quarantined")
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
                }
                bytes = Math.addExact(bytes, Files.size(file));
                if (bytes > maxBytes) {
                    throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
                }
            }
            if (Files.getFileStore(directory).getUsableSpace() < minFreeBytes) {
                throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
            }
        } catch (IOException | ArithmeticException failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    /** The monitor keeps ownership held throughout the caller's recovery transactions. */
    public synchronized void recoverRetiredBoots(Consumer<RetiredBoot> recovery) {
        recoverRetiredBootsAndPrune(
                proof -> {
                    recovery.accept(proof);
                    return false;
                });
    }

    /** The callback returns true only after committing retirement with no pending leases. */
    public synchronized void recoverRetiredBootsAndPrune(Predicate<RetiredBoot> recovery) {
        owner.requireHeld();
        try (DirectoryStream<Path> pending = Files.newDirectoryStream(directory, "wal-*")) {
            for (Path file : pending) {
                String name = file.getFileName().toString();
                if (name.endsWith(".wal") || name.endsWith(".quarantined")) {
                    throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
                }
            }
        } catch (IOException failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "boot-*.boot")) {
            int scanned = 0;
            for (Path file : files) {
                if (++scanned > 10000
                        || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(file) > 512) {
                    throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
                }
                try (DataInputStream input =
                        new DataInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
                    int version = input.readUnsignedByte();
                    if (version != 1 && version != 2) {
                        throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
                    }
                    ExecutionOwner retired = new ExecutionOwner(input.readUTF(), input.readUTF());
                    if (input.available() != 0
                            || !file.getFileName()
                                    .toString()
                                    .equals("boot-" + retired.bootId() + ".boot")) {
                        throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
                    }
                    if (!retired.bootId().equals(bootId)) {
                        boolean collectable = recovery.test(new RetiredBoot(owner, retired));
                        if (version == 2 && collectable) {
                            owner.requireHeld();
                            Files.delete(file);
                            WalDirectoryOwner.sync(directory);
                        }
                    }
                }
            }
        } catch (IOException failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    private void persistBoot() {
        Path target = directory.resolve("boot-" + bootId + ".boot");
        // Reusing a boot identity could make its previous tasks look live. Fail closed.
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        Path temporary = directory.resolve("boot-" + bootId + ".tmp");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeByte(2);
                output.writeUTF(gatewayInstanceId);
                output.writeUTF(bootId);
            }
            try (FileChannel file =
                    FileChannel.open(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) {
                    file.write(buffer);
                }
                file.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            WalDirectoryOwner.sync(directory);
        } catch (IOException failure) {
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
            if (segmentIndex == Long.MAX_VALUE) {
                throw new IOException("WAL segment sequence exhausted");
            }
            segmentIndex++;
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

    private String segmentName(long index) {
        return String.format("wal-%06d-%s.open", index, bootId);
    }

    private void seal() throws IOException {
        if (channel == null) {
            return;
        }
        FileChannel sealing = channel;
        channel = null;
        try (sealing) {
            sealing.force(true);
        }
        String name = activeSegment.getFileName().toString();
        Path target = activeSegment.resolveSibling(name.substring(0, name.length() - 5) + ".wal");
        if (Files.exists(target)) {
            throw new IOException("WAL segment collision");
        }
        Files.move(activeSegment, target, StandardCopyOption.ATOMIC_MOVE);
        WalDirectoryOwner.sync(directory);
        activeSegment = null;
    }

    private static long highestExistingSegment(Path directory) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            long highest = 0;
            for (Path file : files) {
                Matcher match = SEGMENT_FILE.matcher(file.getFileName().toString());
                if (match.matches()) {
                    highest = Math.max(highest, Long.parseLong(match.group(1)));
                }
            }
            return highest;
        } catch (IOException error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }
}
