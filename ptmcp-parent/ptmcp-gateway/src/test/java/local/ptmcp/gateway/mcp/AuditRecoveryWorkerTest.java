package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.AuditStart;
import local.ptmcp.persistence.audit.AuditTerminal;
import local.ptmcp.persistence.audit.ExecutionOwner;
import local.ptmcp.persistence.audit.wal.TerminalWal;
import local.ptmcp.persistence.audit.wal.WalKey;
import local.ptmcp.persistence.audit.wal.WalKeyProvider;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.mysql.MySQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.sql.DataSource;

/** Actual WAL/metadata recovery at the gateway admission boundary; no business database. */
class AuditRecoveryWorkerTest {
    private static final WalKeyProvider KEYS = () -> new WalKey("recovery-test", new byte[32]);
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static MySQLContainer mysql;
    private static HikariDataSource primary;
    @TempDir Path directory;

    @BeforeAll
    static void startOwnedMetadataDatabase() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("gateway_recovery_test")
                        .withUsername("recovery_test")
                        .withPassword(Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
                        .withUrlParam("connectionTimeZone", "UTC")
                        .withUrlParam("forceConnectionTimeZoneToSession", "true")
                        .withUrlParam("allowPublicKeyRetrieval", "true")
                        .withUrlParam("sslMode", "DISABLED");
        mysql.start();
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(mysql.getJdbcUrl());
            config.setUsername(mysql.getUsername());
            config.setPassword(mysql.getPassword());
            config.setMaximumPoolSize(2);
            primary = new HikariDataSource(config);
            Flyway.configure()
                    .dataSource(primary)
                    .locations("classpath:db/ptmcp-migration")
                    .load()
                    .migrate();
        } catch (RuntimeException | Error failure) {
            stopOwnedMetadataDatabase();
            throw failure;
        }
    }

    @AfterAll
    static void stopOwnedMetadataDatabase() {
        if (primary != null) {
            primary.close();
        }
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void clearOnlyOwnedAuditRows() throws Exception {
        try (Connection connection = primary.getConnection(); var sql = connection.createStatement()) {
            sql.executeUpdate("DELETE FROM execution_lease");
            sql.executeUpdate("DELETE FROM audit_outbox");
            sql.executeUpdate("DELETE FROM audit_log");
        }
    }

    @Test
    void startupRecoversTerminalBeforeUnknownAndAdmitsAnOwnedStart() throws Exception {
        AuditRepository repository = new AuditRepository(primary);
        AuditStart completed = start();
        AuditStart lost = start();
        try (TerminalWal retired = wal()) {
            repository.insertStart(completed, retired.executionOwner());
            repository.insertStart(lost, retired.executionOwner());
            retired.append(success(completed));
            expire(retired.executionOwner());
        }
        try (TerminalWal current = wal();
                AuditRecoveryWorker worker = worker(repository, current)) {
            AuditStart live = start();
            repository.insertStart(live, current.executionOwner());
            expire(current.executionOwner());
            McpAuditWriter writer = new McpAuditWriter(repository, current);
            AuditStart admitted = start();
            assertThrows(DomainException.class, () -> writer.start(admitted));
            assertEquals(0, count("audit_log", admitted.requestId()));
            worker.start();
            current.requireAdmission();
            writer.start(admitted);
            assertEquals("SUCCESS", outcome(completed.requestId()));
            assertEquals("UNKNOWN", outcome(lost.requestId()));
            assertEquals("RUNNING", outcome(live.requestId()));
            assertEquals(1, count("execution_lease", admitted.requestId()));
            try (Connection connection = primary.getConnection();
                    var sql = connection.prepareStatement(
                            "SELECT boot_id, datasource_id, state FROM execution_lease WHERE request_id = ?")) {
                sql.setString(1, admitted.requestId().toString());
                try (var rows = sql.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals(current.executionOwner().bootId(), rows.getString(1));
                    assertEquals(null, rows.getObject(2));
                    assertEquals("QUEUED", rows.getString(3));
                }
            }
            worker.recover();
            assertEquals(2, count("audit_outbox", completed.requestId()));
            assertEquals(2, count("audit_outbox", lost.requestId()));
        }
    }

    @Test
    void primaryLossPausesAdmissionAndRetryRecoversTheSameTerminal() throws Exception {
        AtomicBoolean unavailable = new AtomicBoolean();
        AuditRepository repository = new AuditRepository(switchable(unavailable));
        AuditStart completed = start();
        try (TerminalWal retired = wal()) {
            repository.insertStart(completed, retired.executionOwner());
            retired.append(success(completed));
            expire(retired.executionOwner());
        }
        try (TerminalWal current = wal();
                AuditRecoveryWorker worker = worker(repository, current)) {
            unavailable.set(true);
            worker.recover();
            assertThrows(DomainException.class, current::requireAdmission);
            assertEquals("RUNNING", outcome(completed.requestId()));
            unavailable.set(false);
            worker.recover();
            current.requireAdmission();
            assertEquals("SUCCESS", outcome(completed.requestId()));
            worker.recover();
            assertEquals(2, count("audit_outbox", completed.requestId()));
        }
    }

    @Test
    void corruptSegmentPreservesEvidenceAndCannotBecomeUnknown() throws Exception {
        AuditRepository repository = new AuditRepository(primary);
        AuditStart completed = start();
        try (TerminalWal retired = wal()) {
            repository.insertStart(completed, retired.executionOwner());
            retired.append(success(completed));
            expire(retired.executionOwner());
        }
        Path segment;
        try (var files = Files.list(directory)) {
            segment = files.filter(file -> file.toString().endsWith(".wal")).findFirst().orElseThrow();
        }
        byte[] bytes = Files.readAllBytes(segment);
        bytes[bytes.length - 1] ^= 1;
        Files.write(segment, bytes);
        try (TerminalWal current = wal();
                AuditRecoveryWorker worker = worker(repository, current)) {
            worker.recover();
            assertThrows(DomainException.class, current::requireAdmission);
            assertTrue(Files.exists(segment.resolveSibling(segment.getFileName() + ".quarantined")));
            assertEquals("RUNNING", outcome(completed.requestId()));
            worker.recover();
            assertEquals(1, count("audit_outbox", completed.requestId()));
        }
    }

    @Test
    void capacityFailureRetainsUnreplayedSegmentsUntilCapacityIsAvailable() throws Exception {
        AuditRepository repository = new AuditRepository(primary);
        AuditStart completed = start();
        try (TerminalWal retired = wal()) {
            repository.insertStart(completed, retired.executionOwner());
            retired.append(success(completed));
        }
        try (TerminalWal current = wal();
                AuditRecoveryWorker constrained = new AuditRecoveryWorker(repository, current, 1, 0);
                AuditRecoveryWorker normal = worker(repository, current)) {
            constrained.recover();
            assertThrows(DomainException.class, current::requireAdmission);
            assertEquals("RUNNING", outcome(completed.requestId()));
            try (var files = Files.list(directory)) {
                assertTrue(files.anyMatch(file -> file.toString().endsWith(".wal")));
            }
            normal.recover();
            current.requireAdmission();
            assertEquals("SUCCESS", outcome(completed.requestId()));
        }
    }

    @Test
    void lowFreeDiskSpacePausesAdmission() {
        AuditRepository repository = new AuditRepository(primary);
        try (TerminalWal current = wal();
                AuditRecoveryWorker worker = new AuditRecoveryWorker(repository, current, MAX_BYTES, Long.MAX_VALUE)) {
            worker.recover();
            assertThrows(DomainException.class, current::requireAdmission);
        }
    }

    private TerminalWal wal() {
        return new TerminalWal(directory, "gateway-test", UUID.randomUUID().toString(), KEYS);
    }

    private static AuditRecoveryWorker worker(AuditRepository repository, TerminalWal wal) {
        return new AuditRecoveryWorker(repository, wal, MAX_BYTES, 0);
    }

    private static AuditStart start() {
        return new AuditStart(
                UUID.randomUUID(), 1L, 1L, "list_databases_and_tables", null, null,
                Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS));
    }

    private static AuditTerminal success(AuditStart start) {
        return new AuditTerminal(
                start.requestId(), AuditTerminal.Result.SUCCESS, AuditTerminal.Verdict.ALLOW,
                null, 1L, null, 0, false, null, null, null, null,
                start.tool(), start.userId(), start.platformId(), null, start.startedAt());
    }

    private static void expire(ExecutionOwner owner) throws Exception {
        try (Connection connection = primary.getConnection();
                var sql = connection.prepareStatement(
                        "UPDATE execution_lease SET lease_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 MINUTE) WHERE boot_id = ?")) {
            sql.setString(1, owner.bootId());
            sql.executeUpdate();
        }
    }

    private static String outcome(UUID requestId) throws Exception {
        try (Connection connection = primary.getConnection();
                var sql = connection.prepareStatement("SELECT result FROM audit_log WHERE request_id = ?")) {
            sql.setString(1, requestId.toString());
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private static int count(String table, UUID requestId) throws Exception {
        if (!java.util.Set.of("audit_log", "audit_outbox", "execution_lease").contains(table)) {
            throw new IllegalArgumentException("Unexpected test table");
        }
        try (Connection connection = primary.getConnection();
                var sql = connection.prepareStatement("SELECT COUNT(*) FROM " + table + " WHERE request_id = ?")) {
            sql.setString(1, requestId.toString());
            try (var rows = sql.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private static DataSource switchable(AtomicBoolean unavailable) {
        return (DataSource) Proxy.newProxyInstance(
                DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection") && unavailable.get()) {
                        throw new SQLException("synthetic metadata outage");
                    }
                    try {
                        return method.invoke(primary, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
