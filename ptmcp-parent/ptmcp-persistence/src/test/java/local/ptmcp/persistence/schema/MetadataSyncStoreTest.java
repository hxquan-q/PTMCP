package local.ptmcp.persistence.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * Real MySQL boundary for the sync-job state machine, atomic publish and the runner. No mocks and
 * no missing-Docker skips: the guard chain depends on these transitions being durable.
 */
class MetadataSyncStoreTest {
    private static MySQLContainer mysql;

    @BeforeAll
    static void startSyntheticMetadataDatabase() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69")
                        .withDatabaseName("sync_store_test")
                        .withUsername("sync_test")
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
        Flyway.configure()
                .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .locations("classpath:db/ptmcp-migration")
                .cleanDisabled(true)
                .baselineOnMigrate(false)
                .load()
                .migrate();
    }

    @AfterAll
    static void stopContainer() throws Exception {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void resetTables() throws Exception {
        try (Connection connection = open()) {
            update(connection, "DELETE FROM acl_grant");
            update(connection, "DELETE FROM snapshot_object");
            update(connection, "DELETE FROM resource_column");
            update(connection, "DELETE FROM resource_object");
            update(connection, "DELETE FROM resource_namespace");
            update(connection, "DELETE FROM schema_snapshot");
            update(connection, "DELETE FROM metadata_sync_job");
            update(connection, "DELETE FROM sys_security_event");
            update(connection, "DELETE FROM datasource");
            update(
                    connection,
                    "INSERT INTO datasource (id, code, name, engine, host, port, database_name, "
                            + "username, credential_cipher, key_id, tls_mode, pool_max, status, "
                            + "config_version, resource_version, create_time, update_time, "
                            + "created_by, updated_by) VALUES (1, 'sync-test', 'Sync Test', 'MYSQL', "
                            + "'127.0.0.1', 3306, 'synthetic', 'sync', X'00', 'test-key', "
                            + "'VERIFY_FULL', 1, 'ACTIVE', 1, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), "
                            + "1, 1)");
        }
    }

    @Test
    void concurrentClaimsElectExactlyOneWorker() throws Exception {
        jobStore().enqueue(1L, 1L, 1L);
        ExecutorService pool =
                new ThreadPoolExecutor(
                        2, 2, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            CallableClaim first = new CallableClaim(ready, start);
            CallableClaim second = new CallableClaim(ready, start);
            Future<Long> firstResult = pool.submit(first::call);
            Future<Long> secondResult = pool.submit(second::call);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            long firstJob = firstResult.get(10, TimeUnit.SECONDS);
            long secondJob = secondResult.get(10, TimeUnit.SECONDS);
            List<Long> claimed =
                    List.of(firstJob, secondJob).stream().filter(id -> id > 0).toList();
            assertEquals(1, claimed.size());
            MetadataSyncJobStore.JobRow job = jobStore().get(claimed.getFirst());
            assertEquals("RUNNING", job.state());
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class CallableClaim {
        private final CountDownLatch ready;
        private final CountDownLatch start;

        CallableClaim(CountDownLatch ready, CountDownLatch start) {
            this.ready = ready;
            this.start = start;
        }

        long call() throws Exception {
            ready.countDown();
            assertTrue(start.await(10, TimeUnit.SECONDS));
            return jobStore().claimQueued(1L, "worker", 60);
        }
    }

    @Test
    void cancelAppliesOnlyToQueuedJobs() throws Exception {
        MetadataSyncJobStore store = jobStore();
        long jobId = store.enqueue(1L, 1L, 1L);
        assertTrue(store.cancelQueued(jobId, 1L));
        assertEquals("CANCELLED", store.get(jobId).state());
        long runningJob = store.enqueue(1L, 1L, 1L);
        assertNotEquals(-1, store.claimQueued(1L, "worker", 60));
        assertFalse(store.cancelQueued(runningJob, 1L));
        assertEquals("RUNNING", store.get(runningJob).state());
    }

    @Test
    void expiredLeaseTakeoverFollowsPublishEvidence() throws Exception {
        MetadataSyncJobStore store = jobStore();
        long notExpired = store.enqueue(1L, 1L, 1L);
        store.claimQueued(1L, "worker-a", 300);
        assertEquals(
                MetadataSyncJobStore.TakeOver.NOT_TAKEN,
                store.takeOverExpired(notExpired, "worker-b", 60));
        store.finish(
                notExpired, "worker-a", MetadataSyncJobStore.Outcome.FAILED, null, "DRIVER_ERROR");

        long orphan = store.enqueue(1L, 1L, 1L);
        store.claimQueued(1L, "worker-a", 1);
        Thread.sleep(1200);
        assertEquals(
                MetadataSyncJobStore.TakeOver.TAKEN, store.takeOverExpired(orphan, "worker-b", 60));
        assertEquals("worker-b", store.get(orphan).leaseOwner());
        store.finish(orphan, "worker-b", MetadataSyncJobStore.Outcome.FAILED, null, "DRIVER_ERROR");

        long lostAfterPublish = store.enqueue(1L, 1L, 1L);
        store.claimQueued(1L, "worker-a", 1);
        SchemaSnapshotStore snapshots = snapshotStore();
        long snapshotId = snapshots.stage(1L, model("public", "kept"), nowUtc());
        snapshots.publish(snapshotId, 1L);
        Thread.sleep(1200);
        assertEquals(
                MetadataSyncJobStore.TakeOver.RECOVERED_PUBLISHED,
                store.takeOverExpired(lostAfterPublish, "worker-b", 60));
        MetadataSyncJobStore.JobRow recovered = store.get(lostAfterPublish);
        assertEquals("SUCCEEDED", recovered.state());
        assertEquals(snapshotId, recovered.snapshotId());
    }

    @Test
    void publishSwitchesActiveRetiresPreviousAndBumpsResourceVersion() throws Exception {
        SchemaSnapshotStore snapshots = snapshotStore();
        long first = snapshots.stage(1L, model("public", "alpha"), nowUtc());
        assertEquals(2L, snapshots.publish(first, 1L));
        SchemaSnapshotStore.ActiveSnapshot active = snapshots.requireUsableActiveSnapshot(1L);
        assertEquals(first, active.snapshotId());
        long second = snapshots.stage(1L, model("public", "alpha", "beta"), nowUtc());
        assertEquals(3L, snapshots.publish(second, 1L));
        assertEquals(second, snapshots.requireUsableActiveSnapshot(1L).snapshotId());
        assertEquals("RETIRED", statusOf("schema_snapshot", first));
    }

    @Test
    void configDriftRejectsPublishAndKeepsPreviousActive() throws Exception {
        SchemaSnapshotStore snapshots = snapshotStore();
        long first = snapshots.stage(1L, model("public", "alpha"), nowUtc());
        snapshots.publish(first, 1L);
        long second = snapshots.stage(1L, model("public", "alpha"), nowUtc());
        try (Connection connection = open()) {
            update(connection, "UPDATE datasource SET config_version = 2 WHERE id = 1");
        }
        DomainException error =
                assertThrows(DomainException.class, () -> snapshots.publish(second, 1L));
        assertEquals(ErrorCode.RESOURCE_VERSION_CHANGED, error.errorCode());
        assertEquals("PUBLISHED", statusOf("schema_snapshot", first));
        assertEquals("STAGING", statusOf("schema_snapshot", second));
        snapshots.markSnapshotFailed(second);
        assertEquals("FAILED", statusOf("schema_snapshot", second));
    }

    @Test
    void unusableActiveSnapshotsAreRejected() throws Exception {
        SchemaSnapshotStore snapshots = snapshotStore();
        assertThrows(DomainException.class, () -> snapshots.requireUsableActiveSnapshot(1L));
        long snapshotId = snapshots.stage(1L, model("public", "alpha"), nowUtc());
        snapshots.publish(snapshotId, 1L);
        try (Connection connection = open()) {
            update(
                    connection,
                    "UPDATE schema_snapshot SET captured_at = UTC_TIMESTAMP(6) - INTERVAL 2 DAY, "
                            + "expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE WHERE id = "
                            + snapshotId);
        }
        DomainException expired =
                assertThrows(
                        DomainException.class, () -> snapshots.requireUsableActiveSnapshot(1L));
        assertEquals(ErrorCode.SCHEMA_STALE, expired.errorCode());
        try (Connection connection = open()) {
            update(
                    connection,
                    "UPDATE schema_snapshot SET expires_at = ? WHERE id = " + snapshotId,
                    LocalDateTime.now(ZoneOffset.UTC).plusHours(1));
            update(connection, "UPDATE datasource SET config_version = 9 WHERE id = 1");
        }
        DomainException stale =
                assertThrows(
                        DomainException.class, () -> snapshots.requireUsableActiveSnapshot(1L));
        assertEquals(ErrorCode.SCHEMA_STALE, stale.errorCode());
    }

    @Test
    void dropRecreateStartsANewGenerationAndRevokesStaleGrants() throws Exception {
        SchemaSnapshotStore snapshots = snapshotStore();
        long first = snapshots.stage(1L, model("public", "alpha"), nowUtc());
        snapshots.publish(first, 1L);
        long objectId = singleObjectId();
        try (Connection connection = open()) {
            update(
                    connection,
                    "INSERT INTO acl_grant (subject_type, subject_id, resource_type, resource_id, "
                            + "resource_generation, action, effect, valid_from, status, reason, "
                            + "create_time, update_time, created_by, updated_by) VALUES "
                            + "('USER', 7, 'OBJECT', ?, 1, 'READ', 'ALLOW', UTC_TIMESTAMP(6) - INTERVAL 1 DAY, "
                            + "'ACTIVE', 'test', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 1)",
                    objectId);
        }

        long second = snapshots.stage(1L, model("public", "beta"), nowUtc());
        snapshots.publish(second, 1L);
        assertEquals("MISSING", statusOf("resource_object", objectId));
        try (Connection connection = open()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT status, reason, version FROM acl_grant WHERE resource_id = ?")) {
                statement.setLong(1, objectId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("REVOKED", result.getString(1));
                    assertEquals("SCHEMA_RETIRED", result.getString(2));
                    assertEquals(2L, result.getLong(3));
                }
            }
        }

        long third = snapshots.stage(1L, model("public", "alpha"), nowUtc());
        snapshots.publish(third, 1L);
        try (Connection connection = open()) {
            try (PreparedStatement statement =
                            connection.prepareStatement(
                                    "SELECT generation, status FROM resource_object "
                                            + "WHERE object_norm = 'alpha' ORDER BY generation");
                    ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1L, result.getLong(1));
                assertEquals("MISSING", result.getString(2));
                assertTrue(result.next());
                // Generation is allocated per namespace (MAX + 1), so beta's recreation consumed
                // generation 2 and alpha's return starts at 3.
                assertEquals(3L, result.getLong(1));
                assertEquals("ACTIVE", result.getString(2));
            }
        }
    }

    @Test
    void runnerPublishesCapturedSnapshotEndToEnd() throws Exception {
        MetadataSyncJobStore jobs = jobStore();
        SchemaSnapshotStore snapshots = snapshotStore();
        MetadataSyncJobRunner runner =
                new MetadataSyncJobRunner(source(), jobs, snapshots, connection -> open());
        long jobId = jobs.enqueue(1L, 1L, 1L);
        MetadataSyncJobRunner.Outcome outcome =
                runner.runQueued(1L, "worker-a", 60, connection -> model("public", "alpha"));
        assertEquals(MetadataSyncJobRunner.Outcome.Status.SUCCEEDED, outcome.status());
        MetadataSyncJobStore.JobRow job = jobs.get(jobId);
        assertEquals("SUCCEEDED", job.state());
        assertEquals(outcome.snapshotId(), job.snapshotId());
        assertEquals(outcome.snapshotId(), snapshots.requireUsableActiveSnapshot(1L).snapshotId());
        assertEquals(2L, outcome.resourceVersion());
    }

    @Test
    void runnerFailureKeepsPreviousActiveAndMarksJobFailed() throws Exception {
        MetadataSyncJobStore jobs = jobStore();
        SchemaSnapshotStore snapshots = snapshotStore();
        MetadataSyncJobRunner runner =
                new MetadataSyncJobRunner(source(), jobs, snapshots, connection -> open());
        jobs.enqueue(1L, 1L, 1L);
        MetadataSyncJobRunner.Outcome good =
                runner.runQueued(1L, "worker-a", 60, connection -> model("public", "alpha"));
        assertEquals(MetadataSyncJobRunner.Outcome.Status.SUCCEEDED, good.status());
        long activeBefore = snapshots.requireUsableActiveSnapshot(1L).snapshotId();

        jobs.enqueue(1L, 1L, 1L);
        MetadataSyncJobRunner.Outcome bad =
                runner.runQueued(
                        1L,
                        "worker-b",
                        60,
                        connection -> {
                            throw new RuntimeException("synthetic collection failure");
                        });
        assertEquals(MetadataSyncJobRunner.Outcome.Status.FAILED, bad.status());
        assertEquals("DRIVER_ERROR", bad.errorCode());
        assertEquals(activeBefore, snapshots.requireUsableActiveSnapshot(1L).snapshotId());
        List<Long> jobIds = jobIds();
        MetadataSyncJobStore.JobRow failedJob = jobs.get(jobIds.getLast());
        assertEquals("FAILED", failedJob.state());
        assertEquals("DRIVER_ERROR", jobError(jobIds.getLast()));
    }

    @Test
    void runnerRejectsInactiveDatasourceWithoutOpeningMetadataConnection() throws Exception {
        MetadataSyncJobStore jobs = jobStore();
        SchemaSnapshotStore snapshots = snapshotStore();
        MetadataSyncJobRunner runner =
                new MetadataSyncJobRunner(
                        source(),
                        jobs,
                        snapshots,
                        connection -> {
                            throw new SQLException("metadata connection must not be opened");
                        });
        long jobId = jobs.enqueue(1L, 1L, 1L);
        try (Connection connection = open()) {
            update(connection, "UPDATE datasource SET status = 'DISABLED' WHERE id = 1");
        }
        MetadataSyncJobRunner.Outcome outcome =
                runner.runQueued(1L, "worker-a", 60, connection -> model("public", "alpha"));
        assertEquals(MetadataSyncJobRunner.Outcome.Status.FAILED, outcome.status());
        assertEquals("DRIVER_ERROR", outcome.errorCode());
        assertEquals("FAILED", jobs.get(jobId).state());
    }

    private static String jobError(long jobId) throws SQLException {
        try (Connection connection = open()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT error_code FROM metadata_sync_job WHERE id = ?")) {
                statement.setLong(1, jobId);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            }
        }
    }

    private static List<Long> jobIds() throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT id FROM metadata_sync_job")) {
            List<Long> ids = new ArrayList<>();
            while (result.next()) {
                ids.add(result.getLong(1));
            }
            return ids;
        }
    }

    private static long singleObjectId() throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery("SELECT id FROM resource_object LIMIT 1")) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static String statusOf(String table, long id) throws SQLException {
        try (Connection connection = open()) {
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT status FROM " + table + " WHERE id = ?")) {
                statement.setLong(1, id);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    return result.getString(1);
                }
            }
        }
    }

    static MetadataSyncJobStore jobStore() {
        return new MetadataSyncJobStore(source());
    }

    static SchemaSnapshotStore snapshotStore() {
        return new SchemaSnapshotStore(source());
    }

    static SnapshotModel model(String schema, String... objectNames) {
        List<SnapshotModel.ObjectModel> objects = new ArrayList<>();
        for (String name : objectNames) {
            objects.add(object(name));
        }
        SnapshotModel.NamespaceModel namespace =
                new SnapshotModel.NamespaceModel("catalog", "catalog", schema, schema, objects);
        return new SnapshotModel("synthetic", "TRUE", List.of(namespace));
    }

    private static SnapshotModel.ObjectModel object(String name) {
        SnapshotModel.ColumnModel id =
                new SnapshotModel.ColumnModel(
                        1,
                        "id",
                        "id",
                        false,
                        "int4",
                        Types.INTEGER,
                        false,
                        null,
                        null,
                        null,
                        null,
                        true,
                        null);
        SnapshotModel.ColumnModel label =
                new SnapshotModel.ColumnModel(
                        2,
                        "label",
                        "label",
                        false,
                        "varchar",
                        Types.VARCHAR,
                        true,
                        null,
                        null,
                        64L,
                        null,
                        false,
                        null);
        return new SnapshotModel.ObjectModel(
                name,
                name,
                false,
                SnapshotModel.ObjectModel.KIND_TABLE,
                sha256(name),
                null,
                null,
                List.of(id, label));
    }

    private static byte[] sha256(String text) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static LocalDateTime nowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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

    private static void update(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < args.length; index++) {
                if (args[index] instanceof LocalDateTime moment) {
                    statement.setObject(index + 1, moment);
                } else if (args[index] instanceof Long value) {
                    statement.setLong(index + 1, value);
                } else {
                    statement.setObject(index + 1, args[index]);
                }
            }
            statement.executeUpdate();
        }
    }

    private static DataSource source() {
        return (DataSource)
                java.lang.reflect.Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getConnection")) {
                                return open();
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }
}
