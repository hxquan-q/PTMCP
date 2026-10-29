package local.ptmcp.admin.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.security.HostIdentityStore;
import local.ptmcp.persistence.security.IdentityPasswordChanges;
import local.ptmcp.persistence.security.UserSecurityStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.mysql.MySQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** Real, separate MySQL stores with synthetic identities and deterministic transaction faults. */
class IdentityPasswordServiceTest {
    private static MySQLContainer mysql;
    private static MySQLContainer identityMysql;
    private static DataSource primary;
    private static DataSource identity;
    private static JdbcTemplate jdbc;
    private static JdbcTemplate hostJdbc;
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String OLD = secret();
    private static final String NEXT = secret();
    private static final String LATEST = secret();

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static MySQLContainer mysql(String database) {
        return new MySQLContainer(
                        System.getProperty(
                                "ptmcp.test.mysqlImage",
                                "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                .withDatabaseName(database)
                .withUsername("identity_test")
                .withPassword(secret());
    }

    @BeforeAll
    static void start() {
        mysql = mysql("metadata_test");
        identityMysql = mysql("host_identity_test");
        mysql.start();
        identityMysql.start();
        primary =
                new DriverManagerDataSource(
                        mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        identity =
                new DriverManagerDataSource(
                        identityMysql.getJdbcUrl(),
                        identityMysql.getUsername(),
                        identityMysql.getPassword());
        jdbc = new JdbcTemplate(primary);
        hostJdbc = new JdbcTemplate(identity);
        Flyway.configure()
                .dataSource(primary)
                .locations("classpath:db/ptmcp-migration")
                .load()
                .migrate();
        hostJdbc.execute(
                "CREATE TABLE sys_user (user_id BIGINT PRIMARY KEY, user_name VARCHAR(64), nick_name VARCHAR(64), password VARCHAR(100), status CHAR(1), del_flag CHAR(1))");
        jdbc.update(
                "INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,status,del_flag,create_time,update_time) VALUES (42,0,'','Test','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        jdbc.update(
                "INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,version,create_time,update_time) VALUES (42,42,'Synthetic',?,'ACTIVE',5,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                ENCODER.encode(OLD));
        jdbc.update(
                "INSERT INTO sys_user_security (user_id,auth_version,identity_version,version,create_time,update_time) VALUES (42,7,1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        hostJdbc.update(
                "INSERT INTO sys_user VALUES (42,'synthetic','Synthetic',?,'0','0')",
                ENCODER.encode(OLD));
    }

    @AfterAll
    static void stop() {
        if (identityMysql != null) identityMysql.stop();
        if (mysql != null) mysql.stop();
    }

    @BeforeEach
    void seed() {
        jdbc.update(
                "UPDATE sys_user_security SET auth_version=7,identity_version=1,identity_state='READY',identity_change_id=NULL WHERE user_id=42");
        jdbc.update("DELETE FROM identity_password_change");
        jdbc.update(
                "UPDATE sys_user_profile SET password_hash=?,version=5,status='ACTIVE' WHERE user_id=42",
                ENCODER.encode(OLD));
        hostJdbc.update(
                "UPDATE sys_user SET password=?,status='0',del_flag='0' WHERE user_id=42",
                ENCODER.encode(OLD));
        jdbc.update("UPDATE sys_security_state SET policy_epoch=20 WHERE singleton_id=1");
        jdbc.update("DELETE FROM web_session_index");
    }

    @Test
    void hostFailureKeepsTheUserPendingAndRevokesBothSessionKinds() {
        for (String kind : new String[] {"ADMIN", "AS"}) addSession(kind, 7);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                service(primary, failingHost())
                                        .reset(42, 5, NEXT, "Synthetic recovery test", 99));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
        assertPending();
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM web_session_index WHERE revoked_at IS NULL",
                        Integer.class));
        assertTrue(ENCODER.matches(OLD, hostHash()));
        assertEquals(6, service(primary, identity).retry(42, 6));
        assertReady(NEXT, 8);
    }

    @Test
    void resetReturnsTheProfileVersionAndAdvancesTheAuthority() {
        assertEquals(6, service(primary, identity).reset(42, 5, NEXT, "Synthetic reset test", 99));
        assertReady(NEXT, 8);
    }

    @Test
    void retryAfterCompletedOperationIsIdempotent() {
        service(primary, identity).reset(42, 5, NEXT, "Synthetic reset test", 99);
        long epoch = epoch();
        assertEquals(6, service(primary, identity).retry(42, 6));
        assertEquals(epoch, epoch());
        assertReady(NEXT, 8);
    }

    @Test
    void hostCommitFailureBeforeCommitRollsBackAndRetryConverges() {
        assertHostCommitFault(false);
        assertTrue(ENCODER.matches(OLD, hostHash()));
        assertEquals(6, service(primary, identity).retry(42, 6));
        assertReady(NEXT, 8);
    }

    @Test
    void hostCommitAcknowledgementLossUsesPersistedTargetOnRetry() {
        assertHostCommitFault(true);
        assertTrue(ENCODER.matches(NEXT, hostHash()));
        assertEquals(6, service(primary, identity).retry(42, 6));
        assertReady(NEXT, 8);
    }

    private void assertHostCommitFault(boolean after) {
        DataSource faulty = commitFault(identity, 1, after);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                service(primary, faulty)
                                        .reset(42, 5, NEXT, "Synthetic commit fault", 99));
        assertEquals(ErrorCode.OPERATION_UNKNOWN, failure.errorCode());
        assertPending();
        assertThrows(
                DomainException.class,
                () ->
                        service(primary, identity)
                                .reset(42, 6, LATEST, "Cannot replace pending", 99));
    }

    @Test
    void unknownBeginDoesNotWriteHostAndCanBeRecoveredFromNewConnections() {
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                service(commitFault(primary, 1, true), identity)
                                        .reset(42, 5, NEXT, "Synthetic begin fault", 99));
        assertEquals(ErrorCode.OPERATION_UNKNOWN, failure.errorCode());
        assertPending();
        assertTrue(ENCODER.matches(OLD, hostHash()));
        assertEquals(6, service(primary, identity).retry(42, 6));
        assertReady(NEXT, 8);
    }

    @Test
    void unknownCompleteDoesNotRestorePendingAndRetryReturnsCompletedVersion() {
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                service(commitFault(primary, 2, true), identity)
                                        .reset(42, 5, NEXT, "Synthetic complete fault", 99));
        assertEquals(ErrorCode.OPERATION_UNKNOWN, failure.errorCode());
        assertReady(NEXT, 8);
        long epoch = epoch();
        assertEquals(6, service(primary, identity).retry(42, 6));
        assertEquals(epoch, epoch());
    }

    @Test
    void hashMismatchAndDisabledTargetNeverCompleteReady() {
        createPending();
        String target = changes(identity).pending(42).target();
        hostJdbc.update("UPDATE sys_user SET password=? WHERE user_id=42", ENCODER.encode(LATEST));
        assertEquals(
                ErrorCode.VERSION_CONFLICT,
                assertThrows(DomainException.class, () -> service(primary, identity).retry(42, 6))
                        .errorCode());
        assertPending();
        hostJdbc.update("UPDATE sys_user SET password=?,status='1' WHERE user_id=42", target);
        assertThrows(DomainException.class, () -> service(primary, identity).retry(42, 6));
        assertPending();
    }

    @Test
    void lateRetryCannotOverwriteANewerCompletedChangeOrRevokeItsSessions() throws Exception {
        createPending();
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        DataSource held =
                intercept(
                        identity,
                        (real, method, args) -> {
                            if (isHostUpdate(method, args)) {
                                paused.countDown();
                                await(resume);
                            }
                            return invoke(real, method, args);
                        });
        CompletableFuture<ErrorCode> late =
                CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                service(primary, held).retry(42, 6);
                                return null;
                            } catch (DomainException failure) {
                                return failure.errorCode();
                            }
                        });
        try {
            await(paused);
            assertEquals(6, service(primary, identity).retry(42, 6));
            assertEquals(
                    7,
                    service(primary, identity).reset(42, 6, LATEST, "Synthetic next change", 99));
            addSession("ADMIN", 9);
            long epoch = epoch();
            resume.countDown();
            assertEquals(ErrorCode.VERSION_CONFLICT, late.get(20, TimeUnit.SECONDS));
            assertReady(LATEST, 9);
            assertEquals(epoch, epoch());
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM web_session_index WHERE revoked_at IS NULL",
                            Integer.class));
        } finally {
            resume.countDown();
        }
    }

    @Test
    void concurrentRetriesShareOneTargetAndOneCredentialVersion() throws Exception {
        createPending();
        CountDownLatch beforeCommit = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        CountDownLatch secondUpdate = new CountDownLatch(1);
        DataSource held =
                intercept(
                        identity,
                        (real, method, args) -> {
                            if (method.getName().equals("commit")) {
                                beforeCommit.countDown();
                                await(releaseCommit);
                            }
                            return invoke(real, method, args);
                        });
        CompletableFuture<Long> first =
                CompletableFuture.supplyAsync(() -> service(primary, held).retry(42, 6));
        try {
            await(beforeCommit);
            DataSource observed =
                    intercept(
                            identity,
                            (real, method, args) -> {
                                if (isHostUpdate(method, args)) secondUpdate.countDown();
                                return invoke(real, method, args);
                            });
            CompletableFuture<Long> second =
                    CompletableFuture.supplyAsync(() -> service(primary, observed).retry(42, 6));
            await(secondUpdate);
            releaseCommit.countDown();
            assertEquals(6L, first.get(20, TimeUnit.SECONDS));
            assertEquals(6L, second.get(20, TimeUnit.SECONDS));
            assertReady(NEXT, 8);
            assertEquals(
                    1,
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM identity_password_change", Integer.class));
        } finally {
            releaseCommit.countDown();
        }
    }

    private void createPending() {
        assertThrows(
                DomainException.class,
                () ->
                        service(primary, failingHost())
                                .reset(42, 5, NEXT, "Synthetic pending seed", 99));
        assertPending();
    }

    private static IdentityPasswordService service(DataSource metadata, DataSource host) {
        return new IdentityPasswordService(metadata, new IdentityPasswordWriter(host));
    }

    private static IdentityPasswordChanges changes(DataSource host) {
        return new IdentityPasswordChanges(primary, new HostIdentityStore(host));
    }

    private static DataSource failingHost() {
        return intercept(
                identity,
                (real, method, args) -> {
                    if (isHostUpdate(method, args))
                        throw new SQLException("synthetic host write outage");
                    return invoke(real, method, args);
                });
    }

    private static boolean isHostUpdate(Method method, Object[] args) {
        return method.getName().equals("prepareStatement")
                && ((String) args[0]).startsWith("UPDATE sys_user ");
    }

    private static DataSource commitFault(DataSource delegate, int number, boolean after) {
        AtomicInteger commits = new AtomicInteger();
        AtomicBoolean fired = new AtomicBoolean();
        return intercept(
                delegate,
                (real, method, args) -> {
                    if (method.getName().equals("commit")
                            && commits.incrementAndGet() == number
                            && fired.compareAndSet(false, true)) {
                        if (after) real.commit();
                        throw new SQLException("synthetic lost commit acknowledgement");
                    }
                    return invoke(real, method, args);
                });
    }

    private static DataSource intercept(DataSource delegate, ConnectionCall call) {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("getConnection")) {
                                Connection real = (Connection) invoke(delegate, method, args);
                                return Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (connection, current, values) ->
                                                call.apply(real, current, values));
                            }
                            return invoke(delegate, method, args);
                        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @FunctionalInterface
    private interface ConnectionCall {
        Object apply(Connection real, Method method, Object[] args) throws Throwable;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS))
                throw new AssertionError("transaction checkpoint timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private void addSession(String kind, long version) {
        jdbc.update(
                "INSERT INTO web_session_index (session_hash,kind,user_id,auth_version,issued_at,expires_at,version,create_time,update_time) VALUES (UNHEX(SHA2(?,256)),?,42,?,UTC_TIMESTAMP(6),DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 8 HOUR),1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                kind,
                kind,
                version);
    }

    private void assertPending() {
        assertFalse(new UserSecurityStore(primary).read(42).ready());
        assertEquals(8, new UserSecurityStore(primary).read(42).authVersion());
    }

    private void assertReady(String value, long version) {
        assertTrue(new UserSecurityStore(primary).read(42).ready());
        assertEquals(version, new UserSecurityStore(primary).read(42).authVersion());
        assertTrue(ENCODER.matches(value, hostHash()));
    }

    private String hostHash() {
        return hostJdbc.queryForObject(
                "SELECT password FROM sys_user WHERE user_id=42", String.class);
    }

    private long epoch() {
        return jdbc.queryForObject(
                "SELECT policy_epoch FROM sys_security_state WHERE singleton_id=1", Long.class);
    }
}
