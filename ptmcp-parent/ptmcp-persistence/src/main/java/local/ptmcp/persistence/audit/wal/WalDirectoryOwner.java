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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** One OS-enforced owner per persistent WAL directory, shared by its writer and recovery worker. */
final class WalDirectoryOwner implements AutoCloseable {
    // POSIX record locks are process scoped: closing any second descriptor for .owner can
    // release the first descriptor's native lock. Reject local contenders before opening it.
    private static final Set<Path> LOCAL_OWNERS = ConcurrentHashMap.newKeySet();
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;

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
            if (registered) {
                LOCAL_OWNERS.remove(directory);
            }
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    Path directory() {
        return directory;
    }

    void requireHeld() {
        if (!lock.isValid()) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    /** Only a new directory owner may seal segments abandoned by an earlier process. */
    void recoverAbandonedSegments() {
        requireHeld();
        try (DirectoryStream<Path> files =
                Files.newDirectoryStream(directory, "wal-??????-*.open")) {
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
        } finally {
            LOCAL_OWNERS.remove(directory);
        }
    }

    static void sync(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    @Override
    public void close() {
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
        }
    }
}
