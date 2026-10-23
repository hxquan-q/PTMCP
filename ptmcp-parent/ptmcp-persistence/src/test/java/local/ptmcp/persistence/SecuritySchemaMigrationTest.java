package local.ptmcp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real MySQL boundary tests. A missing Docker daemon is an error, never a skipped test. */
class SecuritySchemaMigrationTest {
    private static final List<String> TABLES =
            List.of("sys_security_state", "sys_security_event", "security_consumer_cursor");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 23, 12, 0, 0, 123456000);
    private static MySQLContainer mysql;
    private static Flyway flyway;

    @BeforeAll
    static void migrateEmptyDatabaseExactlyOnce() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69")
                        .withDatabaseName("security_schema_test")
                        .withUsername("schema_test")
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
            flyway =
                    Flyway.configure()
                            .dataSource(
                                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                            .locations("classpath:db/ptmcp-migration")
                            .initSql("SET time_zone = '+00:00'")
                            .cleanDisabled(true)
                            .baselineOnMigrate(false)
                            .load();
            try (Connection connection = connection()) {
                assertEquals(
                        0,
                        scalar(
                                connection,
                                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"));
            }
            // RED without V1: 0 migrations, rather than the required single versioned migration.
            assertEquals(1, flyway.migrate().migrationsExecuted);
            try (Connection connection = connection()) {
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM sys_security_state"));
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM sys_security_event"));
                assertEquals(
                        0, scalar(connection, "SELECT COUNT(*) FROM security_consumer_cursor"));
                assertEquals(
                        1,
                        scalar(
                                connection,
                                "SELECT COUNT(*) FROM sys_security_state WHERE singleton_id = ? AND policy_epoch = ? AND create_time = update_time",
                                1,
                                1));
            }
            assertEquals(0, flyway.migrate().migrationsExecuted);
            try (Connection connection = connection()) {
                assertEquals(
                        1,
                        scalar(
                                connection,
                                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = ?",
                                true));
            }
            assertEquals("1", flyway.info().current().getVersion().getVersion());
            try (Connection connection = connection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT VERSION(), @@session.time_zone, @@sql_mode, @@lower_case_table_names, "
                                            + "@@character_set_server, @@collation_server, @@transaction_isolation");
                    ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertTrue(result.getString(1).startsWith("8.4."));
                assertEquals("+00:00", result.getString(2));
                assertTrue(result.getString(3).contains("STRICT_TRANS_TABLES"));
                System.out.println(
                        "MySQL boundary: version="
                                + result.getString(1)
                                + ", time_zone="
                                + result.getString(2)
                                + ", sql_mode="
                                + result.getString(3)
                                + ", lower_case_table_names="
                                + result.getString(4)
                                + ", charset="
                                + result.getString(5)
                                + ", collation="
                                + result.getString(6)
                                + ", isolation="
                                + result.getString(7));
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

    @Test
    void domainTablesHaveExplicitPrimaryKeysUtcMicrosecondTimesAndNoAutomaticUpdates()
            throws Exception {
        try (Connection connection = connection()) {
            assertEquals(
                    4,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()"));
            for (String table : TABLES) {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT engine, table_collation FROM information_schema.tables "
                                        + "WHERE table_schema = DATABASE() AND table_name = ?")) {
                    statement.setString(1, table);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals("InnoDB", result.getString(1));
                        assertTrue(result.getString(2).startsWith("utf8mb4_"));
                    }
                }
                assertTrue(
                        scalar(
                                        connection,
                                        "SELECT COUNT(*) FROM information_schema.key_column_usage "
                                                + "WHERE table_schema = DATABASE() AND table_name = ? AND constraint_name = 'PRIMARY'",
                                        table)
                                > 0);
                for (String column : List.of("create_time", "update_time")) {
                    try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT data_type, datetime_precision, is_nullable, extra "
                                            + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                                            + "AND table_name = ? AND column_name = ?")) {
                        statement.setString(1, table);
                        statement.setString(2, column);
                        try (ResultSet result = statement.executeQuery()) {
                            assertTrue(result.next());
                            assertEquals("datetime", result.getString(1));
                            assertEquals(6, result.getInt(2));
                            assertEquals("NO", result.getString(3));
                            assertFalse(result.getString(4).toLowerCase().contains("on update"));
                        }
                    }
                }
            }
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT table_name, column_name, column_type FROM information_schema.columns "
                                            + "WHERE table_schema = DATABASE() AND data_type = 'bigint'");
                    ResultSet result = statement.executeQuery()) {
                Set<String> actual = new HashSet<>();
                while (result.next()) {
                    actual.add(result.getString(1) + "." + result.getString(2));
                    assertFalse(result.getString(3).contains("unsigned"));
                }
                assertEquals(
                        Set.of(
                                "sys_security_state.policy_epoch",
                                "sys_security_event.epoch",
                                "security_consumer_cursor.last_epoch"),
                        actual);
            }
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT table_name, column_name FROM information_schema.columns "
                                            + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    assertTrue(result.getString(1).matches("[a-z][a-z0-9_]*"));
                    assertTrue(result.getString(2).matches("[a-z][a-z0-9_]*"));
                }
            }
        }
    }

    @Test
    void contractIndexesAndChecksArePresentAndEnforced() throws Exception {
        try (Connection connection = connection()) {
            assertIndex(connection, "uk_security_event_epoch_event_id", 0, "epoch,event_id");
            assertIndex(
                    connection,
                    "idx_security_event_published",
                    1,
                    "published_at,occurred_at,event_id");
            assertEquals(
                    7,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM information_schema.table_constraints "
                                    + "WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' AND enforced = 'YES'"));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM information_schema.table_constraints "
                                    + "WHERE constraint_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'"));
        }
    }

    @Test
    void onlySeedIsPositiveSingletonAndIllegalStateAndCursorRowsFail() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM sys_security_state"));
                assertEquals(
                        1,
                        scalar(
                                connection,
                                "SELECT policy_epoch FROM sys_security_state WHERE singleton_id = ?",
                                1));
                rejected(
                        connection,
                        3819,
                        "UPDATE sys_security_state SET policy_epoch = ? WHERE singleton_id = ?",
                        0,
                        1);
                rejected(
                        connection,
                        3819,
                        "UPDATE sys_security_state SET policy_epoch = ? WHERE singleton_id = ?",
                        -1,
                        1);
                rejected(
                        connection,
                        3819,
                        "UPDATE sys_security_state SET singleton_id = ? WHERE singleton_id = ?",
                        2,
                        1);
                rejected(
                        connection,
                        1062,
                        "INSERT INTO sys_security_state (singleton_id, policy_epoch, create_time, update_time) VALUES (?, ?, ?, ?)",
                        1,
                        1,
                        NOW,
                        NOW);
                execute(
                        connection,
                        "INSERT INTO security_consumer_cursor (consumer_id, last_epoch, create_time, update_time) VALUES (?, ?, ?, ?)",
                        "new-consumer",
                        0,
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        "UPDATE security_consumer_cursor SET last_epoch = ? WHERE consumer_id = ?",
                        -1,
                        "new-consumer");
                rejected(
                        connection,
                        1048,
                        "UPDATE security_consumer_cursor SET create_time = ? WHERE consumer_id = ?",
                        null,
                        "new-consumer");
                rejected(
                        connection,
                        1264,
                        "UPDATE security_consumer_cursor SET last_epoch = ? WHERE consumer_id = ?",
                        "9223372036854775808",
                        "new-consumer");
                execute(
                        connection,
                        "UPDATE security_consumer_cursor SET last_epoch = ? WHERE consumer_id = ?",
                        Long.MAX_VALUE,
                        "new-consumer");
                assertEquals(
                        Long.MAX_VALUE,
                        scalar(
                                connection,
                                "SELECT last_epoch FROM security_consumer_cursor WHERE consumer_id = ?",
                                "new-consumer"));
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void eventObjectPayloadEpochAttemptsNullabilityAndIdempotencyAreConstrained() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                String id = UUID.randomUUID().toString();
                insertEvent(connection, id, 2, "{\"user_id\":\"1\",\"auth_version\":2}");
                assertEquals(
                        0,
                        scalar(
                                connection,
                                "SELECT attempts FROM sys_security_event WHERE event_id = ?",
                                id));
                assertEquals(
                        1,
                        scalar(
                                connection,
                                "SELECT COUNT(*) FROM sys_security_event WHERE event_id = ? AND published_at IS NULL",
                                id));
                // Epoch is not unique on its own: one transaction may have multiple events.
                insertEvent(connection, UUID.randomUUID().toString(), 2, "{}");
                // No frozen event enum: future legitimate event types remain representable.
                execute(
                        connection,
                        "UPDATE sys_security_event SET event_type = ?, attempts = ?, published_at = ? WHERE event_id = ?",
                        "FUTURE_LEGITIMATE_EVENT",
                        1,
                        NOW.plusSeconds(1),
                        id);
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT create_time, update_time, occurred_at FROM sys_security_event WHERE event_id = ?")) {
                    statement.setString(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals(NOW, result.getObject(1, LocalDateTime.class));
                        assertEquals(NOW, result.getObject(2, LocalDateTime.class));
                        assertEquals(NOW, result.getObject(3, LocalDateTime.class));
                    }
                }
                rejected(
                        connection,
                        3819,
                        "UPDATE sys_security_event SET event_id = ? WHERE event_id = ?",
                        "not-a-uuid",
                        id);
                SQLException duplicate =
                        assertThrows(
                                SQLException.class, () -> insertEvent(connection, id, 2, "{}"));
                assertEquals(1062, duplicate.getErrorCode());
                SQLException conflictingRetry =
                        assertThrows(
                                SQLException.class,
                                () -> insertEvent(connection, id, 3, "{\"auth_version\":3}"));
                assertEquals(1062, conflictingRetry.getErrorCode());
                rejected(
                        connection,
                        3819,
                        "UPDATE sys_security_event SET epoch = ? WHERE event_id = ?",
                        0,
                        id);
                rejected(
                        connection,
                        3819,
                        "UPDATE sys_security_event SET attempts = ? WHERE event_id = ?",
                        -1,
                        id);
                for (String payload : List.of("[]", "1", "null", "\"text\"")) {
                    rejected(
                            connection,
                            3819,
                            "UPDATE sys_security_event SET payload = ? WHERE event_id = ?",
                            payload,
                            id);
                }
                rejected(
                        connection,
                        1048,
                        "UPDATE sys_security_event SET payload = ? WHERE event_id = ?",
                        null,
                        id);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void stateEventAndCursorDmlRollBackTogetherInMetadataDatabaseOnly() throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            execute(
                    connection,
                    "UPDATE sys_security_state SET policy_epoch = ?, update_time = ? WHERE singleton_id = ?",
                    2,
                    NOW,
                    1);
            insertEvent(connection, id, 2, "{}");
            execute(
                    connection,
                    "INSERT INTO security_consumer_cursor (consumer_id, last_epoch, create_time, update_time) VALUES (?, ?, ?, ?)",
                    id,
                    2,
                    NOW,
                    NOW);
            connection.rollback();
        }
        try (Connection connection = connection()) {
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT policy_epoch FROM sys_security_state WHERE singleton_id = ?",
                            1));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM sys_security_event WHERE event_id = ?",
                            id));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM security_consumer_cursor WHERE consumer_id = ?",
                            id));
        }
    }

    @Test
    void concurrentSameEventIdHasExactlyOneWinner() throws Exception {
        String id = UUID.randomUUID().toString();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> raceInsert(id, ready, start));
            var second = executor.submit(() -> raceInsert(id, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            List<Integer> outcomes =
                    List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertTrue(outcomes.contains(0));
            assertTrue(outcomes.contains(1062));
        } finally {
            start.countDown();
        }
        try (Connection connection = connection()) {
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM sys_security_event WHERE event_id = ?",
                            id));
            execute(connection, "DELETE FROM sys_security_event WHERE event_id = ?", id);
        }
    }

    @Test
    void destructiveCleanIsDisabledAndUnknownExistingSchemaIsNotBaselined() throws Exception {
        assertThrows(FlywayException.class, () -> flyway.clean());
        Flyway unknown =
                Flyway.configure()
                        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                        .locations("classpath:db/ptmcp-migration")
                        .table("unknown_schema_history")
                        .cleanDisabled(true)
                        .baselineOnMigrate(false)
                        .load();
        assertThrows(FlywayException.class, () -> unknown.migrate());
        try (Connection connection = connection()) {
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?",
                            "unknown_schema_history"));
        }
    }

    private static int raceInsert(String id, CountDownLatch ready, CountDownLatch start)
            throws Exception {
        try (Connection connection = connection()) {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            try {
                insertEvent(connection, id, 9, "{}");
                return 0;
            } catch (SQLException failure) {
                if (failure.getErrorCode() != 1062) {
                    throw failure;
                }
                return failure.getErrorCode();
            }
        }
    }

    private static void assertIndex(
            Connection connection, String name, int nonUnique, String columns) throws Exception {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                                + "FROM information_schema.statistics WHERE table_schema = DATABASE() "
                                + "AND table_name = ? AND index_name = ? GROUP BY non_unique")) {
            statement.setString(1, "sys_security_event");
            statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(nonUnique, result.getInt(1));
                assertEquals(columns, result.getString(2));
                assertFalse(result.next());
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static void insertEvent(Connection connection, String id, long epoch, String payload)
            throws SQLException {
        execute(
                connection,
                "INSERT INTO sys_security_event (event_id, epoch, aggregate_type, aggregate_id, event_type, payload, occurred_at, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                epoch,
                "USER",
                "1",
                "VERSION_CHANGED",
                payload,
                NOW,
                NOW,
                NOW);
    }

    private static long scalar(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static void rejected(
            Connection connection, int errorCode, String sql, Object... values) {
        SQLException failure =
                assertThrows(SQLException.class, () -> execute(connection, sql, values));
        assertEquals(errorCode, failure.getErrorCode());
    }

    private static void execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }
}
