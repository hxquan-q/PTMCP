package local.ptmcp.persistence.audit.wal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.persistence.audit.AuditTerminal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 目录刷盘必须在所有支持平台上可用。
 *
 * <p>回归背景：2026-10-29 引入的 {@code sync(directory)} 用 {@code FileChannel.open(directory, READ)} 做目录
 * fsync，这是 Unix 语义。Windows 上目录 不能作为 FileChannel 打开，任何带 {@code TerminalWal} 的集成测试在 Windows native 全部
 * 初始化失败（DEV-20261104）。这里钉住「拿到目录 owner 后能真正 append 并关闭」这条路径， 在 Windows 上它就是 RED。
 */
class WalDirectorySyncTest {
    private final WalKeyProvider keys = () -> new WalKey("dir-sync-test", new byte[32]);

    @TempDir Path directory;

    @Test
    void directorySyncWorksOnTheRunningPlatform() {
        assertDoesNotThrow(() -> WalDirectoryOwner.sync(directory));
    }

    @Test
    void directorySyncLeavesNoProbeArtifacts() throws IOException {
        WalDirectoryOwner.sync(directory);
        try (Stream<Path> files = Files.list(directory)) {
            List<String> names = new ArrayList<>();
            files.forEach(p -> names.add(p.getFileName().toString()));
            assertTrue(
                    names.stream().noneMatch(n -> n.startsWith(".fsync-")), "临时探针文件必须被清理：" + names);
        }
    }

    @Test
    void walCanAppendAfterDirectorySync() throws Exception {
        try (TerminalWal wal =
                new TerminalWal(directory, "instance", UUID.randomUUID().toString(), keys)) {
            wal.append(terminal());
        }
        try (Stream<Path> files = Files.list(directory)) {
            assertTrue(
                    files.anyMatch(p -> p.getFileName().toString().startsWith("wal-")),
                    "关闭后必须留下至少一个 WAL 段");
        }
    }

    @Test
    void syncOnMissingDirectoryFailsClosed() {
        Path missing = directory.resolve("not-created-yet");
        assertThrows(IOException.class, () -> WalDirectoryOwner.sync(missing));
    }

    @Test
    void secondOwnerOnSameDirectoryIsRejected() throws Exception {
        try (TerminalWal first =
                new TerminalWal(directory, "instance", UUID.randomUUID().toString(), keys)) {
            assertThrows(
                    DomainException.class,
                    () -> new TerminalWal(directory, "other", UUID.randomUUID().toString(), keys));
            first.append(terminal());
        }
        assertEquals(1, countWalSegments());
    }

    private long countWalSegments() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(p -> p.getFileName().toString().startsWith("wal-")).count();
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
