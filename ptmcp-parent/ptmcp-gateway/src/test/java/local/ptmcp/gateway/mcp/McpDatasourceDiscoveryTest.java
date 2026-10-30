package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.persistence.org.AuthorizationReader;
import local.ptmcp.persistence.schema.PublishedSnapshotReader;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;

/**
 * Datasource discovery evaluation against real metadata rows: a usable published datasource is
 * listed only when at least one of its objects is visible under the caller's platform/user
 * intersection, with the same DISCOVER/HIDE semantics as list_databases_and_tables. Exercised
 * directly through the readers and the permission service without HTTP or Spring; unusable
 * (unpublished or expired) datasources must never appear in any mode.
 */
class McpDatasourceDiscoveryTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final MySQLContainer META =
            new MySQLContainer(
                            System.getProperty(
                                    "ptmcp.test.mysqlImage",
                                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                    .withDatabaseName("discovery_meta")
                    .withUsername("repro_user")
                    .withPassword("repro_password")
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final HikariDataSource PRIMARY;
    private static final DatasourceDiscoveryService DISCOVERY;

    static {
        try {
            META.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(META.getJdbcUrl());
            config.setUsername(META.getUsername());
            config.setPassword(META.getPassword());
            config.setInitializationFailTimeout(15000);
            PRIMARY = new HikariDataSource(config);
            Flyway.configure()
                    .dataSource(PRIMARY)
                    .locations("classpath:db/ptmcp-migration")
                    .load()
                    .migrate();
            seed();
            AuthorizationReader authorization = new AuthorizationReader(PRIMARY);
            DISCOVERY =
                    new DatasourceDiscoveryService(
                            new PublishedSnapshotReader(PRIMARY),
                            authorization,
                            new ToolPermissionService(authorization, true));
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    /**
     * Datasource 1 (MYSQL, snapshot published and unexpired) is granted to user 1; datasource 2
     * (POSTGRESQL, usable) is granted to user 2 only; datasource 3 is ACTIVE without any snapshot;
     * datasource 4 points at an expired snapshot. All four sit inside the platform ceiling so the
     * assertions below isolate usability and per-user visibility.
     */
    private static void seed() throws Exception {
        CredentialCipher.Sealed credential = new CredentialCipher(KEY).encrypt("unused");
        String[][] datasources = {
            {"1", "orders", "Orders DB", "MYSQL", "orders_db"},
            {"2", "ledger", "Ledger DB", "POSTGRESQL", "ledger_db"},
            {"3", "draft", "Draft DB", "MYSQL", "draft_db"},
            {"4", "stale", "Stale DB", "MYSQL", "stale_db"}
        };
        for (String[] datasource : datasources) {
            try (Connection meta = PRIMARY.getConnection();
                    PreparedStatement sql =
                            meta.prepareStatement(
                                    "INSERT INTO datasource (id,code,name,engine,host,port,database_name,username,credential_cipher,key_id,tls_mode,pool_max,status,create_time,update_time,created_by,updated_by) VALUES (?,?,?,?,'127.0.0.1',3306,?,'u',?,?,'VERIFY_FULL',2,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)")) {
                sql.setLong(1, Long.parseLong(datasource[0]));
                sql.setString(2, datasource[1]);
                sql.setString(3, datasource[2]);
                sql.setString(4, datasource[3]);
                sql.setString(5, datasource[4]);
                sql.setBytes(6, credential.ciphertext());
                sql.setString(7, credential.keyId());
                sql.executeUpdate();
            }
        }
        try (Connection meta = PRIMARY.getConnection();
                Statement sql = meta.createStatement()) {
            sql.execute(
                    "INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,status,del_flag,create_time,update_time) VALUES (1,0,'0','test','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,create_time,update_time) VALUES"
                            + " (1,1,'one','unused','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (2,1,'two','unused','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO platform_registry (id,code,name,tier,status,catalog_mode,proof_mode,create_time,update_time,created_by,updated_by) VALUES (1,'test','test',3,'ACTIVE','HIDE','NONE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
            sql.execute(
                    "INSERT INTO resource_namespace (id,datasource_id,catalog_raw,catalog_norm,schema_raw,schema_norm,generation,status,create_time,update_time) VALUES"
                            + " (1,1,'orders_db','orders_db','','',1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (2,2,'ledger_db','ledger_db','public','public',1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (4,4,'stale_db','stale_db','','',1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO resource_object (id,namespace_id,generation,object_raw,object_norm,quoted,kind,status,definition_hash,first_seen_at,last_seen_at,create_time,update_time) VALUES"
                            + " (1,1,1,'orders','orders',0,'TABLE','ACTIVE',UNHEX('0000000000000000000000000000000000000000000000000000000000000001'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (2,1,1,'secret','secret',0,'TABLE','ACTIVE',UNHEX('0000000000000000000000000000000000000000000000000000000000000002'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (3,2,1,'entries','entries',0,'TABLE','ACTIVE',UNHEX('0000000000000000000000000000000000000000000000000000000000000003'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (4,4,1,'old_entries','old_entries',0,'TABLE','ACTIVE',UNHEX('0000000000000000000000000000000000000000000000000000000000000004'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO schema_snapshot (id,datasource_id,config_version,resource_version,status,captured_at,expires_at,object_count,checksum,create_time,update_time) VALUES"
                            + " (101,1,1,1,'PUBLISHED',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),2,UNHEX('0000000000000000000000000000000000000000000000000000000000000011'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (102,2,1,1,'PUBLISHED',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),1,UNHEX('0000000000000000000000000000000000000000000000000000000000000012'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (104,4,1,1,'PUBLISHED',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL -2 DAY),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL -1 DAY),1,UNHEX('0000000000000000000000000000000000000000000000000000000000000013'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute("UPDATE datasource SET active_snapshot_id = 101 WHERE id = 1");
            sql.execute("UPDATE datasource SET active_snapshot_id = 102 WHERE id = 2");
            sql.execute("UPDATE datasource SET active_snapshot_id = 104 WHERE id = 4");
            sql.execute(
                    "INSERT INTO snapshot_object (snapshot_id,object_id,object_version,table_comment,column_signature,create_time,update_time) VALUES"
                            + " (101,1,1,'',UNHEX('01'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (101,2,1,'',UNHEX('02'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (102,3,1,'',UNHEX('03'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (104,4,1,'',UNHEX('04'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO platform_scope (platform_id,resource_type,resource_id,resource_generation,action,include_descendants,create_time,update_time,created_by,updated_by) VALUES"
                            + " (1,'DATASOURCE',1,1,'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),(1,'DATASOURCE',1,1,'META',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),"
                            + " (1,'DATASOURCE',2,1,'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),(1,'DATASOURCE',2,1,'META',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),"
                            + " (1,'DATASOURCE',3,1,'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),(1,'DATASOURCE',3,1,'META',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),"
                            + " (1,'DATASOURCE',4,1,'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),(1,'DATASOURCE',4,1,'META',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
            sql.execute(
                    "INSERT INTO acl_grant (subject_type,subject_id,resource_type,resource_id,resource_generation,action,effect,include_descendants,valid_from,status,reason,create_time,update_time,created_by,updated_by) VALUES"
                            + " ('USER',1,'DATASOURCE',1,1,'READ','ALLOW',1,UTC_TIMESTAMP(6),'ACTIVE','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),"
                            + " ('USER',2,'DATASOURCE',2,1,'READ','ALLOW',1,UTC_TIMESTAMP(6),'ACTIVE','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
        }
    }

    @AfterEach
    void restoreStrictCatalog() throws Exception {
        setCatalogMode("HIDE");
    }

    @Test
    void hideModeListsOnlyDatasourcesWithVisibleObjectsPerUser() {
        setCatalogModeQuietly("HIDE");
        List<DatasourceDiscoveryService.VisibleDatasource> mine = DISCOVERY.listVisible(1L, 1L);
        assertEquals(1, mine.size());
        DatasourceDiscoveryService.VisibleDatasource orders = mine.get(0);
        assertEquals(1L, orders.datasourceId());
        assertEquals("orders", orders.code());
        assertEquals("Orders DB", orders.name());
        assertEquals("MYSQL", orders.engine());
        assertEquals("orders_db", orders.databaseName());
        assertTrue(orders.queryable(), "user 1 holds READ on datasource 1 objects");

        List<DatasourceDiscoveryService.VisibleDatasource> theirs = DISCOVERY.listVisible(2L, 1L);
        assertEquals(1, theirs.size());
        assertEquals(2L, theirs.get(0).datasourceId());
        assertEquals("POSTGRESQL", theirs.get(0).engine());
        assertTrue(theirs.get(0).queryable());
    }

    @Test
    void discoverModeListsPlatformVisibleDatasourcesWithQueryableFlag() {
        setCatalogModeQuietly("DISCOVER");
        List<DatasourceDiscoveryService.VisibleDatasource> mine = DISCOVERY.listVisible(1L, 1L);
        assertEquals(2, mine.size(), "platform-visible usable datasources, id ordered");
        assertEquals(1L, mine.get(0).datasourceId());
        assertTrue(mine.get(0).queryable());
        DatasourceDiscoveryService.VisibleDatasource ledger = mine.get(1);
        assertEquals(2L, ledger.datasourceId());
        assertFalse(
                ledger.queryable(), "DISCOVER lists the platform ceiling without user READ grants");
        assertEquals("ledger", ledger.code());
        assertEquals("ledger_db", ledger.databaseName());
    }

    @Test
    void discoverModeHidesDatasourceWhenMetaDenyCoversEveryObject() throws Exception {
        setCatalogModeQuietly("DISCOVER");
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement sql =
                        meta.prepareStatement(
                                "INSERT INTO acl_grant (subject_type,subject_id,resource_type,resource_id,resource_generation,action,effect,include_descendants,valid_from,status,reason,create_time,update_time,created_by,updated_by) VALUES ('USER',1,'DATASOURCE',1,1,'META','DENY',1,UTC_TIMESTAMP(6),'ACTIVE','synthetic deny',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)")) {
            sql.executeUpdate();
        }
        try {
            List<DatasourceDiscoveryService.VisibleDatasource> mine = DISCOVERY.listVisible(1L, 1L);
            assertEquals(1, mine.size(), "the META deny hides every object of datasource 1");
            assertEquals(2L, mine.get(0).datasourceId());
        } finally {
            try (Connection meta = PRIMARY.getConnection();
                    PreparedStatement sql =
                            meta.prepareStatement(
                                    "DELETE FROM acl_grant WHERE subject_type = 'USER' AND subject_id = 1 AND action = 'META' AND effect = 'DENY' AND reason = 'synthetic deny'")) {
                sql.executeUpdate();
            }
        }
    }

    private static void setCatalogModeQuietly(String mode) {
        try {
            setCatalogMode(mode);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void setCatalogMode(String mode) throws Exception {
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement sql =
                        meta.prepareStatement(
                                "UPDATE platform_registry SET catalog_mode = ? WHERE id = 1")) {
            sql.setString(1, mode);
            sql.executeUpdate();
        }
    }
}
