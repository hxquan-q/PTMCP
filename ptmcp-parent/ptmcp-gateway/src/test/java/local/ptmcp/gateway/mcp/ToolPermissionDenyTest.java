package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.authz.PermissionEvaluator;
import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.persistence.org.AuthorizationReader;
import local.ptmcp.persistence.schema.PublishedSnapshotReader;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;

/**
 * Object-level READ DENY against real metadata rows: exercised directly through the readers and the
 * permission service without HTTP or Spring, pinning the evaluation semantics (deny on the exact
 * live object generation beats a datasource-level allow) independently of the transport.
 */
class ToolPermissionDenyTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final MySQLContainer META =
            new MySQLContainer(
                            System.getProperty(
                                    "ptmcp.test.mysqlImage",
                                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                    .withDatabaseName("deny_repro_meta")
                    .withUsername("repro_user")
                    .withPassword("repro_password")
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final HikariDataSource PRIMARY;

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
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static void seed() throws Exception {
        try (Connection meta = PRIMARY.getConnection();
                Statement sql = meta.createStatement()) {
            sql.execute(
                    "INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,status,del_flag,create_time,update_time) VALUES (1,0,'0','test','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,create_time,update_time) VALUES (1,1,'test','unused','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO platform_registry (id,code,name,tier,status,catalog_mode,proof_mode,create_time,update_time,created_by,updated_by) VALUES (1,'test','test',3,'ACTIVE','DISCOVER','NONE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
        }
        CredentialCipher.Sealed credential = new CredentialCipher(KEY).encrypt("unused");
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement sql =
                        meta.prepareStatement(
                                "INSERT INTO datasource (id,code,name,engine,host,port,database_name,username,credential_cipher,key_id,tls_mode,pool_max,status,create_time,update_time,created_by,updated_by) VALUES (1,'test','test','MYSQL','127.0.0.1',3306,'db','u',?,?,'VERIFY_FULL',2,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)")) {
            sql.setBytes(1, credential.ciphertext());
            sql.setString(2, credential.keyId());
            sql.executeUpdate();
        }
        // A minimal snapshot without a business database: stage a synthetic model through the
        // store's public API is not possible without a live connection, so insert the published
        // snapshot rows directly for this focused evaluation test.
        try (Connection meta = PRIMARY.getConnection();
                Statement sql = meta.createStatement()) {
            sql.execute(
                    "INSERT INTO resource_namespace (id,datasource_id,catalog_raw,catalog_norm,schema_raw,schema_norm,generation,status,create_time,update_time) VALUES (1,1,'db','db','','',1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO resource_object (id,namespace_id,generation,object_raw,object_norm,quoted,kind,status,definition_hash,first_seen_at,last_seen_at,create_time,update_time) VALUES"
                            + " (1,1,1,'orders','orders',0,'TABLE','ACTIVE',UNHEX('0000000000000000000000000000000000000000000000000000000000000001'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                            + " (2,1,1,'secret','secret',0,'TABLE','ACTIVE',UNHEX('0000000000000000000000000000000000000000000000000000000000000002'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO schema_snapshot (id,datasource_id,config_version,resource_version,status,captured_at,expires_at,object_count,checksum,create_time,update_time) VALUES (1,1,1,1,'PUBLISHED',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),2,UNHEX('0000000000000000000000000000000000000000000000000000000000000003'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute("UPDATE datasource SET active_snapshot_id = 1 WHERE id = 1");
            sql.execute(
                    "INSERT INTO snapshot_object (snapshot_id,object_id,object_version,table_comment,column_signature,create_time,update_time) VALUES (1,1,1,'',UNHEX('01'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(1,2,1,'',UNHEX('02'),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO platform_scope (platform_id,resource_type,resource_id,resource_generation,action,include_descendants,create_time,update_time,created_by,updated_by) VALUES (1,'DATASOURCE',1,1,'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),(1,'DATASOURCE',1,1,'META',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
            sql.execute(
                    "INSERT INTO acl_grant (subject_type,subject_id,resource_type,resource_id,resource_generation,action,effect,include_descendants,valid_from,status,reason,create_time,update_time,created_by,updated_by) VALUES"
                            + " ('USER',1,'DATASOURCE',1,1,'READ','ALLOW',1,UTC_TIMESTAMP(6),'ACTIVE','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),"
                            + " ('USER',1,'OBJECT',2,1,'READ','DENY',0,UTC_TIMESTAMP(6),'ACTIVE','synthetic deny',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
        }
        try (Connection meta = PRIMARY.getConnection();
                Statement sql = meta.createStatement();
                ResultSet rows =
                        sql.executeQuery(
                                "SELECT id, subject_type, resource_type, resource_id,"
                                        + " resource_generation, action, effect, status FROM"
                                        + " acl_grant ORDER BY id")) {
            while (rows.next()) {
                System.out.println(
                        "acl_grant row: id="
                                + rows.getLong(1)
                                + " subject="
                                + rows.getString(2)
                                + " resource="
                                + rows.getString(3)
                                + ":"
                                + rows.getLong(4)
                                + " gen="
                                + rows.getLong(5)
                                + " "
                                + rows.getString(6)
                                + "/"
                                + rows.getString(7)
                                + " status="
                                + rows.getString(8));
            }
        }
    }

    @Test
    void objectReadDenyBeatsDatasourceAllow() {
        AuthorizationReader authorization = new AuthorizationReader(PRIMARY);
        PublishedSnapshotReader snapshots = new PublishedSnapshotReader(PRIMARY);
        PublishedSnapshotReader.Published published = snapshots.load(1);
        assertTrue(published.usable());
        ToolPermissionService permissions = new ToolPermissionService(authorization, true);
        ToolPermissionService.Session session = permissions.open(1, 1, 1, published);

        PermissionEvaluator.Decision orders = session.decide(1);
        PermissionEvaluator.Decision secret = session.decide(2);

        assertTrue(orders.read(), "the datasource-level ALLOW must grant READ on orders");
        assertFalse(secret.read(), "the object-level READ DENY must beat the datasource ALLOW");
    }

    @AfterAll
    static void cleanup() {
        PRIMARY.close();
        META.stop();
    }
}
