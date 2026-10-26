package local.ptmcp.datasource.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import local.ptmcp.persistence.schema.MetadataSyncJobRunner;
import local.ptmcp.persistence.schema.MetadataSyncJobStore;
import local.ptmcp.persistence.schema.SchemaSnapshotStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;

import javax.sql.DataSource;

/**
 * End-to-end capture through the real MySQL collector into the job store and atomic publish, on a
 * real container. No mocks: this is the wiring the sync worker will run in production.
 */
class MetadataSyncRunnerIntegrationTest {
    private static MySQLContainer mysql;
    private static DataSource source;
    private static MetadataSyncJobStore jobs;
    private static SchemaSnapshotStore snapshots;

    @BeforeAll
    static void startContainerAndSchema() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        String password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("runner_test")
                        .withUsername("runner_test")
                        .withPassword(password)
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
                    .load()
                    .migrate();
            try (Connection connection = open();
                    PreparedStatement insert =
                            connection.prepareStatement(
                                    "INSERT INTO datasource (id, code, name, engine, host, port, "
                                            + "database_name, username, credential_cipher, key_id, "
                                            + "tls_mode, pool_max, status, config_version, "
                                            + "resource_version, create_time, update_time, created_by, "
                                            + "updated_by) VALUES (1, 'runner-test', 'Runner Test', "
                                            + "'MYSQL', '127.0.0.1', 3306, 'runner_test', 'runner', "
                                            + "X'00', 'test-key', 'VERIFY_FULL', 1, 'ACTIVE', 1, 1, "
                                            + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 1)")) {
                insert.executeUpdate();
            }
            try (Connection connection =
                            DriverManager.getConnection(
                                    mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                    java.sql.Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE kept_items (id BIGINT PRIMARY KEY, label VARCHAR(32) NULL)");
            }
            source =
                    (DataSource)
                            java.lang.reflect.Proxy.newProxyInstance(
                                    DataSource.class.getClassLoader(),
                                    new Class<?>[] {DataSource.class},
                                    (proxy, method, args) -> {
                                        if (method.getName().equals("getConnection")) {
                                            return open();
                                        }
                                        throw new UnsupportedOperationException(method.getName());
                                    });
            jobs = new MetadataSyncJobStore(source);
            snapshots = new SchemaSnapshotStore(source);
        } catch (Exception | AssertionError failure) {
            mysql.stop();
            throw failure;
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    private static Connection open() throws SQLException {
        SQLException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return DriverManager.getConnection(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
            } catch (SQLException failure) {
                lastFailure = failure;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
        throw lastFailure;
    }

    @Test
    void runnerPublishesRealMysqlCaptureEndToEnd() {
        MetadataSyncJobRunner runner =
                new MetadataSyncJobRunner(source, jobs, snapshots, datasourceId -> open());
        long jobId = jobs.enqueue(1L, 1L, 1L);
        MetadataSyncJobRunner.Outcome outcome =
                runner.runQueued(1L, "worker-a", 60, new MysqlMetadataCollector());
        assertEquals(MetadataSyncJobRunner.Outcome.Status.SUCCEEDED, outcome.status());
        MetadataSyncJobStore.JobRow job = jobs.get(jobId);
        assertEquals("SUCCEEDED", job.state());
        assertEquals(2L, outcome.resourceVersion());
        SchemaSnapshotStore.ActiveSnapshot active = snapshots.requireUsableActiveSnapshot(1L);
        assertEquals(outcome.snapshotId(), active.snapshotId());
    }
}
