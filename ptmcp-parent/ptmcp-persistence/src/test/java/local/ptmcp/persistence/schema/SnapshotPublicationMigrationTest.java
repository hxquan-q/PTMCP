package local.ptmcp.persistence.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Base64;

/** Upgrade boundary: immutable history is retained, and legacy candidates require a recapture. */
class SnapshotPublicationMigrationTest {
    @Test
    void upgradesV7WithoutTrustingPollutedResources() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        try (MySQLContainer mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("snapshot_upgrade_test")
                        .withUsername("upgrade_test")
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
                    .target("7")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            try (Connection connection =
                            DriverManager.getConnection(
                                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT INTO datasource (id,code,name,engine,host,port,database_name,"
                                + "username,credential_cipher,key_id,tls_mode,pool_max,status,config_version,"
                                + "resource_version,create_time,update_time,created_by,updated_by) VALUES "
                                + "(1,'synthetic','Synthetic','MYSQL','synthetic.invalid',3306,'synthetic','reader',"
                                + "X'00','synthetic','VERIFY_FULL',1,'ACTIVE',1,2,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
                statement.executeUpdate(
                        "INSERT INTO schema_snapshot (id,datasource_id,config_version,"
                                + "resource_version,status,captured_at,expires_at,object_count,checksum,create_time,update_time) "
                                + "VALUES (1,1,1,1,'PUBLISHED',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),"
                                + "0,UNHEX(SHA2('published',256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                                + "(2,1,1,2,'STAGING',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),"
                                + "0,UNHEX(SHA2('staged',256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                statement.executeUpdate("UPDATE datasource SET active_snapshot_id=1 WHERE id=1");
                statement.executeUpdate(
                        "INSERT INTO datasource (id,code,name,engine,host,port,database_name,"
                                + "username,credential_cipher,key_id,tls_mode,pool_max,status,config_version,"
                                + "resource_version,active_snapshot_id,create_time,update_time,created_by,updated_by) VALUES "
                                + "(2,'clean','Clean','MYSQL','synthetic.invalid',3306,'synthetic','reader',"
                                + "X'00','synthetic','VERIFY_FULL',1,'ACTIVE',1,2,3,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
                statement.executeUpdate(
                        "INSERT INTO schema_snapshot (id,datasource_id,config_version,"
                                + "resource_version,status,captured_at,expires_at,object_count,checksum,create_time,update_time) "
                                + "VALUES (3,2,1,1,'PUBLISHED',UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),"
                                + "1,UNHEX(SHA2('clean',256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                statement.executeUpdate(
                        "INSERT INTO resource_namespace (id,datasource_id,catalog_raw,catalog_norm,"
                                + "schema_raw,schema_norm,generation,status,create_time,update_time) VALUES "
                                + "(1,1,'catalog','catalog','public','public',1,'MISSING',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                                + "(2,1,'catalog','catalog','replacement','replacement',1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                                + "(3,2,'catalog','catalog','public','public',1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                statement.executeUpdate(
                        "INSERT INTO resource_object (id,namespace_id,generation,object_raw,object_norm,"
                                + "quoted,kind,status,definition_hash,first_seen_at,last_seen_at,create_time,update_time) VALUES "
                                + "(1,1,1,'alpha','alpha',0,'TABLE','MISSING',UNHEX(SHA2('alpha',256)),"
                                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                                + "(2,2,1,'beta','beta',0,'TABLE','ACTIVE',UNHEX(SHA2('beta',256)),"
                                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                                + "(3,3,1,'clean','clean',0,'TABLE','ACTIVE',UNHEX(SHA2('clean',256)),"
                                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                statement.executeUpdate(
                        "INSERT INTO snapshot_object (snapshot_id,object_id,object_version,"
                                + "column_signature,create_time,update_time) VALUES "
                                + "(1,1,1,UNHEX(SHA2('alpha',256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                                + "(2,2,1,UNHEX(SHA2('beta',256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),"
                                + "(3,3,1,UNHEX(SHA2('clean',256)),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                statement.executeUpdate(
                        "INSERT INTO acl_grant (subject_type,subject_id,resource_type,resource_id,"
                                + "resource_generation,action,effect,valid_from,status,reason,create_time,update_time,created_by,updated_by) "
                                + "VALUES ('USER',7,'OBJECT',2,1,'READ','ALLOW',UTC_TIMESTAMP(6),'ACTIVE','synthetic',"
                                + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
            }
            Flyway upgraded =
                    Flyway.configure()
                            .dataSource(
                                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                            .locations("classpath:db/ptmcp-migration")
                            .target("8")
                            .cleanDisabled(true)
                            .load();
            assertEquals(7, upgraded.migrate().migrationsExecuted);
            assertEquals(0, upgraded.migrate().migrationsExecuted);
            try (Connection connection =
                            DriverManager.getConnection(
                                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    Statement statement = connection.createStatement()) {
                try (ResultSet result =
                        statement.executeQuery("SELECT status FROM schema_snapshot ORDER BY id")) {
                    assertTrue(result.next());
                    assertEquals("RETIRED", result.getString(1));
                    assertTrue(result.next());
                    assertEquals("FAILED", result.getString(1));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT active_snapshot_id,resource_version FROM datasource WHERE id=1")) {
                    assertTrue(result.next());
                    assertEquals(null, result.getObject(1));
                    assertEquals(3, result.getLong(2));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT active_snapshot_id,resource_version FROM datasource WHERE id=2")) {
                    assertTrue(result.next());
                    assertEquals(3, result.getLong(1));
                    assertEquals(2, result.getLong(2));
                }
                try (ResultSet result =
                        statement.executeQuery("SELECT status FROM resource_object ORDER BY id")) {
                    assertTrue(result.next());
                    assertEquals("MISSING", result.getString(1));
                    assertTrue(result.next());
                    assertEquals("RETIRED", result.getString(1));
                    assertTrue(result.next());
                    assertEquals("ACTIVE", result.getString(1));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT status,reason,version FROM acl_grant WHERE resource_id=2")) {
                    assertTrue(result.next());
                    assertEquals("REVOKED", result.getString(1));
                    assertEquals("SNAPSHOT_UPGRADE", result.getString(2));
                    assertEquals(2, result.getLong(3));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM snapshot_object WHERE definition_hash IS NULL")) {
                    assertTrue(result.next());
                    assertEquals(3, result.getInt(1));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT policy_epoch FROM sys_security_state WHERE singleton_id=1")) {
                    assertTrue(result.next());
                    assertEquals(2, result.getLong(1));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT epoch,aggregate_id,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.version')) FROM sys_security_event WHERE event_type='ACL_CHANGED'")) {
                    assertTrue(result.next());
                    assertEquals(2, result.getLong(1));
                    assertEquals("1", result.getString(2));
                    assertEquals("3", result.getString(3));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                                        + "AND table_name='snapshot_object' AND column_name IN ('definition_hash','physical_identity')")) {
                    assertTrue(result.next());
                    assertEquals(2, result.getInt(1));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                                        + "AND table_name='datasource_candidate' AND column_name IN "
                                        + "('datasource_id','base_config_version','state','valid_until','credential_cipher','key_id','probe_scope')")) {
                    assertTrue(result.next());
                    assertEquals(7, result.getInt(1));
                }
                try (ResultSet result =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                                        + "AND table_name='metadata_sync_job' AND column_name='candidate_id'")) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
            }
        }
    }
}
