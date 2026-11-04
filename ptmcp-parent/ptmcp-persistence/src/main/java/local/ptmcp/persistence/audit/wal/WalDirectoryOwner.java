package local.ptmcp.persistence.audit.wal;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** One OS-enforced owner per persistent WAL directory, shared by its writer and recovery worker. */
final class WalDirectoryOwner implements AutoCloseable {
    // POSIX record locks are process scoped: closing any second descriptor for .owner can
    // release the first descriptor's native lock. Reject local contenders before opening it.
    private static final Set<Path> LOCAL_OWNERS = ConcurrentHashMap.newKeySet();

    /** 目录不能作为 FileChannel 打开的平台：Windows 上目录 fsync 需退化，见 {@link #syncOnWindows}。 */
    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");

    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WalDirectoryOwner(Path directory, FileChannel channel, FileLock lock) {
        this.directory = directory;
        this.channel = channel;
        this.lock = lock;
    }

    static WalDirectoryOwner acquire(Path requested) {
        FileChannel channel = null;
        FileLock lock = null;
        Path directory = null;
        boolean registered = false;
        try {
            Files.createDirectories(requested);
            directory = requested.toRealPath();
            registered = LOCAL_OWNERS.add(directory);
            if (!registered) {
                throw new IOException("WAL directory is already owned");
            }
            channel =
                    FileChannel.open(
                            directory.resolve(".owner"),
                            StandardOpenOption.CREATE,
                            StandardOpenOption.WRITE,
                            LinkOption.NOFOLLOW_LINKS);
            lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("WAL directory is already owned");
            }
            sync(directory);
            return new WalDirectoryOwner(directory, channel, lock);
        } catch (IOException | OverlappingFileLockException failure) {
            try {
                if (lock != null) {
                    lock.release();
                }
            } catch (IOException ignored) {
                // Closing the channel below still releases its OS lock.
            }
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException ignored) {
                // The acquisition fails closed regardless of cleanup outcome.
            }
            if (registered && (channel == null || !channel.isOpen())) {
                LOCAL_OWNERS.remove(directory);
            }
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    Path directory() {
        return directory;
    }

    void requireHeld() {
        if (closed.get() || !lock.isValid()) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    /** Only a new directory owner may seal segments abandoned by an earlier process. */
    void recoverAbandonedSegments() {
        requireHeld();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "wal-*.open")) {
            for (Path file : files) {
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Unexpected WAL segment type");
                }
                String name = file.getFileName().toString();
                Path closed = file.resolveSibling(name.substring(0, name.length() - 5) + ".wal");
                if (Files.exists(closed, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("WAL segment collision");
                }
                Files.move(file, closed, StandardCopyOption.ATOMIC_MOVE);
            }
            sync(directory);
        } catch (IOException failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    static void sync(Path directory) throws IOException {
        if (!WINDOWS) {
            // POSIX：打开目录并 force 是把目录项落盘的惯用做法。
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
                channel.force(true);
            }
            return;
        }
        syncOnWindows(directory);
    }

    /**
     * Windows 不能把目录当 FileChannel 打开（CreateFile 需 FILE_FLAG_BACKUP_SEMANTICS， 纯 NIO 不可达），所以上面那条
     * POSIX 语义在 NTFS 上直接抛异常。
     *
     * <p>这里退化为「在 WAL 目录内建临时文件 → force(true) → 删除」：强制刷盘的仍是文件数据， 不是目录项，因此属于尽力而为而非等价保证。取舍是明确的——保留
     * Windows 上可运行的审计链， 同时把降级写在这里而不是静默跳过；临时文件用 `.fsync-` 前缀，不会与 `wal-*.open` 恢复扫描撞车。
     */
    private static void syncOnWindows(Path directory) throws IOException {
        Path probe = Files.createTempFile(directory, ".fsync-", ".tmp");
        try (FileChannel channel = FileChannel.open(probe, StandardOpenOption.WRITE)) {
            channel.force(true);
        } finally {
            Files.deleteIfExists(probe);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            try {
                if (lock.isValid()) {
                    lock.release();
                }
            } finally {
                channel.close();
            }
        } catch (IOException failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        } finally {
            if (!channel.isOpen()) {
                LOCAL_OWNERS.remove(directory);
            }
        }
    }
}
