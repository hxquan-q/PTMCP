package local.ptmcp.persistence.audit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** Owned MySQL 8.4 container; neither mocks nor missing-Docker skips prove durable admission. */
class AuditRepositoryTest {
    private static final String SENSITIVE_RAW = "synthetic-secret-card-4111111111111111";
    private static MySQLContainer mysql;

    @BeforeAll
    static void startSyntheticMetadataDatabase() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69")
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
    void startAdmissionIsMinimalAndVisibleFromFreshConnection() throws Exception {
        UUID requestId = UUID.randomUUID();
        Instant startedAt = pastMinutes(30);
        byte[] hash = sha256("SELECT card_number FROM payment WHERE customer = 'synthetic'");
        repository()
                .insertStart(
                        new AuditStart(
                                requestId, 7L, 3L, "execute_readonly_sql", hash, 123, startedAt));

        try (Connection fresh = open()) {
            try (PreparedStatement statement =
                    fresh.prepareStatement(
                            "SELECT event_kind, seq, payload_hash, event_payload, published_at "
                                    + "FROM audit_outbox WHERE request_id = ?")) {
                statement.setString(1, requestId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("START", result.getString(1));
                    assertEquals(0, result.getInt(2));
                    assertArrayEquals(
                            sha256(new String(result.getBytes(4), StandardCharsets.UTF_8)),
                            result.getBytes(3));
                    String payload = new String(result.getBytes(4), StandardCharsets.UTF_8);
                    assertFalse(payload.contains("card_number"));
                    assertFalse(payload.contains("payment"));
                    assertFalse(payload.contains("synthetic"));
                    assertNull(result.getObject(5));
                    assertFalse(result.next());
                }
            }
            try (PreparedStatement statement =
                    fresh.prepareStatement(
                            "SELECT user_id, platform_id, tool, datasource_id, started_at, "
                                    + "completed_at, result, verdict, error_code, raw_length, "
                                    + "truncated, sql_masked FROM audit_log WHERE request_id = ?"); ) {
                statement.setString(1, requestId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(7L, result.getLong(1));
                    assertEquals(3L, result.getLong(2));
                    assertEquals("execute_readonly_sql", result.getString(3));
                    assertNull(result.getObject(4));
                    assertEquals(utc(startedAt), result.getObject(5, LocalDateTime.class));
                    assertNull(result.getObject(6));
                    assertEquals("RUNNING", result.getString(7));
                    assertEquals("UNKNOWN", result.getString(8));
                    assertNull(result.getString(9));
                    assertEquals(123, result.getInt(10));
                    assertFalse(result.getBoolean(11));
                    assertNull(result.getString(12));
                    assertFalse(result.next());
                }
            }
        }
    }

    @Test
    void repeatedStartIsIdempotentAndChangedPayloadIsRejected() throws Exception {
        UUID requestId = UUID.randomUUID();
        Instant startedAt = pastMinutes(30);
        byte[] hash = sha256("SELECT 1");
        AuditStart start =
                new AuditStart(requestId, 7L, null, "execute_readonly_sql", hash, 8, startedAt);
        AuditRepository repository = repository();
        repository.insertStart(start);
        repository.insertStart(start);

        try (Connection connection = open()) {
            assertEquals(1, count(connection, "audit_outbox", requestId));
            assertEquals(1, count(connection, "audit_log", requestId));
        }

        AuditStart changed =
                new AuditStart(requestId, 7L, null, "execute_readonly_sql", hash, 9, startedAt);
        assertSanitized(
                assertThrows(DomainException.class, () -> repository.insertStart(changed)),
                ErrorCode.IDEMPOTENCY_KEY_REUSED);
        try (Connection connection = open()) {
            assertEquals(1, count(connection, "audit_outbox", requestId));
            assertEquals(1, count(connection, "audit_log", requestId));
            assertEquals(
                    8,
                    scalar(
                            connection,
                            "SELECT raw_length FROM audit_log WHERE request_id = ?",
                            requestId.toString()));
        }
    }

    @Test
    void terminalIsDurableIdempotentAndNeverOverwritesSuccess() throws Exception {
        UUID requestId = UUID.randomUUID();
        Instant startedAt = pastMinutes(30);
        AuditRepository repository = repository();
        repository.insertStart(
                new AuditStart(
                        requestId,
                        7L,
                        3L,
                        "execute_readonly_sql",
                        sha256("SELECT id FROM orders"),
                        22,
                        startedAt));

        AuditTerminal success = terminal(requestId, startedAt, AuditTerminal.Result.SUCCESS);
        AuditRepository.TerminalState first = repository.submitTerminal(success);
        assertEquals(AuditTerminal.Result.SUCCESS, first.result());
        assertEquals(AuditTerminal.Verdict.ALLOW, first.verdict());
        assertFalse(first.alreadyExisted());
        assertFalse(first.conflict());
        assertNotNull(first.completedAt());

        byte[] firstTerminalHash;
        try (Connection connection = open()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT result, verdict, error_code, duration_ms, rows_returned, "
                                    + "truncated, sql_masked, terminal_hash FROM audit_log "
                                    + "WHERE request_id = ?"); ) {
                statement.setString(1, requestId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("SUCCESS", result.getString(1));
                    assertEquals("ALLOW", result.getString(2));
                    assertNull(result.getString(3));
                    assertEquals(12L, result.getLong(4));
                    assertEquals(3, result.getInt(5));
                    assertFalse(result.getBoolean(6));
                    assertEquals(
                            "SELECT id FROM orders WHERE customer_id = ?", result.getString(7));
                    firstTerminalHash = result.getBytes(8);
                    assertNotNull(firstTerminalHash);
                }
            }
            assertEquals(2, totalOutbox(connection, requestId));
            assertEquals(1, terminalOutbox(connection, requestId));
        }

        AuditTerminal conflicting =
                new AuditTerminal(
                        requestId,
                        AuditTerminal.Result.ERROR,
                        AuditTerminal.Verdict.ERROR,
                        "QUERY_TIMEOUT",
                        99L,
                        2,
                        0,
                        true,
                        "SELECT id FROM orders WHERE customer_id = ?",
                        sha256("fingerprint"),
                        null,
                        null,
                        "execute_readonly_sql",
                        7L,
                        3L,
                        1L,
                        startedAt);
        AuditRepository.TerminalState second = repository.submitTerminal(conflicting);
        assertEquals(AuditTerminal.Result.SUCCESS, second.result());
        assertEquals(AuditTerminal.Verdict.ALLOW, second.verdict());
        assertTrue(second.alreadyExisted());
        assertTrue(second.conflict());

        AuditRepository.TerminalState replay = repository.submitTerminal(success);
        assertEquals(AuditTerminal.Result.SUCCESS, replay.result());
        assertTrue(replay.alreadyExisted());
        assertFalse(replay.conflict());

        try (Connection connection = open()) {
            assertEquals(1, count(connection, "audit_log", requestId));
            assertEquals(2, totalOutbox(connection, requestId));
            assertEquals(1, terminalOutbox(connection, requestId));
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT result, verdict, error_code, duration_ms, terminal_hash "
                                    + "FROM audit_log WHERE request_id = ?"); ) {
                statement.setString(1, requestId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("SUCCESS", result.getString(1));
                    assertEquals("ALLOW", result.getString(2));
                    assertNull(result.getString(3));
                    assertEquals(12L, result.getLong(4));
                    assertArrayEquals(firstTerminalHash, result.getBytes(5));
                }
            }
        }
    }

    @Test
    void terminalBeforeStartCreatesIdentityAndLateStartOnlyFillsBlanks() throws Exception {
        UUID requestId = UUID.randomUUID();
        Instant terminalStartedAt = pastMinutes(20);
        Instant lateStartAt = pastMinutes(10);
        AuditRepository repository = repository();

        AuditTerminal terminal =
                new AuditTerminal(
                        requestId,
                        AuditTerminal.Result.SUCCESS,
                        AuditTerminal.Verdict.ALLOW,
                        null,
                        20L,
                        0,
                        4,
                        false,
                        "SELECT id FROM orders WHERE amount > ?",
                        sha256("fingerprint"),
                        null,
                        null,
                        "execute_readonly_sql",
                        null,
                        null,
                        1L,
                        terminalStartedAt);
        assertEquals(AuditTerminal.Result.SUCCESS, repository.submitTerminal(terminal).result());

        repository.insertStart(
                new AuditStart(
                        requestId,
                        9L,
                        2L,
                        "execute_readonly_sql",
                        sha256("SELECT id FROM orders"),
                        88,
                        lateStartAt));

        try (Connection connection = open()) {
            assertEquals(1, count(connection, "audit_log", requestId));
            assertEquals(2, totalOutbox(connection, requestId));
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT user_id, platform_id, started_at, result, verdict, "
                                    + "error_code, raw_length, sql_masked, completed_at "
                                    + "FROM audit_log WHERE request_id = ?"); ) {
                statement.setString(1, requestId.toString());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(9L, result.getLong(1));
                    assertEquals(2L, result.getLong(2));
                    assertEquals(utc(terminalStartedAt), result.getObject(3, LocalDateTime.class));
                    assertEquals("SUCCESS", result.getString(4));
                    assertEquals("ALLOW", result.getString(5));
                    assertNull(result.getString(6));
                    assertEquals(88, result.getInt(7));
                    assertEquals("SELECT id FROM orders WHERE amount > ?", result.getString(8));
                    assertNotNull(result.getObject(9));
                }
            }
        }
    }

    @Test
    void maskedSqlIsStoredVerbatimWithoutRawSensitiveValues() throws Exception {
        UUID requestId = UUID.randomUUID();
        Instant startedAt = pastMinutes(30);
        String masked = "SELECT id FROM payment WHERE card_number = ?";
        AuditRepository repository = repository();
        repository.insertStart(
                new AuditStart(
                        requestId,
                        null,
                        null,
                        "execute_readonly_sql",
                        sha256(SENSITIVE_RAW),
                        SENSITIVE_RAW.length(),
                        startedAt));
        repository.submitTerminal(
                new AuditTerminal(
                        requestId,
                        AuditTerminal.Result.SUCCESS,
                        AuditTerminal.Verdict.ALLOW,
                        null,
                        5L,
                        0,
                        1,
                        false,
                        masked,
                        null,
                        null,
                        null,
                        "execute_readonly_sql",
                        null,
                        null,
                        null,
                        startedAt));

        try (Connection connection = open()) {
            assertEquals(
                    masked,
                    string(
                            connection,
                            "SELECT sql_masked FROM audit_log WHERE request_id = ?",
                            requestId.toString()));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM audit_log WHERE request_id = ? "
                                    + "AND LOCATE(?, sql_masked) > 0",
                            requestId.toString(),
                            SENSITIVE_RAW));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM audit_outbox WHERE request_id = ? "
                                    + "AND LOCATE(?, CONVERT(event_payload USING utf8mb4)) > 0",
                            requestId.toString(),
                            SENSITIVE_RAW));
        }
    }

    @Test
    void startFailureRollsBackWholeAdmissionAndMapsDependencyUnavailable() throws Exception {
        UUID requestId = UUID.randomUUID();
        AuditStart start =
                new AuditStart(
                        requestId,
                        7L,
                        null,
                        "execute_readonly_sql",
                        sha256("SELECT 1"),
                        8,
                        pastMinutes(30));

        DataSource failingInsert =
                source(
                        connection ->
                                (Connection)
                                        Proxy.newProxyInstance(
                                                Connection.class.getClassLoader(),
                                                new Class<?>[] {Connection.class},
                                                (proxy, method, args) -> {
                                                    if (method.getName().equals("prepareStatement")
                                                            && args != null
                                                            && args[0] instanceof String sql
                                                            && sql.startsWith(
                                                                    "INSERT INTO audit_log ")) {
                                                        throw new SQLException(
                                                                "synthetic-secret password=never-expose");
                                                    }
                                                    return invoke(connection, method, args);
                                                }),
                        new AtomicInteger());
        assertSanitized(
                assertThrows(
                        DomainException.class,
                        () -> new AuditRepository(failingInsert).insertStart(start)),
                ErrorCode.DEPENDENCY_UNAVAILABLE);

        DataSource failingAcquire =
                (DataSource)
                        Proxy.newProxyInstance(
                                DataSource.class.getClassLoader(),
                                new Class<?>[] {DataSource.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("getConnection")) {
                                        throw new SQLException(
                                                "synthetic-secret jdbc:mysql://private-host/db");
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        assertSanitized(
                assertThrows(
                        DomainException.class,
                        () -> new AuditRepository(failingAcquire).insertStart(start)),
                ErrorCode.DEPENDENCY_UNAVAILABLE);

        try (Connection connection = open()) {
            assertEquals(0, count(connection, "audit_outbox", requestId));
            assertEquals(0, count(connection, "audit_log", requestId));
        }
    }

    @Test
    void staleStartBecomesUnknownOnlyWithoutActiveLease() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant oldStart = now.minus(10, ChronoUnit.MINUTES);
        AuditRepository repository = repository();
        UUID noLease = admitted(repository, oldStart);
        UUID activeLease = admitted(repository, oldStart);
        UUID expiredLease = admitted(repository, oldStart);
        UUID completed = admitted(repository, oldStart);
        repository.submitTerminal(terminal(completed, oldStart, AuditTerminal.Result.SUCCESS));

        try (Connection connection = open()) {
            insertLease(connection, activeLease, "RUNNING", now, now.plusSeconds(300));
            insertLease(
                    connection,
                    expiredLease,
                    "CANCELLING",
                    now.minusSeconds(120),
                    now.minusSeconds(60));
        }

        int recovered = repository.markUnknownIfStale(now.minusSeconds(60), 100);
        assertEquals(2, recovered);
        assertEquals(0, repository.markUnknownIfStale(now.minusSeconds(60), 100));

        try (Connection connection = open()) {
            assertOutcome(connection, noLease, "UNKNOWN", "UNKNOWN", "EXECUTION_OUTCOME_UNKNOWN");
            assertOutcome(connection, activeLease, "RUNNING", "UNKNOWN", null);
            assertOutcome(
                    connection, expiredLease, "UNKNOWN", "UNKNOWN", "EXECUTION_OUTCOME_UNKNOWN");
            assertOutcome(connection, completed, "SUCCESS", "ALLOW", null);
            assertEquals(1, terminalOutbox(connection, noLease));
            assertEquals(0, terminalOutbox(connection, activeLease));
            assertEquals(1, terminalOutbox(connection, expiredLease));
            assertEquals(1, terminalOutbox(connection, completed));
        }
    }

    @Test
    void invalidInputNeverAcquiresConnection() {
        AtomicInteger acquisitions = new AtomicInteger();
        AuditRepository repository =
                new AuditRepository(source(connection -> connection, acquisitions));
        UUID requestId = UUID.randomUUID();
        Instant startedAt = pastMinutes(30);
        List<Runnable> invalidStarts =
                List.of(
                        () ->
                                new AuditStart(
                                        requestId,
                                        1L,
                                        null,
                                        "drop_everything",
                                        null,
                                        null,
                                        startedAt),
                        () ->
                                new AuditStart(
                                        requestId,
                                        0L,
                                        null,
                                        "execute_readonly_sql",
                                        null,
                                        null,
                                        startedAt),
                        () ->
                                new AuditStart(
                                        requestId,
                                        1L,
                                        null,
                                        "execute_readonly_sql",
                                        new byte[31],
                                        8,
                                        startedAt),
                        () ->
                                new AuditStart(
                                        requestId,
                                        1L,
                                        null,
                                        "execute_readonly_sql",
                                        null,
                                        8,
                                        startedAt),
                        () ->
                                new AuditStart(
                                        requestId,
                                        1L,
                                        null,
                                        "execute_readonly_sql",
                                        sha256("SELECT 1"),
                                        null,
                                        startedAt),
                        () ->
                                new AuditStart(
                                        requestId,
                                        1L,
                                        null,
                                        "execute_readonly_sql",
                                        sha256("SELECT 1"),
                                        -1,
                                        startedAt),
                        () ->
                                new AuditStart(
                                        requestId,
                                        1L,
                                        null,
                                        "execute_readonly_sql",
                                        sha256("SELECT 1"),
                                        1_048_577,
                                        startedAt),
                        () ->
                                new AuditStart(
                                        requestId,
                                        1L,
                                        null,
                                        "execute_readonly_sql",
                                        null,
                                        null,
                                        null),
                        () -> repository.insertStart(null));
        List<Runnable> invalidTerminals =
                List.of(
                        () -> terminal(requestId, startedAt, AuditTerminal.Result.RUNNING),
                        () ->
                                new AuditTerminal(
                                        requestId,
                                        AuditTerminal.Result.ERROR,
                                        AuditTerminal.Verdict.ERROR,
                                        "lower_case",
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "execute_readonly_sql",
                                        null,
                                        null,
                                        null,
                                        startedAt),
                        () ->
                                new AuditTerminal(
                                        requestId,
                                        AuditTerminal.Result.SUCCESS,
                                        AuditTerminal.Verdict.ALLOW,
                                        null,
                                        -1L,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "execute_readonly_sql",
                                        null,
                                        null,
                                        null,
                                        startedAt),
                        () ->
                                new AuditTerminal(
                                        requestId,
                                        AuditTerminal.Result.SUCCESS,
                                        AuditTerminal.Verdict.ALLOW,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        new byte[31],
                                        null,
                                        null,
                                        "execute_readonly_sql",
                                        null,
                                        null,
                                        null,
                                        startedAt),
                        () ->
                                new AuditTerminal(
                                        requestId,
                                        AuditTerminal.Result.SUCCESS,
                                        AuditTerminal.Verdict.ALLOW,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null,
                                        new byte[] {1},
                                        null,
                                        "execute_readonly_sql",
                                        null,
                                        null,
                                        null,
                                        startedAt),
                        () ->
                                new AuditTerminal(
                                        requestId,
                                        AuditTerminal.Result.SUCCESS,
                                        AuditTerminal.Verdict.ALLOW,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null,
                                        null,
                                        "key-1",
                                        "execute_readonly_sql",
                                        null,
                                        null,
                                        null,
                                        startedAt),
                        () -> repository.submitTerminal(null),
                        () -> repository.markUnknownIfStale(null, 10),
                        () -> repository.markUnknownIfStale(startedAt, 0),
                        () -> repository.markUnknownIfStale(startedAt, 1001));
        List<Runnable> invalid = new ArrayList<>(invalidStarts);
        invalid.addAll(invalidTerminals);
        for (Runnable action : invalid) {
            assertSanitized(
                    assertThrows(DomainException.class, action::run), ErrorCode.INVALID_ARGUMENT);
        }
        assertEquals(0, acquisitions.get());
    }

    @Test
    void concurrentTerminalsLeaveOneDurableOutcome() throws Exception {
        UUID requestId = UUID.randomUUID();
        Instant startedAt = pastMinutes(30);
        AuditRepository repository = repository();
        repository.insertStart(
                new AuditStart(
                        requestId,
                        7L,
                        null,
                        "execute_readonly_sql",
                        sha256("SELECT id FROM orders"),
                        22,
                        startedAt));
        AuditTerminal success = terminal(requestId, startedAt, AuditTerminal.Result.SUCCESS);
        AuditTerminal failure =
                new AuditTerminal(
                        requestId,
                        AuditTerminal.Result.ERROR,
                        AuditTerminal.Verdict.ERROR,
                        "QUERY_TIMEOUT",
                        50L,
                        0,
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        "execute_readonly_sql",
                        7L,
                        null,
                        null,
                        startedAt);

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () -> {
                                assertTrue(start.await(10, TimeUnit.SECONDS));
                                return repository.submitTerminal(success);
                            });
            var second =
                    pool.submit(
                            () -> {
                                assertTrue(start.await(10, TimeUnit.SECONDS));
                                return repository.submitTerminal(failure);
                            });
            start.countDown();
            AuditRepository.TerminalState left = first.get(20, TimeUnit.SECONDS);
            AuditRepository.TerminalState right = second.get(20, TimeUnit.SECONDS);
            assertEquals(left.result(), right.result());
            assertEquals(left.verdict(), right.verdict());
            assertEquals(left.errorCode(), right.errorCode());
            assertTrue(left.alreadyExisted() != right.alreadyExisted());
        }

        try (Connection connection = open()) {
            assertEquals(1, terminalOutbox(connection, requestId));
            String stored =
                    string(
                            connection,
                            "SELECT result FROM audit_log WHERE request_id = ?",
                            requestId.toString());
            assertTrue(stored.equals("SUCCESS") || stored.equals("ERROR"));
        }
    }

    private static Instant pastMinutes(long minutes) {
        return Instant.now().minus(minutes, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
    }

    private static UUID admitted(AuditRepository repository, Instant startedAt) {
        UUID requestId = UUID.randomUUID();
        repository.insertStart(
                new AuditStart(
                        requestId,
                        7L,
                        null,
                        "execute_readonly_sql",
                        sha256("SELECT id FROM orders"),
                        22,
                        startedAt));
        return requestId;
    }

    private static AuditTerminal terminal(
            UUID requestId, Instant startedAt, AuditTerminal.Result result) {
        boolean success = result == AuditTerminal.Result.SUCCESS;
        return new AuditTerminal(
                requestId,
                result,
                success ? AuditTerminal.Verdict.ALLOW : AuditTerminal.Verdict.ERROR,
                success ? null : "QUERY_TIMEOUT",
                success ? 12L : 50L,
                1,
                success ? 3 : null,
                false,
                "SELECT id FROM orders WHERE customer_id = ?",
                sha256("fingerprint"),
                null,
                null,
                "execute_readonly_sql",
                7L,
                null,
                1L,
                startedAt);
    }

    private static void insertLease(
            Connection connection,
            UUID requestId,
            String state,
            Instant heartbeat,
            Instant leaseUntil)
            throws SQLException {
        update(
                connection,
                "INSERT INTO execution_lease (request_id, user_id, datasource_id, "
                        + "gateway_instance_id, boot_id, state, execution_started_at, heartbeat_at, "
                        + "lease_until, create_time, update_time) "
                        + "VALUES (?, 7, 1, 'gateway-test', ?, ?, "
                        + "DATE_SUB(?, INTERVAL 1 MINUTE), ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                requestId.toString(),
                UUID.randomUUID().toString(),
                state,
                utc(heartbeat),
                utc(heartbeat),
                utc(leaseUntil));
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

    private static int count(Connection connection, String table, UUID requestId)
            throws SQLException {
        return (int)
                scalar(
                        connection,
                        "SELECT COUNT(*) FROM " + table + " WHERE request_id = ?",
                        requestId.toString());
    }

    private static int totalOutbox(Connection connection, UUID requestId) throws SQLException {
        return count(connection, "audit_outbox", requestId);
    }

    private static int terminalOutbox(Connection connection, UUID requestId) throws SQLException {
        return (int)
                scalar(
                        connection,
                        "SELECT COUNT(*) FROM audit_outbox WHERE request_id = ? "
                                + "AND event_kind = 'TERMINAL'",
                        requestId.toString());
    }

    private static void assertSanitized(DomainException failure, ErrorCode code) {
        assertEquals(code, failure.errorCode());
        assertEquals(code.name(), failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
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

    private static AuditRepository repository() {
        return new AuditRepository(source(connection -> connection, new AtomicInteger()));
    }

    @FunctionalInterface
    private interface ConnectionDecorator {
        Connection apply(Connection connection) throws SQLException;
    }

    private static DataSource source(ConnectionDecorator decorator, AtomicInteger acquisitions) {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getConnection")) {
                                acquisitions.incrementAndGet();
                                return decorator.apply(open());
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static Object invoke(
            Connection connection, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(connection, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
