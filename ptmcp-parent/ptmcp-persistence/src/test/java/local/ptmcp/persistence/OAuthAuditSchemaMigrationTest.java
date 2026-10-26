package local.ptmcp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Real MySQL boundary for the V3 OAuth/audit/idempotency/lease migration. */
class OAuthAuditSchemaMigrationTest {
    private static final List<String> V3_TABLES =
            List.of(
                    "oauth2_registered_client",
                    "oauth2_authorization",
                    "oauth2_authorization_consent",
                    "oauth_refresh_family",
                    "oauth_refresh_token",
                    "personal_token",
                    "web_session_index",
                    "execution_lease",
                    "audit_outbox",
                    "audit_log",
                    "audit_sensitive_access",
                    "api_idempotency",
                    "admin_operation_log");
    private static final List<String> V2_TABLES =
            List.of(
                    "platform_registry",
                    "datasource",
                    "resource_namespace",
                    "schema_snapshot",
                    "resource_object",
                    "snapshot_object",
                    "resource_column",
                    "business_dictionary",
                    "platform_scope",
                    "acl_grant",
                    "admin_delegation",
                    "sys_dept",
                    "sys_role",
                    "sys_user_role",
                    "sys_user_login_name");
    private static final List<String> OFFICIAL_REGISTERED_CLIENT_COLUMNS =
            List.of(
                    "id",
                    "client_id",
                    "client_id_issued_at",
                    "client_secret",
                    "client_secret_expires_at",
                    "client_name",
                    "client_authentication_methods",
                    "authorization_grant_types",
                    "redirect_uris",
                    "post_logout_redirect_uris",
                    "scopes",
                    "client_settings",
                    "token_settings");
    private static final List<String> OFFICIAL_AUTHORIZATION_COLUMNS =
            List.of(
                    "id",
                    "registered_client_id",
                    "principal_name",
                    "authorization_grant_type",
                    "authorized_scopes",
                    "attributes",
                    "state",
                    "authorization_code_value",
                    "authorization_code_issued_at",
                    "authorization_code_expires_at",
                    "authorization_code_metadata",
                    "access_token_value",
                    "access_token_issued_at",
                    "access_token_expires_at",
                    "access_token_metadata",
                    "access_token_type",
                    "access_token_scopes",
                    "oidc_id_token_value",
                    "oidc_id_token_issued_at",
                    "oidc_id_token_expires_at",
                    "oidc_id_token_metadata",
                    "refresh_token_value",
                    "refresh_token_issued_at",
                    "refresh_token_expires_at",
                    "refresh_token_metadata",
                    "user_code_value",
                    "user_code_issued_at",
                    "user_code_expires_at",
                    "user_code_metadata",
                    "device_code_value",
                    "device_code_issued_at",
                    "device_code_expires_at",
                    "device_code_metadata");
    private static final List<String> OFFICIAL_CONSENT_COLUMNS =
            List.of("registered_client_id", "principal_name", "authorities");
    private static final Set<String> IMMUTABLE_TABLES =
            Set.of(
                    "audit_outbox",
                    "audit_log",
                    "audit_sensitive_access",
                    "api_idempotency",
                    "admin_operation_log");
    private static final String INSERT_PLATFORM =
            "INSERT INTO platform_registry (code, name, tier, status, catalog_mode, proof_mode, "
                    + "scope_version, security_version, create_time, update_time, created_by, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_DATASOURCE =
            "INSERT INTO datasource (code, name, engine, host, port, database_name, username, "
                    + "credential_cipher, key_id, tls_mode, pool_max, status, config_version, "
                    + "resource_version, create_time, update_time, created_by, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_CLIENT =
            "INSERT INTO oauth2_registered_client (id, client_id, client_secret, client_name, "
                    + "client_authentication_methods, authorization_grant_types, redirect_uris, "
                    + "post_logout_redirect_uris, scopes, client_settings, token_settings, "
                    + "platform_id, client_type, client_secret_hash, jwks_uri, metadata_uri, "
                    + "metadata_digest, status, registration_source, version, create_time, "
                    + "update_time, created_by, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_AUTHORIZATION =
            "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, "
                    + "authorization_grant_type, status, create_time, update_time, created_by, "
                    + "updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_CONSENT =
            "INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, "
                    + "authorities, version, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_FAMILY =
            "INSERT INTO oauth_refresh_family (id, authorization_id, user_id, registered_client_id, "
                    + "platform_id, status, auth_version, absolute_expires_at, idle_expires_at, "
                    + "last_rotated_at, version, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_REFRESH_TOKEN =
            "INSERT INTO oauth_refresh_token (token_hash, family_id, generation, issued_at, "
                    + "expires_at, status, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_PERSONAL_TOKEN =
            "INSERT INTO personal_token (user_id, platform_id, token_prefix, token_hash, name, "
                    + "scopes, auth_version, expires_at, status, create_time, update_time, "
                    + "created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_SESSION =
            "INSERT INTO web_session_index (session_hash, kind, user_id, auth_version, issued_at, "
                    + "expires_at, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_LEASE =
            "INSERT INTO execution_lease (request_id, user_id, datasource_id, gateway_instance_id, "
                    + "boot_id, state, execution_started_at, heartbeat_at, lease_until, create_time, "
                    + "update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_OUTBOX =
            "INSERT INTO audit_outbox (event_id, request_id, event_kind, seq, event_payload, "
                    + "payload_hash, create_time) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_AUDIT =
            "INSERT INTO audit_log (request_id, tool, started_at, result, verdict, create_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_AUDIT_TERMINAL =
            "INSERT INTO audit_log (request_id, tool, started_at, completed_at, result, verdict, "
                    + "error_code, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_SENSITIVE =
            "INSERT INTO audit_sensitive_access (request_id, operator_id, reason, fields, decision, "
                    + "create_time) VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_IDEMPOTENCY =
            "INSERT INTO api_idempotency (actor_id, route_key, key_hash, request_hash, state, "
                    + "create_time, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_ADMIN_LOG =
            "INSERT INTO admin_operation_log (actor_id, request_id, action, aggregate_type, "
                    + "aggregate_id, occurred_at, create_time) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String UPSERT_AUDIT_TERMINAL =
            "INSERT INTO audit_log (request_id, tool, started_at, completed_at, result, verdict, "
                    + "error_code, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?) AS incoming "
                    + "ON DUPLICATE KEY UPDATE "
                    + "completed_at = IF(audit_log.result = 'RUNNING', incoming.completed_at, "
                    + "audit_log.completed_at), "
                    + "result = IF(audit_log.result = 'RUNNING', incoming.result, audit_log.result), "
                    + "verdict = IF(audit_log.result = 'RUNNING', incoming.verdict, "
                    + "audit_log.verdict), "
                    + "error_code = IF(audit_log.result = 'RUNNING', incoming.error_code, "
                    + "audit_log.error_code)";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0, 0, 123456000);
    private static MySQLContainer mysql;
    private static Flyway flyway;

    @BeforeAll
    static void upgradeEmptyDatabaseThroughV3Once() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69")
                        .withDatabaseName("oauth_schema_test")
                        .withUsername("oauth_test")
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
                            .target("3")
                            .cleanDisabled(true)
                            .baselineOnMigrate(false)
                            .load();
            try (Connection connection = connection()) {
                assertEquals(
                        0,
                        scalar(
                                connection,
                                "SELECT COUNT(*) FROM information_schema.tables "
                                        + "WHERE table_schema = DATABASE()"));
            }
            assertEquals(3, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertEquals("3", flyway.info().current().getVersion().getVersion());
            try (Connection connection = connection()) {
                assertEquals(
                        V3_TABLES.size() + V2_TABLES.size() + 3,
                        scalar(
                                connection,
                                "SELECT COUNT(*) FROM information_schema.tables "
                                        + "WHERE table_schema = DATABASE() "
                                        + "AND table_name <> 'flyway_schema_history'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM sys_security_state"));
                assertEquals(
                        3,
                        scalar(
                                connection,
                                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = ?",
                                true));
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
    void officialSpringSecurityColumnsArePreservedAndRawTokenBlobsAreRefused() throws Exception {
        try (Connection connection = connection()) {
            assertColumns(
                    connection, "oauth2_registered_client", OFFICIAL_REGISTERED_CLIENT_COLUMNS);
            assertColumns(connection, "oauth2_authorization", OFFICIAL_AUTHORIZATION_COLUMNS);
            assertColumns(connection, "oauth2_authorization_consent", OFFICIAL_CONSENT_COLUMNS);
            assertColumns(
                    connection,
                    "oauth2_registered_client",
                    List.of(
                            "platform_id",
                            "client_type",
                            "client_secret_hash",
                            "jwks_uri",
                            "metadata_uri",
                            "metadata_digest",
                            "status",
                            "registration_source",
                            "version",
                            "create_time",
                            "update_time",
                            "created_by",
                            "updated_by"));
            assertColumns(
                    connection,
                    "oauth2_authorization",
                    List.of(
                            "user_id",
                            "family_id",
                            "authorization_code_hash",
                            "code_expires_at",
                            "code_consumed_at",
                            "redirect_uri",
                            "pkce_challenge",
                            "pkce_method",
                            "scopes",
                            "auth_version",
                            "status",
                            "version",
                            "expires_at",
                            "create_time",
                            "update_time",
                            "created_by",
                            "updated_by"));
            assertColumns(
                    connection,
                    "oauth2_authorization_consent",
                    List.of("user_id", "scopes", "version", "create_time", "update_time"));
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT table_name, column_name, data_type, datetime_precision "
                                            + "FROM information_schema.columns "
                                            + "WHERE table_schema = DATABASE() "
                                            + "AND table_name IN ('oauth2_registered_client', "
                                            + "'oauth2_authorization', 'oauth2_authorization_consent') "
                                            + "AND column_name LIKE '%\\_at'");
                    ResultSet result = statement.executeQuery()) {
                int seen = 0;
                while (result.next()) {
                    seen++;
                    assertEquals("datetime", result.getString(3));
                    assertEquals(
                            6, result.getInt(4), result.getString(1) + "." + result.getString(2));
                }
                assertTrue(seen > 0);
            }

            connection.setAutoCommit(false);
            try {
                long platformId = insertPlatform(connection, "PLATFORM_OAUTH");
                execute(
                        connection,
                        INSERT_CLIENT,
                        "client-oauth",
                        "client-oauth",
                        null,
                        "OAuth client",
                        "client_secret_basic",
                        "authorization_code",
                        "https://app.example/callback",
                        null,
                        "openid,profile",
                        "{}",
                        "{}",
                        platformId,
                        "CONFIDENTIAL",
                        hash((byte) 9),
                        null,
                        null,
                        null,
                        "ACTIVE",
                        "ADMIN",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                execute(
                        connection,
                        INSERT_AUTHORIZATION,
                        "auth-oauth",
                        "client-oauth",
                        "alice",
                        "authorization_code",
                        "ACTIVE",
                        NOW,
                        NOW,
                        1,
                        1);
                for (String column :
                        List.of(
                                "authorization_code_value",
                                "access_token_value",
                                "oidc_id_token_value",
                                "refresh_token_value",
                                "user_code_value",
                                "device_code_value")) {
                    rejected(
                            connection,
                            3819,
                            "UPDATE oauth2_authorization SET " + column + " = X'01' WHERE id = ?",
                            "auth-oauth");
                }
                rejected(
                        connection,
                        3819,
                        "UPDATE oauth2_registered_client SET client_type = 'ROGUE' WHERE id = ?",
                        "client-oauth");
                assertFalse(hasColumn(connection, "personal_token", "token"));
                assertFalse(hasColumn(connection, "personal_token", "token_plain"));
                assertFalse(hasColumn(connection, "personal_token", "plaintext"));
                assertFalse(hasColumn(connection, "personal_token", "secret"));
                try (PreparedStatement statement =
                                connection.prepareStatement(
                                        "SELECT column_type FROM information_schema.columns "
                                                + "WHERE table_schema = DATABASE() "
                                                + "AND table_name = 'personal_token' "
                                                + "AND column_name = 'token_hash'");
                        ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("binary(32)", result.getString(1).toLowerCase());
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void everyV3TableUsesExplicitKeysUtcMicrosecondTimesAndContractNames() throws Exception {
        try (Connection connection = connection()) {
            for (String table : V3_TABLES) {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT engine, table_collation FROM information_schema.tables "
                                        + "WHERE table_schema = DATABASE() AND table_name = ?")) {
                    statement.setString(1, table);
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next(), table);
                        assertEquals("InnoDB", result.getString(1), table);
                        assertTrue(result.getString(2).startsWith("utf8mb4_"), table);
                    }
                }
                assertTrue(
                        scalar(
                                        connection,
                                        "SELECT COUNT(*) FROM information_schema.key_column_usage "
                                                + "WHERE table_schema = DATABASE() AND table_name = ? "
                                                + "AND constraint_name = 'PRIMARY'",
                                        table)
                                > 0,
                        table);
                assertTimeColumn(connection, table, "create_time");
                if (!IMMUTABLE_TABLES.contains(table)) {
                    assertTimeColumn(connection, table, "update_time");
                } else {
                    assertFalse(hasColumn(connection, table, "update_time"), table);
                }
            }
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT table_name, column_name FROM information_schema.columns "
                                            + "WHERE table_schema = DATABASE() AND table_name IN ("
                                            + inList(V3_TABLES)
                                            + ")");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    assertTrue(result.getString(1).matches("[a-z][a-z0-9_]*"));
                    assertTrue(result.getString(2).matches("[a-z][a-z0-9_]*"));
                }
            }
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT table_name, column_name, column_type "
                                            + "FROM information_schema.columns "
                                            + "WHERE table_schema = DATABASE() AND data_type = 'bigint' "
                                            + "AND table_name IN ("
                                            + inList(V3_TABLES)
                                            + ")");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    assertFalse(result.getString(3).contains("unsigned"), result.getString(1));
                }
            }
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT table_name, column_name, extra "
                                            + "FROM information_schema.columns "
                                            + "WHERE table_schema = DATABASE() AND table_name IN ("
                                            + inList(V3_TABLES)
                                            + ")");
                    ResultSet result = statement.executeQuery()) {
                int count = 0;
                while (result.next()) {
                    count++;
                    assertFalse(result.getString(3).toLowerCase().contains("on update"));
                }
                assertTrue(count > 0);
            }
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT column_type FROM information_schema.columns "
                                            + "WHERE table_schema = DATABASE() "
                                            + "AND table_name = 'audit_log' "
                                            + "AND column_name = 'truncated'");
                    ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("tinyint(1)", result.getString(1).toLowerCase());
            }
            assertEquals(
                    58,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM information_schema.table_constraints "
                                    + "WHERE constraint_schema = DATABASE() "
                                    + "AND constraint_type = 'CHECK' AND enforced = 'YES' "
                                    + "AND table_name IN ("
                                    + inList(V3_TABLES)
                                    + ")"));
        }
    }

    @Test
    void contractIndexesAndForeignKeysMatchTheDesign() throws Exception {
        try (Connection connection = connection()) {
            assertIndex(
                    connection,
                    "oauth2_registered_client",
                    "uk_oauth2_registered_client_client_id",
                    0,
                    "client_id");
            assertIndex(
                    connection,
                    "oauth2_authorization",
                    "uk_oauth2_authorization_code_hash",
                    0,
                    "authorization_code_hash");
            assertIndex(
                    connection,
                    "oauth2_authorization",
                    "idx_oauth2_authorization_family",
                    1,
                    "family_id,status");
            assertIndex(
                    connection,
                    "oauth2_authorization",
                    "idx_oauth2_authorization_user",
                    1,
                    "user_id,create_time");
            assertIndex(
                    connection,
                    "oauth_refresh_family",
                    "idx_oauth_refresh_family_user",
                    1,
                    "user_id,status");
            assertIndex(
                    connection,
                    "oauth_refresh_family",
                    "idx_oauth_refresh_family_expiry",
                    1,
                    "status,absolute_expires_at,idle_expires_at");
            assertIndex(
                    connection,
                    "oauth_refresh_token",
                    "uk_oauth_refresh_token_generation",
                    0,
                    "family_id,generation");
            assertIndex(
                    connection,
                    "oauth_refresh_token",
                    "idx_oauth_refresh_token_family",
                    1,
                    "family_id,status");
            assertIndex(connection, "personal_token", "uk_personal_token_hash", 0, "token_hash");
            assertIndex(
                    connection,
                    "personal_token",
                    "idx_personal_token_user",
                    1,
                    "user_id,status,create_time");
            assertIndex(
                    connection,
                    "web_session_index",
                    "idx_web_session_index_user",
                    1,
                    "user_id,kind");
            assertIndex(
                    connection,
                    "execution_lease",
                    "idx_execution_lease_owner",
                    1,
                    "gateway_instance_id,boot_id,state,lease_until");
            assertIndex(
                    connection,
                    "audit_outbox",
                    "uk_audit_outbox_request_kind",
                    0,
                    "request_id,event_kind");
            assertIndex(
                    connection,
                    "audit_outbox",
                    "idx_audit_outbox_publish",
                    1,
                    "published_at,create_time,event_id");
            assertIndex(
                    connection, "audit_log", "idx_audit_log_started", 1, "started_at,request_id");
            assertIndex(
                    connection,
                    "audit_log",
                    "idx_audit_log_user",
                    1,
                    "user_id,started_at,request_id");
            assertIndex(
                    connection,
                    "audit_log",
                    "idx_audit_log_platform",
                    1,
                    "platform_id,started_at,request_id");
            assertIndex(
                    connection,
                    "audit_log",
                    "idx_audit_log_result",
                    1,
                    "result,started_at,request_id");
            assertIndex(
                    connection,
                    "audit_sensitive_access",
                    "idx_audit_sensitive_access_request",
                    1,
                    "request_id,create_time");
            assertIndex(
                    connection, "api_idempotency", "idx_api_idempotency_expiry", 1, "expires_at");
            assertIndex(
                    connection,
                    "admin_operation_log",
                    "idx_admin_operation_log_actor",
                    1,
                    "actor_id,occurred_at");
            Set<String> foreignKeys = new HashSet<>();
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT constraint_name FROM information_schema.table_constraints "
                                            + "WHERE constraint_schema = DATABASE() "
                                            + "AND constraint_type = 'FOREIGN KEY' "
                                            + "AND table_name IN ("
                                            + inList(V3_TABLES)
                                            + ")");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    foreignKeys.add(result.getString(1));
                }
            }
            assertEquals(
                    Set.of(
                            "fk_oauth2_registered_client_platform",
                            "fk_oauth2_authorization_client",
                            "fk_oauth2_authorization_consent_client",
                            "fk_oauth_refresh_family_authorization",
                            "fk_oauth_refresh_family_client",
                            "fk_oauth_refresh_family_platform",
                            "fk_oauth_refresh_token_family",
                            "fk_personal_token_platform",
                            "fk_execution_lease_datasource"),
                    foreignKeys);
        }
    }

    @Test
    void oauthAndAuditChecksUniqueKeysAndForeignKeysRejectInvalidRows() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                long platformId = insertPlatform(connection, "PLATFORM_V3");
                long datasourceId = insertDatasource(connection, "DS_V3");
                insertClient(connection, "client-a", "client-a", platformId);
                execute(
                        connection,
                        INSERT_AUTHORIZATION,
                        "auth-a",
                        "client-a",
                        "alice",
                        "authorization_code",
                        "ACTIVE",
                        NOW,
                        NOW,
                        1,
                        1);
                execute(connection, INSERT_CONSENT, "client-a", "alice", "openid", 1, NOW, NOW);
                rejected(
                        connection,
                        1062,
                        INSERT_CONSENT,
                        "client-a",
                        "alice",
                        "profile",
                        1,
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        "UPDATE oauth2_authorization_consent SET version = 0 "
                                + "WHERE registered_client_id = ? AND principal_name = ?",
                        "client-a",
                        "alice");
                rejected(
                        connection,
                        1062,
                        INSERT_CLIENT,
                        "client-a-dup",
                        "client-a",
                        null,
                        "Duplicate client",
                        "client_secret_basic",
                        "authorization_code",
                        "https://app.example/callback",
                        null,
                        "openid",
                        "{}",
                        "{}",
                        platformId,
                        "CONFIDENTIAL",
                        hash((byte) 8),
                        null,
                        null,
                        null,
                        "ACTIVE",
                        "ADMIN",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        1452,
                        INSERT_CLIENT,
                        "client-orphan",
                        "client-orphan",
                        null,
                        "Orphan client",
                        "client_secret_basic",
                        "authorization_code",
                        "https://app.example/callback",
                        null,
                        "openid",
                        "{}",
                        "{}",
                        999_999L,
                        "CONFIDENTIAL",
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        "ADMIN",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        1452,
                        INSERT_AUTHORIZATION,
                        "auth-orphan",
                        "missing-client",
                        "alice",
                        "authorization_code",
                        "ACTIVE",
                        NOW,
                        NOW,
                        1,
                        1);
                execute(
                        connection,
                        INSERT_FAMILY,
                        "family-a",
                        "auth-a",
                        9,
                        "client-a",
                        platformId,
                        "ACTIVE",
                        1,
                        NOW.plusDays(30),
                        NOW.plusDays(7),
                        NOW,
                        1,
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_FAMILY,
                        "family-bad-window",
                        "auth-a",
                        9,
                        "client-a",
                        platformId,
                        "ACTIVE",
                        1,
                        NOW.plusDays(7),
                        NOW.plusDays(30),
                        NOW,
                        1,
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_FAMILY,
                        "family-bad-revoked",
                        "auth-a",
                        9,
                        "client-a",
                        platformId,
                        "REVOKED",
                        1,
                        NOW.plusDays(30),
                        NOW.plusDays(7),
                        NOW,
                        1,
                        NOW,
                        NOW);
                rejected(
                        connection,
                        1452,
                        INSERT_FAMILY,
                        "family-orphan",
                        "missing-auth",
                        9,
                        "client-a",
                        platformId,
                        "ACTIVE",
                        1,
                        NOW.plusDays(30),
                        NOW.plusDays(7),
                        NOW,
                        1,
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_REFRESH_TOKEN,
                        hash((byte) 1),
                        "family-a",
                        1,
                        NOW,
                        NOW.plusDays(7),
                        "ACTIVE",
                        NOW,
                        NOW);
                rejected(
                        connection,
                        1062,
                        INSERT_REFRESH_TOKEN,
                        hash((byte) 2),
                        "family-a",
                        1,
                        NOW,
                        NOW.plusDays(7),
                        "ACTIVE",
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_REFRESH_TOKEN,
                        hash((byte) 3),
                        "family-a",
                        0,
                        NOW,
                        NOW.plusDays(7),
                        "ACTIVE",
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_REFRESH_TOKEN,
                        hash((byte) 4),
                        "family-a",
                        2,
                        NOW,
                        NOW,
                        "ACTIVE",
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_REFRESH_TOKEN,
                        hash((byte) 5),
                        "family-a",
                        3,
                        NOW,
                        NOW.plusDays(7),
                        "CONSUMED",
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_PERSONAL_TOKEN,
                        9,
                        platformId,
                        "ptm_",
                        hash((byte) 6),
                        "CLI token",
                        "[\"READ\"]",
                        1,
                        NOW.plusDays(30),
                        "ACTIVE",
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        1062,
                        INSERT_PERSONAL_TOKEN,
                        10,
                        platformId,
                        "ptm_",
                        hash((byte) 6),
                        "Duplicate token",
                        "[\"READ\"]",
                        1,
                        NOW.plusDays(30),
                        "ACTIVE",
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_PERSONAL_TOKEN,
                        10,
                        platformId,
                        "ptm_",
                        hash((byte) 7),
                        "Empty window",
                        "[\"READ\"]",
                        1,
                        NOW,
                        "ACTIVE",
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_PERSONAL_TOKEN,
                        10,
                        platformId,
                        "",
                        hash((byte) 10),
                        "Empty prefix",
                        "[\"READ\"]",
                        1,
                        NOW.plusDays(30),
                        "ACTIVE",
                        NOW,
                        NOW,
                        1,
                        1);
                execute(
                        connection,
                        INSERT_SESSION,
                        hash((byte) 11),
                        "ADMIN",
                        9,
                        1,
                        NOW,
                        NOW.plusHours(8),
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_SESSION,
                        hash((byte) 12),
                        "UNKNOWN",
                        9,
                        1,
                        NOW,
                        NOW.plusHours(8),
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_LEASE,
                        "req-lease",
                        9,
                        datasourceId,
                        "gateway-1",
                        "boot-1",
                        "RUNNING",
                        NOW,
                        NOW,
                        NOW.plusSeconds(30),
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_LEASE,
                        "req-lease-bad",
                        9,
                        datasourceId,
                        "gateway-1",
                        "boot-1",
                        "PAUSED",
                        NOW,
                        NOW,
                        NOW.plusSeconds(30),
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_LEASE,
                        "req-lease-window",
                        9,
                        datasourceId,
                        "gateway-1",
                        "boot-1",
                        "RUNNING",
                        NOW,
                        NOW,
                        NOW.minusSeconds(1),
                        NOW,
                        NOW);
                rejected(
                        connection,
                        1452,
                        INSERT_LEASE,
                        "req-lease-orphan",
                        9,
                        999_999L,
                        "gateway-1",
                        "boot-1",
                        "RUNNING",
                        NOW,
                        NOW,
                        NOW.plusSeconds(30),
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_OUTBOX,
                        "event-start",
                        "req-audit",
                        "START",
                        0,
                        new byte[] {1},
                        hash((byte) 13),
                        NOW);
                execute(
                        connection,
                        INSERT_OUTBOX,
                        "event-terminal",
                        "req-audit",
                        "TERMINAL",
                        1,
                        new byte[] {2},
                        hash((byte) 14),
                        NOW);
                rejected(
                        connection,
                        1062,
                        INSERT_OUTBOX,
                        "event-start-dup",
                        "req-audit",
                        "START",
                        0,
                        new byte[] {1},
                        hash((byte) 13),
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_OUTBOX,
                        "event-bad-seq",
                        "req-bad",
                        "START",
                        1,
                        new byte[] {1},
                        hash((byte) 15),
                        NOW);
                execute(
                        connection,
                        INSERT_AUDIT,
                        "req-audit",
                        "query",
                        NOW,
                        "RUNNING",
                        "ALLOW",
                        NOW);
                rejected(
                        connection,
                        3819,
                        "UPDATE audit_log SET result = 'MAYBE' WHERE request_id = ?",
                        "req-audit");
                rejected(
                        connection,
                        3819,
                        "UPDATE audit_log SET truncated = 2 WHERE request_id = ?",
                        "req-audit");
                rejected(
                        connection,
                        3819,
                        "UPDATE audit_log SET completed_at = ? WHERE request_id = ?",
                        NOW.minusSeconds(1),
                        "req-audit");
                rejected(
                        connection,
                        3819,
                        "UPDATE audit_log SET duration_ms = -1 WHERE request_id = ?",
                        "req-audit");
                execute(
                        connection,
                        INSERT_SENSITIVE,
                        "req-audit",
                        9,
                        "break glass",
                        "[\"sql_raw\"]",
                        "ALLOW",
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_SENSITIVE,
                        "req-audit",
                        9,
                        "bad decision",
                        "[\"sql_raw\"]",
                        "MAYBE",
                        NOW);
                execute(
                        connection,
                        INSERT_IDEMPOTENCY,
                        9,
                        "POST /tokens",
                        hash((byte) 16),
                        hash((byte) 17),
                        "RUNNING",
                        NOW,
                        NOW.plusHours(24));
                rejected(
                        connection,
                        3819,
                        INSERT_IDEMPOTENCY,
                        9,
                        "POST /tokens",
                        hash((byte) 18),
                        hash((byte) 19),
                        "MAYBE",
                        NOW,
                        NOW.plusHours(24));
                rejected(
                        connection,
                        3819,
                        INSERT_IDEMPOTENCY,
                        9,
                        "POST /empty-window",
                        hash((byte) 20),
                        hash((byte) 21),
                        "RUNNING",
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_ADMIN_LOG,
                        9,
                        "req-admin",
                        "ACL_GRANT",
                        "acl_grant",
                        42,
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        INSERT_ADMIN_LOG,
                        9,
                        "req-admin-bad",
                        "",
                        "acl_grant",
                        42,
                        NOW,
                        NOW);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void terminalUpsertsNeverRewindHistoryAndAuditTablesStayUnpartitioned() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                execute(
                        connection,
                        INSERT_AUDIT_TERMINAL,
                        "req-terminal",
                        "query",
                        NOW.minusMinutes(1),
                        NOW,
                        "ERROR",
                        "ALLOW",
                        "SQL_DENIED",
                        NOW);
                execute(
                        connection,
                        UPSERT_AUDIT_TERMINAL,
                        "req-terminal",
                        "query",
                        NOW.minusMinutes(1),
                        NOW.plusMinutes(1),
                        "SUCCESS",
                        "ALLOW",
                        null,
                        NOW.plusMinutes(1));
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT result, error_code, completed_at FROM audit_log "
                                        + "WHERE request_id = ?")) {
                    statement.setString(1, "req-terminal");
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals("ERROR", result.getString(1));
                        assertEquals("SQL_DENIED", result.getString(2));
                        assertEquals(NOW, result.getObject(3, LocalDateTime.class));
                    }
                }
                execute(
                        connection,
                        INSERT_AUDIT,
                        "req-late-terminal",
                        "query",
                        NOW,
                        "RUNNING",
                        "ALLOW",
                        NOW);
                execute(
                        connection,
                        UPSERT_AUDIT_TERMINAL,
                        "req-late-terminal",
                        "query",
                        NOW,
                        NOW.plusSeconds(5),
                        "SUCCESS",
                        "ALLOW",
                        null,
                        NOW.plusSeconds(5));
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT result, completed_at FROM audit_log WHERE request_id = ?")) {
                    statement.setString(1, "req-late-terminal");
                    try (ResultSet result = statement.executeQuery()) {
                        assertTrue(result.next());
                        assertEquals("SUCCESS", result.getString(1));
                        assertEquals(NOW.plusSeconds(5), result.getObject(2, LocalDateTime.class));
                    }
                }
                for (String table : List.of("audit_log", "audit_outbox")) {
                    assertEquals(
                            0,
                            scalar(
                                    connection,
                                    "SELECT COUNT(*) FROM information_schema.partitions "
                                            + "WHERE table_schema = DATABASE() AND table_name = ? "
                                            + "AND partition_name IS NOT NULL",
                                    table),
                            table);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void apiIdempotencyPrimaryKeyAllowsExactlyOneConcurrentClaim() throws Exception {
        byte[] keyHash = hash((byte) 30);
        byte[] firstRequest = hash((byte) 31);
        byte[] secondRequest = hash((byte) 32);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool =
                new ThreadPoolExecutor(
                        2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        Callable<Integer> first =
                () -> claim(barrier, 9, keyHash, firstRequest, NOW, NOW.plusHours(24));
        Callable<Integer> second =
                () -> claim(barrier, 9, keyHash, secondRequest, NOW, NOW.plusHours(24));
        try {
            List<Future<Integer>> futures = pool.invokeAll(List.of(first, second));
            List<Integer> outcomes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                outcomes.add(future.get(30, TimeUnit.SECONDS));
            }
            assertEquals(1, outcomes.stream().filter(code -> code == 0).count());
            assertEquals(1, outcomes.stream().filter(code -> code == 1062).count());
            try (Connection connection = connection()) {
                assertEquals(
                        1,
                        scalar(
                                connection,
                                "SELECT COUNT(*) FROM api_idempotency "
                                        + "WHERE actor_id = ? AND route_key = ? AND key_hash = ?",
                                9,
                                "POST /idempotent",
                                keyHash));
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void orphanRowsAreRejectedAndV3DmlRollsBackAsOneGroup() throws Exception {
        try (Connection connection = connection()) {
            Map<String, Long> before = counts(connection, V3_TABLES);
            connection.setAutoCommit(false);
            try {
                long platformId = insertPlatform(connection, "PLATFORM_GROUP");
                long datasourceId = insertDatasource(connection, "DS_GROUP");
                insertClient(connection, "client-group", "client-group", platformId);
                execute(
                        connection,
                        INSERT_AUTHORIZATION,
                        "auth-group",
                        "client-group",
                        "alice",
                        "authorization_code",
                        "ACTIVE",
                        NOW,
                        NOW,
                        1,
                        1);
                execute(connection, INSERT_CONSENT, "client-group", "alice", "openid", 1, NOW, NOW);
                execute(
                        connection,
                        INSERT_FAMILY,
                        "family-group",
                        "auth-group",
                        9,
                        "client-group",
                        platformId,
                        "ACTIVE",
                        1,
                        NOW.plusDays(30),
                        NOW.plusDays(7),
                        NOW,
                        1,
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_REFRESH_TOKEN,
                        hash((byte) 40),
                        "family-group",
                        1,
                        NOW,
                        NOW.plusDays(7),
                        "ACTIVE",
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_PERSONAL_TOKEN,
                        9,
                        platformId,
                        "ptm_",
                        hash((byte) 41),
                        "Grouped token",
                        "[\"READ\"]",
                        1,
                        NOW.plusDays(30),
                        "ACTIVE",
                        NOW,
                        NOW,
                        1,
                        1);
                execute(
                        connection,
                        INSERT_SESSION,
                        hash((byte) 42),
                        "AS",
                        9,
                        1,
                        NOW,
                        NOW.plusHours(8),
                        NOW,
                        NOW);
                for (int index = 0; index < 500; index++) {
                    execute(
                            connection,
                            INSERT_LEASE,
                            "req-group-" + index,
                            9,
                            datasourceId,
                            "gateway-group",
                            "boot-group",
                            "RUNNING",
                            NOW,
                            NOW,
                            NOW.plusSeconds(30),
                            NOW,
                            NOW);
                }
                execute(
                        connection,
                        INSERT_OUTBOX,
                        "event-group",
                        "req-group",
                        "START",
                        0,
                        new byte[] {1},
                        hash((byte) 43),
                        NOW);
                execute(
                        connection,
                        INSERT_AUDIT,
                        "req-group",
                        "query",
                        NOW,
                        "RUNNING",
                        "ALLOW",
                        NOW);
                execute(
                        connection,
                        INSERT_SENSITIVE,
                        "req-group",
                        9,
                        "grouped",
                        "[\"sql_raw\"]",
                        "ALLOW",
                        NOW);
                execute(
                        connection,
                        INSERT_IDEMPOTENCY,
                        9,
                        "POST /grouped",
                        hash((byte) 44),
                        hash((byte) 45),
                        "RUNNING",
                        NOW,
                        NOW.plusHours(24));
                execute(
                        connection,
                        INSERT_ADMIN_LOG,
                        9,
                        "req-group",
                        "ACL_GRANT",
                        "acl_grant",
                        42,
                        NOW,
                        NOW);
                assertEquals(
                        "idx_execution_lease_owner",
                        explainLease(connection),
                        "execution_lease index");
                for (String table : V3_TABLES) {
                    assertTrue(scalar(connection, "SELECT COUNT(*) FROM " + table) > 0, table);
                }
            } finally {
                connection.rollback();
            }
            assertEquals(before, counts(connection, V3_TABLES));
        }
    }

    private static String explainLease(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "EXPLAIN SELECT request_id FROM execution_lease "
                                + "FORCE INDEX (idx_execution_lease_owner) "
                                + "WHERE gateway_instance_id = ? AND boot_id = ? "
                                + "AND state = ? AND lease_until > ?")) {
            statement.setString(1, "gateway-group");
            statement.setString(2, "boot-group");
            statement.setString(3, "RUNNING");
            statement.setObject(4, NOW);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString("key");
            }
        }
    }

    private static int claim(
            CyclicBarrier barrier,
            long actorId,
            byte[] keyHash,
            byte[] requestHash,
            LocalDateTime createTime,
            LocalDateTime expiresAt) {
        try (Connection connection = connection()) {
            barrier.await(10, TimeUnit.SECONDS);
            execute(
                    connection,
                    INSERT_IDEMPOTENCY,
                    actorId,
                    "POST /idempotent",
                    keyHash,
                    requestHash,
                    "RUNNING",
                    createTime,
                    expiresAt);
            return 0;
        } catch (SQLException failure) {
            return failure.getErrorCode();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long insertPlatform(Connection connection, String code) throws SQLException {
        return insert(
                connection,
                INSERT_PLATFORM,
                code,
                "Platform " + code,
                1,
                "ACTIVE",
                "DISCOVER",
                "MTLS",
                1,
                1,
                NOW,
                NOW,
                1,
                1);
    }

    private static long insertDatasource(Connection connection, String code) throws SQLException {
        return insert(
                connection,
                INSERT_DATASOURCE,
                code,
                "Datasource " + code,
                "MYSQL",
                "db.internal",
                3306,
                "biz",
                "reader",
                new byte[] {1, 2, 3},
                "key-1",
                "VERIFY_FULL",
                4,
                "ACTIVE",
                1,
                1,
                NOW,
                NOW,
                1,
                1);
    }

    private static void insertClient(
            Connection connection, String id, String clientId, long platformId)
            throws SQLException {
        execute(
                connection,
                INSERT_CLIENT,
                id,
                clientId,
                null,
                "OAuth client",
                "client_secret_basic",
                "authorization_code",
                "https://app.example/callback",
                null,
                "openid,profile",
                "{}",
                "{}",
                platformId,
                "CONFIDENTIAL",
                hash((byte) 50),
                null,
                null,
                null,
                "ACTIVE",
                "ADMIN",
                1,
                NOW,
                NOW,
                1,
                1);
    }

    private static byte[] hash(byte marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, marker);
        return value;
    }

    private static void assertTimeColumn(Connection connection, String table, String column)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT data_type, datetime_precision, is_nullable, extra "
                                + "FROM information_schema.columns WHERE table_schema = DATABASE() "
                                + "AND table_name = ? AND column_name = ?")) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), table + "." + column);
                assertEquals("datetime", result.getString(1));
                assertEquals(6, result.getInt(2));
                assertEquals("NO", result.getString(3));
                assertFalse(result.getString(4).toLowerCase().contains("on update"));
            }
        }
    }

    private static void assertColumns(Connection connection, String table, List<String> columns)
            throws SQLException {
        Set<String> actual = new HashSet<>();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    actual.add(result.getString(1));
                }
            }
        }
        assertTrue(actual.containsAll(columns), table + " missing " + columns);
    }

    private static boolean hasColumn(Connection connection, String table, String column)
            throws SQLException {
        return scalar(
                        connection,
                        "SELECT COUNT(*) FROM information_schema.columns "
                                + "WHERE table_schema = DATABASE() AND table_name = ? "
                                + "AND column_name = ?",
                        table,
                        column)
                > 0;
    }

    private static String inList(List<String> tables) {
        return String.join(",", tables.stream().map(table -> "'" + table + "'").toList());
    }

    private static Map<String, Long> counts(Connection connection, List<String> tables)
            throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : tables) {
            counts.put(table, scalar(connection, "SELECT COUNT(*) FROM " + table));
        }
        return counts;
    }

    private static void assertIndex(
            Connection connection, String table, String name, int nonUnique, String columns)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT non_unique, GROUP_CONCAT(column_name ORDER BY seq_in_index) "
                                + "FROM information_schema.statistics WHERE table_schema = DATABASE() "
                                + "AND table_name = ? AND index_name = ? GROUP BY non_unique")) {
            statement.setString(1, table);
            statement.setString(2, name);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), table + "." + name);
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

    private static long insert(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, values);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }
}
