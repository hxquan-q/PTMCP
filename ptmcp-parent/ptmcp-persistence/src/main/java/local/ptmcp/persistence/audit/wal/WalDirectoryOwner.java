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

/** One OS-enforced owner per persistent WAL directory, shared by its writer and recovery worker. */
final class WalDirectoryOwner implements AutoCloseable {
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
        try {
            Files.createDirectories(requested);
            Path directory = requested.toRealPath();
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
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException ignored) {
                // The acquisition fails closed regardless of cleanup outcome.
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
            if (lock.isValid()) {
                lock.release();
            }
            channel.close();
        } catch (IOException failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }
}
