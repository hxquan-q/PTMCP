package local.ptmcp.persistence.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;
import java.util.UUID;

/** Forward upgrade preserves recovery material and never infers a new host binding. */
class UnboundIdentityMigrationTest {
    @Test
    void upgradePreservesReadyPendingAndCompletedWhileNewIdentitiesDefaultToUnbound() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try (MySQLContainer database = new MySQLContainer(System.getProperty("ptmcp.test.mysqlImage",
                "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                .withDatabaseName("identity_upgrade_test")
                .withUsername("identity_upgrade")
                .withPassword(Base64.getUrlEncoder().withoutPadding().encodeToString(secret))) {
            database.start();
            migrations(database, "12").migrate();
            UUID pending = UUID.randomUUID();
            UUID completed = UUID.randomUUID();
            byte[] digest = new byte[32];
            new SecureRandom().nextBytes(digest);
            String target = UUID.randomUUID().toString();
            try (Connection connection = open(database)) {
                execute(connection, "INSERT INTO sys_user_security (user_id,auth_version,identity_version,identity_state,identity_change_id,version,create_time,update_time)"
                        + " VALUES (1,7,3,'READY',NULL,4,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                        + "(2,8,4,'CHANGE_PENDING',?,5,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                        + "(3,9,5,'READY',NULL,6,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))", pending.toString());
                execute(connection, "INSERT INTO identity_password_change"
                        + " (operation_id,user_id,auth_version,profile_version,expected_hash,target_hash,status,requested_by,create_time,update_time)"
                        + " VALUES (?,2,8,6,?,?,'PENDING',99,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                        + " (?,3,9,8,NULL,NULL,'COMPLETED',99,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                        pending.toString(), digest, target, completed.toString());
            }
            assertEquals(1, migrations(database, "13").migrate().migrationsExecuted);
            assertEquals(0, migrations(database, "13").migrate().migrationsExecuted);
            try (Connection connection = open(database)) {
                execute(connection, "INSERT INTO sys_user_security (user_id,create_time,update_time)"
                        + " VALUES (4,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT user_id,auth_version,identity_version,identity_state,identity_change_id,version"
                                + " FROM sys_user_security ORDER BY user_id"); ResultSet result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(1, result.getLong(1));
                    assertEquals(7, result.getLong(2));
                    assertEquals(3, result.getLong(3));
                    assertEquals("READY", result.getString(4));
                    assertNull(result.getString(5));
                    assertEquals(4, result.getLong(6));
                    assertTrue(result.next());
                    assertEquals(8, result.getLong(2));
                    assertEquals("CHANGE_PENDING", result.getString(4));
                    assertEquals(pending.toString(), result.getString(5));
                    assertEquals(5, result.getLong(6));
                    assertTrue(result.next());
                    assertEquals("READY", result.getString(4));
                    assertEquals(9, result.getLong(2));
                    assertTrue(result.next());
                    assertEquals("UNBOUND", result.getString(4));
                    assertNull(result.getString(5));
                }
                try (PreparedStatement query = connection.prepareStatement(
                        "SELECT operation_id,expected_hash,target_hash,status,profile_version FROM identity_password_change ORDER BY user_id");
                        ResultSet result = query.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(pending.toString(), result.getString(1));
                    assertArrayEquals(digest, result.getBytes(2));
                    assertEquals(target, result.getString(3));
                    assertEquals("PENDING", result.getString(4));
                    assertEquals(6, result.getLong(5));
                    assertTrue(result.next());
                    assertEquals(completed.toString(), result.getString(1));
                    assertNull(result.getBytes(2));
                    assertNull(result.getString(3));
                    assertEquals("COMPLETED", result.getString(4));
                }
                assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE sys_user_security SET identity_change_id=? WHERE user_id=4", UUID.randomUUID().toString()));
            }
        }
    }

    private static Flyway migrations(MySQLContainer database, String target) {
        return Flyway.configure().dataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword())
                .locations("classpath:db/ptmcp-migration").target(target).cleanDisabled(true).load();
    }

    private static Connection open(MySQLContainer database) throws SQLException {
        return DriverManager.getConnection(database.getJdbcUrl(), database.getUsername(), database.getPassword());
    }

    private static void execute(Connection connection, String sql, Object... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            statement.executeUpdate();
        }
    }
}
