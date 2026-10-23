package local.ptmcp.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Real MySQL boundary for the V2 organization/platform/resource/ACL migration. */
class MetadataSchemaMigrationTest {
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
    private static final String V1_TABLES =
            "'sys_security_state','sys_security_event','security_consumer_cursor'";
    private static final String INSERT_PLATFORM =
            "INSERT INTO platform_registry (code, name, tier, status, catalog_mode, proof_mode, "
                    + "scope_version, security_version, create_time, update_time, created_by, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_DATASOURCE =
            "INSERT INTO datasource (code, name, engine, host, port, database_name, username, "
                    + "credential_cipher, key_id, tls_mode, pool_max, status, config_version, "
                    + "resource_version, create_time, update_time, created_by, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_NAMESPACE =
            "INSERT INTO resource_namespace (datasource_id, catalog_raw, catalog_norm, schema_raw, "
                    + "schema_norm, generation, status, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_OBJECT =
            "INSERT INTO resource_object (namespace_id, generation, object_raw, object_norm, quoted, "
                    + "kind, status, definition_hash, physical_identity, first_seen_at, last_seen_at, "
                    + "create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_SNAPSHOT =
            "INSERT INTO schema_snapshot (datasource_id, config_version, resource_version, status, "
                    + "captured_at, expires_at, object_count, checksum, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_SNAPSHOT_OBJECT =
            "INSERT INTO snapshot_object (snapshot_id, object_id, object_version, table_comment, "
                    + "column_signature, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_COLUMN =
            "INSERT INTO resource_column (snapshot_id, object_id, ordinal, column_raw, column_norm, "
                    + "quoted, data_type, jdbc_type, nullable, precision_value, scale_value, max_length, "
                    + "comment, is_primary_key, foreign_key, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_SCOPE =
            "INSERT INTO platform_scope (platform_id, resource_type, resource_id, resource_generation, "
                    + "action, include_descendants, create_time, update_time, created_by, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_GRANT =
            "INSERT INTO acl_grant (subject_type, subject_id, resource_type, resource_id, "
                    + "resource_generation, action, effect, include_descendants, valid_from, valid_to, "
                    + "status, reason, version, create_time, update_time, created_by, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_DELEGATION =
            "INSERT INTO admin_delegation (user_id, department_root_id, resource_type, resource_id, "
                    + "resource_generation, allowed_actions, valid_from, valid_to, status, version, "
                    + "create_time, update_time, created_by, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_DEPT =
            "INSERT INTO sys_dept (dept_id, parent_id, ancestors, dept_name, status, del_flag, "
                    + "create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String INSERT_ROLE =
            "INSERT INTO sys_role (role_key, role_name, status, del_flag, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String INSERT_USER_ROLE =
            "INSERT INTO sys_user_role (user_id, role_id, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?)";
    private static final String INSERT_LOGIN_NAME =
            "INSERT INTO sys_user_login_name (user_id, normalized_name, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?)";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0, 0, 123456000);
    private static MySQLContainer mysql;
    private static Flyway flyway;

    @BeforeAll
    static void upgradeEmptyDatabaseThroughV2Once() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69")
                        .withDatabaseName("metadata_schema_test")
                        .withUsername("metadata_test")
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
                            .target("2")
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
            // RED with only V1: one migration, not the required V1+V2 head.
            assertEquals(2, flyway.migrate().migrationsExecuted);
            assertEquals(0, flyway.migrate().migrationsExecuted);
            assertEquals("2", flyway.info().current().getVersion().getVersion());
            try (Connection connection = connection()) {
                assertEquals(
                        V2_TABLES.size() + 3,
                        scalar(
                                connection,
                                "SELECT COUNT(*) FROM information_schema.tables "
                                        + "WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'"));
                assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM sys_security_state"));
                assertEquals(
                        1,
                        scalar(
                                connection,
                                "SELECT policy_epoch FROM sys_security_state WHERE singleton_id = ?",
                                1));
                assertEquals(
                        2,
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
    void everyV2TableUsesExplicitKeysUtcMicrosecondTimesAndSnakeCaseNames() throws Exception {
        try (Connection connection = connection()) {
            for (String table : V2_TABLES) {
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
                for (String column : List.of("create_time", "update_time")) {
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
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT table_name, column_name, column_type FROM information_schema.columns "
                                            + "WHERE table_schema = DATABASE() AND data_type = 'bigint' "
                                            + "AND table_name <> 'flyway_schema_history'");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    assertFalse(
                            result.getString(3).contains("unsigned"),
                            result.getString(1) + "." + result.getString(2));
                }
            }
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT table_name, column_name, column_type FROM information_schema.columns "
                                            + "WHERE table_schema = DATABASE() AND column_name IN "
                                            + "('quoted', 'nullable', 'is_primary_key', 'include_descendants')");
                    ResultSet result = statement.executeQuery()) {
                Set<String> seen = new HashSet<>();
                while (result.next()) {
                    seen.add(result.getString(1) + "." + result.getString(2));
                    assertEquals("tinyint(1)", result.getString(3));
                }
                assertEquals(6, seen.size());
            }
        }
    }

    @Test
    void contractIndexesChecksAndForeignKeysMatchTheDesign() throws Exception {
        try (Connection connection = connection()) {
            assertIndex(connection, "platform_registry", "uk_platform_registry_code", 0, "code");
            assertIndex(
                    connection,
                    "resource_namespace",
                    "uk_resource_namespace_identity",
                    0,
                    "datasource_id,catalog_norm,schema_norm,generation");
            assertIndex(
                    connection,
                    "resource_object",
                    "uk_resource_object_identity",
                    0,
                    "namespace_id,object_norm,generation");
            assertIndex(
                    connection,
                    "platform_scope",
                    "uk_platform_scope_target",
                    0,
                    "platform_id,resource_type,resource_id,resource_generation,action");
            assertIndex(
                    connection,
                    "acl_grant",
                    "idx_acl_grant_subject",
                    1,
                    "subject_type,subject_id,status,valid_from,valid_to");
            assertIndex(
                    connection,
                    "acl_grant",
                    "idx_acl_grant_resource",
                    1,
                    "resource_type,resource_id,resource_generation,status");
            assertEquals(
                    57,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM information_schema.table_constraints "
                                    + "WHERE constraint_schema = DATABASE() AND constraint_type = 'CHECK' "
                                    + "AND enforced = 'YES' AND table_name NOT IN ("
                                    + V1_TABLES
                                    + ", 'flyway_schema_history')"));
            Set<String> foreignKeys = new HashSet<>();
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT constraint_name FROM information_schema.table_constraints "
                                            + "WHERE constraint_schema = DATABASE() AND constraint_type = 'FOREIGN KEY'");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    foreignKeys.add(result.getString(1));
                }
            }
            assertEquals(
                    Set.of(
                            "fk_resource_namespace_datasource",
                            "fk_schema_snapshot_datasource",
                            "fk_resource_object_namespace",
                            "fk_snapshot_object_snapshot",
                            "fk_snapshot_object_object",
                            "fk_resource_column_snapshot",
                            "fk_resource_column_object",
                            "fk_business_dictionary_object",
                            "fk_platform_scope_platform",
                            "fk_sys_user_role_role"),
                    foreignKeys);
        }
    }

    @Test
    void platformDatasourceNamespaceObjectAndSnapshotConstraintsRejectInvalidRows()
            throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                long platformId = insertPlatform(connection, "PLATFORM_A");
                rejected(
                        connection,
                        3819,
                        INSERT_PLATFORM,
                        "TIER1_NONE",
                        "Tier 1",
                        1,
                        "ACTIVE",
                        "DISCOVER",
                        "NONE",
                        1,
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_PLATFORM,
                        "TIER_ZERO",
                        "Tier 0",
                        0,
                        "ACTIVE",
                        "DISCOVER",
                        "MTLS",
                        1,
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        1062,
                        INSERT_PLATFORM,
                        "PLATFORM_A",
                        "Duplicate",
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
                long datasourceId = insertDatasource(connection, "DS_A");
                rejected(
                        connection,
                        3819,
                        INSERT_DATASOURCE,
                        "DS_PORT",
                        "Port",
                        "MYSQL",
                        "db.internal",
                        0,
                        "biz",
                        "reader",
                        new byte[] {1},
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
                rejected(
                        connection,
                        3819,
                        INSERT_DATASOURCE,
                        "DS_POOL",
                        "Pool",
                        "MYSQL",
                        "db.internal",
                        3306,
                        "biz",
                        "reader",
                        new byte[] {1},
                        "key-1",
                        "VERIFY_FULL",
                        9,
                        "ACTIVE",
                        1,
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_DATASOURCE,
                        "DS_TLS",
                        "TLS",
                        "MYSQL",
                        "db.internal",
                        3306,
                        "biz",
                        "reader",
                        new byte[] {1},
                        "key-1",
                        "PREFERRED",
                        4,
                        "ACTIVE",
                        1,
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_DATASOURCE,
                        "DS_ENGINE",
                        "Engine",
                        "ORACLE",
                        "db.internal",
                        3306,
                        "biz",
                        "reader",
                        new byte[] {1},
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
                rejected(
                        connection,
                        1062,
                        INSERT_DATASOURCE,
                        "DS_A",
                        "Duplicate",
                        "MYSQL",
                        "db.internal",
                        3306,
                        "biz",
                        "reader",
                        new byte[] {1},
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
                long namespaceId = insertNamespace(connection, datasourceId);
                rejected(
                        connection,
                        3819,
                        "UPDATE resource_namespace SET generation = 0 WHERE id = ?",
                        namespaceId);
                rejected(
                        connection,
                        1062,
                        INSERT_NAMESPACE,
                        datasourceId,
                        "",
                        "",
                        "app",
                        "app",
                        1,
                        "ACTIVE",
                        NOW,
                        NOW);
                long objectId = insertObject(connection, namespaceId);
                rejected(
                        connection,
                        3819,
                        "UPDATE resource_object SET generation = 0 WHERE id = ?",
                        objectId);
                rejected(
                        connection,
                        3819,
                        "UPDATE resource_object SET kind = 'FUNCTION' WHERE id = ?",
                        objectId);
                rejected(
                        connection,
                        1062,
                        INSERT_OBJECT,
                        namespaceId,
                        1,
                        "orders",
                        "orders",
                        0,
                        "TABLE",
                        "ACTIVE",
                        hash(),
                        null,
                        NOW,
                        NOW,
                        NOW,
                        NOW);
                long snapshotId = insertSnapshot(connection, datasourceId);
                rejected(
                        connection,
                        3819,
                        "UPDATE schema_snapshot SET expires_at = captured_at WHERE id = ?",
                        snapshotId);
                rejected(
                        connection,
                        3819,
                        "UPDATE schema_snapshot SET object_count = -1 WHERE id = ?",
                        snapshotId);
                execute(
                        connection,
                        INSERT_SNAPSHOT_OBJECT,
                        snapshotId,
                        objectId,
                        1,
                        "orders table",
                        hash(),
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        "UPDATE snapshot_object SET object_version = 0 WHERE snapshot_id = ? AND object_id = ?",
                        snapshotId,
                        objectId);
                rejected(
                        connection,
                        1062,
                        INSERT_SNAPSHOT_OBJECT,
                        snapshotId,
                        objectId,
                        2,
                        null,
                        hash(),
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_COLUMN,
                        snapshotId,
                        objectId,
                        0,
                        "id",
                        "id",
                        0,
                        "BIGINT",
                        -5,
                        0,
                        null,
                        null,
                        null,
                        null,
                        1,
                        null,
                        NOW,
                        NOW);
                rejected(
                        connection,
                        3819,
                        "UPDATE resource_column SET ordinal = -1 WHERE snapshot_id = ? AND object_id = ? AND ordinal = 0",
                        snapshotId,
                        objectId);
                rejected(
                        connection,
                        3819,
                        "UPDATE resource_column SET nullable = 2 WHERE snapshot_id = ? AND object_id = ? AND ordinal = 0",
                        snapshotId,
                        objectId);
                rejected(
                        connection,
                        1062,
                        INSERT_COLUMN,
                        snapshotId,
                        objectId,
                        1,
                        "ID",
                        "id",
                        0,
                        "BIGINT",
                        -5,
                        0,
                        null,
                        null,
                        null,
                        null,
                        0,
                        null,
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_SCOPE,
                        platformId,
                        "DATASOURCE",
                        datasourceId,
                        1,
                        "READ",
                        0,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_SCOPE,
                        platformId,
                        "DATASOURCE",
                        datasourceId,
                        1,
                        "WRITE",
                        0,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        1062,
                        INSERT_SCOPE,
                        platformId,
                        "DATASOURCE",
                        datasourceId,
                        1,
                        "READ",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void aclDelegationAndOrganizationConstraintsRejectInvalidRows() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                long platformId = insertPlatform(connection, "PLATFORM_B");
                execute(
                        connection,
                        INSERT_GRANT,
                        "USER",
                        7,
                        "DATASOURCE",
                        42,
                        1,
                        "READ",
                        "ALLOW",
                        0,
                        NOW,
                        NOW.plusYears(1),
                        "ACTIVE",
                        "initial grant",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_GRANT,
                        "USER",
                        7,
                        "DATASOURCE",
                        42,
                        1,
                        "READ",
                        "ALLOW",
                        0,
                        NOW,
                        NOW,
                        "ACTIVE",
                        "empty window",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_GRANT,
                        "USER",
                        7,
                        "DATASOURCE",
                        42,
                        1,
                        "READ",
                        "MAYBE",
                        0,
                        NOW,
                        null,
                        "ACTIVE",
                        "bad effect",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_GRANT,
                        "GROUP",
                        7,
                        "DATASOURCE",
                        42,
                        1,
                        "READ",
                        "ALLOW",
                        0,
                        NOW,
                        null,
                        "ACTIVE",
                        "bad subject",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_GRANT,
                        "USER",
                        7,
                        "DATASOURCE",
                        42,
                        1,
                        "WRITE",
                        "ALLOW",
                        0,
                        NOW,
                        null,
                        "ACTIVE",
                        "bad action",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                execute(
                        connection,
                        INSERT_DELEGATION,
                        9,
                        3,
                        "DATASOURCE",
                        42,
                        1,
                        "[\"META\",\"READ\"]",
                        NOW,
                        NOW.plusMonths(1),
                        "ACTIVE",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_DELEGATION,
                        9,
                        3,
                        "DATASOURCE",
                        42,
                        1,
                        "{}",
                        NOW,
                        NOW.plusMonths(1),
                        "ACTIVE",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        3819,
                        INSERT_DELEGATION,
                        9,
                        3,
                        "DATASOURCE",
                        42,
                        1,
                        "[\"READ\"]",
                        NOW,
                        NOW,
                        "ACTIVE",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                execute(connection, INSERT_DEPT, 1, 0, "", "root", "0", "0", NOW, NOW);
                rejected(
                        connection, 3819, INSERT_DEPT, 3, 1, "0", "bad status", "2", "0", NOW, NOW);
                long roleId =
                        insert(
                                connection,
                                INSERT_ROLE,
                                "admin",
                                "Administrator",
                                "0",
                                "0",
                                NOW,
                                NOW);
                rejected(connection, 1062, INSERT_ROLE, "admin", "Duplicate", "0", "0", NOW, NOW);
                rejected(connection, 3819, INSERT_ROLE, "ops", "Ops", "2", "0", NOW, NOW);
                execute(connection, INSERT_USER_ROLE, 9, roleId, NOW, NOW);
                rejected(connection, 1062, INSERT_USER_ROLE, 9, roleId, NOW, NOW);
                execute(connection, INSERT_LOGIN_NAME, 9, "alice", NOW, NOW);
                rejected(connection, 3819, INSERT_LOGIN_NAME, 10, "Alice", NOW, NOW);
                rejected(connection, 3819, INSERT_LOGIN_NAME, 11, "", NOW, NOW);
                rejected(connection, 1062, INSERT_LOGIN_NAME, 12, "alice", NOW, NOW);
                execute(
                        connection,
                        INSERT_SCOPE,
                        platformId,
                        "OBJECT",
                        42,
                        1,
                        "META",
                        0,
                        NOW,
                        NOW,
                        1,
                        1);
            } finally {
                connection.rollback();
            }
        }
    }

    @Test
    void foreignKeysRejectOrphanRowsAndV2DmlRollsBackAsOneGroup() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                rejected(
                        connection,
                        1452,
                        INSERT_NAMESPACE,
                        999_999,
                        "",
                        "",
                        "app",
                        "app",
                        1,
                        "ACTIVE",
                        NOW,
                        NOW);
                rejected(
                        connection,
                        1452,
                        INSERT_OBJECT,
                        999_999,
                        1,
                        "orders",
                        "orders",
                        0,
                        "TABLE",
                        "ACTIVE",
                        hash(),
                        null,
                        NOW,
                        NOW,
                        NOW,
                        NOW);
                rejected(
                        connection,
                        1452,
                        INSERT_SNAPSHOT_OBJECT,
                        999_999,
                        999_999,
                        1,
                        null,
                        hash(),
                        NOW,
                        NOW);
                rejected(
                        connection,
                        1452,
                        "INSERT INTO business_dictionary (object_id, column_norm, title, version, "
                                + "create_time, update_time, created_by, updated_by) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        999_999,
                        "",
                        "Orphan",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(
                        connection,
                        1452,
                        INSERT_SCOPE,
                        999_999,
                        "DATASOURCE",
                        1,
                        1,
                        "READ",
                        0,
                        NOW,
                        NOW,
                        1,
                        1);
                rejected(connection, 1452, INSERT_USER_ROLE, 1, 999_999, NOW, NOW);
            } finally {
                connection.rollback();
            }
        }

        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                long platformId = insertPlatform(connection, "PLATFORM_C");
                long datasourceId = insertDatasource(connection, "DS_C");
                long namespaceId = insertNamespace(connection, datasourceId);
                long objectId = insertObject(connection, namespaceId);
                long snapshotId = insertSnapshot(connection, datasourceId);
                execute(
                        connection,
                        INSERT_SNAPSHOT_OBJECT,
                        snapshotId,
                        objectId,
                        1,
                        null,
                        hash(),
                        NOW,
                        NOW);
                execute(
                        connection,
                        INSERT_COLUMN,
                        snapshotId,
                        objectId,
                        0,
                        "id",
                        "id",
                        0,
                        "BIGINT",
                        -5,
                        0,
                        null,
                        null,
                        null,
                        null,
                        1,
                        null,
                        NOW,
                        NOW);
                execute(
                        connection,
                        "INSERT INTO business_dictionary (object_id, column_norm, title, version, "
                                + "create_time, update_time, created_by, updated_by) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        objectId,
                        "",
                        "Orders",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                execute(
                        connection,
                        INSERT_SCOPE,
                        platformId,
                        "DATASOURCE",
                        datasourceId,
                        1,
                        "READ",
                        0,
                        NOW,
                        NOW,
                        1,
                        1);
                execute(
                        connection,
                        INSERT_GRANT,
                        "USER",
                        9,
                        "DATASOURCE",
                        datasourceId,
                        1,
                        "READ",
                        "ALLOW",
                        0,
                        NOW,
                        null,
                        "ACTIVE",
                        "grouped",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                execute(
                        connection,
                        INSERT_DELEGATION,
                        9,
                        1,
                        "DATASOURCE",
                        datasourceId,
                        1,
                        "[\"READ\"]",
                        NOW,
                        null,
                        "ACTIVE",
                        1,
                        NOW,
                        NOW,
                        1,
                        1);
                execute(connection, INSERT_DEPT, 1, 0, "", "root", "0", "0", NOW, NOW);
                long roleId =
                        insert(connection, INSERT_ROLE, "reader", "Reader", "0", "0", NOW, NOW);
                execute(connection, INSERT_USER_ROLE, 9, roleId, NOW, NOW);
                execute(connection, INSERT_LOGIN_NAME, 9, "reader", NOW, NOW);
                for (String table : V2_TABLES) {
                    assertTrue(scalar(connection, "SELECT COUNT(*) FROM " + table) > 0, table);
                }
            } finally {
                connection.rollback();
            }
        }
        try (Connection connection = connection()) {
            for (String table : V2_TABLES) {
                assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM " + table), table);
            }
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM sys_security_state"));
        }
    }

    @Test
    void sameGenerationRebuildReceivesANewObjectId() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                long datasourceId = insertDatasource(connection, "DS_D");
                long namespaceId = insertNamespace(connection, datasourceId);
                long first = insertObject(connection, namespaceId);
                execute(connection, "DELETE FROM resource_object WHERE id = ?", first);
                long second = insertObject(connection, namespaceId);
                assertNotEquals(first, second);
            } finally {
                connection.rollback();
            }
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

    private static long insertNamespace(Connection connection, long datasourceId)
            throws SQLException {
        return insert(
                connection,
                INSERT_NAMESPACE,
                datasourceId,
                "",
                "",
                "app",
                "app",
                1,
                "ACTIVE",
                NOW,
                NOW);
    }

    private static long insertObject(Connection connection, long namespaceId) throws SQLException {
        return insert(
                connection,
                INSERT_OBJECT,
                namespaceId,
                1,
                "orders",
                "orders",
                0,
                "TABLE",
                "ACTIVE",
                hash(),
                null,
                NOW,
                NOW,
                NOW,
                NOW);
    }

    private static long insertSnapshot(Connection connection, long datasourceId)
            throws SQLException {
        return insert(
                connection,
                INSERT_SNAPSHOT,
                datasourceId,
                1,
                1,
                "PUBLISHED",
                NOW,
                NOW.plusDays(1),
                1,
                hash(),
                NOW,
                NOW);
    }

    private static byte[] hash() {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) 7);
        return value;
    }

    private static long insert(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, values);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void assertIndex(
            Connection connection, String table, String name, int nonUnique, String columns)
            throws Exception {
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

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }
}
