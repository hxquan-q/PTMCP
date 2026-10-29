package local.ptmcp.persistence.org;

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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

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
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

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
        long delegate = orgStore.createUser("utc-delegate", "委派人", department,
                "unused", List.of(), true, 1L).id();
        Instant from = Instant.parse("2026-03-08T06:59:59.123456Z");
        Instant to = Instant.parse("2026-03-08T07:00:59.123456Z");
        List<String> actions = List.of("ptmcp:user:update");
        TimeZone original = TimeZone.getDefault();
        try {
            int index = 0;
            for (String zone : List.of("UTC", "Asia/Taipei", "America/New_York")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                long source = insertDatasource("utc-window-" + index++);
                DelegationStore.DelegationRow stored = delegationStore.create(1L,
                        "utc-window-" + zone,
                        new DelegationStore.DelegationDraft(delegate, department, "DATASOURCE",
                                source, actions, from, to), Set.copyOf(actions));
                assertEquals(from, stored.validFrom(), zone);
                assertEquals(to, stored.validTo(), zone);
                DelegationStore.DelegationRow adjacent = delegationStore.create(1L,
                        "utc-adjacent-" + zone,
                        new DelegationStore.DelegationDraft(delegate, department, "DATASOURCE",
                                source, actions, to, to.plusSeconds(60)), Set.copyOf(actions));
                assertEquals(to, adjacent.validFrom(), zone);
                assertEquals(ErrorCode.DUPLICATE_GRANT, codeOf(() -> delegationStore.create(1L,
                        "utc-overlap-" + zone,
                        new DelegationStore.DelegationDraft(delegate, department, "DATASOURCE",
                                source, actions, to.minusNanos(1000), to.plusSeconds(30)),
                        Set.copyOf(actions))), zone);
                for (Instant boundary : List.of(
                        Instant.parse("2026-03-08T02:30:00.123456Z"),
                        Instant.parse("2026-11-01T01:30:00.123456Z"))) {
                    // These UTC digits fall into New York's gap/overlap if treated as local time.
                    DelegationStore.DelegationRow dst = delegationStore.create(1L,
                            java.util.UUID.randomUUID().toString(),
                            new DelegationStore.DelegationDraft(delegate, department, "DATASOURCE",
                                    source, actions, boundary, boundary.plusSeconds(3600)),
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
