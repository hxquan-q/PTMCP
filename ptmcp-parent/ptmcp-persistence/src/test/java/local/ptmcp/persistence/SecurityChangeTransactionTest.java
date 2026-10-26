package local.ptmcp.persistence;

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
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** Owned MySQL 8.4 container; neither mocks nor missing-Docker skips prove atomicity. */
class SecurityChangeTransactionTest {
    private static MySQLContainer mysql;

    @BeforeAll
    static void startSyntheticMetadataDatabase() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("security_tx_test")
                        .withUsername("tx_test")
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
            // Test-only stand-in for future trusted user/ACL repositories, never a V1 business
            // table.
            try (Connection connection = open();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "CREATE TABLE synthetic_security_aggregate (id BIGINT NOT NULL PRIMARY KEY, "
                                            + "version BIGINT NOT NULL CHECK (version > 0), "
                                            + "create_time DATETIME(6) NOT NULL, update_time DATETIME(6) NOT NULL) ENGINE=InnoDB")) {
                statement.executeUpdate();
            }
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
    void resetOnlyOwnedSyntheticData() throws Exception {
        try (Connection connection = open()) {
            update(connection, "DELETE FROM sys_security_event");
            update(connection, "DELETE FROM synthetic_security_aggregate");
            update(connection, "DELETE FROM sys_security_state");
            update(
                    connection,
                    "INSERT INTO sys_security_state "
                            + "(singleton_id, policy_epoch, create_time, update_time) "
                            + "VALUES (1, 1, '2000-01-01 00:00:00', '2000-01-01 00:00:00')");
            update(
                    connection,
                    "INSERT INTO synthetic_security_aggregate "
                            + "(id, version, create_time, update_time) "
                            + "VALUES (1, 1, '2000-01-01 00:00:00', '2000-01-01 00:00:00'), "
                            + "(2, 1, '2000-01-01 00:00:00', '2000-01-01 00:00:00')");
        }
    }

    @Test
    void confirmedReceiptMatchesRowEpochAndRestrictedEventFromFreshConnection() throws Exception {
        SecurityEvent event = event(UUID.randomUUID(), 2);
        AtomicInteger invocations = new AtomicInteger();
        var tx =
                new SecurityChangeTransaction(
                        source(
                                connection -> {
                                    update(connection, "SET time_zone = '+09:00'");
                                    connection.setTransactionIsolation(
                                            Connection.TRANSACTION_SERIALIZABLE);
                                    connection.setReadOnly(true);
                                    return connection;
                                },
                                new AtomicInteger()));
        var receipt =
                tx.execute(
                        event,
                        connection -> {
                            invocations.incrementAndGet();
                            assertFalse(connection.getAutoCommit());
                            assertFalse(connection.isReadOnly());
                            assertEquals(
                                    Connection.TRANSACTION_READ_COMMITTED,
                                    connection.getTransactionIsolation());
                            try (PreparedStatement statement =
                                            connection.prepareStatement(
                                                    "SELECT @@session.time_zone, @@session.transaction_isolation, @@session.transaction_read_only");
                                    ResultSet result = statement.executeQuery()) {
                                assertTrue(result.next());
                                assertEquals("+00:00", result.getString(1));
                                assertEquals("READ-COMMITTED", result.getString(2));
                                assertEquals(0, result.getInt(3));
                            }
                            assertEquals(
                                    1,
                                    scalar(
                                            connection,
                                            "SELECT policy_epoch FROM sys_security_state WHERE singleton_id = 1"));
                            // NOWAIT on a second real connection proves the global lock is
                            // already held before the very first repository row access.
                            assertGlobalRowLocked();
                            changeRow(connection);
                            // Uncommitted repository work is invisible to an independent
                            // transaction.
                            assertState(1, 1, 0);
                        });
        assertEquals(1, invocations.get());
        assertEquals(event.eventId(), receipt.eventId());
        assertEquals(2, receipt.epoch());
        assertFalse(receipt.cleanupFailed());
        assertState(2, 2, 1);
        try (Connection connection = open();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT event_id, epoch, aggregate_type, aggregate_id, event_type, "
                                        + "JSON_LENGTH(payload), JSON_UNQUOTE(JSON_EXTRACT(payload, '$.aggregateId')), "
                                        + "JSON_EXTRACT(payload, '$.version'), JSON_EXTRACT(payload, '$.schemaVersion'), "
                                        + "occurred_at = create_time AND create_time = update_time, "
                                        + "published_at, attempts, occurred_at <= UTC_TIMESTAMP(6) "
                                        + "FROM sys_security_event WHERE event_id = ?")) {
            statement.setString(1, event.eventId().toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(event.eventId().toString(), result.getString(1));
                assertEquals(receipt.epoch(), result.getLong(2));
                assertEquals("USER", result.getString(3));
                assertEquals("1", result.getString(4));
                assertEquals("USER_SECURITY_CHANGED", result.getString(5));
                assertEquals(3, result.getInt(6));
                assertEquals("1", result.getString(7));
                assertEquals(2, result.getLong(8));
                assertEquals(1, result.getLong(9));
                assertTrue(result.getBoolean(10));
                assertNull(result.getObject(11));
                assertEquals(0, result.getInt(12));
                assertTrue(result.getBoolean(13));
                assertFalse(result.next());
            }
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM sys_security_state "
                                    + "WHERE update_time > create_time AND update_time <= UTC_TIMESTAMP(6)"));
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM synthetic_security_aggregate "
                                    + "WHERE id = 1 AND update_time > create_time AND update_time <= UTC_TIMESTAMP(6)"));
        }
    }

    @Test
    void uuidAggregateUsesOnlyCanonicalUuidAndVersionPayload() throws Exception {
        UUID family = UUID.randomUUID();
        transaction()
                .execute(
                        new SecurityEvent(
                                UUID.randomUUID(),
                                SecurityEvent.Kind.REFRESH_FAMILY_CHANGED,
                                new SecurityEvent.UuidId(family),
                                1),
                        SecurityChangeTransactionTest::changeRow);
        try (Connection connection = open();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT aggregate_type, aggregate_id, JSON_LENGTH(payload), "
                                        + "JSON_UNQUOTE(JSON_EXTRACT(payload, '$.aggregateId')) FROM sys_security_event");
                ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals("REFRESH_FAMILY", result.getString(1));
            assertEquals(family.toString(), result.getString(2));
            assertEquals(3, result.getInt(3));
            assertEquals(family.toString(), result.getString(4));
        }
    }

    @Test
    void callbackSqlAndRuntimeFailureRollBackAndDoNotLeakInfrastructureText() throws Exception {
        for (boolean sqlFailure : List.of(true, false)) {
            DomainException failure =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    transaction()
                                            .execute(
                                                    event(UUID.randomUUID(), 2),
                                                    connection -> {
                                                        changeRow(connection);
                                                        if (sqlFailure) {
                                                            throw new SQLException(
                                                                    "synthetic-secret raw SQL password=never-expose");
                                                        }
                                                        throw new IllegalStateException(
                                                                "synthetic-secret raw SQL password=never-expose");
                                                    }));
            assertSanitized(
                    failure,
                    sqlFailure ? ErrorCode.DEPENDENCY_UNAVAILABLE : ErrorCode.INTERNAL_ERROR);
            assertState(1, 1, 0);
        }
    }

    @Test
    void duplicateEventIdRollsBackRepositoryRowAndEpochNotJustEventInsert() throws Exception {
        SecurityEvent original = event(UUID.randomUUID(), 2);
        transaction().execute(original, SecurityChangeTransactionTest::changeRow);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                transaction()
                                        .execute(
                                                event(original.eventId(), 3),
                                                SecurityChangeTransactionTest::changeRow));
        assertSanitized(failure, ErrorCode.IDEMPOTENCY_KEY_REUSED);
        assertState(2, 2, 1);
    }

    @Test
    void missingSingletonAndExhaustedEpochRejectBeforeRepositoryCallback() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        try (Connection connection = open()) {
            update(
                    connection,
                    "UPDATE sys_security_state SET policy_epoch = ?, update_time = UTC_TIMESTAMP(6) WHERE singleton_id = 1",
                    Long.MAX_VALUE);
        }
        assertSanitized(
                assertThrows(
                        DomainException.class,
                        () ->
                                transaction()
                                        .execute(
                                                event(UUID.randomUUID(), 2),
                                                connection -> callbacks.incrementAndGet())),
                ErrorCode.SECURITY_STATE_UNAVAILABLE);
        assertState(1, Long.MAX_VALUE, 0);
        try (Connection connection = open()) {
            update(connection, "DELETE FROM sys_security_state");
        }
        assertSanitized(
                assertThrows(
                        DomainException.class,
                        () ->
                                transaction()
                                        .execute(
                                                event(UUID.randomUUID(), 2),
                                                connection -> callbacks.incrementAndGet())),
                ErrorCode.SECURITY_STATE_UNAVAILABLE);
        assertEquals(0, callbacks.get());
        try (Connection connection = open()) {
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM sys_security_state"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM sys_security_event"));
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT version FROM synthetic_security_aggregate WHERE id = 1"));
        }
    }

    @Test
    void lastRepresentableEpochCommitsWithoutWrappingAndNextChangeFailsClosed() throws Exception {
        try (Connection connection = open()) {
            update(
                    connection,
                    "UPDATE sys_security_state SET policy_epoch = ?, update_time = UTC_TIMESTAMP(6) WHERE singleton_id = 1",
                    Long.MAX_VALUE - 1);
        }
        var receipt =
                transaction()
                        .execute(
                                event(UUID.randomUUID(), 2),
                                SecurityChangeTransactionTest::changeRow);
        assertEquals(Long.MAX_VALUE, receipt.epoch());
        assertState(2, Long.MAX_VALUE, 1);
        assertSanitized(
                assertThrows(
                        DomainException.class,
                        () ->
                                transaction()
                                        .execute(
                                                event(UUID.randomUUID(), 3),
                                                SecurityChangeTransactionTest::changeRow)),
                ErrorCode.SECURITY_STATE_UNAVAILABLE);
        assertState(2, Long.MAX_VALUE, 1);
    }

    @Test
    void readOnlyMetadataEndpointIsRejectedBeforeAnyRepositoryWork() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        // Root is only for toggling this disposable container's global state, never ptmcp_dev.
        try (Connection owner =
                DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword())) {
            for (boolean superReadOnly : List.of(false, true)) {
                try {
                    update(
                            owner,
                            superReadOnly
                                    ? "SET GLOBAL super_read_only = ON"
                                    : "SET GLOBAL read_only = ON");
                    assertSanitized(
                            assertThrows(
                                    DomainException.class,
                                    () ->
                                            transaction()
                                                    .execute(
                                                            event(UUID.randomUUID(), 2),
                                                            connection ->
                                                                    callbacks.incrementAndGet())),
                            ErrorCode.SECURITY_STATE_UNAVAILABLE);
                    assertState(1, 1, 0);
                } finally {
                    update(owner, "SET GLOBAL super_read_only = OFF");
                    update(owner, "SET GLOBAL read_only = OFF");
                }
            }
        }
        assertEquals(0, callbacks.get());
    }

    @Test
    void illegalInputCannotAcquireConnection() {
        AtomicInteger acquisitions = new AtomicInteger();
        var tx = new SecurityChangeTransaction(source(connection -> connection, acquisitions));
        for (Runnable invalid :
                List.<Runnable>of(
                        () -> new SecurityEvent.NumericId(0),
                        () -> new SecurityEvent.NumericId(-1),
                        () -> new SecurityEvent.UuidId(null),
                        () ->
                                new SecurityEvent(
                                        null,
                                        SecurityEvent.Kind.USER_SECURITY_CHANGED,
                                        new SecurityEvent.NumericId(1),
                                        1),
                        () ->
                                new SecurityEvent(
                                        UUID.randomUUID(), null, new SecurityEvent.NumericId(1), 1),
                        () ->
                                new SecurityEvent(
                                        UUID.randomUUID(), SecurityEvent.Kind.ACL_CHANGED, null, 1),
                        () ->
                                new SecurityEvent(
                                        UUID.randomUUID(),
                                        SecurityEvent.Kind.ACL_CHANGED,
                                        new SecurityEvent.NumericId(1),
                                        0),
                        () ->
                                new SecurityEvent(
                                        UUID.randomUUID(),
                                        SecurityEvent.Kind.ACL_CHANGED,
                                        new SecurityEvent.NumericId(1),
                                        -1),
                        () ->
                                new SecurityEvent(
                                        UUID.randomUUID(),
                                        SecurityEvent.Kind.REFRESH_FAMILY_CHANGED,
                                        new SecurityEvent.NumericId(1),
                                        1),
                        () ->
                                new SecurityEvent(
                                        UUID.randomUUID(),
                                        SecurityEvent.Kind.USER_SECURITY_CHANGED,
                                        new SecurityEvent.UuidId(UUID.randomUUID()),
                                        1),
                        () -> tx.execute(null, connection -> changeRow(connection)),
                        () -> tx.execute(event(UUID.randomUUID(), 2), null))) {
            assertSanitized(
                    assertThrows(DomainException.class, invalid::run), ErrorCode.INVALID_ARGUMENT);
        }
        assertEquals(0, acquisitions.get());
        assertNotNull(event(UUID.randomUUID(), Long.MAX_VALUE));
        assertNotNull(new SecurityEvent.NumericId(Long.MAX_VALUE));
    }

    @Test
    void concurrentIndependentAggregatesCannotLoseEpochOrRunBeforeGlobalLock() throws Exception {
        int commands = 12;
        try (Connection connection = open()) {
            for (long id = 3; id <= commands; id++) {
                update(
                        connection,
                        "INSERT INTO synthetic_security_aggregate "
                                + "(id, version, create_time, update_time) VALUES (?, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                        id);
            }
        }
        Set<Long> epochs = new HashSet<>();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool =
                new ThreadPoolExecutor(
                        4, 4, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>())) {
            var futures =
                    new ArrayList<java.util.concurrent.Future<SecurityChangeTransaction.Receipt>>();
            for (long id = 1; id <= commands; id++) {
                long aggregateId = id;
                futures.add(
                        pool.submit(
                                () -> {
                                    assertTrue(start.await(10, TimeUnit.SECONDS));
                                    return transaction()
                                            .execute(
                                                    new SecurityEvent(
                                                            UUID.randomUUID(),
                                                            SecurityEvent.Kind
                                                                    .USER_SECURITY_CHANGED,
                                                            new SecurityEvent.NumericId(
                                                                    aggregateId),
                                                            2),
                                                    connection -> {
                                                        assertGlobalRowLocked();
                                                        assertEquals(
                                                                1,
                                                                update(
                                                                        connection,
                                                                        "UPDATE synthetic_security_aggregate SET version = ?, update_time = UTC_TIMESTAMP(6) "
                                                                                + "WHERE id = ? AND version = ?",
                                                                        2,
                                                                        aggregateId,
                                                                        1));
                                                    });
                                }));
            }
            start.countDown();
            for (var future : futures) {
                assertTrue(epochs.add(future.get(20, TimeUnit.SECONDS).epoch()));
            }
        }
        assertEquals(commands, epochs.size());
        for (long epoch = 2; epoch <= commands + 1; epoch++) {
            assertTrue(epochs.contains(epoch));
        }
        assertState(2, commands + 1, commands);
        try (Connection connection = open()) {
            assertEquals(
                    commands,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM sys_security_event e JOIN synthetic_security_aggregate a "
                                    + "ON e.aggregate_id = CAST(a.id AS CHAR) "
                                    + "WHERE a.version = 2 AND JSON_EXTRACT(e.payload, '$.version') = a.version"));
        }
    }

    @Test
    void commitExceptionIsUnknownWhetherServerCommittedOrNotAndNeverRetried() throws Exception {
        for (boolean serverCommitted : List.of(false, true)) {
            AtomicInteger commits = new AtomicInteger();
            var tx =
                    new SecurityChangeTransaction(
                            source(
                                    connection ->
                                            (Connection)
                                                    Proxy.newProxyInstance(
                                                            Connection.class.getClassLoader(),
                                                            new Class<?>[] {Connection.class},
                                                            (proxy, method, args) -> {
                                                                if (method.getName()
                                                                        .equals("commit")) {
                                                                    commits.incrementAndGet();
                                                                    if (serverCommitted) {
                                                                        connection.commit();
                                                                    }
                                                                    throw new SQLException(
                                                                            "synthetic-secret lost commit acknowledgement");
                                                                }
                                                                return invoke(
                                                                        connection, method, args);
                                                            }),
                                    new AtomicInteger()));
            assertSanitized(
                    assertThrows(
                            DomainException.class,
                            () ->
                                    tx.execute(
                                            event(UUID.randomUUID(), 2),
                                            SecurityChangeTransactionTest::changeRow)),
                    ErrorCode.OPERATION_UNKNOWN);
            assertEquals(1, commits.get());
            assertState(serverCommitted ? 2 : 1, serverCommitted ? 2 : 1, serverCommitted ? 1 : 0);
        }
    }

    @Test
    void confirmedCommitSurvivesCloseFailureAndCleanupDoesNotHideOriginalFailure()
            throws Exception {
        var tx =
                new SecurityChangeTransaction(
                        source(
                                connection ->
                                        (Connection)
                                                Proxy.newProxyInstance(
                                                        Connection.class.getClassLoader(),
                                                        new Class<?>[] {Connection.class},
                                                        (proxy, method, args) -> {
                                                            if (method.getName().equals("close")) {
                                                                connection.close();
                                                                throw new SQLException(
                                                                        "synthetic-secret close failure");
                                                            }
                                                            return invoke(connection, method, args);
                                                        }),
                                new AtomicInteger()));
        var receipt =
                tx.execute(event(UUID.randomUUID(), 2), SecurityChangeTransactionTest::changeRow);
        assertEquals(2, receipt.epoch());
        assertTrue(receipt.cleanupFailed());
        assertState(2, 2, 1);
        assertSanitized(
                assertThrows(
                        DomainException.class,
                        () ->
                                tx.execute(
                                        event(UUID.randomUUID(), 3),
                                        connection -> {
                                            changeRow(connection);
                                            throw new SQLException(
                                                    "synthetic-secret callback failure");
                                        })),
                ErrorCode.DEPENDENCY_UNAVAILABLE);
        assertState(2, 2, 1);
    }

    @Test
    void existingTransactionIsNotJoinedOrCommitted() throws Exception {
        AtomicInteger callbacks = new AtomicInteger();
        var tx =
                new SecurityChangeTransaction(
                        source(
                                connection -> {
                                    connection.setAutoCommit(false);
                                    return connection;
                                },
                                new AtomicInteger()));
        assertSanitized(
                assertThrows(
                        DomainException.class,
                        () ->
                                tx.execute(
                                        event(UUID.randomUUID(), 2),
                                        connection -> callbacks.incrementAndGet())),
                ErrorCode.SECURITY_STATE_UNAVAILABLE);
        assertEquals(0, callbacks.get());
        assertState(1, 1, 0);
    }

    @Test
    void rollbackAcknowledgementFailureIsUnknownWithoutLeakingAndWithoutRetry() throws Exception {
        AtomicInteger rollbacks = new AtomicInteger();
        var tx =
                new SecurityChangeTransaction(
                        source(
                                connection ->
                                        (Connection)
                                                Proxy.newProxyInstance(
                                                        Connection.class.getClassLoader(),
                                                        new Class<?>[] {Connection.class},
                                                        (proxy, method, args) -> {
                                                            if (method.getName()
                                                                    .equals("rollback")) {
                                                                rollbacks.incrementAndGet();
                                                                connection.rollback();
                                                                throw new SQLException(
                                                                        "synthetic-secret lost rollback acknowledgement");
                                                            }
                                                            return invoke(connection, method, args);
                                                        }),
                                new AtomicInteger()));
        assertSanitized(
                assertThrows(
                        DomainException.class,
                        () ->
                                tx.execute(
                                        event(UUID.randomUUID(), 2),
                                        connection -> {
                                            changeRow(connection);
                                            throw new SQLException(
                                                    "synthetic-secret callback failure");
                                        })),
                ErrorCode.OPERATION_UNKNOWN);
        assertEquals(1, rollbacks.get());
        assertState(1, 1, 0);
    }

    @Test
    void trustedDomainRejectionAndErrorBothRollBackEntireChange() throws Exception {
        assertSanitized(
                assertThrows(
                        DomainException.class,
                        () ->
                                transaction()
                                        .execute(
                                                event(UUID.randomUUID(), 2),
                                                connection -> {
                                                    changeRow(connection);
                                                    throw new DomainException(
                                                            ErrorCode.VERSION_CONFLICT);
                                                })),
                ErrorCode.VERSION_CONFLICT);
        assertState(1, 1, 0);
        assertThrows(
                AssertionError.class,
                () ->
                        transaction()
                                .execute(
                                        event(UUID.randomUUID(), 2),
                                        connection -> {
                                            changeRow(connection);
                                            throw new AssertionError("synthetic assertion failure");
                                        }));
        assertState(1, 1, 0);
    }

    private static void assertGlobalRowLocked() throws SQLException {
        try (Connection probe = open()) {
            probe.setAutoCommit(false);
            try {
                SQLException blocked =
                        assertThrows(
                                SQLException.class,
                                () ->
                                        scalar(
                                                probe,
                                                "SELECT policy_epoch FROM sys_security_state WHERE singleton_id = 1 FOR UPDATE NOWAIT"));
                assertEquals(3572, blocked.getErrorCode());
            } finally {
                probe.rollback();
            }
        }
    }

    private static void assertSanitized(DomainException failure, ErrorCode code) {
        assertEquals(code, failure.errorCode());
        assertEquals(code.name(), failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    private static SecurityEvent event(UUID eventId, long version) {
        return new SecurityEvent(
                eventId,
                SecurityEvent.Kind.USER_SECURITY_CHANGED,
                new SecurityEvent.NumericId(1),
                version);
    }

    private static SecurityChangeTransaction transaction() {
        return new SecurityChangeTransaction(source(connection -> connection, new AtomicInteger()));
    }

    private static void changeRow(Connection connection) throws SQLException {
        assertEquals(
                1,
                update(
                        connection,
                        "UPDATE synthetic_security_aggregate SET version = version + 1, "
                                + "update_time = UTC_TIMESTAMP(6) WHERE id = ?",
                        1));
    }

    private static void assertState(long version, long epoch, long events) throws SQLException {
        try (Connection connection = open()) {
            assertEquals(
                    version,
                    scalar(
                            connection,
                            "SELECT version FROM synthetic_security_aggregate WHERE id = 1"));
            assertEquals(
                    epoch,
                    scalar(
                            connection,
                            "SELECT policy_epoch FROM sys_security_state WHERE singleton_id = 1"));
            assertEquals(events, scalar(connection, "SELECT COUNT(*) FROM sys_security_event"));
        }
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getLong(1);
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
