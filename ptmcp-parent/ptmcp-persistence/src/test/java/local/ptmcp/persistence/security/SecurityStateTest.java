package local.ptmcp.persistence.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

/** Real MySQL boundary for snapshot freshness, revocations and admission quotas (T12-a). */
class SecurityStateTest {
    private static final UUID FAMILY_1 = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID FAMILY_2 = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID FAMILY_3 = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 24, 12, 0, 0);
    private static MySQLContainer mysql;

    @BeforeAll
    static void startSyntheticMetadataDatabase() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("security_state_test")
                        .withUsername("security_test")
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
            Flyway.configure()
                    .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/ptmcp-migration")
                    .initSql("SET time_zone = '+00:00'")
                    .target("11")
                    .cleanDisabled(true)
                    .baselineOnMigrate(false)
                    .load()
                    .migrate();
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

    @BeforeEach
    void resetOwnedSecurityData() throws Exception {
        try (Connection connection = open()) {
            update(connection, "DELETE FROM sys_user_security");
            update(connection, "DELETE FROM oauth_refresh_token");
            update(connection, "DELETE FROM oauth_refresh_family");
            update(connection, "DELETE FROM oauth2_authorization_consent");
            update(connection, "DELETE FROM oauth2_authorization");
            update(connection, "DELETE FROM oauth2_registered_client");
            update(connection, "DELETE FROM platform_registry");
            update(connection, "DELETE FROM sys_security_event");
            update(connection, "DELETE FROM security_consumer_cursor");
            update(
                    connection,
                    "UPDATE sys_security_state SET policy_epoch = 1 WHERE singleton_id = 1");
            insertFixture(connection);
        }
    }

    private static void insertFixture(Connection connection) throws SQLException {
        update(
                connection,
                "INSERT INTO platform_registry (id, code, name, tier, status, catalog_mode, "
                        + "proof_mode, scope_version, security_version, create_time, update_time, "
                        + "created_by, updated_by) VALUES (1, 'SEC_TEST', 'Security Test', 1, "
                        + "'ACTIVE', 'DISCOVER', 'MTLS', 1, 1, UTC_TIMESTAMP(6), "
                        + "UTC_TIMESTAMP(6), 1, 1)");
        update(
                connection,
                "INSERT INTO oauth2_registered_client (id, client_id, client_secret, client_name, "
                        + "client_authentication_methods, authorization_grant_types, redirect_uris, "
                        + "post_logout_redirect_uris, scopes, client_settings, token_settings, "
                        + "platform_id, client_type, client_secret_hash, jwks_uri, metadata_uri, "
                        + "metadata_digest, status, registration_source, version, create_time, "
                        + "update_time, created_by, updated_by) VALUES ('client-1', 'client-1', "
                        + "NULL, 'Security client', 'client_secret_basic', 'authorization_code', "
                        + "'https://app.example/callback', NULL, 'openid', '{}', '{}', 1, "
                        + "'CONFIDENTIAL', X'00', NULL, NULL, NULL, 'ACTIVE', 'ADMIN', 1, "
                        + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 1)");
        update(
                connection,
                "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, "
                        + "authorization_grant_type, status, create_time, update_time, created_by, "
                        + "updated_by) VALUES ('auth-1', 'client-1', 'alice', "
                        + "'authorization_code', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), "
                        + "1, 1)");
        update(
                connection,
                "INSERT INTO sys_user_security (user_id,auth_version,identity_version,version,create_time,update_time) VALUES (7,3,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),(8,2,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        insertFamily(connection, FAMILY_1, 7L, 1L);
        insertFamily(connection, FAMILY_2, 7L, 3L);
        insertFamily(connection, FAMILY_3, 8L, 2L);
        update(
                connection,
                "INSERT INTO oauth_refresh_token (token_hash, family_id, generation, issued_at, "
                        + "expires_at, status, create_time, update_time) VALUES (X'01', '"
                        + FAMILY_1
                        + "', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 7 DAY, 'ACTIVE', "
                        + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    private static void insertFamily(
            Connection connection, UUID familyId, long userId, long authVersion)
            throws SQLException {
        update(
                connection,
                "INSERT INTO oauth_refresh_family (id, authorization_id, user_id, "
                        + "registered_client_id, platform_id, status, auth_version, "
                        + "absolute_expires_at, idle_expires_at, last_rotated_at, version, "
                        + "create_time, update_time) VALUES ('"
                        + familyId
                        + "', 'auth-1', "
                        + userId
                        + ", 'client-1', 1, 'ACTIVE', "
                        + authVersion
                        + ", UTC_TIMESTAMP(6) + INTERVAL 30 DAY, UTC_TIMESTAMP(6) + INTERVAL 7 DAY, "
                        + "UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
    }

    @Test
    void readerProducesActiveOnlySnapshotWithPlatformAndSubjectVersions() throws Exception {
        SecurityStateReader reader = new SecurityStateReader(source());
        SecurityStateReader.SubjectRequest request =
                new SecurityStateReader.SubjectRequest(
                        1L, 7L, List.of(FAMILY_1, FAMILY_2, FAMILY_3));
        SecuritySnapshot snapshot = reader.read(request);

        assertEquals(1L, snapshot.policyEpoch());
        assertEquals(1L, snapshot.platformVersion());
        assertEquals(Map.of(7L, 3L), snapshot.subjectVersions(), "one subject per request");
        assertEquals(Map.of(FAMILY_1, 1L, FAMILY_2, 1L, FAMILY_3, 1L), snapshot.familyVersions());
        assertNotNull(snapshot.checkedAt());
        assertTrue(snapshot.isFresh(Instant.now()));

        try (Connection connection = open()) {
            update(
                    connection,
                    "UPDATE oauth_refresh_family SET status = 'REVOKED', revoked_at = "
                            + "UTC_TIMESTAMP(6), revoke_reason = 'test', version = version + 1, "
                            + "update_time = UTC_TIMESTAMP(6) WHERE id = '"
                            + FAMILY_2
                            + "'");
        }
        SecuritySnapshot after = reader.read(request);
        assertEquals(
                Map.of(FAMILY_1, 1L, FAMILY_3, 1L),
                after.familyVersions(),
                "a revoked family disappears from the snapshot");
        assertEquals(
                Map.of(7L, 3L),
                after.subjectVersions(),
                "family revocation cannot roll back the user version");
    }

    @Test
    void aSubjectWithoutAnOAuthFamilyStillHasAnAuthoritativeVersion() throws Exception {
        try (Connection connection = open()) {
            update(connection, "UPDATE sys_user_security SET auth_version = 11 WHERE user_id = 7");
            update(connection, "DELETE FROM oauth_refresh_token");
            update(connection, "DELETE FROM oauth_refresh_family");
        }
        SecuritySnapshot state =
                new SecurityStateReader(source())
                        .read(new SecurityStateReader.SubjectRequest(1L, 7L, List.of()));
        assertEquals(Map.of(7L, 11L), state.subjectVersions());
    }

    @Test
    void aPendingIdentityDoesNotSupplyAnActiveUserVersion() throws Exception {
        try (Connection connection = open()) {
            update(
                    connection,
                    "UPDATE sys_user_security SET identity_state = 'CHANGE_PENDING', identity_change_id = '00000000-0000-0000-0000-000000000001' WHERE user_id = 7");
        }
        SecuritySnapshot state =
                new SecurityStateReader(source())
                        .read(new SecurityStateReader.SubjectRequest(1L, 7L, List.of()));
        assertEquals(0L, state.subjectVersions().get(7L));
    }

    @Test
    void snapshotFreshnessCoversExactlyFiveSeconds() {
        Instant checkedAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        SecuritySnapshot snapshot = new SecuritySnapshot(1, Map.of(), 1, Map.of(), checkedAt);
        SecurityStateGuard guard = new SecurityStateGuard();

        assertTrue(guard.isUsable(snapshot, checkedAt));
        assertTrue(guard.isUsable(snapshot, checkedAt.plusSeconds(5)));
        assertFalse(guard.isUsable(snapshot, checkedAt.plusNanos(5_000_000_001L)));
        assertFalse(guard.isUsable(snapshot, checkedAt.minusNanos(1)));
        assertFalse(guard.isUsable(null, Instant.now()));
    }

    @Test
    void localCacheExpiresAfterOneSecondAndNeverRenewsOnLookup() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        SecurityStateGuard guard = new SecurityStateGuard(clock::get);
        SecurityStateGuard.CacheKey key = new SecurityStateGuard.CacheKey(1, 7L, List.of());
        Instant checkedAt = Instant.now();
        SecuritySnapshot snapshot = new SecuritySnapshot(1, Map.of(), 1, Map.of(), checkedAt);

        guard.cache(key, snapshot);
        assertEquals(snapshot, guard.cached(key), "hit inside the TTL window");

        clock.set(1_000_000L + 999_999_999L);
        assertEquals(snapshot, guard.cached(key), "still inside one second");

        clock.set(1_000_000L + 1_000_000_001L);
        assertNull(guard.cached(key), "expired entry is evicted, not renewed");
        assertNull(guard.cached(key));

        guard.cache(key, snapshot);
        clock.set(1_000_000L + 2_000_000_002L);
        assertNull(guard.cached(key), "re-caching does not extend the old window");
    }

    @Test
    void guardRefusesToCacheStaleSnapshotsAndBlocksAfterThreeConsecutiveFailures() {
        SecurityStateGuard guard = new SecurityStateGuard();
        Instant old = Instant.now().minusSeconds(30);
        SecuritySnapshot stale = new SecuritySnapshot(1, Map.of(), 1, Map.of(), old);
        DomainException rejected =
                assertThrows(
                        DomainException.class,
                        () ->
                                guard.cache(
                                        new SecurityStateGuard.CacheKey(1, 7L, List.of()), stale));
        assertEquals(ErrorCode.INVALID_ARGUMENT, rejected.errorCode());

        assertFalse(guard.shouldRejectReads());
        guard.recordReadFailure();
        assertFalse(guard.shouldRejectReads());
        guard.recordReadFailure();
        assertFalse(guard.shouldRejectReads());
        guard.recordReadFailure();
        assertTrue(guard.shouldRejectReads(), "three consecutive failures latch rejection");
        guard.recordReadFailure();
        assertTrue(guard.shouldRejectReads(), "the latch stays on, it does not count past three");
        guard.recordReadSuccess();
        assertFalse(guard.shouldRejectReads());
    }

    @Test
    void revokeFamilyBumpsEpochCommitsEventAndRefusesSecondRevocation() throws Exception {
        SecurityRevocations revocations = new SecurityRevocations(source());
        long epochBefore = epoch();

        var receipt = revocations.revokeFamily(FAMILY_1, "admin revoke");

        assertEquals(epochBefore + 1, receipt.epoch());
        assertTrue(receipt.eventId() != null);
        try (Connection connection = open()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT status, version, revoke_reason, revoked_at "
                                    + "FROM oauth_refresh_family WHERE id = ?")) {
                statement.setString(1, FAMILY_1.toString());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("REVOKED", result.getString(1));
                    assertEquals(2L, result.getLong(2));
                    assertEquals("admin revoke", result.getString(3));
                    assertNotNull(result.getObject(4));
                }
            }
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM sys_security_event WHERE aggregate_type = "
                                    + "'REFRESH_FAMILY' AND event_type = 'REFRESH_FAMILY_CHANGED' "
                                    + "AND aggregate_id = '"
                                    + FAMILY_1
                                    + "' AND JSON_EXTRACT(payload, '$.version') = 2"));
        }

        DomainException second =
                assertThrows(
                        DomainException.class, () -> revocations.revokeFamily(FAMILY_1, "again"));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, second.errorCode());

        SecurityStateReader.SubjectRequest watch =
                new SecurityStateReader.SubjectRequest(1L, 7L, List.of(FAMILY_1, FAMILY_3));
        SecuritySnapshot before = new SecurityStateReader(source()).read(watch);
        var secondReceipt = revocations.revokeFamily(FAMILY_3, "another revoke");
        SecuritySnapshot after = new SecurityStateReader(source()).read(watch);
        assertEquals(secondReceipt.epoch(), after.policyEpoch());
        assertFalse(
                before.sameState(after), "a committed revocation must invalidate the old snapshot");
    }

    @Test
    void revokeTokenRevokesOnlyTheTokenAndBumpsFamilyVersion() throws Exception {
        SecurityRevocations revocations = new SecurityRevocations(source());
        byte[] tokenHash = new byte[32];
        tokenHash[0] = 1;
        byte[] unknownToken = new byte[32];
        Arrays.fill(unknownToken, (byte) 9);
        assertThrows(
                DomainException.class,
                () -> revocations.revokeToken(FAMILY_1, unknownToken, "no such token"));

        var receipt = revocations.revokeToken(FAMILY_1, tokenHash, "token revoke");
        assertEquals(epoch(), receipt.epoch());

        try (Connection connection = open()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT status FROM oauth_refresh_token "
                                    + "WHERE token_hash = ? AND family_id = ?")) {
                statement.setBytes(1, tokenHash);
                statement.setString(2, FAMILY_1.toString());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("REVOKED", result.getString(1));
                }
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT status, version FROM oauth_refresh_family WHERE id = ?")) {
                statement.setString(1, FAMILY_1.toString());
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("ACTIVE", result.getString(1));
                    assertEquals(2L, result.getLong(2));
                }
            }
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM sys_security_event WHERE event_type = "
                                    + "'REFRESH_FAMILY_CHANGED' AND aggregate_id = '"
                                    + FAMILY_1
                                    + "' AND JSON_EXTRACT(payload, '$.version') = 2"));
        }
    }

    @Test
    void revokeSubjectRevokesEveryActiveFamilyOfTheUserOnly() throws Exception {
        SecurityRevocations revocations = new SecurityRevocations(source());

        assertThrows(DomainException.class, () -> revocations.revokeSubject(7L, 0L, "bad version"));
        assertThrows(DomainException.class, () -> revocations.revokeSubject(7L, 3L, " "));
        assertThrows(
                DomainException.class, () -> revocations.revokeSubject(7L, 3L, "x".repeat(201)));

        var receipt = revocations.revokeSubject(7L, 3L, "user revoked");
        assertEquals(epoch(), receipt.epoch());
        try (Connection connection = open()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT status, version FROM oauth_refresh_family "
                                    + "WHERE user_id = ? ORDER BY id")) {
                statement.setLong(1, 7L);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("REVOKED", result.getString(1));
                    assertEquals(2L, result.getLong(2));
                    assertTrue(result.next());
                    assertEquals("REVOKED", result.getString(1));
                    assertEquals(2L, result.getLong(2));
                    assertFalse(result.next());
                }
            }
            assertEquals(
                    "ACTIVE",
                    familyStatus(connection, FAMILY_3),
                    "other users' families are untouched");
            assertEquals(
                    "REVOKED", familyStatus(connection, FAMILY_1), "user 7's families revoked");
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM sys_security_event WHERE aggregate_type = "
                                    + "'USER' AND event_type = 'USER_SECURITY_CHANGED'"));
        }
    }

    private static String familyStatus(Connection connection, UUID familyId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT status FROM oauth_refresh_family WHERE id = ?")) {
            statement.setString(1, familyId.toString());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    @Test
    void quotaLimitsUserDatasourceGatewayAndQueueWithoutOversell() {
        QuotaLimiter limiter = new InMemoryQuotaLimiter();

        List<QuotaLimiter.ExecutionPermit> userPermits = new ArrayList<>();
        for (int i = 0; i < QuotaLimiter.USER_MAX; i++) {
            userPermits.add(acquire(limiter, 7L, 1L + i));
        }
        assertEquals(
                ErrorCode.USER_CONCURRENCY_LIMIT, thrown(limiter, 7L, 50L), "4th same-user slot");
        userPermits.forEach(limiter::release);

        List<QuotaLimiter.ExecutionPermit> datasourcePermits = new ArrayList<>();
        for (int i = 0; i < QuotaLimiter.DATASOURCE_MAX; i++) {
            datasourcePermits.add(acquire(limiter, 100L + i, 9L));
        }
        assertEquals(
                ErrorCode.DATASOURCE_CONCURRENCY_LIMIT,
                thrown(limiter, 200L, 9L),
                "9th same-datasource slot");
        datasourcePermits.forEach(limiter::release);

        List<QuotaLimiter.ExecutionPermit> gatewayPermits = new ArrayList<>();
        for (int i = 0; i < QuotaLimiter.GATEWAY_MAX; i++) {
            gatewayPermits.add(acquire(limiter, 300L + i, 40L + (i % 3)));
        }
        assertEquals(ErrorCode.GATEWAY_BUSY, thrown(limiter, 400L, 60L), "17th gateway slot");
        gatewayPermits.forEach(limiter::release);

        List<QuotaLimiter.QueuePermit> queued = new ArrayList<>();
        for (int i = 0; i < QuotaLimiter.QUEUED_MAX; i++) {
            queued.add(limiter.acquireQueue(500L));
        }
        assertThrows(
                DomainException.class, () -> limiter.acquireQueue(500L), "33rd queued request");
        queued.forEach(limiter::release);
        QuotaLimiter.QueuePermit freed = limiter.acquireQueue(500L);
        limiter.release(freed);
    }

    @Test
    void expiredQueuePermitTurnsIntoGatewayBusyAndFreesItsSlot() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryQuotaLimiter limiter = new InMemoryQuotaLimiter(clock::get);

        QuotaLimiter.QueuePermit permit = limiter.acquireQueue(7L);
        clock.set(1_000L + QuotaLimiter.QUEUE_WINDOW_NANOS + 1L);
        DomainException expired =
                assertThrows(
                        DomainException.class,
                        () -> limiter.acquireExecution(7L, 1L, permit),
                        "a queue wait beyond the window is gateway-busy, never an execution");
        assertEquals(ErrorCode.GATEWAY_BUSY, expired.errorCode());
        QuotaLimiter.QueuePermit freed = limiter.acquireQueue(7L);
        limiter.release(freed);
        assertThrows(
                DomainException.class,
                () -> limiter.acquireExecution(7L, 1L, null),
                "execution requires the queue permit that admitted the request");
    }

    @Test
    void rejectedAcquisitionReleasesTheQueueSlotAndRollsBackPartialPermits() {
        QuotaLimiter limiter = new InMemoryQuotaLimiter();

        List<QuotaLimiter.ExecutionPermit> held = new ArrayList<>();
        for (int i = 0; i < QuotaLimiter.USER_MAX; i++) {
            held.add(acquire(limiter, 7L, 1L + i));
        }
        // thrown() takes its own queue permit; the rejection must hand that slot back.
        assertEquals(ErrorCode.USER_CONCURRENCY_LIMIT, thrown(limiter, 7L, 9L));
        held.forEach(limiter::release);
        List<QuotaLimiter.QueuePermit> queuedAfter = new ArrayList<>();
        for (int i = 0; i < QuotaLimiter.QUEUED_MAX; i++) {
            queuedAfter.add(limiter.acquireQueue(8L));
        }
        assertThrows(
                DomainException.class,
                () -> limiter.acquireQueue(8L),
                "the rejected request's queue slot was freed, so exactly QUEUED_MAX more fit");
        queuedAfter.forEach(limiter::release);

        List<QuotaLimiter.ExecutionPermit> gateway = new ArrayList<>();
        for (int i = 0; i < QuotaLimiter.GATEWAY_MAX; i++) {
            gateway.add(acquire(limiter, 100L + i, 40L + (i % 3)));
        }
        assertEquals(ErrorCode.GATEWAY_BUSY, thrown(limiter, 55L, 88L), "gateway is full");
        limiter.release(gateway.getFirst());
        QuotaLimiter.ExecutionPermit admitted = acquire(limiter, 55L, 88L);
        gateway.forEach(limiter::release);
        limiter.release(admitted);
    }

    @Test
    void concurrentAcquirersNeverOversellUserOrDatasourceSlots() throws Exception {
        QuotaLimiter limiter = new InMemoryQuotaLimiter();
        assertEquals(
                3, race(limiter, 7L, 1L, 4, false), "4 racers for one user: exactly 3 admitted");
        assertEquals(
                8, race(limiter, 600L, 77L, 9, true), "9 racers for one datasource: exactly 8");
    }

    private static int race(
            QuotaLimiter limiter, long userId, long datasourceId, int racers, boolean distinctUsers)
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(racers);
        ExecutorService pool =
                new ThreadPoolExecutor(
                        racers, racers, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                final long racerUser = distinctUsers ? userId + i : userId;
                tasks.add(
                        () -> {
                            barrier.await(10, TimeUnit.SECONDS);
                            try {
                                limiter.acquireExecution(
                                        racerUser, datasourceId, limiter.acquireQueue(racerUser));
                                return 1;
                            } catch (DomainException error) {
                                return 0;
                            }
                        });
            }
            List<Future<Integer>> futures = pool.invokeAll(tasks);
            int admitted = 0;
            for (Future<Integer> future : futures) {
                admitted += future.get(30, TimeUnit.SECONDS);
            }
            return admitted;
        } finally {
            pool.shutdownNow();
        }
    }

    private static QuotaLimiter.ExecutionPermit acquire(
            QuotaLimiter limiter, long userId, long datasourceId) {
        return limiter.acquireExecution(userId, datasourceId, limiter.acquireQueue(userId));
    }

    private static ErrorCode thrown(QuotaLimiter limiter, long userId, long datasourceId) {
        try {
            acquire(limiter, userId, datasourceId);
        } catch (DomainException error) {
            return error.errorCode();
        }
        throw new AssertionError("expected a quota rejection");
    }

    private static long epoch() throws SQLException {
        try (Connection connection = open()) {
            return scalar(connection, "SELECT policy_epoch FROM sys_security_state");
        }
    }

    private static DataSource source() {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getConnection")) {
                                return open();
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static Connection open() throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static long scalar(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private static void update(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }
}
