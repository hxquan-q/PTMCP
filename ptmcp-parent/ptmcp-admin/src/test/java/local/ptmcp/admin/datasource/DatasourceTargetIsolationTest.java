package local.ptmcp.admin.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.schema.PublishedSnapshotReader;
import local.ptmcp.persistence.schema.SchemaSnapshotStore;
import local.ptmcp.persistence.schema.SnapshotModel;

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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import javax.sql.DataSource;

/** Real metadata transactions; no business endpoint is connected during configuration writes. */
class DatasourceTargetIsolationTest {
    private static MySQLContainer mysql;
    private static HikariDataSource metadata;
    private DatasourceStore store;
    private DatasourceStore.Row source;
    private long objectId;
    private long namespaceId;

    @BeforeAll
    static void start() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("target_isolation")
                        .withUsername("isolation_test")
                        .withPassword(
                                Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
                        .withEnv("TZ", "UTC")
                        .withCommand("--default-time-zone=+00:00")
                        .withUrlParam("sslMode", "DISABLED")
                        .withUrlParam("allowPublicKeyRetrieval", "true");
        mysql.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(mysql.getJdbcUrl());
        config.setUsername(mysql.getUsername());
        config.setPassword(mysql.getPassword());
        metadata = new HikariDataSource(config);
        Flyway.configure()
                .dataSource(metadata)
                .locations("classpath:db/ptmcp-migration")
                .cleanDisabled(true)
                .load()
                .migrate();
    }

    @AfterAll
    static void stop() {
        if (metadata != null) metadata.close();
        if (mysql != null) mysql.stop();
    }

    @BeforeEach
    void seed() throws Exception {
        try (Connection connection = metadata.getConnection();
                var statement = connection.createStatement()) {
            for (String table :
                    List.of(
                            "acl_grant",
                            "platform_scope",
                            "admin_delegation",
                            "resource_column",
                            "snapshot_object",
                            "resource_object",
                            "resource_namespace",
                            "schema_snapshot",
                            "datasource",
                            "platform_registry",
                            "sys_security_event",
                            "admin_operation_log")) {
                statement.executeUpdate("DELETE FROM " + table);
            }
            statement.executeUpdate(
                    "UPDATE sys_security_state SET policy_epoch=1 WHERE singleton_id=1");
            statement.executeUpdate(
                    "INSERT INTO platform_registry (id,code,name,tier,status,catalog_mode,proof_mode,"
                            + "create_time,update_time,created_by,updated_by) VALUES (11,'synthetic','Synthetic',3,'ACTIVE','DISCOVER','NONE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
        }
        store = new DatasourceStore(metadata);
        source = store.create(fields("127.0.0.1", 3306, "catalog"), new byte[] {1}, "synthetic", 1);
        SchemaSnapshotStore snapshots = new SchemaSnapshotStore(metadata);
        long snapshot = snapshots.stage(source.id(), model(), LocalDateTime.now(ZoneOffset.UTC));
        snapshots.publish(snapshot, source.configVersion());
        source = store.get(source.id());
        var published = new PublishedSnapshotReader(metadata).load(source.id());
        namespaceId = published.namespaces().getFirst().id();
        objectId = published.namespaces().getFirst().objects().getFirst().id();
        try (Connection connection = metadata.getConnection()) {
            for (String type : List.of("DATASOURCE", "NAMESPACE", "OBJECT")) {
                long id =
                        switch (type) {
                            case "DATASOURCE" -> source.id();
                            case "NAMESPACE" -> namespaceId;
                            default -> objectId;
                        };
                execute(
                        connection,
                        "INSERT INTO acl_grant (subject_type,subject_id,resource_type,resource_id,resource_generation,"
                                + "action,effect,include_descendants,valid_from,status,reason,create_time,update_time,created_by,updated_by) "
                                + "VALUES ('USER',7,?,?,1,'READ','ALLOW',1,UTC_TIMESTAMP(6),'ACTIVE','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)",
                        type,
                        id);
                execute(
                        connection,
                        "INSERT INTO platform_scope (platform_id,resource_type,resource_id,resource_generation,action,include_descendants,"
                                + "create_time,update_time,created_by,updated_by) VALUES (11,?,?,1,'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)",
                        type,
                        id);
                execute(
                        connection,
                        "INSERT INTO admin_delegation (user_id,department_root_id,resource_type,resource_id,resource_generation,allowed_actions,"
                                + "valid_from,status,create_time,update_time,created_by,updated_by) VALUES (7,1,?,?,1,JSON_ARRAY('META','READ'),"
                                + "UTC_TIMESTAMP(6),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)",
                        type,
                        id);
            }
        }
    }

    private static DatasourceStore.Fields fields(String host, int port, String database) {
        return new DatasourceStore.Fields(
                "synthetic",
                "Synthetic",
                "MYSQL",
                host,
                port,
                database,
                "reader",
                "VERIFY_FULL",
                1,
                "ACTIVE");
    }

    private static SnapshotModel model() {
        var column =
                new SnapshotModel.ColumnModel(
                        1,
                        "id",
                        "id",
                        false,
                        "int",
                        Types.INTEGER,
                        false,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null);
        var object =
                new SnapshotModel.ObjectModel(
                        "orders",
                        "orders",
                        false,
                        "TABLE",
                        new byte[32],
                        null,
                        null,
                        List.of(column));
        return new SnapshotModel(
                "catalog",
                "TRUE",
                List.of(
                        new SnapshotModel.NamespaceModel(
                                "catalog", "catalog", "", "", List.of(object))));
    }

    @Test
    void hostChangeDoesNotInheritTheOldTargetAuthority() throws Exception {
        assertTargetSwitch(fields("127.0.0.2", 3306, "catalog"));
    }

    @Test
    void portChangeDoesNotInheritTheOldTargetAuthority() throws Exception {
        assertTargetSwitch(fields("127.0.0.1", 3307, "catalog"));
    }

    @Test
    void databaseChangeDoesNotInheritTheOldTargetAuthority() throws Exception {
        assertTargetSwitch(fields("127.0.0.1", 3306, "replacement"));
    }

    private void assertTargetSwitch(DatasourceStore.Fields replacement) throws Exception {
        long epoch = scalar("SELECT policy_epoch FROM sys_security_state");
        DatasourceStore.Row updated =
                store.update(source.id(), source.configVersion(), replacement, null, null, 1);
        assertEquals(null, updated.activeSnapshotId());
        assertEquals(source.resourceVersion() + 1, updated.resourceVersion());
        assertEquals("RETIRED", text("SELECT status FROM resource_object WHERE id=?", objectId));
        assertEquals(
                "RETIRED", text("SELECT status FROM resource_namespace WHERE id=?", namespaceId));
        assertEquals(
                "RETIRED",
                text("SELECT status FROM schema_snapshot WHERE id=?", source.activeSnapshotId()));
        assertEquals(0, scalar("SELECT COUNT(*) FROM acl_grant WHERE status='ACTIVE'"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM platform_scope"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM admin_delegation WHERE status='ACTIVE'"));
        assertEquals(2, scalar("SELECT scope_version FROM platform_registry WHERE id=11"));
        assertEquals(2, scalar("SELECT security_version FROM platform_registry WHERE id=11"));
        assertEquals(2, scalar("SELECT MIN(version) FROM acl_grant"));
        assertEquals(epoch + 1, scalar("SELECT policy_epoch FROM sys_security_state"));
        SchemaSnapshotStore snapshots = new SchemaSnapshotStore(metadata);
        long snapshot = snapshots.stage(source.id(), model(), LocalDateTime.now(ZoneOffset.UTC));
        snapshots.publish(snapshot, updated.configVersion());
        var published = new PublishedSnapshotReader(metadata).load(source.id());
        assertTrue(published.usable());
        assertNotEquals(namespaceId, published.namespaces().getFirst().id());
        assertNotEquals(objectId, published.namespaces().getFirst().objects().getFirst().id());
        assertEquals(0, scalar("SELECT COUNT(*) FROM acl_grant WHERE status='ACTIVE'"));
    }

    @Test
    void credentialRotationKeepsSameTargetResourceIdentityAndGrants() throws Exception {
        long epoch = scalar("SELECT policy_epoch FROM sys_security_state");
        store.update(
                source.id(),
                source.configVersion(),
                fields(source.host(), source.port(), source.databaseName()),
                new byte[] {2},
                "rotated",
                1);
        assertEquals("ACTIVE", text("SELECT status FROM resource_object WHERE id=?", objectId));
        assertEquals(3, scalar("SELECT COUNT(*) FROM acl_grant WHERE status='ACTIVE'"));
        assertEquals(3, scalar("SELECT COUNT(*) FROM platform_scope"));
        assertEquals(3, scalar("SELECT COUNT(*) FROM admin_delegation WHERE status='ACTIVE'"));
        assertEquals(epoch + 1, scalar("SELECT policy_epoch FROM sys_security_state"));
    }

    @Test
    void switchingOneTargetPreservesOtherSourceAuthorityInTheSamePlatform() throws Exception {
        var other =
                store.create(
                        new DatasourceStore.Fields(
                                "other",
                                "Other",
                                "MYSQL",
                                "127.0.0.1",
                                3306,
                                "other_catalog",
                                "reader",
                                "VERIFY_FULL",
                                1,
                                "ACTIVE"),
                        new byte[] {3},
                        "synthetic",
                        1);
        try (Connection connection = metadata.getConnection()) {
            execute(
                    connection,
                    "INSERT INTO acl_grant (subject_type,subject_id,resource_type,resource_id,resource_generation,action,effect,valid_from,status,reason,"
                            + "create_time,update_time,created_by,updated_by) VALUES ('USER',7,'DATASOURCE',?,1,'READ','ALLOW',UTC_TIMESTAMP(6),'ACTIVE','other',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)",
                    other.id());
            execute(
                    connection,
                    "INSERT INTO platform_scope (platform_id,resource_type,resource_id,resource_generation,action,include_descendants,"
                            + "create_time,update_time,created_by,updated_by) VALUES (11,'DATASOURCE',?,1,'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)",
                    other.id());
            execute(
                    connection,
                    "INSERT INTO admin_delegation (user_id,department_root_id,resource_type,resource_id,resource_generation,allowed_actions,valid_from,"
                            + "status,create_time,update_time,created_by,updated_by) VALUES (7,1,'DATASOURCE',?,1,JSON_ARRAY('META'),UTC_TIMESTAMP(6),'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)",
                    other.id());
        }
        store.update(
                source.id(),
                source.configVersion(),
                fields("127.0.0.2", 3306, "catalog"),
                null,
                null,
                1);
        assertEquals(1, scalar("SELECT COUNT(*) FROM acl_grant WHERE status='ACTIVE'"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM platform_scope"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM admin_delegation WHERE status='ACTIVE'"));
        assertEquals(other.configVersion(), store.get(other.id()).configVersion());
        assertEquals(other.resourceVersion(), store.get(other.id()).resourceVersion());
        assertEquals(other.databaseName(), store.get(other.id()).databaseName());
    }

    @Test
    void staleVersionHasNoSecurityOrResourceSideEffects() throws Exception {
        long epoch = scalar("SELECT policy_epoch FROM sys_security_state");
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () ->
                                store.update(
                                        source.id(),
                                        99,
                                        fields("127.0.0.2", 3306, "catalog"),
                                        null,
                                        null,
                                        1));
        assertEquals(ErrorCode.VERSION_CONFLICT, error.errorCode());
        assertEquals(epoch, scalar("SELECT policy_epoch FROM sys_security_state"));
        assertEquals(source.activeSnapshotId(), store.get(source.id()).activeSnapshotId());
        assertEquals(3, scalar("SELECT COUNT(*) FROM acl_grant WHERE status='ACTIVE'"));
    }

    @Test
    void eventFailureRollsBackConfigurationResourcesAndAllAuthority() throws Exception {
        DatasourceStore failing = new DatasourceStore(faultSource(false));
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () ->
                                failing.update(
                                        source.id(),
                                        source.configVersion(),
                                        fields("127.0.0.2", 3306, "catalog"),
                                        null,
                                        null,
                                        1));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, error.errorCode());
        assertEquals(source.host(), store.get(source.id()).host());
        assertEquals(source.activeSnapshotId(), store.get(source.id()).activeSnapshotId());
        assertEquals("ACTIVE", text("SELECT status FROM resource_object WHERE id=?", objectId));
        assertEquals(3, scalar("SELECT COUNT(*) FROM acl_grant WHERE status='ACTIVE'"));
        assertEquals(3, scalar("SELECT COUNT(*) FROM platform_scope"));
        assertEquals(3, scalar("SELECT COUNT(*) FROM admin_delegation WHERE status='ACTIVE'"));
        assertEquals(2, scalar("SELECT policy_epoch FROM sys_security_state"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM sys_security_event"));
        assertEquals(0, scalar("SELECT COUNT(*) FROM admin_operation_log"));
    }

    @Test
    void lostCommitAckIsUnknownAndMustNotReplayTheTargetChange() throws Exception {
        DatasourceStore uncertain = new DatasourceStore(faultSource(true));
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () ->
                                uncertain.update(
                                        source.id(),
                                        source.configVersion(),
                                        fields("127.0.0.2", 3306, "catalog"),
                                        null,
                                        null,
                                        1));
        assertEquals(ErrorCode.OPERATION_UNKNOWN, error.errorCode());
        assertEquals("127.0.0.2", store.get(source.id()).host());
        assertEquals(0, scalar("SELECT COUNT(*) FROM acl_grant WHERE status='ACTIVE'"));
        assertEquals(3, scalar("SELECT policy_epoch FROM sys_security_state"));
        assertEquals(2, scalar("SELECT COUNT(*) FROM sys_security_event"));
    }

    private static DataSource faultSource(boolean loseCommitAck) {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            if (!method.getName().equals("getConnection"))
                                throw new UnsupportedOperationException(method.getName());
                            Connection connection = metadata.getConnection();
                            return Proxy.newProxyInstance(
                                    Connection.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (unused, operation, values) -> {
                                        if (!loseCommitAck
                                                && operation.getName().equals("prepareStatement")
                                                && ((String) values[0])
                                                        .startsWith(
                                                                "INSERT INTO sys_security_event"))
                                            throw new SQLException("synthetic write failure");
                                        try {
                                            Object result = operation.invoke(connection, values);
                                            if (loseCommitAck
                                                    && operation.getName().equals("commit"))
                                                throw new SQLException(
                                                        "synthetic lost acknowledgement");
                                            return result;
                                        } catch (InvocationTargetException failure) {
                                            throw failure.getCause();
                                        }
                                    });
                        });
    }

    private static void execute(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.executeUpdate();
        }
    }

    private static long scalar(String sql) throws SQLException {
        try (Connection connection = metadata.getConnection();
                var statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String text(String sql, Object id) throws SQLException {
        try (Connection connection = metadata.getConnection();
                var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }
}
