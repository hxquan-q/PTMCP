package local.ptmcp.persistence.audit.wal;

import local.ptmcp.persistence.audit.AuditTerminal;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Crash simulator for {@link TerminalWalReplayTest}. Runs in a forked JVM, writes fsynced frames
 * and optionally a torn half frame, then leaves the JVM via {@link Runtime#halt(int)} so no
 * shutdown hook, buffer flush or close can mask the crash. Modes: {@code halt} and {@code
 * torn-tail}; args: mode, directory, keyId, base64 aesKey, frames.
 */
public final class WalCrashChild {
    private WalCrashChild() {}

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Path directory = Path.of(args[1]);
        String keyId = args[2];
        byte[] aesKey = Base64.getDecoder().decode(args[3]);
        int frames = Integer.parseInt(args[4]);
        WalKey key = new WalKey(keyId, aesKey);
        try (TerminalWal wal =
                new TerminalWal(directory, "child-instance", "child-boot", () -> key)) {
            for (int index = 0; index < frames; index++) {
                wal.append(terminal(index));
            }
            if ("torn-tail".equals(mode)) {
                writeTornTail(directory, key, frames);
            }
        }
        Runtime.getRuntime().halt(0);
    }

    private static void writeTornTail(Path directory, WalKey key, int frames) throws Exception {
        AuditTerminal extra = terminal(frames);
        WalFormat.Frame frame =
                new WalFormat.Frame(
                        WalFormat.KIND_TERMINAL,
                        UUID.randomUUID(),
                        extra.requestId(),
                        frames,
                        WalPayload.encode(extra));
        byte[] bytes = WalFormat.encodeFrame(frame, key, WalFormat.newNonce());
        Path segment;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "wal-*.wal")) {
            segment = files.iterator().next();
        }
        try (FileChannel channel =
                FileChannel.open(segment, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer half = ByteBuffer.wrap(bytes, 0, bytes.length / 2);
            while (half.hasRemaining()) {
                channel.write(half);
            }
            channel.force(true);
        }
    }

    private static AuditTerminal terminal(int index) {
        return new AuditTerminal(
                new UUID(42L, index),
                AuditTerminal.Result.SUCCESS,
                AuditTerminal.Verdict.ALLOW,
                null,
                100L + index,
                5,
                10 + index,
                false,
                "SELECT 'frame-" + index + "'",
                null,
                null,
                null,
                "execute_readonly_sql",
                7L,
                3L,
                1L,
                Instant.ofEpochSecond(1_700_000_000L, index));
    }
}
