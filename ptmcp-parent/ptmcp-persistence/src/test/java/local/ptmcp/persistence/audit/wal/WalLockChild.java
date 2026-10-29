package local.ptmcp.persistence.audit.wal;

import local.ptmcp.common.error.DomainException;

import java.nio.file.Path;
import java.util.UUID;

/** Separate JVM probe, so an invalid native lock cannot hide behind FileLock.isValid(). */
public final class WalLockChild {
    private WalLockChild() {}

    public static void main(String[] args) {
        try (TerminalWal ignored =
                new TerminalWal(
                        Path.of(args[0]),
                        "child",
                        UUID.randomUUID().toString(),
                        () -> new WalKey("test", new byte[32]))) {
            // An acquired directory has exit code 0.
        } catch (DomainException alreadyOwned) {
            System.exit(23);
        }
    }
}
