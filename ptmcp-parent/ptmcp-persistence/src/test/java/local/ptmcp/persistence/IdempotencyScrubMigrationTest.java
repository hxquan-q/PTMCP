package local.ptmcp.persistence;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;

/**
 * V15 must scrub only the client-create replay rows that could carry a pre-sanitization plaintext
 * secret; other routes and in-flight leases stay intact.
 */
class IdempotencyScrubMigrationTest {
    @Test
    void v15DropsOnlySucceededClientCreateReplayRows() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try (MySQLContainer mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("idempotency_scrub_test")
                        .withUsername("scrub_test")
                        .withPassword(
                                Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
                        .withEnv("TZ", "UTC")
                        .withCommand("--default-time-zone=+00:00")
                        .withUrlParam("connectionTimeZone", "UTC")
                        .withUrlParam("allowPublicKeyRetrieval", "true")
                        .withUrlParam("useSSL", "false")) {
            mysql.start();
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/ptmcp-migration")
                    .target("14")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            try (Connection connection =
                    DriverManager.getConnection(
                            mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                insertLease(
                        connection,
                        1,
                        "clients.create:9",
                        "legacy-client-key",
                        "SUCCEEDED",
                        "...\"clientSecret\":\"ptmcp_legacy_secret\"..."
                                .getBytes(StandardCharsets.UTF_8),
                        201,
                        now);
                insertLease(
                        connection,
                        2,
                        "users.create",
                        "legacy-user-key",
                        "SUCCEEDED",
                        "{\"id\":7}".getBytes(StandardCharsets.UTF_8),
                        201,
                        now);
                insertLease(
                        connection,
                        3,
                        "clients.create:9",
                        "inflight-key",
                        "RUNNING",
                        null,
                        null,
                        now);
            }
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/ptmcp-migration")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            try (Connection connection =
                    DriverManager.getConnection(
                            mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                try (PreparedStatement otherRoute =
                        connection.prepareStatement(
                                "SELECT response_cipher FROM api_idempotency WHERE actor_id = 2")) {
                    try (ResultSet rows = otherRoute.executeQuery()) {
                        assertTrue(rows.next(), "other routes keep their replay body");
                    }
                }
                try (PreparedStatement lease =
                        connection.prepareStatement(
                                "SELECT state FROM api_idempotency WHERE actor_id = 3")) {
                    try (ResultSet rows = lease.executeQuery()) {
                        assertTrue(rows.next(), "in-flight leases survive the scrub");
                    }
                }
                try (PreparedStatement scrubbed =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM api_idempotency"
                                        + " WHERE actor_id = 1 AND response_cipher IS NOT NULL")) {
                    try (ResultSet rows = scrubbed.executeQuery()) {
                        assertTrue(rows.next());
                        assertFalse(
                                rows.getLong(1) > 0,
                                "succeeded client-create replay bodies must be scrubbed");
                    }
                }
            }
        }
    }

    private static void insertLease(
            Connection connection,
            long actorId,
            String routeKey,
            String keySeed,
            String state,
            byte[] cipher,
            Integer status,
            LocalDateTime now)
            throws Exception {
        try (PreparedStatement insert =
                connection.prepareStatement(
                        "INSERT INTO api_idempotency (actor_id, route_key, key_hash, request_hash,"
                                + " state, resource_id, response_cipher, http_status, create_time,"
                                + " expires_at) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?, ?)")) {
            insert.setLong(1, actorId);
            insert.setString(2, routeKey);
            insert.setBytes(3, sha256(keySeed));
            insert.setBytes(4, sha256(keySeed + "-request"));
            insert.setString(5, state);
            if (cipher == null) {
                insert.setNull(6, Types.BLOB);
            } else {
                insert.setBytes(6, cipher);
            }
            if (status == null) {
                insert.setNull(7, Types.INTEGER);
            } else {
                insert.setInt(7, status);
            }
            insert.setObject(8, now.minusHours(2));
            insert.setObject(9, now.plusHours(22));
            insert.executeUpdate();
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }
}
