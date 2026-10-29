package local.ptmcp.persistence.audit.wal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.AuditStart;
import local.ptmcp.persistence.audit.AuditTerminal;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.mysql.MySQLContainer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * Real filesystem plus real MySQL: crash recovery is proven by a forked JVM that halts mid-write,
 * not by mocks or skipped assertions. Docker absence fails the suite; it never skips it.
 */
class TerminalWalReplayTest {
    private static final String MARKER = "ptmcp-wal-plaintext-marker-7f3a91";
    private static MySQLContainer mysql;
    private static WalKey key;
    private static WalKeyProvider keys;

    @TempDir Path walDirectory;

    @BeforeAll
    static void startSyntheticMetadataDatabase() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("audit_repository_test")
                        .withUsername("audit_test")
                        .withPassword(
                                Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
                        .withEnv("TZ", "UTC")
                        .withCommand(
                                "--default-time-zone=+00:00",
                                "--character-set-server=utf8mb4",
                                "--collation-server=utf8mb4_0900_bin")
                        .withUrlParam("connectionTimeZone", "UTC")
                        .withUrlParam("forceConnectionTimeZoneToSession", "true")
                        .withUrlParam("allowPublicKeyRetrieval", "true")
                        .withUrlParam("useSSL", "false");
        mysql.start();
        try {
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/ptmcp-migration")
                    .cleanDisabled(true)
                    .baselineOnMigrate(false)
                    .load()
                    .migrate();
        } catch (Exception | AssertionError failure) {
            mysql.stop();
            throw failure;
        }
        byte[] aesKey = new byte[32];
        new SecureRandom().nextBytes(aesKey);
        key = new WalKey("test-key-1", aesKey);
        keys = () -> key;
    }

    @AfterAll
    static void stopOwnedContainer() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void resetOnlyOwnedAuditData() throws Exception {
        try (Connection connection = open()) {
            update(connection, "DELETE FROM audit_outbox");
            update(connection, "DELETE FROM audit_log");
            update(connection, "DELETE FROM execution_lease");
            update(connection, "DELETE FROM datasource");
            update(
                    connection,
                    "INSERT INTO datasource (id, code, name, engine, host, port, database_name, "
                            + "username, credential_cipher, key_id, tls_mode, pool_max, status, "
                            + "config_version, resource_version, create_time, update_time, "
                            + "created_by, updated_by) VALUES (1, 'audit-test', 'Audit Test', 'MYSQL', "
                            + "'127.0.0.1', 3306, 'audit', 'audit', X'00', 'test-key', 'VERIFY_FULL', "
                            + "1, 'ACTIVE', 1, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 1)");
        }
    }

    @Test
    void replayIngestsTerminalsAndSecondReplayIsIdempotent() throws Exception {
        AuditRepository repository = repository();
        List<UUID> expected = new ArrayList<>();
        try (TerminalWal wal = new TerminalWal(walDirectory, "test-instance", "test-boot", keys)) {
            for (int index = 0; index < 3; index++) {
                UUID requestId = UUID.randomUUID();
                expected.add(requestId);
                wal.append(
                        terminal(
                                requestId,
                                pastMinutes(30).plusMillis(index),
                                "SELECT 'frame-" + index + "'"));
            }
        }

        List<UUID> ingested = new ArrayList<>();
        WalReplayer.ReplayReport first =
                new WalReplayer()
                        .replay(
                                walDirectory,
                                keys,
                                terminal -> {
                                    ingested.add(terminal.requestId());
                                    return repository.submitTerminal(terminal);
                                });
        assertEquals(1, first.segmentsReplayed());
        assertEquals(3, first.framesIngested());
        assertEquals(0, first.framesDuplicated());
        assertEquals(expected, ingested);

        try (Connection connection = open()) {
            assertEquals(3, count(connection, "WHERE result = 'SUCCESS'"));
            assertEquals(
                    "SELECT 'frame-2'",
                    string(
                            connection,
                            "SELECT sql_masked FROM audit_log WHERE request_id = ?",
                            expected.get(2).toString()));
        }

        WalReplayer.ReplayReport second =
                new WalReplayer().replay(walDirectory, keys, repository::submitTerminal);
        assertEquals(0, second.segmentsReplayed());
        assertEquals(0, second.framesIngested());
        assertEquals(0, second.framesDuplicated());
        try (Connection connection = open()) {
            assertEquals(3, count(connection, ""));
        }
    }

    @Test
    void fsyncedFramesSurviveForkedJvmHalt() throws Exception {
        crash("halt", 3);

        AuditRepository repository = repository();
        WalReplayer.ReplayReport report =
                new WalReplayer().replay(walDirectory, keys, repository::submitTerminal);
        assertEquals(1, report.segmentsReplayed());
        assertEquals(3, report.framesIngested());
        assertEquals(0, report.truncatedBytes());
        try (Connection connection = open()) {
            assertEquals(3, count(connection, "WHERE result = 'SUCCESS'"));
        }
    }

    @Test
    void tornTailIsTruncatedToLastFsyncedFrame() throws Exception {
        crash("torn-tail", 2);

        AuditRepository repository = repository();
        WalReplayer.ReplayReport report =
                new WalReplayer().replay(walDirectory, keys, repository::submitTerminal);
        assertEquals(1, report.segmentsReplayed());
        assertEquals(2, report.framesIngested());
        assertTrue(report.truncatedBytes() > 0, "torn half frame must be cut off");
        try (Connection connection = open()) {
            assertEquals(2, count(connection, "WHERE result = 'SUCCESS'"));
        }
    }

    @Test
    void middleCorruptionQuarantinesWholeSegmentWithoutIngesting() throws Exception {
        AuditRepository repository = repository();
        try (TerminalWal wal = new TerminalWal(walDirectory, "test-instance", "test-boot", keys)) {
            wal.append(terminal(UUID.randomUUID(), pastMinutes(30), "SELECT 'first'"));
            wal.append(terminal(UUID.randomUUID(), pastMinutes(29), "SELECT 'second'"));
        }
        Path segment = onlySegment(walDirectory);
        int headerLength = WalFormat.headerLength(WalFormat.KIND_TERMINAL, key.keyId());
        int cipherOffset = frameStartPosition(segment) + 4 + headerLength + 5;
        byte[] bytes = Files.readAllBytes(segment);
        assertTrue(cipherOffset < bytes.length);
        bytes[cipherOffset] ^= 0x01;
        Files.write(segment, bytes);

        WalReplayer.ReplayReport report =
                new WalReplayer().replay(walDirectory, keys, repository::submitTerminal);
        assertEquals(1, report.segmentsQuarantined());
        assertEquals(0, report.framesIngested());
        assertEquals(1, report.quarantined().size());
        assertFalse(Files.exists(segment));
        assertTrue(Files.exists(Path.of(segment + ".quarantined")));
        try (Connection connection = open()) {
            assertEquals(0, count(connection, ""));
        }
    }

    @Test
    void doneMarkerSkipsAlreadyIngestedSegment() throws Exception {
        AuditRepository repository = repository();
        try (TerminalWal wal = new TerminalWal(walDirectory, "test-instance", "test-boot", keys)) {
            wal.append(terminal(UUID.randomUUID(), pastMinutes(30), "SELECT 'first'"));
        }
        Path segment = onlySegment(walDirectory);
        Files.createFile(Path.of(segment + ".done"));

        WalReplayer.ReplayReport report =
                new WalReplayer().replay(walDirectory, keys, repository::submitTerminal);
        assertEquals(1, report.segmentsSkipped());
        assertEquals(0, report.framesIngested());
        try (Connection connection = open()) {
            assertEquals(0, count(connection, ""));
        }
    }

    @Test
    void previouslyStoredUnknownNeverOverwritesSuccessAndLateWalSuccessConflicts()
            throws Exception {
        AuditRepository repository = repository();
        Instant startedAt = pastMinutes(30);

        UUID completed = UUID.randomUUID();
        repository.insertStart(start(completed, startedAt));
        repository.submitTerminal(terminal(completed, startedAt, "SELECT 'done'"));
        UUID walled = UUID.randomUUID();
        repository.insertStart(start(walled, startedAt));
        try (TerminalWal wal = new TerminalWal(walDirectory, "test-instance", "test-boot", keys)) {
            wal.append(terminal(walled, startedAt, "SELECT 'walled'"));
        }

        // Simulate an UNKNOWN produced by an older/manual recovery. New automated recovery
        // must replay the WAL first; this test preserves the conflicting-ending invariant.
        repository.submitTerminal(
                new AuditTerminal(
                        walled,
                        AuditTerminal.Result.UNKNOWN,
                        AuditTerminal.Verdict.UNKNOWN,
                        "EXECUTION_OUTCOME_UNKNOWN",
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        "execute_readonly_sql",
                        7L,
                        3L,
                        1L,
                        startedAt));

        List<AuditRepository.TerminalState> states = new ArrayList<>();
        WalReplayer.ReplayReport report =
                new WalReplayer()
                        .replay(
                                walDirectory,
                                keys,
                                terminal -> {
                                    AuditRepository.TerminalState state =
                                            repository.submitTerminal(terminal);
                                    states.add(state);
                                    return state;
                                });
        assertEquals(1, report.framesDuplicated());
        assertEquals(0, report.framesIngested());
        assertEquals(1, states.size());
        assertTrue(states.get(0).conflict());
        assertTrue(states.get(0).alreadyExisted());

        try (Connection connection = open()) {
            assertOutcome(connection, completed, "SUCCESS", "ALLOW", null);
            assertOutcome(connection, walled, "UNKNOWN", "UNKNOWN", "EXECUTION_OUTCOME_UNKNOWN");
        }
    }

    @Test
    void segmentBytesNeverContainPlaintextPayload() throws Exception {
        String rawMarker = "ptmcp-wal-raw-marker-b29c4e";
        try (TerminalWal wal = new TerminalWal(walDirectory, "test-instance", "test-boot", keys)) {
            wal.append(
                    new AuditTerminal(
                            UUID.randomUUID(),
                            AuditTerminal.Result.SUCCESS,
                            AuditTerminal.Verdict.ALLOW,
                            null,
                            5L,
                            0,
                            1,
                            false,
                            "SELECT '" + MARKER + "' AS leak",
                            null,
                            rawMarker.getBytes(StandardCharsets.UTF_8),
                            "test-key-1",
                            "execute_readonly_sql",
                            7L,
                            3L,
                            1L,
                            pastMinutes(30)));
        }
        byte[] raw = Files.readAllBytes(onlySegment(walDirectory));
        assertFalse(
                indexOf(raw, MARKER.getBytes(StandardCharsets.UTF_8)) >= 0,
                "masked SQL must never reach the segment in plaintext");
        assertFalse(
                indexOf(raw, rawMarker.getBytes(StandardCharsets.UTF_8)) >= 0,
                "payload bytes must never reach the segment in plaintext");
        assertFalse(
                indexOf(raw, new byte[] {'S', 'E', 'L', 'E', 'C', 'T', ' ', '\''}) >= 0,
                "no SQL prefix may appear in plaintext");
    }

    @Test
    void unusableDirectoryIsRejectedAsAuditUnavailable(@TempDir Path parent) throws Exception {
        Path file = parent.resolve("not-a-directory");
        Files.write(file, new byte[] {1});
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () -> new TerminalWal(file, "test-instance", "test-boot", keys));
        assertEquals(ErrorCode.AUDIT_UNAVAILABLE, failure.errorCode());
    }

    @Test
    void smallSegmentsReplayInCreationOrder() throws Exception {
        AuditRepository repository = repository();
        List<UUID> expected = new ArrayList<>();
        try (TerminalWal wal =
                new TerminalWal(walDirectory, "test-instance", "test-boot", keys, true, 1)) {
            for (int index = 0; index < 3; index++) {
                UUID requestId = UUID.randomUUID();
                expected.add(requestId);
                wal.append(
                        terminal(
                                requestId,
                                pastMinutes(30).plusMillis(index),
                                "SELECT 'frame-" + index + "'"));
            }
        }
        assertEquals(3, segmentCount(walDirectory));

        List<UUID> ingested = new ArrayList<>();
        WalReplayer.ReplayReport report =
                new WalReplayer()
                        .replay(
                                walDirectory,
                                keys,
                                terminal -> {
                                    ingested.add(terminal.requestId());
                                    return repository.submitTerminal(terminal);
                                });
        assertEquals(3, report.segmentsReplayed());
        assertEquals(3, report.framesIngested());
        assertEquals(expected, ingested);
    }

    private void crash(String mode, int frames) throws Exception {
        String java =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name", "").toLowerCase().contains("win")
                                        ? "java.exe"
                                        : "java")
                        .toString();
        ProcessBuilder builder =
                new ProcessBuilder(
                        java,
                        "-cp",
                        System.getProperty("java.class.path"),
                        "local.ptmcp.persistence.audit.wal.WalCrashChild",
                        mode,
                        walDirectory.toString(),
                        key.keyId(),
                        Base64.getEncoder().encodeToString(key.aesKey()),
                        String.valueOf(frames));
        builder.redirectErrorStream(true);
        Process child = builder.start();
        byte[] output;
        try (var stream = child.getInputStream()) {
            output = stream.readAllBytes();
        }
        assertTrue(child.waitFor(120, TimeUnit.SECONDS), "crash child timed out");
        assertEquals(0, child.exitValue(), new String(output, StandardCharsets.UTF_8));
    }

    private static int frameStartPosition(Path segment) throws IOException {
        byte[] bytes = Files.readAllBytes(segment);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try {
            WalFormat.decodeSegmentHeader(buffer);
        } catch (WalFormat.CorruptFrameException failure) {
            throw new IllegalStateException(failure);
        }
        return buffer.position();
    }

    private static Path onlySegment(Path directory) throws IOException {
        List<Path> segments = segments(directory);
        assertEquals(1, segments.size());
        return segments.get(0);
    }

    private static int segmentCount(Path directory) throws IOException {
        return segments(directory).size();
    }

    private static List<Path> segments(Path directory) throws IOException {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "wal-*")) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(".wal") || name.endsWith(".open")) {
                    found.add(file);
                }
            }
        }
        return found;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        for (int start = 0; start <= haystack.length - needle.length; start++) {
            if (Arrays.equals(haystack, start, start + needle.length, needle, 0, needle.length)) {
                return start;
            }
        }
        return -1;
    }

    private static AuditStart start(UUID requestId, Instant startedAt) {
        return new AuditStart(
                requestId,
                7L,
                3L,
                "execute_readonly_sql",
                WalPayload.sha256("SELECT id FROM orders".getBytes(StandardCharsets.UTF_8)),
                22,
                startedAt);
    }

    @Test
    void payloadRoundTripsFinalCiphertextAndReadsLegacyV1Frames() throws Exception {
        byte[] raw = "sealed-raw".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] fin = "sealed-final".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        AuditTerminal withFinal =
                new AuditTerminal(
                        UUID.randomUUID(),
                        AuditTerminal.Result.SUCCESS,
                        AuditTerminal.Verdict.ALLOW,
                        null,
                        5L,
                        null,
                        null,
                        false,
                        "SELECT ?",
                        null,
                        raw,
                        "local:audit",
                        "execute_readonly_sql",
                        7L,
                        3L,
                        1L,
                        pastMinutes(1),
                        fin);
        byte[] encoded = WalPayload.encode(withFinal);
        AuditTerminal decoded = WalPayload.decode(WalPayload.SCHEMA_VERSION, encoded);
        assertEquals("local:audit", decoded.keyId());
        assertEquals(
                "sealed-raw",
                new String(decoded.encryptedRaw(), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(
                "sealed-final",
                new String(decoded.encryptedFinal(), java.nio.charset.StandardCharsets.UTF_8));

        // Schema-1 frames (pre-upgrade crash) carry no final field; the version varint is the
        // only difference — patch it back to 1 and decode must yield a null final form.
        byte[] legacy = encoded.clone();
        legacy[0] = 1;
        AuditTerminal legacyDecoded = WalPayload.decode(WalPayload.SCHEMA_VERSION, legacy);
        assertNull(legacyDecoded.encryptedFinal());
        assertEquals(
                "sealed-raw",
                new String(legacyDecoded.encryptedRaw(), java.nio.charset.StandardCharsets.UTF_8));

        // Unknown future schemas stay quarantined.
        byte[] future = encoded.clone();
        future[0] = 9;
        assertThrows(
                IllegalArgumentException.class,
                () -> WalPayload.decode(WalPayload.SCHEMA_VERSION, future));
    }

    private static AuditTerminal terminal(UUID requestId, Instant startedAt, String maskedSql) {
        return new AuditTerminal(
                requestId,
                AuditTerminal.Result.SUCCESS,
                AuditTerminal.Verdict.ALLOW,
                null,
                12L,
                1,
                3,
                false,
                maskedSql,
                null,
                null,
                null,
                "execute_readonly_sql",
                7L,
                3L,
                1L,
                startedAt);
    }

    private static Instant pastMinutes(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    }

    private static void assertOutcome(
            Connection connection, UUID requestId, String result, String verdict, String errorCode)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT result, verdict, error_code FROM audit_log WHERE request_id = ?")) {
            statement.setString(1, requestId.toString());
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertEquals(result, rows.getString(1));
                assertEquals(verdict, rows.getString(2));
                assertEquals(errorCode, rows.getString(3));
            }
        }
    }

    private static int count(Connection connection, String where) throws SQLException {
        return (int) scalar(connection, "SELECT COUNT(*) FROM audit_log " + where);
    }

    private static long scalar(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static String string(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static int update(Connection connection, String sql, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < parameters.length; index++) {
                statement.setObject(index + 1, parameters[index]);
            }
            return statement.executeUpdate();
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static AuditRepository repository() {
        return new AuditRepository(
                (DataSource)
                        java.lang.reflect.Proxy.newProxyInstance(
                                DataSource.class.getClassLoader(),
                                new Class<?>[] {DataSource.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getConnection")) {
                                        return open();
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                }));
    }
}
