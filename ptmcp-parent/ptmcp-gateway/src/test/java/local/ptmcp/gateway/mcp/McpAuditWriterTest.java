package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.AuditStart;
import local.ptmcp.persistence.audit.AuditTerminal;
import local.ptmcp.persistence.audit.wal.TerminalWal;
import local.ptmcp.persistence.audit.wal.WalKey;
import local.ptmcp.persistence.audit.wal.WalKeyProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Terminal durability fallback at the audit-writer seam: a primary-database terminal failure must
 * still produce a durable fsynced WAL ending, and only the loss of both paths may reject the call.
 * The broken primary is simulated at the connection boundary, so no real database is involved.
 */
class McpAuditWriterTest {
    @TempDir Path walDirectory;

    @Test
    void startFailureSurfacesAsAuditUnavailable() {
        McpAuditWriter writer = new McpAuditWriter(new AuditRepository(broken()), null);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                writer.start(
                                        new AuditStart(
                                                UUID.randomUUID(),
                                                1L,
                                                1L,
                                                "execute_readonly_sql",
                                                sha256("SELECT 1"),
                                                9,
                                                Instant.now())));
        assertEquals(failure.errorCode(), ErrorCode.AUDIT_UNAVAILABLE);
    }

    @Test
    void terminalFallsBackToFsyncedWalWhenPrimaryIsUnavailable() throws Exception {
        TerminalWal wal = wal();
        McpAuditWriter writer = new McpAuditWriter(new AuditRepository(broken()), wal);
        writer.terminal(terminal());
        List<Path> segments;
        try (var files = Files.list(walDirectory)) {
            segments = files.filter(path -> path.toString().endsWith(".open")).toList();
        }
        assertEquals(segments.size(), 1);
        assertTrue(Files.size(segments.get(0)) > 0, "the terminal frame must be durable on disk");
        wal.close();
    }

    @Test
    void terminalWithoutWalAndDatabaseRejects() {
        McpAuditWriter writer = new McpAuditWriter(new AuditRepository(broken()), null);
        DomainException failure =
                assertThrows(DomainException.class, () -> writer.terminal(terminal()));
        assertEquals(failure.errorCode(), ErrorCode.AUDIT_UNAVAILABLE);
    }

    private TerminalWal wal() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        byte[] raw = Base64.getDecoder().decode(key);
        WalKeyProvider keys = () -> new WalKey("local:test", raw);
        return new TerminalWal(walDirectory, "test-instance", UUID.randomUUID().toString(), keys);
    }

    private static AuditTerminal terminal() {
        return new AuditTerminal(
                UUID.randomUUID(),
                AuditTerminal.Result.SUCCESS,
                AuditTerminal.Verdict.ALLOW,
                null,
                42L,
                null,
                7,
                false,
                null,
                sha256("SELECT 1"),
                null,
                null,
                "execute_readonly_sql",
                1L,
                1L,
                1L,
                Instant.now());
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static DataSource broken() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("primary unavailable");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("primary unavailable");
            }

            @Override
            public PrintWriter getLogWriter() {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) {}

            @Override
            public void setLoginTimeout(int seconds) {}

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return Logger.getGlobal();
            }

            @Override
            public <T> T unwrap(Class<T> interfaceType) throws SQLException {
                throw new SQLException("Not a wrapper");
            }

            @Override
            public boolean isWrapperFor(Class<?> interfaceType) {
                return false;
            }
        };
    }
}
