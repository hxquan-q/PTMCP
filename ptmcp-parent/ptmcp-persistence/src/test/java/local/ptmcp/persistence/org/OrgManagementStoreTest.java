package local.ptmcp.persistence.org;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mysql.cj.jdbc.exceptions.CommunicationsException;

import local.ptmcp.common.authz.GrantExpander;
import local.ptmcp.common.authz.PermissionEvaluator;
import local.ptmcp.common.authz.ResourceNode;
import local.ptmcp.common.authz.SubjectScope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.security.UserSecurityStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.mysql.MySQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/** Owned MySQL 8.4 container; admin org/ACL stores are proven against the real schema only. */
class OrgManagementStoreTest {
    private static MySQLContainer mysql;
    private static DataSource dataSource;
    private static OrgStore orgStore;
    private static AclGrantStore grantStore;
    private static IdempotencyRecorder idempotency;
    private static AclDirectory directory;
    private static DelegationStore delegationStore;
    private static DelegationScopeResolver scopeResolver;

    @BeforeAll
    static void startSyntheticPlatformDatabase() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("org_store_test")
                        .withUsername("org_test")
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
        for (int attempt = 0; ; attempt++) {
            try {
                Flyway.configure()
                        .dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                        .locations("classpath:db/ptmcp-migration")
                        .cleanDisabled(true)
                        .baselineOnMigrate(false)
                        .load()
                        .migrate();
                break;
            } catch (Exception | AssertionError failure) {
                if (attempt >= 4 || !isCommunicationFailure(failure)) {
                    mysql.stop();
                    throw failure;
                }
                pauseBeforeRetry(attempt);
            }
        }
        String url =
                mysql.getJdbcUrl()
                        + "&user="
                        + mysql.getUsername()
                        + "&password="
                        + mysql.getPassword();
        dataSource =
                new DataSource() {
                    @Override
                    public Connection getConnection() throws SQLException {
                        return open(url, null, null);
                    }

                    @Override
                    public Connection getConnection(String username, String password)
                            throws SQLException {
                        return open(url, username, password);
                    }

                    @Override
                    public <T> T unwrap(Class<T> iface) throws SQLException {
                        throw new SQLException("not a wrapper");
                    }

                    @Override
                    public boolean isWrapperFor(Class<?> iface) {
                        return false;
                    }

                    @Override
                    public java.io.PrintWriter getLogWriter() {
                        return null;
                    }

                    @Override
                    public void setLogWriter(java.io.PrintWriter out) {}

                    @Override
                    public void setLoginTimeout(int seconds) {}

                    @Override
                    public int getLoginTimeout() {
                        return 0;
                    }

                    @Override
                    public java.util.logging.Logger getParentLogger() {
                        return java.util.logging.Logger.getGlobal();
                    }
                };
        orgStore = new OrgStore(dataSource);
        delegationStore = new DelegationStore(dataSource);
        scopeResolver = new DelegationScopeResolver(dataSource);
        grantStore = new AclGrantStore(dataSource);
        idempotency = new IdempotencyRecorder(dataSource);
        directory = new AclDirectory(dataSource);
    }

    /**
     * WSL2 Docker occasionally drops the forwarded container port for a few seconds right after
     * startup. Retry only transport-level failures so the suite still exercises the real database;
     * assertions, failure conditions and the test set itself are unchanged.
     */
    private static Connection open(String url, String username, String password)
            throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return username == null
                        ? DriverManager.getConnection(url)
                        : DriverManager.getConnection(url, username, password);
            } catch (SQLException failure) {
                last = failure;
                if (!isCommunicationFailure(failure)) {
                    throw failure;
                }
                pauseBeforeRetry(attempt);
            }
        }
        throw last;
    }

    private static boolean isCommunicationFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CommunicationsException
                    || cause instanceof java.net.ConnectException
                    || cause instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static void pauseBeforeRetry(int attempt) {
        try {
            Thread.sleep(2_000L * (attempt + 1));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterAll
    static void stopOwnedContainer() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void resetOnlyOwnedData() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table :
                    List.of(
                            "identity_password_change",
                            "web_session_index",
                            "personal_token",
                            "oauth_refresh_token",
                            "oauth_refresh_family",
                            "oauth2_authorization_consent",
                            "oauth2_authorization",
                            "oauth2_registered_client",
                            "sys_security_event",
                            "admin_operation_log",
                            "resource_object",
                            "resource_namespace",
                            "schema_snapshot",
                            "datasource",
                            "acl_grant",
                            "platform_scope",
                            "admin_delegation",
                            "sys_user_role",
                            "sys_user_security",
                            "sys_user_profile",
                            "sys_user_login_name",
                            "sys_role_permission",
                            "sys_role",
                            "sys_dept",
                            "platform_registry",
                            "api_idempotency")) {
                statement.execute("DELETE FROM " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    // ---- helpers -----------------------------------------------------------

    private long insertPlatform(String code) throws SQLException {
        return insert(
                "INSERT INTO platform_registry (code, name, tier, status, catalog_mode, proof_mode,"
                        + " scope_version, security_version, create_time, update_time, created_by,"
                        + " updated_by) VALUES (?, '平台', 3, 'ACTIVE', 'DISCOVER', 'NONE', 1, 1,"
                        + " NOW(6), NOW(6), 1, 1)",
                code);
    }

    private long insertPlatformScope(
            long platformId, String resourceType, long resourceId, String action)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO platform_scope (platform_id, resource_type,"
                                        + " resource_id, resource_generation, action,"
                                        + " include_descendants, create_time, update_time,"
                                        + " created_by, updated_by) VALUES (?, ?, ?, 1, ?, 1,"
                                        + " NOW(6), NOW(6), 1, 1)")) {
            statement.setLong(1, platformId);
            statement.setString(2, resourceType);
            statement.setLong(3, resourceId);
            statement.setString(4, action);
            return statement.executeUpdate();
        }
    }

    private long insertDatasource(String code) throws SQLException {
        return insert(
                "INSERT INTO datasource (code, name, engine, host, port, database_name, username,"
                        + " credential_cipher, key_id, tls_mode, pool_max, status, create_time,"
                        + " update_time, created_by, updated_by) VALUES (?, '数据源', 'MYSQL',"
                        + " 'db.example.internal', 3306, 'biz', 'ro', X'00', 'k1', 'VERIFY_FULL', 4,"
                        + " 'ACTIVE', NOW(6), NOW(6), 1, 1)",
                code);
    }

    private long insertNamespace(long datasourceId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO resource_namespace (datasource_id, catalog_raw, catalog_norm,"
                                        + " schema_raw, schema_norm, generation, status, create_time,"
                                        + " update_time) VALUES (?, 'def', 'def', 'sales', 'sales', 1,"
                                        + " 'ACTIVE', NOW(6), NOW(6))",
                                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, datasourceId);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private long insertObject(long namespaceId) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO resource_object (namespace_id, generation, object_raw,"
                                        + " object_norm, quoted, kind, status, definition_hash,"
                                        + " first_seen_at, last_seen_at, create_time, update_time)"
                                        + " VALUES (?, 1, 'orders', 'orders', 0, 'TABLE', 'ACTIVE',"
                                        + " X'00', NOW(6), NOW(6), NOW(6), NOW(6))",
                                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, namespaceId);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private long insert(String sql, String parameter) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, parameter);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long generatedKey(Statement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            keys.next();
            return keys.getLong(1);
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static ErrorCode codeOf(Runnable action) {
        try {
            action.run();
        } catch (DomainException error) {
            return error.errorCode();
        }
        throw new AssertionError("expected DomainException");
    }

    // ---- user status security transaction ---------------------------------

    @Test
    void userStatusTransitionsRevokeEveryDomainWithoutRevivingCredentials() throws Exception {
        OrgStore.UserRow user = statusUser("status-target", true);
        OrgStore.UserRow observer = statusUser("status-observer", true);
        insertStatusCredentials(user.id());
        insertStatusCredentials(observer.id());
        StatusSnapshot initial = statusSnapshot(user.id());
        List<String> observerCredentials = credentialStates(observer.id());

        OrgStore.UserRow disabled = changeStatus(orgStore, user, false);
        assertFalse(disabled.enabled());
        assertEquals(user.version() + 1, disabled.version());
        assertStatusSecurityAdvanced(initial, statusSnapshot(user.id()));
        assertEquals(5, credentialStates(user.id()).size());
        assertTrue(credentialStates(user.id()).stream().allMatch(row -> row.contains(":REVOKED:")));
        assertEquals(observerCredentials, credentialStates(observer.id()));
        StatusSnapshot afterDisable = statusSnapshot(user.id());
        assertEquals("READY", afterDisable.security().identityState());
        assertEquals(
                1,
                statusScalar(
                        "SELECT COUNT(*) FROM sys_security_event WHERE aggregate_id=? AND event_type='USER_SECURITY_CHANGED'"
                                + " AND JSON_EXTRACT(payload,'$.version')="
                                + afterDisable.security().authVersion(),
                        String.valueOf(user.id())));

        OrgStore.UserRow enabled = changeStatus(orgStore, disabled, true);
        assertTrue(enabled.enabled());
        StatusSnapshot afterEnable = statusSnapshot(user.id());
        assertStatusSecurityAdvanced(afterDisable, afterEnable);
        assertEquals(afterDisable.credentials(), afterEnable.credentials());
        assertEquals(observerCredentials, credentialStates(observer.id()));
    }

    @Test
    void userStatusSameValueAndOrdinaryProfileEditsDoNotRevoke() throws Exception {
        OrgStore.UserRow user = statusUser("status-noop", true);
        insertStatusCredentials(user.id());
        StatusSnapshot initial = statusSnapshot(user.id());
        OrgStore.UserRow same = changeStatus(orgStore, user, true);
        orgStore.updateUser(
                same.id(),
                "新显示名",
                same.deptId(),
                "person@example.invalid",
                null,
                null,
                null,
                null,
                same.version(),
                1L);
        StatusSnapshot after = statusSnapshot(user.id());
        assertEquals(initial.security(), after.security());
        assertEquals(initial.securityRowVersion(), after.securityRowVersion());
        assertEquals(initial.credentials(), after.credentials());
        assertEquals(initial.epoch(), after.epoch());
        assertEquals(initial.events(), after.events());
        assertEquals(initial.operations() + 2, after.operations());
        assertEquals(user.version() + 2, after.user().version());
    }

    @Test
    void userStatusDoesNotBindAnUnprovisionedIdentity() throws Exception {
        OrgStore.UserRow user = statusUser("status-unbound", false);
        StatusSnapshot before = statusSnapshot(user.id());
        OrgStore.UserRow disabled = changeStatus(orgStore, user, false);
        assertEquals("UNBOUND", new UserSecurityStore(dataSource).read(user.id()).identityState());
        assertStatusSecurityAdvanced(before, statusSnapshot(user.id()));
        changeStatus(orgStore, disabled, true);
        assertEquals("UNBOUND", new UserSecurityStore(dataSource).read(user.id()).identityState());
    }

    @Test
    void userStatusVersionConflictAndPendingHaveNoSideEffects() throws Exception {
        OrgStore.UserRow user = statusUser("status-conflict", true);
        insertStatusCredentials(user.id());
        OrgStore.UserRow edited = changeStatus(orgStore, user, true);
        StatusSnapshot before = statusSnapshot(user.id());
        assertEquals(ErrorCode.VERSION_CONFLICT, codeOf(() -> changeStatus(orgStore, user, false)));
        OrgStore.UserRow future =
                new OrgStore.UserRow(
                        edited.id(),
                        edited.username(),
                        edited.displayName(),
                        edited.deptId(),
                        edited.email(),
                        edited.phoneNumber(),
                        edited.remark(),
                        edited.enabled(),
                        edited.roleIds(),
                        edited.version() + 1);
        assertEquals(
                ErrorCode.VERSION_CONFLICT, codeOf(() -> changeStatus(orgStore, future, false)));
        assertEquals(before, statusSnapshot(user.id()));

        statusExecute(
                "UPDATE sys_user_security SET identity_state='CHANGE_PENDING',identity_change_id=? WHERE user_id=?",
                java.util.UUID.randomUUID().toString(),
                user.id());
        StatusSnapshot pending = statusSnapshot(user.id());
        assertEquals(
                ErrorCode.VERSION_CONFLICT, codeOf(() -> changeStatus(orgStore, edited, false)));
        assertEquals(pending, statusSnapshot(user.id()));
    }

    @Test
    void userStatusEventFailureRollsBackProfileRolesAuthorityAndRevocation() throws Exception {
        OrgStore.UserRow user = statusUser("status-rollback", true);
        insertStatusCredentials(user.id());
        long role =
                orgStore.createRole("status_role", "状态角色", true, List.of("ptmcp:user:list"), 1L)
                        .id();
        StatusSnapshot before = statusSnapshot(user.id());
        OrgStore failing =
                new OrgStore(statusFaultDataSource("INSERT INTO sys_security_event", false));
        assertEquals(
                ErrorCode.DEPENDENCY_UNAVAILABLE,
                codeOf(
                        () ->
                                failing.updateUser(
                                        user.id(),
                                        "不应提交",
                                        user.deptId(),
                                        null,
                                        null,
                                        null,
                                        false,
                                        List.of(role),
                                        user.version(),
                                        1L)));
        assertEquals(before, statusSnapshot(user.id()));
    }

    @Test
    void userStatusCommitAcknowledgementLossReturnsUnknownAndDoesNotReplay() throws Exception {
        OrgStore.UserRow user = statusUser("status-unknown", true);
        insertStatusCredentials(user.id());
        StatusSnapshot before = statusSnapshot(user.id());
        OrgStore uncertain = new OrgStore(statusFaultDataSource(null, true));
        assertEquals(
                ErrorCode.OPERATION_UNKNOWN, codeOf(() -> changeStatus(uncertain, user, false)));
        StatusSnapshot committed = statusSnapshot(user.id());
        assertFalse(committed.user().enabled());
        assertStatusSecurityAdvanced(before, committed);
        assertTrue(committed.credentials().stream().allMatch(row -> row.contains(":REVOKED:")));
        assertEquals(ErrorCode.VERSION_CONFLICT, codeOf(() -> changeStatus(orgStore, user, false)));
        assertEquals(committed, statusSnapshot(user.id()));
    }

    @Test
    void userStatusRejectsExhaustedAuthorityWithoutPartialChanges() throws Exception {
        OrgStore.UserRow user = statusUser("status-exhausted", true);
        insertStatusCredentials(user.id());
        for (String column : List.of("auth_version", "identity_version")) {
            statusExecute(
                    "UPDATE sys_user_security SET auth_version=1,identity_version=1 WHERE user_id=?",
                    user.id());
            statusExecute(
                    "UPDATE sys_user_security SET " + column + "=? WHERE user_id=?",
                    Long.MAX_VALUE,
                    user.id());
            StatusSnapshot before = statusSnapshot(user.id());
            assertEquals(
                    ErrorCode.SECURITY_STATE_UNAVAILABLE,
                    codeOf(() -> changeStatus(orgStore, user, false)));
            assertEquals(before, statusSnapshot(user.id()));
        }
    }

    @Test
    void userStatusRejectsChangesAfterCaptureWithoutFollowingNewVersions() throws Exception {
        for (String change : List.of("PROFILE", "AUTHORITY", "PENDING")) {
            OrgStore.UserRow user =
                    statusUser("status-race-" + change.toLowerCase(java.util.Locale.ROOT), true);
            insertStatusCredentials(user.id());
            CountDownLatch captured = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            OrgStore paused =
                    new OrgStore(
                            statusFaultDataSource(
                                    null, false, () -> awaitStatusRace(captured, resume)));
            var executor = Executors.newSingleThreadExecutor();
            try {
                var outcome =
                        executor.submit(() -> codeOf(() -> changeStatus(paused, user, false)));
                assertTrue(
                        captured.await(10, TimeUnit.SECONDS),
                        "status command did not capture its versions");
                if ("PROFILE".equals(change)) {
                    orgStore.updateUser(
                            user.id(),
                            "并发资料",
                            user.deptId(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            user.version(),
                            1L);
                } else {
                    try (Connection connection = dataSource.getConnection()) {
                        connection.setAutoCommit(false);
                        UserSecurityStore.lockGlobal(connection);
                        UserSecurityStore.read(connection, user.id(), true);
                        String update =
                                "PENDING".equals(change)
                                        ? "UPDATE sys_user_security SET identity_state='CHANGE_PENDING',identity_change_id=?,identity_version=identity_version+1,version=version+1 WHERE user_id=?"
                                        : "UPDATE sys_user_security SET identity_version=identity_version+1,version=version+1 WHERE user_id=?";
                        try (PreparedStatement statement = connection.prepareStatement(update)) {
                            int parameter = 1;
                            if ("PENDING".equals(change))
                                statement.setString(
                                        parameter++, java.util.UUID.randomUUID().toString());
                            statement.setLong(parameter, user.id());
                            assertEquals(1, statement.executeUpdate());
                        }
                        connection.commit();
                    }
                }
                StatusSnapshot concurrent = statusSnapshot(user.id());
                resume.countDown();
                assertEquals(ErrorCode.VERSION_CONFLICT, outcome.get(10, TimeUnit.SECONDS), change);
                assertEquals(concurrent, statusSnapshot(user.id()), change);
            } finally {
                resume.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void userStatusSameValueCannotReenableAConcurrentlyDisabledUser() throws Exception {
        OrgStore.UserRow user = statusUser("status-same-race", true);
        insertStatusCredentials(user.id());
        CountDownLatch captured = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        OrgStore paused =
                new OrgStore(
                        statusFaultDataSource(
                                null, false, () -> awaitStatusRace(captured, resume)));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var outcome = executor.submit(() -> codeOf(() -> changeStatus(paused, user, true)));
            assertTrue(
                    captured.await(10, TimeUnit.SECONDS),
                    "same-status command did not capture its profile");
            changeStatus(orgStore, user, false);
            StatusSnapshot disabled = statusSnapshot(user.id());
            resume.countDown();
            assertEquals(ErrorCode.VERSION_CONFLICT, outcome.get(10, TimeUnit.SECONDS));
            assertEquals(disabled, statusSnapshot(user.id()));
        } finally {
            resume.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static void awaitStatusRace(CountDownLatch captured, CountDownLatch resume) {
        captured.countDown();
        try {
            if (!resume.await(15, TimeUnit.SECONDS))
                throw new AssertionError("status race was not released");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private OrgStore.UserRow statusUser(String username, boolean ready) throws Exception {
        long department = orgStore.createDepartment(null, username, true, 1L).id();
        OrgStore.UserRow user =
                orgStore.createUser(username, "测试身份", department, "external", List.of(), true, 1L);
        if (ready) {
            statusExecute(
                    "UPDATE sys_user_security SET identity_state='READY' WHERE user_id=?",
                    user.id());
        }
        return user;
    }

    private static OrgStore.UserRow changeStatus(
            OrgStore store, OrgStore.UserRow user, boolean enabled) {
        return store.updateUser(
                user.id(),
                user.displayName(),
                user.deptId(),
                user.email(),
                user.phoneNumber(),
                user.remark(),
                enabled,
                null,
                user.version(),
                1L);
    }

    private record StatusSnapshot(
            OrgStore.UserRow user,
            UserSecurityStore.State security,
            long securityRowVersion,
            long epoch,
            long events,
            long operations,
            List<String> credentials) {}

    private StatusSnapshot statusSnapshot(long userId) throws Exception {
        return new StatusSnapshot(
                orgStore.getUser(userId),
                new UserSecurityStore(dataSource).read(userId),
                statusScalar("SELECT version FROM sys_user_security WHERE user_id=?", userId),
                statusScalar(
                        "SELECT policy_epoch FROM sys_security_state WHERE singleton_id=?", 1L),
                statusScalar(
                        "SELECT COUNT(*) FROM sys_security_event WHERE aggregate_type='USER' AND aggregate_id=?",
                        String.valueOf(userId)),
                statusScalar(
                        "SELECT COUNT(*) FROM admin_operation_log WHERE aggregate_type='USER' AND aggregate_id=?",
                        userId),
                credentialStates(userId));
    }

    private static void assertStatusSecurityAdvanced(StatusSnapshot before, StatusSnapshot after) {
        assertEquals(before.security().authVersion() + 1, after.security().authVersion());
        assertEquals(before.security().identityVersion() + 1, after.security().identityVersion());
        assertEquals(before.securityRowVersion() + 1, after.securityRowVersion());
        assertEquals(before.epoch() + 1, after.epoch());
        assertEquals(before.events() + 1, after.events());
        assertEquals(before.operations() + 1, after.operations());
    }

    private long statusScalar(String sql, Object parameter) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private void statusExecute(String sql, Object... parameters) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
            statement.executeUpdate();
        }
    }

    private void insertStatusCredentials(long userId) throws SQLException {
        long platform = insertPlatform("status-" + userId);
        String client = java.util.UUID.randomUUID().toString();
        String authorization = java.util.UUID.randomUUID().toString();
        String family = java.util.UUID.randomUUID().toString();
        statusExecute(
                "INSERT INTO oauth2_registered_client (id,client_id,client_name,client_authentication_methods,"
                        + "authorization_grant_types,scopes,client_settings,token_settings,platform_id,client_type,status,"
                        + "registration_source,create_time,update_time,created_by,updated_by)"
                        + " VALUES (?,?,'fixture','none','authorization_code,refresh_token','mcp:query','{}','{}',?,'PUBLIC','ACTIVE','ADMIN',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)",
                client,
                client,
                platform);
        statusExecute(
                "INSERT INTO oauth2_authorization (id,registered_client_id,principal_name,authorization_grant_type,"
                        + "user_id,family_id,auth_version,status,create_time,update_time,created_by,updated_by)"
                        + " VALUES (?,?,?,'authorization_code',?,?,1,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)",
                authorization,
                client,
                String.valueOf(userId),
                userId,
                family);
        statusExecute(
                "INSERT INTO oauth_refresh_family (id,authorization_id,user_id,registered_client_id,platform_id,status,"
                        + "auth_version,absolute_expires_at,idle_expires_at,last_rotated_at,create_time,update_time)"
                        + " VALUES (?,?,?,?,?,'ACTIVE',1,UTC_TIMESTAMP(6)+INTERVAL 2 DAY,UTC_TIMESTAMP(6)+INTERVAL 1 DAY,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                family,
                authorization,
                userId,
                client,
                platform);
        statusExecute(
                "INSERT INTO personal_token (user_id,platform_id,token_prefix,token_hash,name,scopes,auth_version,expires_at,status,"
                        + "create_time,update_time,created_by,updated_by) VALUES (?,?,'test',?,'fixture','[\"mcp:query\"]',1,UTC_TIMESTAMP(6)+INTERVAL 1 DAY,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)",
                userId,
                platform,
                digest("pat-status-" + userId));
        for (String kind : List.of("ADMIN", "AS")) {
            statusExecute(
                    "INSERT INTO web_session_index (session_hash,kind,user_id,auth_version,issued_at,expires_at,create_time,update_time)"
                            + " VALUES (?,?,?,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)+INTERVAL 1 HOUR,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                    digest(kind + "-status-" + userId),
                    kind,
                    userId);
        }
    }

    private List<String> credentialStates(long userId) throws SQLException {
        List<String> rows = new ArrayList<>();
        String query =
                "SELECT CONCAT(kind,':',IF(revoked_at IS NULL,'ACTIVE','REVOKED'),':',version,':',COALESCE(CAST(revoked_at AS CHAR),''))"
                        + " FROM web_session_index WHERE user_id=? UNION ALL SELECT CONCAT('PAT:',status,':',version,':',COALESCE(CAST(revoked_at AS CHAR),''))"
                        + " FROM personal_token WHERE user_id=? UNION ALL SELECT CONCAT('FAMILY:',status,':',version,':',COALESCE(CAST(revoked_at AS CHAR),''))"
                        + " FROM oauth_refresh_family WHERE user_id=? UNION ALL SELECT CONCAT('AUTH:',status,':',version) FROM oauth2_authorization WHERE user_id=? ORDER BY 1";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 1; i <= 4; i++) statement.setLong(i, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) rows.add(result.getString(1));
            }
        }
        return List.copyOf(rows);
    }

    /** Real server commit/DML with only the transport failure injected. */
    private DataSource statusFaultDataSource(String failSqlPrefix, boolean loseCommitAck) {
        return statusFaultDataSource(failSqlPrefix, loseCommitAck, null);
    }

    private DataSource statusFaultDataSource(
            String failSqlPrefix, boolean loseCommitAck, Runnable beforeGlobalLock) {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (sourceProxy, sourceMethod, sourceArgs) -> {
                            try {
                                Object value = sourceMethod.invoke(dataSource, sourceArgs);
                                if (!(value instanceof Connection connection)) return value;
                                return Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (connectionProxy, method, args) -> {
                                            if ("prepareStatement".equals(method.getName())
                                                    && beforeGlobalLock != null
                                                    && ((String) args[0])
                                                            .startsWith(
                                                                    "SELECT policy_epoch FROM sys_security_state"))
                                                beforeGlobalLock.run();
                                            if ("prepareStatement".equals(method.getName())
                                                    && failSqlPrefix != null
                                                    && ((String) args[0]).startsWith(failSqlPrefix))
                                                throw new SQLException("synthetic event failure");
                                            try {
                                                Object result = method.invoke(connection, args);
                                                if (loseCommitAck
                                                        && "commit".equals(method.getName()))
                                                    throw new SQLException(
                                                            "synthetic lost commit acknowledgement");
                                                return result;
                                            } catch (InvocationTargetException error) {
                                                throw error.getCause();
                                            }
                                        });
                            } catch (InvocationTargetException error) {
                                throw error.getCause();
                            }
                        });
    }

    // ---- departments -------------------------------------------------------

    @Test
    void departmentCreateUpdateAncestorsAndOptimisticVersion() {
        OrgStore.DepartmentRow root = orgStore.createDepartment(null, "集团", true, 1L);
        assertNull(root.parentId());
        assertEquals("", root.ancestors());
        assertEquals(1, root.version());

        OrgStore.DepartmentRow child = orgStore.createDepartment(root.id(), "研发部", true, 1L);
        assertEquals(root.id(), child.parentId());
        assertEquals(String.valueOf(root.id()), child.ancestors());

        OrgStore.DepartmentRow grandchild = orgStore.createDepartment(child.id(), "平台组", true, 1L);
        assertEquals(root.id() + "," + child.id(), grandchild.ancestors());

        OrgStore.DepartmentRow renamed =
                orgStore.updateDepartment(grandchild.id(), "数据平台组", false, 1, 1L);
        assertEquals("数据平台组", renamed.name());
        assertFalse(renamed.enabled());
        assertEquals(2, renamed.version());

        assertEquals(
                ErrorCode.VERSION_CONFLICT,
                codeOf(() -> orgStore.updateDepartment(grandchild.id(), "再改", true, 1, 1L)));
        assertEquals(
                ErrorCode.RESOURCE_NOT_FOUND,
                codeOf(() -> orgStore.updateDepartment(999999, "不存在", true, 1, 1L)));
    }

    @Test
    void departmentMoveRejectsCycleAndRebuildsDescendantAncestors() {
        OrgStore.DepartmentRow root = orgStore.createDepartment(null, "根", true, 1L);
        OrgStore.DepartmentRow child = orgStore.createDepartment(root.id(), "子", true, 1L);
        OrgStore.DepartmentRow grandchild = orgStore.createDepartment(child.id(), "孙", true, 1L);

        assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                codeOf(() -> orgStore.moveDepartment(root.id(), grandchild.id(), 1, 1L)));
        assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                codeOf(() -> orgStore.moveDepartment(root.id(), root.id(), 1, 1L)));

        orgStore.moveDepartment(child.id(), null, 1, 1L);
        OrgStore.DepartmentRow movedChild = orgStore.getDepartment(child.id());
        assertNull(movedChild.parentId());
        assertEquals("", movedChild.ancestors());
        OrgStore.DepartmentRow movedGrandchild = orgStore.getDepartment(grandchild.id());
        assertEquals(String.valueOf(child.id()), movedGrandchild.ancestors());
        assertEquals(2, movedGrandchild.version(), "descendant rows must be version-bumped");
    }

    @Test
    void departmentMovePreservesHistoricalNameAndMoveLogFormat() throws Exception {
        OrgStore.DepartmentRow root = orgStore.createDepartment(null, "原根", true, 1L);
        OrgStore.DepartmentRow child = orgStore.createDepartment(root.id(), "子级", false, 1L);
        OrgStore.DepartmentRow grandchild = orgStore.createDepartment(child.id(), "孙级", true, 1L);
        String historicalName = "旧\"名称\\路径";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE sys_dept SET dept_name = ? WHERE dept_id = ?")) {
            statement.setString(1, historicalName);
            statement.setLong(2, child.id());
            assertEquals(1, statement.executeUpdate());
        }

        OrgStore.DepartmentRow moved =
                assertDoesNotThrow(() -> orgStore.moveDepartment(child.id(), null, 1, 1L));
        assertEquals(
                new OrgStore.DepartmentRow(child.id(), null, historicalName, false, 2, ""), moved);
        assertEquals(moved, orgStore.getDepartment(child.id()));
        assertEquals(
                new OrgStore.DepartmentRow(
                        grandchild.id(), child.id(), "孙级", true, 2, String.valueOf(child.id())),
                orgStore.getDepartment(grandchild.id()));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT action, JSON_LENGTH(after_masked),"
                                        + " JSON_UNQUOTE(JSON_EXTRACT(after_masked, '$.descendantsMoved'))"
                                        + " FROM admin_operation_log WHERE aggregate_type='DEPARTMENT'"
                                        + " AND aggregate_id=? AND action<>'DEPARTMENT_CREATE'")) {
            statement.setLong(1, child.id());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("DEPARTMENT_MOVE", result.getString(1));
                assertEquals(1, result.getInt(2));
                assertEquals("1", result.getString(3));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void departmentDeleteBlockedByChildrenOrMembers() {
        OrgStore.DepartmentRow root = orgStore.createDepartment(null, "根", true, 1L);
        orgStore.createDepartment(root.id(), "子", true, 1L);
        assertEquals(
                ErrorCode.REFERENCES_EXIST,
                codeOf(() -> orgStore.deleteDepartment(root.id(), 1, 1L)));

        OrgStore.DepartmentRow leaf = orgStore.createDepartment(root.id(), "叶子", true, 1L);
        orgStore.createUser("u-leaf", "叶子用户", leaf.id(), "hash", List.of(), true, 1L);
        assertEquals(
                ErrorCode.REFERENCES_EXIST,
                codeOf(() -> orgStore.deleteDepartment(leaf.id(), 1, 1L)));

        OrgStore.DepartmentRow free = orgStore.createDepartment(null, "空部门", true, 1L);
        orgStore.deleteDepartment(free.id(), 1, 1L);
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, codeOf(() -> orgStore.getDepartment(free.id())));
    }

    // ---- roles -------------------------------------------------------------

    @Test
    void roleCrudPermissionCodesAndDuplicateKey() {
        OrgStore.RoleRow role =
                orgStore.createRole("data_admin", "数据管理员", true, List.of("acl:write"), 1L);
        assertEquals("data_admin", role.key());
        assertEquals(List.of("acl:write"), role.permissionCodes());

        assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                codeOf(() -> orgStore.createRole("data_admin", "重复", true, List.of(), 1L)));

        OrgStore.RoleRow updated =
                orgStore.updateRole(role.id(), "数据管理员A", false, List.of("acl:read"), 1, 1L);
        assertEquals(List.of("acl:read"), updated.permissionCodes());
        assertEquals(2, updated.version());
        assertEquals(
                ErrorCode.VERSION_CONFLICT,
                codeOf(() -> orgStore.updateRole(role.id(), "冲突", true, List.of(), 1, 1L)));
    }

    @Test
    void roleDeleteBlockedByMembershipOrActiveGrants() throws Exception {
        long platformId = insertPlatform("p-delete");
        OrgStore.RoleRow role = orgStore.createRole("blocked", "占用角色", true, List.of(), 1L);
        orgStore.createUser(
                "u-role",
                "成员",
                orgStore.createDepartment(null, "部", true, 1L).id(),
                "hash",
                List.of(role.id()),
                true,
                1L);
        assertEquals(
                ErrorCode.REFERENCES_EXIST, codeOf(() -> orgStore.deleteRole(role.id(), 1, 1L)));

        OrgStore.RoleRow grantRole = orgStore.createRole("granted", "授权角色", true, List.of(), 1L);
        long datasourceId = insertDatasource("ds-grant");
        grantStore.create(
                new AclGrantStore.AclGrantRecord(
                        0,
                        "ROLE",
                        grantRole.id(),
                        "DATASOURCE",
                        datasourceId,
                        1,
                        "READ",
                        "ALLOW",
                        false,
                        Instant.now(),
                        null,
                        "ACTIVE",
                        "初始",
                        1),
                1L);
        assertEquals(
                ErrorCode.REFERENCES_EXIST,
                codeOf(() -> orgStore.deleteRole(grantRole.id(), 1, 1L)));
        assertTrue(platformId > 0);

        OrgStore.RoleRow free = orgStore.createRole("free", "空闲角色", true, List.of("x"), 1L);
        orgStore.deleteRole(free.id(), 1, 1L);
        assertTrue(orgStore.listRoles().stream().noneMatch(r -> r.id() == free.id()));
    }

    // ---- users -------------------------------------------------------------

    @Test
    void userCreateDuplicateLoginAndRoleAssignment() {
        long deptId = orgStore.createDepartment(null, "用户部", true, 1L).id();
        OrgStore.RoleRow role = orgStore.createRole("analyst", "分析员", true, List.of(), 1L);

        OrgStore.UserRow user =
                orgStore.createUser(
                        "Alice", "爱丽丝", deptId, "bcrypt-hash", List.of(role.id()), true, 1L);
        assertEquals("alice", user.username(), "login name must be normalized to lower case");
        local.ptmcp.persistence.security.UserSecurityStore.State authority =
                new local.ptmcp.persistence.security.UserSecurityStore(dataSource).read(user.id());
        assertFalse(authority.ready());
        assertEquals("UNBOUND", authority.identityState());
        assertEquals(1, authority.authVersion());
        assertEquals(deptId, user.deptId());
        assertEquals(List.of(role.id()), user.roleIds());

        assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                codeOf(
                        () ->
                                orgStore.createUser(
                                        "ALICE", "重名", deptId, "hash", List.of(), true, 1L)));
        assertEquals(
                ErrorCode.RESOURCE_NOT_FOUND,
                codeOf(
                        () ->
                                orgStore.createUser(
                                        "bob", "悬空", 424242, "hash", List.of(), true, 1L)));

        OrgStore.UserRow updated =
                orgStore.updateUser(
                        user.id(),
                        "爱丽丝2",
                        deptId,
                        "a@x",
                        "13800000000",
                        "备注",
                        false,
                        List.of(),
                        user.version(),
                        1L);
        assertFalse(updated.enabled());
        assertEquals(List.of(), updated.roleIds());
        assertEquals(2, updated.version());
        assertEquals(
                "UNBOUND",
                new local.ptmcp.persistence.security.UserSecurityStore(dataSource)
                        .read(user.id())
                        .identityState());
        assertEquals(
                ErrorCode.VERSION_CONFLICT,
                codeOf(
                        () ->
                                orgStore.updateUser(
                                        user.id(), "冲突", deptId, null, null, null, true, List.of(),
                                        1, 1L)));
        assertTrue(orgStore.listUsers().stream().anyMatch(u -> u.id() == user.id()));
    }

    // ---- acl grants --------------------------------------------------------

    @Test
    void grantCreateDuplicateRevokeAndListFilters() throws Exception {
        long datasourceId = insertDatasource("ds-acl");
        long deptId = orgStore.createDepartment(null, "授权部", true, 1L).id();
        long userId =
                orgStore.createUser("grant-user", "被授权", deptId, "hash", List.of(), true, 1L).id();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

        AclGrantStore.AclGrantRecord created =
                grantStore.create(
                        new AclGrantStore.AclGrantRecord(
                                0,
                                "USER",
                                userId,
                                "DATASOURCE",
                                datasourceId,
                                1,
                                "READ",
                                "ALLOW",
                                true,
                                now,
                                null,
                                "ACTIVE",
                                "开门",
                                1),
                        9L);
        assertTrue(created.id() > 0);
        assertEquals("开门", created.reason());
        assertEquals(1, created.version());

        assertEquals(
                ErrorCode.DUPLICATE_GRANT,
                codeOf(
                        () ->
                                grantStore.create(
                                        new AclGrantStore.AclGrantRecord(
                                                0,
                                                "USER",
                                                userId,
                                                "DATASOURCE",
                                                datasourceId,
                                                1,
                                                "READ",
                                                "ALLOW",
                                                true,
                                                now,
                                                null,
                                                "ACTIVE",
                                                "重复",
                                                1),
                                        9L)));

        AclGrantStore.AclGrantRecord revoked = grantStore.revoke(created.id(), 1, "收回", 9L);
        assertEquals("REVOKED", revoked.status());
        assertEquals("收回", revoked.reason());
        assertEquals(2, revoked.version());

        assertEquals(
                ErrorCode.VERSION_CONFLICT,
                codeOf(() -> grantStore.revoke(created.id(), 1, "过期版本", 9L)));
        AclGrantStore.AclGrantRecord revokedAgain =
                grantStore.revoke(created.id(), revoked.version(), "重复收回", 9L);
        assertEquals("REVOKED", revokedAgain.status());

        assertTrue(grantStore.list("USER", userId).stream().allMatch(g -> g.id() == created.id()));
        assertTrue(grantStore.list(null, null).size() >= 1);
        assertEquals(
                ErrorCode.RESOURCE_NOT_FOUND,
                codeOf(() -> grantStore.get(created.id() + 1_000_000)));
    }

    @Test
    void grantCreateRejectsUnknownSubjectOrResource() throws Exception {
        long datasourceId = insertDatasource("ds-acl2");
        assertEquals(
                ErrorCode.RESOURCE_NOT_FOUND,
                codeOf(
                        () ->
                                grantStore.create(
                                        new AclGrantStore.AclGrantRecord(
                                                0,
                                                "USER",
                                                987654,
                                                "DATASOURCE",
                                                datasourceId,
                                                1,
                                                "READ",
                                                "ALLOW",
                                                false,
                                                Instant.now(),
                                                null,
                                                "ACTIVE",
                                                "x",
                                                1),
                                        1L)));
        assertEquals(
                ErrorCode.RESOURCE_NOT_FOUND,
                codeOf(
                        () ->
                                grantStore.create(
                                        new AclGrantStore.AclGrantRecord(
                                                0,
                                                "USER",
                                                1,
                                                "DATASOURCE",
                                                987654,
                                                1,
                                                "READ",
                                                "ALLOW",
                                                false,
                                                Instant.now(),
                                                null,
                                                "ACTIVE",
                                                "x",
                                                1),
                                        1L)));
    }

    // ---- idempotency -------------------------------------------------------

    @Test
    void idempotencyOccupyReplayReuseAndFailedRetry() throws Exception {
        byte[] requestHash = digest("body-one");
        IdempotencyRecorder.OccupyResult first =
                idempotency.occupy(
                        1L, "POST /api/v1/admin/roles", "111111111111111111111111", requestHash);
        assertEquals(IdempotencyRecorder.State.OCCUPIED, first.state());
        assertEquals(
                ErrorCode.IDEMPOTENCY_KEY_REUSED,
                codeOf(
                        () ->
                                idempotency.occupy(
                                        1L,
                                        "POST /api/v1/admin/roles",
                                        "111111111111111111111111",
                                        digest("body-two"))));
        assertEquals(
                ErrorCode.OPERATION_IN_PROGRESS,
                codeOf(
                        () ->
                                idempotency.occupy(
                                        1L,
                                        "POST /api/v1/admin/roles",
                                        "111111111111111111111111",
                                        requestHash)));

        byte[] response = "{\"id\":1}".getBytes(StandardCharsets.UTF_8);
        idempotency.completeSucceeded(
                1L, "POST /api/v1/admin/roles", "111111111111111111111111", response, 201);
        IdempotencyRecorder.OccupyResult replay =
                idempotency.occupy(
                        1L, "POST /api/v1/admin/roles", "111111111111111111111111", requestHash);
        assertEquals(IdempotencyRecorder.State.REPLAY, replay.state());
        assertEquals(201, replay.replay().httpStatus());
        assertTrue(java.util.Arrays.equals(response, replay.replay().responseBody()));

        byte[] retryHash = digest("retry");
        IdempotencyRecorder.OccupyResult failing =
                idempotency.occupy(
                        2L,
                        "POST /api/v1/admin/departments",
                        "222222222222222222222222",
                        retryHash);
        idempotency.completeFailed(
                2L, "POST /api/v1/admin/departments", "222222222222222222222222");
        IdempotencyRecorder.OccupyResult retried =
                idempotency.occupy(
                        2L,
                        "POST /api/v1/admin/departments",
                        "222222222222222222222222",
                        retryHash);
        assertEquals(IdempotencyRecorder.State.OCCUPIED, retried.state());
    }

    // ---- directory, preview and escalation ---------------------------------

    private record DepartmentFixture(
            long root, long middle, long direct, long user, long role, long datasource,
            long platform) {}

    private DepartmentFixture departmentFixture() throws Exception {
        long root = orgStore.createDepartment(null, "权威根", true, 1L).id();
        long middle = orgStore.createDepartment(root, "权威中层", true, 1L).id();
        long direct = orgStore.createDepartment(middle, "直属部门", true, 1L).id();
        long role = orgStore.createRole("subject_reader", "授权角色", true, List.of(), 1L).id();
        long user = orgStore.createUser(
                "subject-user", "授权用户", direct, "unused", List.of(role), true, 1L).id();
        long datasource = insertDatasource("subject-source");
        long platform = insertPlatform("subject-platform");
        insertPlatformScope(platform, "DATASOURCE", datasource, "READ");
        return new DepartmentFixture(root, middle, direct, user, role, datasource, platform);
    }

    private AclGrantStore.AclGrantRecord subjectGrant(
            String subjectType, long subjectId, long datasource, String effect) {
        return grantStore.create(new AclGrantStore.AclGrantRecord(
                0, subjectType, subjectId, "DATASOURCE", datasource, 1, "READ", effect,
                true, Instant.now().minusSeconds(1), null, "ACTIVE", "合成授权", 1), 1L);
    }

    private void setDepartmentState(long deptId, String status, String deleted) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE sys_dept SET status = ?, del_flag = ? WHERE dept_id = ?")) {
            statement.setString(1, status);
            statement.setString(2, deleted);
            statement.setLong(3, deptId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void setDepartmentParent(long deptId, long parentId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE sys_dept SET parent_id = ? WHERE dept_id = ?")) {
            statement.setLong(1, parentId);
            statement.setLong(2, deptId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void setUserDepartment(long userId, long deptId) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE sys_user_profile SET dept_id = ? WHERE user_id = ?")) {
            statement.setLong(1, deptId);
            statement.setLong(2, userId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private AclDirectory.ResourceDecision departmentPreview(DepartmentFixture fixture) {
        return directory.preview(fixture.user(), fixture.platform(), List.of(
                ResourceIds.encode(ResourceIds.Type.DATASOURCE, fixture.datasource())),
                Instant.now()).getFirst();
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-department", "not-a-number"})
    void departmentSubjectsIgnoreCachedAncestors(String cache) throws Exception {
        DepartmentFixture fixture = departmentFixture();
        long foreign = orgStore.createDepartment(null, "外部部门", true, 1L).id();
        subjectGrant("USER", fixture.user(), fixture.datasource(), "ALLOW");
        subjectGrant("DEPT", fixture.root(), fixture.datasource(), "DENY");
        subjectGrant("DEPT", foreign, fixture.datasource(), "ALLOW");
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE sys_dept SET ancestors = ? WHERE dept_id = ?")) {
            statement.setString(1, "foreign-department".equals(cache) ? "0," + foreign : cache);
            statement.setLong(2, fixture.direct());
            assertEquals(1, statement.executeUpdate());
        }
        AuthorizationReader reader = new AuthorizationReader(dataSource);
        Set<Long> expected = Set.of(fixture.root(), fixture.middle());
        assertAll(
                () -> assertEquals(expected, reader.loadSubjectScope(fixture.user(), true).deptAncestorIds()),
                () -> assertEquals(expected, directory.subjectScope(fixture.user()).deptAncestorIds()),
                () -> assertFalse(departmentPreview(fixture).read(), "the real root DENY must survive cache corruption"));
    }

    @ParameterizedTest
    @CsvSource({"ROOT,1,0", "ROOT,0,1", "MIDDLE,1,0", "MIDDLE,0,1", "DIRECT,1,0", "DIRECT,0,1"})
    void departmentSubjectsFilterEachNodesOwnState(String node, String status, String deleted)
            throws Exception {
        DepartmentFixture fixture = departmentFixture();
        Set<Long> all = Set.of(fixture.root(), fixture.middle(), fixture.direct());
        for (long dept : all) {
            subjectGrant("DEPT", dept, fixture.datasource(), "ALLOW");
        }
        long inactive = switch (node) {
            case "ROOT" -> fixture.root();
            case "MIDDLE" -> fixture.middle();
            default -> fixture.direct();
        };
        setDepartmentState(inactive, status, deleted);
        Set<Long> expected = new java.util.HashSet<>(all);
        expected.remove(inactive);
        Set<Long> ancestors = new java.util.HashSet<>(expected);
        ancestors.remove(fixture.direct());
        Long direct = inactive == fixture.direct() ? null : fixture.direct();
        AuthorizationReader reader = new AuthorizationReader(dataSource);
        assertAll(
                () -> assertEquals(direct, reader.loadSubjectScope(fixture.user(), true).deptId()),
                () -> assertEquals(ancestors, reader.loadSubjectScope(fixture.user(), true).deptAncestorIds()),
                () -> assertEquals(direct, directory.subjectScope(fixture.user()).deptId()),
                () -> assertEquals(ancestors, directory.subjectScope(fixture.user()).deptAncestorIds()),
                () -> assertEquals(expected, reader.loadGrants(fixture.user(), reader.loadSubjectScope(fixture.user(), true))
                        .stream().map(AuthorizationReader.AclGrant::subjectId).collect(java.util.stream.Collectors.toSet())),
                () -> assertEquals(expected, directory.activeGrants(fixture.user()).stream()
                        .map(local.ptmcp.common.authz.GrantRow::subjectId).collect(java.util.stream.Collectors.toSet())),
                () -> assertTrue(departmentPreview(fixture).read()));
    }

    @ParameterizedTest
    @CsvSource({"1,0", "0,1"})
    void inactiveMiddleDepartmentDoesNotHideActiveRootDeny(String status, String deleted)
            throws Exception {
        DepartmentFixture fixture = departmentFixture();
        subjectGrant("USER", fixture.user(), fixture.datasource(), "ALLOW");
        subjectGrant("DEPT", fixture.root(), fixture.datasource(), "DENY");
        setDepartmentState(fixture.middle(), status, deleted);
        AuthorizationReader reader = new AuthorizationReader(dataSource);
        assertAll(
                () -> assertEquals(Set.of(fixture.root()), reader.loadSubjectScope(fixture.user(), true).deptAncestorIds()),
                () -> assertEquals(Set.of(fixture.root()), directory.subjectScope(fixture.user()).deptAncestorIds()),
                () -> assertFalse(departmentPreview(fixture).read()));
    }

    @ParameterizedTest
    @CsvSource({"false,1,0", "false,0,1", "true,1,0", "true,0,1"})
    void inactiveDirectDepartmentPreservesUserAndRoleGrantsAndPreview(
            boolean withAncestor, String status, String deleted) throws Exception {
        DepartmentFixture fixture = departmentFixture();
        long direct = withAncestor ? fixture.direct() : fixture.root();
        setUserDepartment(fixture.user(), direct);
        AclGrantStore.AclGrantRecord user = subjectGrant("USER", fixture.user(), fixture.datasource(), "ALLOW");
        AclGrantStore.AclGrantRecord role = subjectGrant("ROLE", fixture.role(), fixture.datasource(), "ALLOW");
        subjectGrant("DEPT", direct, fixture.datasource(), "ALLOW");
        setDepartmentState(direct, status, deleted);
        Set<Long> ancestors = withAncestor ? Set.of(fixture.root(), fixture.middle()) : Set.of();
        AuthorizationReader reader = new AuthorizationReader(dataSource);
        assertAll(
                () -> assertNull(reader.loadSubjectScope(fixture.user(), true).deptId()),
                () -> assertEquals(ancestors, reader.loadSubjectScope(fixture.user(), true).deptAncestorIds()),
                () -> assertNull(directory.subjectScope(fixture.user()).deptId()),
                () -> assertEquals(ancestors, directory.subjectScope(fixture.user()).deptAncestorIds()),
                () -> assertEquals(Set.of(user.id(), role.id()), reader.loadGrants(fixture.user(), reader.loadSubjectScope(fixture.user(), true))
                        .stream().map(AuthorizationReader.AclGrant::grantId).collect(java.util.stream.Collectors.toSet())),
                () -> assertEquals(Set.of(user.id(), role.id()), directory.activeGrants(fixture.user()).stream()
                        .map(local.ptmcp.common.authz.GrantRow::grantId).collect(java.util.stream.Collectors.toSet())),
                () -> assertTrue(departmentPreview(fixture).read()),
                () -> assertEquals(Set.of(user.id(), role.id()), Set.copyOf(departmentPreview(fixture).sourceGrantIds())));
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELF", "CYCLE", "MISSING_PARENT", "MISSING_DIRECT", "NEGATIVE_PARENT", "DEPTH_65"})
    void departmentSubjectsRejectIncompleteOrCyclicAuthority(String corruption) throws Exception {
        DepartmentFixture fixture = departmentFixture();
        subjectGrant("USER", fixture.user(), fixture.datasource(), "ALLOW");
        subjectGrant("DEPT", fixture.root(), fixture.datasource(), "DENY");
        switch (corruption) {
            case "SELF" -> setDepartmentParent(fixture.direct(), fixture.direct());
            case "CYCLE" -> setDepartmentParent(fixture.root(), fixture.direct());
            case "MISSING_PARENT" -> setDepartmentParent(fixture.root(), 9_999_999L);
            case "NEGATIVE_PARENT" -> setDepartmentParent(fixture.root(), -1L);
            case "MISSING_DIRECT" -> {
                try (Connection connection = dataSource.getConnection();
                        Statement statement = connection.createStatement()) {
                    statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                    try (PreparedStatement delete = connection.prepareStatement(
                            "DELETE FROM sys_dept WHERE dept_id = ?")) {
                        delete.setLong(1, fixture.direct());
                        assertEquals(1, delete.executeUpdate());
                    } finally {
                        statement.execute("SET FOREIGN_KEY_CHECKS = 1");
                    }
                }
            }
            default -> {
                long leaf = fixture.direct();
                for (int index = 3; index < 65; index++) {
                    leaf = orgStore.createDepartment(leaf, "深层" + index, true, 1L).id();
                }
                setUserDepartment(fixture.user(), leaf);
            }
        }
        AuthorizationReader reader = new AuthorizationReader(dataSource);
        assertAll(
                () -> assertEquals(ErrorCode.SECURITY_STATE_UNAVAILABLE, codeOf(() -> reader.loadSubjectScope(fixture.user(), true))),
                () -> assertEquals(ErrorCode.SECURITY_STATE_UNAVAILABLE, codeOf(() -> reader.loadSubjectScope(fixture.user(), false))),
                () -> assertEquals(ErrorCode.SECURITY_STATE_UNAVAILABLE, codeOf(() -> directory.subjectScope(fixture.user()))),
                () -> assertEquals(ErrorCode.SECURITY_STATE_UNAVAILABLE, codeOf(() -> departmentPreview(fixture))));
    }

    @Test
    void departmentSubjectsAcceptExactly64NodesAndKeepRootDeny() throws Exception {
        DepartmentFixture fixture = departmentFixture();
        Set<Long> ancestors = new java.util.HashSet<>(Set.of(fixture.root(), fixture.middle()));
        long leaf = fixture.direct();
        for (int index = 3; index < 64; index++) {
            ancestors.add(leaf);
            leaf = orgStore.createDepartment(leaf, "合法深层" + index, true, 1L).id();
        }
        setUserDepartment(fixture.user(), leaf);
        subjectGrant("USER", fixture.user(), fixture.datasource(), "ALLOW");
        subjectGrant("DEPT", fixture.root(), fixture.datasource(), "DENY");
        AuthorizationReader reader = new AuthorizationReader(dataSource);
        long direct = leaf;
        assertAll(
                () -> assertEquals(direct, reader.loadSubjectScope(fixture.user(), true).deptId()),
                () -> assertEquals(ancestors, reader.loadSubjectScope(fixture.user(), true).deptAncestorIds()),
                () -> assertEquals(ancestors, directory.subjectScope(fixture.user()).deptAncestorIds()),
                () -> assertTrue(reader.loadSubjectScope(fixture.user(), false).deptAncestorIds().isEmpty()),
                () -> assertFalse(departmentPreview(fixture).read()));
    }

    @Test
    void directoryBuildsScopeGrantsTreeAndPreview() throws Exception {
        long platformId = insertPlatform("p-preview");
        long datasourceId = insertDatasource("ds-dir");
        insertPlatformScope(platformId, "DATASOURCE", datasourceId, "META");
        insertPlatformScope(platformId, "DATASOURCE", datasourceId, "READ");
        long namespaceId = insertNamespace(datasourceId);
        long objectId = insertObject(namespaceId);
        long encodedDs = ResourceIds.encode(ResourceIds.Type.DATASOURCE, datasourceId);
        long encodedNs = ResourceIds.encode(ResourceIds.Type.NAMESPACE, namespaceId);
        assertEquals(ResourceIds.Type.NAMESPACE, ResourceIds.typeOf(encodedNs));
        assertEquals(namespaceId, ResourceIds.tableIdOf(encodedNs));

        long deptId = orgStore.createDepartment(null, "目录部", true, 1L).id();
        OrgStore.RoleRow role = orgStore.createRole("viewer", "只读", true, List.of(), 1L);
        long userId =
                orgStore.createUser(
                                "dir-user", "目录用户", deptId, "hash", List.of(role.id()), true, 1L)
                        .id();

        SubjectScope scope = directory.subjectScope(userId);
        assertEquals(deptId, scope.deptId());
        assertEquals(Set.of(role.id()), scope.enabledRoleIds());

        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        grantStore.create(
                new AclGrantStore.AclGrantRecord(
                        0,
                        "USER",
                        userId,
                        "DATASOURCE",
                        datasourceId,
                        1,
                        "READ",
                        "ALLOW",
                        true,
                        now,
                        null,
                        "ACTIVE",
                        "目录",
                        1),
                1L);

        List<ResourceNode> tree = directory.resourceTree();
        assertTrue(tree.stream().anyMatch(n -> n.id() == encodedDs && n.parentId() == null));
        assertTrue(tree.stream().anyMatch(n -> n.id() == encodedNs && encodedDs == n.parentId()));

        List<AclDirectory.ResourceDecision> decisions =
                directory.preview(
                        userId, platformId, List.of(encodedDs, encodedNs, encodedDs * 3), now);
        assertTrue(decisions.get(0).meta(), "READ allow implies META within platform ceiling");
        assertTrue(decisions.get(0).read());
        assertTrue(decisions.get(1).meta());
        assertTrue(decisions.get(1).read());
        assertFalse(decisions.get(2).meta(), "unknown resource id denies closed");
        assertFalse(decisions.get(2).read());
    }

    @Test
    void platformCeilingCapsPreview() throws Exception {
        long platformId = insertPlatform("p-ceiling");
        long datasourceId = insertDatasource("ds-ceiling");
        long encodedDs = ResourceIds.encode(ResourceIds.Type.DATASOURCE, datasourceId);
        long deptId = orgStore.createDepartment(null, "顶棚部", true, 1L).id();
        long userId =
                orgStore.createUser("ceil-user", "顶棚", deptId, "hash", List.of(), true, 1L).id();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        grantStore.create(
                new AclGrantStore.AclGrantRecord(
                        0,
                        "USER",
                        userId,
                        "DATASOURCE",
                        datasourceId,
                        1,
                        "READ",
                        "ALLOW",
                        false,
                        now,
                        null,
                        "ACTIVE",
                        "超平台",
                        1),
                1L);
        // The platform was granted nothing, so its ceiling is closed even though the grant allows.
        List<AclDirectory.ResourceDecision> decisions =
                directory.preview(userId, platformId, List.of(encodedDs), now);
        assertFalse(decisions.get(0).meta());
        assertFalse(decisions.get(0).read());
    }

    @Test
    void selfEscalationGuardBlocksReadGainAndAllowsNeutralChange() throws Exception {
        long datasourceId = insertDatasource("ds-esc");
        long deptId = orgStore.createDepartment(null, "自增部", true, 1L).id();
        long userId =
                orgStore.createUser("esc-user", "管理员", deptId, "hash", List.of(), true, 1L).id();
        Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        AclGrantStore.AclGrantRecord metaGrant =
                grantStore.create(
                        new AclGrantStore.AclGrantRecord(
                                0,
                                "USER",
                                userId,
                                "DATASOURCE",
                                datasourceId,
                                1,
                                "META",
                                "ALLOW",
                                true,
                                now,
                                null,
                                "ACTIVE",
                                "现状",
                                1),
                        1L);
        long encodedDs = ResourceIds.encode(ResourceIds.Type.DATASOURCE, datasourceId);
        List<ResourceNode> tree = directory.resourceTree();
        SubjectScope scope = directory.subjectScope(userId);
        List<local.ptmcp.common.authz.GrantRow> before = directory.activeGrants(userId);

        List<local.ptmcp.common.authz.GrantRow> afterWithRead = new java.util.ArrayList<>(before);
        afterWithRead.add(
                new local.ptmcp.common.authz.GrantRow(
                        local.ptmcp.common.authz.SubjectType.USER,
                        userId,
                        encodedDs,
                        local.ptmcp.common.authz.PermissionGrant.Action.READ,
                        local.ptmcp.common.authz.PermissionGrant.Effect.ALLOW,
                        true,
                        now,
                        null,
                        metaGrant.id() + 1));
        DomainException blocked =
                assertThrows(
                        DomainException.class,
                        () ->
                                local.ptmcp.persistence.org.SelfEscalationGuard.assertNoEscalation(
                                        scope, before, afterWithRead, tree, true, true, now));
        assertEquals(ErrorCode.SELF_PRIVILEGE_ESCALATION, blocked.errorCode());

        local.ptmcp.persistence.org.SelfEscalationGuard.assertNoEscalation(
                scope, before, before, tree, true, true, now);
        local.ptmcp.persistence.org.SelfEscalationGuard.assertNoEscalation(
                scope, before, List.of(), tree, true, true, now);

        assertEquals(
                metaGrant.id(),
                PermissionEvaluator.evaluate(
                                GrantExpander.expand(before, scope, tree, encodedDs),
                                true,
                                true,
                                now)
                        .allowGrantIds()
                        .get(0));
    }

    @Test
    void delegationUtcWindowsIgnoreJvmTimezoneAndKeepHalfOpenBoundaries() throws Exception {
        long department = orgStore.createDepartment(null, "UTC委派部", true, 1L).id();
        orgStore.createUser("utc-placeholder", "占位", department, "unused", List.of(), true, 1L);
        long delegate =
                orgStore.createUser(
                                "utc-delegate", "委派人", department, "unused", List.of(), true, 1L)
                        .id();
        Instant from = Instant.parse("2026-03-08T06:59:59.123456Z");
        Instant to = Instant.parse("2026-03-08T07:00:59.123456Z");
        List<String> actions = List.of("ptmcp:user:update");
        TimeZone original = TimeZone.getDefault();
        try {
            int index = 0;
            for (String zone : List.of("UTC", "Asia/Taipei", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                long source = insertDatasource("utc-window-" + index++);
                DelegationStore.DelegationRow stored =
                        delegationStore.create(
                                1L,
                                "utc-window-" + zone,
                                new DelegationStore.DelegationDraft(
                                        delegate,
                                        department,
                                        "DATASOURCE",
                                        source,
                                        actions,
                                        from,
                                        to),
                                Set.copyOf(actions));
                assertEquals(from, stored.validFrom(), zone);
                assertEquals(to, stored.validTo(), zone);
                DelegationStore.DelegationRow adjacent =
                        delegationStore.create(
                                1L,
                                "utc-adjacent-" + zone,
                                new DelegationStore.DelegationDraft(
                                        delegate,
                                        department,
                                        "DATASOURCE",
                                        source,
                                        actions,
                                        to,
                                        to.plusSeconds(60)),
                                Set.copyOf(actions));
                assertEquals(to, adjacent.validFrom(), zone);
                assertEquals(
                        ErrorCode.DUPLICATE_GRANT,
                        codeOf(
                                () ->
                                        delegationStore.create(
                                                1L,
                                                "utc-overlap-" + zone,
                                                new DelegationStore.DelegationDraft(
                                                        delegate,
                                                        department,
                                                        "DATASOURCE",
                                                        source,
                                                        actions,
                                                        to.minusNanos(1000),
                                                        to.plusSeconds(30)),
                                                Set.copyOf(actions))),
                        zone);
                for (Instant boundary :
                        List.of(
                                Instant.parse("2026-03-08T02:30:00.123456Z"),
                                Instant.parse("2026-11-01T01:30:00.123456Z"))) {
                    // These UTC digits fall into New York's gap/overlap if treated as local time.
                    DelegationStore.DelegationRow dst =
                            delegationStore.create(
                                    1L,
                                    java.util.UUID.randomUUID().toString(),
                                    new DelegationStore.DelegationDraft(
                                            delegate,
                                            department,
                                            "DATASOURCE",
                                            source,
                                            actions,
                                            boundary,
                                            boundary.plusSeconds(3600)),
                                    Set.copyOf(actions));
                    assertEquals(boundary, dst.validFrom(), zone);
                    assertEquals(boundary.plusSeconds(3600), dst.validTo(), zone);
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void delegationScopeResolutionHonorsWindowActionsAndSubtree() throws Exception {
        OrgStore.DepartmentRow root = orgStore.createDepartment(null, "委派根", true, 1L);
        OrgStore.DepartmentRow child = orgStore.createDepartment(root.id(), "委派子部", true, 1L);
        OrgStore.DepartmentRow outside = orgStore.createDepartment(null, "委派外部", true, 1L);
        // Reset empties sys_user_login_name; ids restart at 1 (the actor's id), so a throwaway
        // keeps the delegated user distinct from the acting admin.
        orgStore.createUser(
                "scope-throwaway",
                "占位用户",
                root.id(),
                "$2a$10$scopescopescopescopescopescopescopescopescopesc0pe",
                List.of(),
                true,
                1L);
        long delegate =
                orgStore.createUser(
                                "scope-walker",
                                "作用域走查",
                                root.id(),
                                "$2a$10$scopescopescopescopescopescopescopescopescopesc0pe",
                                List.of(),
                                true,
                                1L)
                        .id();
        long datasourceId = 0;
        try (java.sql.Connection connection = dataSource.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO datasource (code, name, engine, host, port, database_name,"
                            + " username, credential_cipher, key_id, tls_mode, pool_max, status,"
                            + " create_time, update_time, created_by, updated_by) VALUES"
                            + " ('ds-scope', '作用域库', 'MYSQL', '127.0.0.1', 3306, 'biz',"
                            + " 'ro', X'00', 'k1', 'VERIFY_FULL', 2, 'ACTIVE', NOW(6), NOW(6), 1, 1)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                datasourceId = keys.getLong(1);
            }
        }

        delegationStore.create(
                1L,
                "scope-test-request",
                new DelegationStore.DelegationDraft(
                        delegate,
                        root.id(),
                        "DATASOURCE",
                        datasourceId,
                        List.of("ptmcp:user:list", "ptmcp:acl:list"),
                        java.time.Instant.parse("2020-01-01T00:00:00Z"),
                        java.time.Instant.parse("2030-01-01T00:00:00Z")),
                Set.of("ptmcp:user:list", "ptmcp:acl:list", "ptmcp:audit:decrypt"));
        java.time.Instant now = java.time.Instant.now();
        List<DelegationScopeResolver.EffectiveDelegation> effective =
                scopeResolver.effectiveDelegations(delegate, now);
        assertEquals(1, effective.size());

        DelegationScopeResolver.EffectiveDelegation delegation = effective.get(0);
        // Department subtree: root itself, the child, and a subject in the child are covered;
        // an unrelated root is not.
        assertTrue(
                scopeResolver.covers(
                        delegation,
                        DelegationScopeResolver.Target.department(root.id()),
                        "ptmcp:user:list",
                        now));
        assertTrue(
                scopeResolver.covers(
                        delegation,
                        DelegationScopeResolver.Target.department(child.id()),
                        "ptmcp:acl:list",
                        now));
        assertTrue(
                scopeResolver.covers(
                        delegation,
                        new DelegationScopeResolver.Target(
                                DelegationScopeResolver.TargetKind.SUBJECT, null, 0, child.id()),
                        null,
                        now));
        assertFalse(
                scopeResolver.covers(
                        delegation,
                        DelegationScopeResolver.Target.department(outside.id()),
                        "ptmcp:user:list",
                        now));

        // Resource: exact type+id match; a different datasource or type is outside.
        assertTrue(
                scopeResolver.covers(
                        delegation,
                        DelegationScopeResolver.Target.resource("DATASOURCE", datasourceId),
                        "ptmcp:acl:list",
                        now));
        assertFalse(
                scopeResolver.covers(
                        delegation,
                        DelegationScopeResolver.Target.resource("DATASOURCE", datasourceId + 1),
                        "ptmcp:acl:list",
                        now));
        assertFalse(
                scopeResolver.covers(
                        delegation,
                        DelegationScopeResolver.Target.resource("OBJECT", datasourceId),
                        "ptmcp:acl:list",
                        now));

        // Actions outside the delegated subset never pass, even inside the subtree.
        assertFalse(
                scopeResolver.covers(
                        delegation,
                        DelegationScopeResolver.Target.department(child.id()),
                        "ptmcp:audit:decrypt",
                        now));
        assertFalse(
                scopeResolver.covers(
                        delegation,
                        DelegationScopeResolver.Target.resource("DATASOURCE", datasourceId),
                        "ptmcp:audit:decrypt",
                        now));

        // Window: an expired delegation grants nothing (second delegated user to avoid the
        // duplicate-window guard, which correctly rejects overlapping rows for the same subject).
        OrgStore.UserRow expiredUser =
                orgStore.createUser(
                        "scope-expired",
                        "过期走查",
                        root.id(),
                        "$2a$10$scopescopescopescopescopescopescopescopescopesc0pe",
                        List.of(),
                        true,
                        1L);
        delegationStore.create(
                1L,
                "scope-test-expired",
                new DelegationStore.DelegationDraft(
                        expiredUser.id(),
                        root.id(),
                        "DATASOURCE",
                        datasourceId,
                        List.of("ptmcp:user:list"),
                        java.time.Instant.parse("2020-01-01T00:00:00Z"),
                        java.time.Instant.parse("2021-01-01T00:00:00Z")),
                Set.of("ptmcp:user:list"));
        // Still one effective row for the active subject; the expired row never enters the set.
        assertEquals(1, scopeResolver.effectiveDelegations(delegate, now).size());
        assertEquals(0, scopeResolver.effectiveDelegations(expiredUser.id(), now).size());

        // A revoked delegation drops out of the effective set.
        DelegationStore.DelegationRow active = delegationStore.get(effective.get(0).id());
        delegationStore.revoke(active.id(), active.version(), 1L, "scope-test-revoke");
        assertEquals(0, scopeResolver.effectiveDelegations(delegate, now).size());
    }
}
