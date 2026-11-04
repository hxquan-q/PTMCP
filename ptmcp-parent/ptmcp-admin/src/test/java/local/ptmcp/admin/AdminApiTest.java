package local.ptmcp.admin;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mysql.cj.jdbc.exceptions.CommunicationsException;

import local.ptmcp.admin.api.AdminAction;
import local.ptmcp.admin.api.AdminActionInterceptor;
import local.ptmcp.admin.api.AdminPrincipal;
import local.ptmcp.admin.api.ApiExceptionAdvice;
import local.ptmcp.admin.api.DepartmentAdminController;
import local.ptmcp.admin.api.IdempotentWriteTestSupport;
import local.ptmcp.admin.api.RoleAdminController;
import local.ptmcp.admin.api.UserAdminController;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.IdempotencyRecorder;
import local.ptmcp.persistence.org.OrgStore;
import local.ptmcp.persistence.org.ResourceIds;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Admin org/ACL APIs against the real MySQL schema; identity uses the documented test shim. */
@SpringBootTest(
        classes = AdminApplication.class,
        properties = {
            "ptmcp.admin.test-principal=true",
            "ptmcp.admin.credential-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ptmcp.admin.audit-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ptmcp.admin.identity-datasource.url=${ptmcp.admin.datasource.url}",
            "ptmcp.admin.identity-datasource.username=${ptmcp.admin.datasource.username}",
            "ptmcp.admin.identity-datasource.password=${ptmcp.admin.datasource.password}"
        })
@AutoConfigureMockMvc
class AdminApiTest {
    private static final String MYSQL_IMAGE =
            System.getProperty(
                    "ptmcp.test.mysqlImage",
                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69");
    private static final MySQLContainer MYSQL = startMysql();

    private static MySQLContainer startMysql() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        MySQLContainer container =
                new MySQLContainer(MYSQL_IMAGE)
                        .withDatabaseName("admin_api_test")
                        .withUsername("admin_test")
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
        container.start();
        for (int attempt = 0; ; attempt++) {
            try {
                Flyway.configure()
                        .dataSource(
                                container.getJdbcUrl(),
                                container.getUsername(),
                                container.getPassword())
                        .locations("classpath:db/ptmcp-migration")
                        .cleanDisabled(true)
                        .baselineOnMigrate(false)
                        .load()
                        .migrate();
                break;
            } catch (FlywayException failure) {
                if (attempt >= 4 || !isCommunicationFailure(failure)) {
                    throw failure;
                }
                pauseBeforeRetry(attempt);
            }
        }
        return container;
    }

    /**
     * WSL2 Docker occasionally drops the forwarded container port for a few seconds right after
     * startup. Retry transport-level failures so the suite still exercises the real database;
     * assertions, timeouts on legitimate SQL errors and the test set itself are unchanged.
     */
    private static Connection connect() throws SQLException {
        SQLException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return DriverManager.getConnection(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
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

    @DynamicPropertySource
    static void adminDatasource(DynamicPropertyRegistry registry) {
        registry.add("ptmcp.admin.datasource.url", MYSQL::getJdbcUrl);
        registry.add("ptmcp.admin.datasource.username", MYSQL::getUsername);
        registry.add("ptmcp.admin.datasource.password", MYSQL::getPassword);
        // 32-byte base64 key so the platform proof-config cipher is exercised for real.
        registry.add(
                "ptmcp.admin.credential-key", () -> "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
    }

    @AfterAll
    static void stopOwnedContainer() {
        MYSQL.stop();
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private OrgStore orgStore;
    @Autowired private IdempotencyRecorder idempotencyRecorder;

    @Autowired
    @Qualifier("adminDataSource")
    private DataSource primary;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @BeforeEach
    void resetOnlyOwnedData() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            // The host identity table is test-created and not migrated; metadata IDs restart per
            // test, so a leftover host row would collide with the next test's user ID.
            statement.execute("DROP TABLE IF EXISTS sys_user");
            for (String table :
                    List.of(
                            "resource_object",
                            "resource_namespace",
                            "schema_snapshot",
                            "datasource",
                            "acl_grant",
                            "platform_scope",
                            "admin_operation_log",
                            "identity_password_change",
                            "identity_provision",
                            "sys_user_security",
                            "web_session_index",
                            "personal_token",
                            "oauth_refresh_family",
                            "oauth2_authorization",
                            "oauth2_registered_client",
                            "sys_user_role",
                            "sys_user_profile",
                            "sys_user_login_name",
                            "sys_role",
                            "sys_dept",
                            "platform_registry",
                            "audit_sensitive_access",
                            "audit_log",
                            "audit_outbox",
                            "api_idempotency")) {
                statement.execute("DELETE FROM " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            seedRoleAdminIdentity(connection);
        }
    }

    /**
     * Only this fixture actor gets an authoritative identity; grant() must not repair identities.
     * Its soft-deleted fixture department stays outside the departments created by each test.
     * Platform role management requires an active profile, not a business department grant.
     */
    private static void seedRoleAdminIdentity(Connection connection) throws SQLException {
        try (PreparedStatement department =
                        connection.prepareStatement(
                                "INSERT INTO sys_dept (dept_id,dept_name,status,del_flag,create_time,update_time)"
                                        + " VALUES (?,'测试管理部门','0','1',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                PreparedStatement profile =
                        connection.prepareStatement(
                                "INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,create_time,update_time)"
                                        + " VALUES (?,?,'测试管理员','external','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                PreparedStatement authority =
                        connection.prepareStatement(
                                "INSERT INTO sys_user_security (user_id,auth_version,identity_version,identity_state,platform_admin,create_time,update_time)"
                                        + " VALUES (?,1,1,'READY',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
            department.setLong(1, ADMIN_ID);
            department.executeUpdate();
            profile.setLong(1, ADMIN_ID);
            profile.setLong(2, ADMIN_ID);
            profile.executeUpdate();
            authority.setLong(1, ADMIN_ID);
            authority.executeUpdate();
        }
    }

    // Kept outside OrgStore's allocated profile IDs; an actor role must not become a user's role.
    private static final long ADMIN_ID = 9_000_001L;

    private String adminHeaders(long userId) {
        return "X-Test-User-Id: " + userId + ", X-Test-Platform-Admin: true";
    }

    @Test
    void adminActionEveryRealRouteHasItsContractedPermission() {
        Map<String, String> actual = new HashMap<>();
        handlerMapping
                .getHandlerMethods()
                .forEach(
                        (mapping, method) -> {
                            for (String path : mapping.getPatternValues()) {
                                if (!path.startsWith("/api/v1/admin/")) {
                                    continue;
                                }
                                AdminAction action = method.getMethodAnnotation(AdminAction.class);
                                assertNotNull(action, "Missing action for " + path);
                                assertFalse(
                                        mapping.getMethodsCondition().getMethods().isEmpty(), path);
                                for (RequestMethod httpMethod :
                                        mapping.getMethodsCondition().getMethods()) {
                                    String route = httpMethod.name() + " " + path;
                                    assertEquals(
                                            null,
                                            actual.put(route, action.value()),
                                            "Duplicate " + route);
                                }
                            }
                        });
        assertEquals(AdminActionTestSupport.ACTION_BY_ROUTE, actual);
    }

    @Test
    void adminActionRevokedPlatformAdminCannotReplayIdempotentResult() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = "{\"name\":\"撤权前创建\",\"enabled\":true}";
        mockMvc.perform(
                        post("/api/v1/admin/departments")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isCreated());
        String before = adminActionWriteEffects();
        // S2: the same caller stripped of platform-admin status is denied before any replay.
        mockMvc.perform(
                        post("/api/v1/admin/departments")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("Idempotency-Key", idempotencyKey)
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
        assertEquals(before, adminActionWriteEffects());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "users",
                "departments",
                "roles",
                "platforms",
                "datasources",
                "delegations",
                "acl/grants",
                "operations",
                "audit"
            })
    @Test
    void adminActionGateReevaluatesPrincipalOnEveryRequest() throws Exception {
        // S2: the gate is binary and per-request; a principal that loses platform-admin status is
        // denied on the next request with no login-time snapshot in between.
        mockMvc.perform(
                        get("/api/v1/admin/departments")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/departments").header("X-Test-User-Id", ADMIN_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
        mockMvc.perform(
                        get("/api/v1/admin/departments")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk());
    }

    @Test
    void ordinaryUserIsDeniedTheWholeAdminSurface() throws Exception {
        // S2 验收反例：platform_admin=0 的普通用户不进管理后台，任何管理端点一律 403。
        for (MockHttpServletRequestBuilder request :
                List.of(
                        get("/api/v1/admin/roles"),
                        get("/api/v1/admin/users"),
                        get("/api/v1/admin/departments"),
                        get("/api/v1/admin/datasources"),
                        post("/api/v1/admin/roles")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content(roleCreateBody("denied_probe")),
                        delete("/api/v1/admin/users/1").header("If-Match", "\"1\""))) {
            mockMvc.perform(request.header("X-Test-User-Id", 42L))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
        }
    }

    @Test
    void platformAdminPassesEveryAdminActionWithoutCapabilityCodes() throws Exception {
        // S2 验收：唯一管理员不给任何能力码也能操作全部管理功能（身份仍来自列/主体）。
        for (String surface : List.of("/roles", "/users", "/departments", "/datasources")) {
            mockMvc.perform(
                            get("/api/v1/admin" + surface)
                                    .header("X-Test-User-Id", ADMIN_ID)
                                    .header("X-Test-Platform-Admin", "true"))
                    .andExpect(status().isOk());
        }
    }

    private String adminActionWriteEffects() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT CONCAT_WS('|',"
                                        + "(SELECT COUNT(*) FROM sys_dept), (SELECT COUNT(*) FROM admin_operation_log),"
                                        + "(SELECT COUNT(*) FROM api_idempotency), (SELECT COUNT(*) FROM sys_security_event),"
                                        + "(SELECT COUNT(*) FROM audit_sensitive_access),"
                                        + "(SELECT policy_epoch FROM sys_security_state WHERE singleton_id=1))")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    @Test
    void departmentCrudEnforcesIfMatchAndVersionConflict() throws Exception {
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/departments")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "dept-crud-" + UUID.randomUUID())
                                        .contentType("application/json")
                                        .content("{\"name\":\"集团\",\"enabled\":true}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.id").isNumber())
                        .andExpect(jsonPath("$.version").value(1))
                        .andReturn();

        long id = readLong(created.getResponse().getContentAsString(), "id");

        mockMvc.perform(
                        get("/api/v1/admin/departments")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("集团"));

        mockMvc.perform(
                        put("/api/v1/admin/departments/" + id)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content("{\"name\":\"集团A\",\"enabled\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        mockMvc.perform(
                        put("/api/v1/admin/departments/" + id)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "9")
                                .contentType("application/json")
                                .content("{\"name\":\"集团A\",\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(
                        put("/api/v1/admin/departments/" + id)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1")
                                .contentType("application/json")
                                .content("{\"name\":\"集团A\",\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(
                        result -> {
                            String etag = result.getResponse().getHeader("ETag");
                            if (!"\"2\"".equals(etag)) {
                                throw new AssertionError("unexpected ETag: " + etag);
                            }
                        });

        mockMvc.perform(
                        delete("/api/v1/admin/departments/" + id)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2"))
                .andExpect(status().isNoContent());

        mockMvc.perform(
                        get("/api/v1/admin/departments/" + id)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void departmentFullUpdateRejectsInvalidNameWithoutMovingSubtree() throws Exception {
        DepartmentEditFixture fixture = departmentEditFixture();
        List<OrgStore.DepartmentRow> before = orgStore.listDepartments();
        String writesBefore = adminActionWriteEffects();

        mockMvc.perform(departmentEditRequest(fixture, 1, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        assertEquals(before, orgStore.listDepartments());
        assertEquals(writesBefore, adminActionWriteEffects());
    }

    @Test
    void departmentFullUpdateRollsBackMoveAndDescendantsWhenLogFails() throws Exception {
        DepartmentEditFixture fixture = departmentEditFixture();
        List<OrgStore.DepartmentRow> before = orgStore.listDepartments();
        String writesBefore = adminActionWriteEffects();
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE admin_operation_log ADD CONSTRAINT department_edit_log_fault"
                            + " CHECK (action <> 'DEPARTMENT_UPDATE')");
        }
        try {
            mockMvc.perform(departmentEditRequest(fixture, 1, "已移动编辑"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));

            assertEquals(before, orgStore.listDepartments());
            assertEquals(writesBefore, adminActionWriteEffects());
        } finally {
            try (Connection connection = connect();
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "ALTER TABLE admin_operation_log DROP CHECK department_edit_log_fault");
            }
        }
    }

    @Test
    void departmentFullUpdateCommitsMoveAndFieldsWithOneVersionAndOneLog() throws Exception {
        DepartmentEditFixture fixture = departmentEditFixture();
        mockMvc.perform(departmentEditRequest(fixture, 1, "已移动编辑"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.parentId").value(fixture.destinationId()))
                .andExpect(jsonPath("$.name").value("已移动编辑"))
                .andExpect(jsonPath("$.enabled").value(false));

        assertEquals(
                new OrgStore.DepartmentRow(
                        fixture.childId(),
                        fixture.destinationId(),
                        "已移动编辑",
                        false,
                        2,
                        String.valueOf(fixture.destinationId())),
                orgStore.getDepartment(fixture.childId()));
        assertEquals(
                new OrgStore.DepartmentRow(
                        fixture.grandchildId(),
                        fixture.childId(),
                        "孙级",
                        true,
                        2,
                        fixture.destinationId() + "," + fixture.childId()),
                orgStore.getDepartment(fixture.grandchildId()));
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT action FROM admin_operation_log WHERE aggregate_type='DEPARTMENT'"
                                        + " AND aggregate_id=? AND action<>'DEPARTMENT_CREATE'")) {
            statement.setLong(1, fixture.childId());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals("DEPARTMENT_UPDATE", result.getString(1));
                assertFalse(result.next(), "one full PUT is one recorded command");
            }
        }

        List<OrgStore.DepartmentRow> committed = orgStore.listDepartments();
        String writesCommitted = adminActionWriteEffects();
        mockMvc.perform(departmentEditRequest(fixture, 1, "迟到写入"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertEquals(committed, orgStore.listDepartments());
        assertEquals(writesCommitted, adminActionWriteEffects());
    }

    private DepartmentEditFixture departmentEditFixture() {
        long source = orgStore.createDepartment(null, "原父级", true, ADMIN_ID).id();
        long destination = orgStore.createDepartment(null, "新父级", true, ADMIN_ID).id();
        long child = orgStore.createDepartment(source, "子级", true, ADMIN_ID).id();
        long grandchild = orgStore.createDepartment(child, "孙级", true, ADMIN_ID).id();
        return new DepartmentEditFixture(destination, child, grandchild);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            departmentEditRequest(DepartmentEditFixture fixture, long version, String name) {
        return put("/api/v1/admin/departments/" + fixture.childId())
                .header("X-Test-User-Id", ADMIN_ID)
                .header("X-Test-Platform-Admin", "true")
                .header("If-Match", version)
                .contentType("application/json")
                .content(
                        "{\"parentId\":"
                                + fixture.destinationId()
                                + ",\"name\":\""
                                + name
                                + "\",\"enabled\":false}");
    }

    private record DepartmentEditFixture(long destinationId, long childId, long grandchildId) {}

    @Test
    void platformDepartmentCommandCanPromoteDepartmentToRoot() throws Exception {
        DepartmentCommandFixture fixture = departmentCommandFixture();

        mockMvc.perform(
                        put("/api/v1/admin/departments/" + fixture.targetId())
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", 1)
                                .contentType("application/json")
                                .content("{\"parentId\":null,\"name\":\"提升为根\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""));
        assertEquals(null, orgStore.getDepartment(fixture.targetId()).parentId());
        assertEquals(
                String.valueOf(fixture.targetId()),
                orgStore.getDepartment(fixture.descendantId()).ancestors());
    }

    @Test
    void departmentCommandRejectsFutureVersionBeforeConcurrentOutOfScopeMove() throws Exception {
        DepartmentCommandFixture fixture = departmentCommandFixture();
        OrgStore.DepartmentRow original = orgStore.getDepartment(fixture.targetId());
        CountDownLatch requestReady = new CountDownLatch(1);
        CountDownLatch concurrentMoveCommitted = new CountDownLatch(1);
        OrgStore observedStore = spy(orgStore);
        doAnswer(
                        invocation -> {
                            requestReady.countDown();
                            assertTrue(concurrentMoveCommitted.await(15, TimeUnit.SECONDS));
                            return invocation.callRealMethod();
                        })
                .when(observedStore)
                .editDepartment(
                        fixture.targetId(),
                        fixture.rootId(),
                        "部门命令已编辑",
                        true,
                        original.version() + 1,
                        fixture.delegateId());
        MockMvc isolatedMvc =
                MockMvcBuilders.standaloneSetup(
                                new DepartmentAdminController(observedStore, idempotencyRecorder))
                        .setControllerAdvice(new ApiExceptionAdvice())
                        .build();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var request =
                    executor.submit(
                            () -> {
                                try {
                                    return isolatedMvc
                                            .perform(
                                                    departmentCommandRequest(
                                                                    fixture,
                                                                    original.id(),
                                                                    original.parentId(),
                                                                    original.version() + 1)
                                                            .requestAttr(
                                                                    AdminPrincipal.ATTRIBUTE,
                                                                    new AdminPrincipal(
                                                                            fixture.delegateId(),
                                                                            false)))
                                            .andReturn();
                                } finally {
                                    // A fixed controller can reject before entering the store.
                                    requestReady.countDown();
                                }
                            });
            try {
                assertTrue(requestReady.await(15, TimeUnit.SECONDS));
                orgStore.moveDepartment(
                        original.id(), fixture.outsideId(), original.version(), ADMIN_ID);
                DepartmentWriteEffects committed = departmentWriteEffects();
                concurrentMoveCommitted.countDown();
                MvcResult result = request.get(15, TimeUnit.SECONDS);
                assertEquals(409, result.getResponse().getStatus());
                assertTrue(result.getResponse().getContentAsString().contains("VERSION_CONFLICT"));
                assertEquals(committed, departmentWriteEffects());
            } finally {
                concurrentMoveCommitted.countDown();
            }
        }
    }

    private DepartmentCommandFixture departmentCommandFixture() throws Exception {
        long top = orgStore.createDepartment(null, "上级范围外", true, ADMIN_ID).id();
        long root = orgStore.createDepartment(top, "部门命令范围", true, ADMIN_ID).id();
        long target = orgStore.createDepartment(root, "原部门", true, ADMIN_ID).id();
        long descendant = orgStore.createDepartment(target, "原后代", true, ADMIN_ID).id();
        long destination = orgStore.createDepartment(root, "范围内目标", true, ADMIN_ID).id();
        long outside = orgStore.createDepartment(null, "范围外目标", true, ADMIN_ID).id();
        long delegate = createUser("department-command-delegate", "部门管理员", root);
        DepartmentCommandFixture fixture =
                new DepartmentCommandFixture(
                        root,
                        target,
                        descendant,
                        destination,
                        outside,
                        delegate,
                        insertActiveDatasource());
        return fixture;
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            departmentCommandRequest(
                    DepartmentCommandFixture fixture, long targetId, Long parentId, long version) {
        return put("/api/v1/admin/departments/" + targetId)
                .header("X-Test-User-Id", fixture.delegateId())
                .header("X-Test-Platform-Admin", "false")
                .header("If-Match", version)
                .contentType("application/json")
                .content("{\"parentId\":" + parentId + ",\"name\":\"部门命令已编辑\",\"enabled\":true}");
    }

    private DepartmentWriteEffects departmentWriteEffects() throws Exception {
        return new DepartmentWriteEffects(orgStore.listDepartments(), adminActionWriteEffects());
    }

    private record DepartmentWriteEffects(List<OrgStore.DepartmentRow> rows, String otherWrites) {}

    private record DepartmentCommandFixture(
            long rootId,
            long targetId,
            long descendantId,
            long destinationId,
            long outsideId,
            long delegateId,
            long datasourceId) {}

    @Test
    void roleAndUserLifecycleWithDuplicateKey() throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/admin/roles")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "role-" + UUID.randomUUID())
                                        .contentType("application/json")
                                        .content(
                                                "{\"key\":\"analyst\",\"name\":\"分析员\","
                                                        + "\"enabled\":true}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long roleId = readLong(body, "id");

        mockMvc.perform(
                        post("/api/v1/admin/roles")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "role-dup-" + UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        "{\"key\":\"analyst\",\"name\":\"重复\","
                                                + "\"enabled\":true}"))
                .andExpect(status().isBadRequest());

        long deptId =
                readLong(
                        mockMvc.perform(
                                        post("/api/v1/admin/departments")
                                                .header("X-Test-User-Id", ADMIN_ID)
                                                .header("X-Test-Platform-Admin", "true")
                                                .header(
                                                        "Idempotency-Key",
                                                        "dept-lc-" + UUID.randomUUID())
                                                .contentType("application/json")
                                                .content("{\"name\":\"部\",\"enabled\":true}"))
                                .andExpect(status().isCreated())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "id");

        String userBody =
                mockMvc.perform(
                                post("/api/v1/admin/users")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                "{\"username\":\"alice\",\"displayName\":\"爱丽丝\","
                                                        + "\"deptId\":"
                                                        + deptId
                                                        + ",\"roleIds\":["
                                                        + roleId
                                                        + "],\"enabled\":true}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.username").value("alice"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        long userId = readLong(userBody, "id");
        int userVersion = (int) readLong(userBody, "version");

        mockMvc.perform(
                        put("/api/v1/admin/users/" + userId + "/roles")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", String.valueOf(userVersion))
                                .contentType("application/json")
                                .content("{\"roleIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roleIds.length()").value(0))
                .andExpect(jsonPath("$.version").value(userVersion + 1));

        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("keyword", "ali"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // 大小写不敏感：数据源列表走 MySQL LIKE（ai_ci）、预览服务端小写化比较，用户列表不能例外。
        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("keyword", "ALI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("alice"));

        // 显示名同样参与匹配：命中中文子串。
        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("keyword", "爱丽"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].username").value("alice"));

        // 无命中时必须是空列表，而不是回退成不过滤。
        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("keyword", "no-such-user-zzz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 空白关键字等于不过滤，不能把列表清空。
        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("keyword", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void selfGrantEscalationIsBlockedWithoutAnyRowWritten() throws Exception {
        long deptId = createDepartment("自增部");
        long selfActor = createUser("esc-admin", "自增管理员", deptId);
        try (Connection connection = connect();
                PreparedStatement ready =
                        connection.prepareStatement(
                                "UPDATE sys_user_security SET identity_state='READY',"
                                        + " platform_admin=1 WHERE user_id=?")) {
            ready.setLong(1, selfActor);
            ready.executeUpdate();
        }
        long datasourceId = insertActiveDatasource();

        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", selfActor)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "grant-esc-" + UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        grantBody("USER", selfActor, datasourceId, "META", "ALLOW"))
                                .header("If-Match", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_PRIVILEGE_ESCALATION"));

        mockMvc.perform(
                        get("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", selfActor)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        long otherUser = createUser("other-user", "其他用户", deptId);
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/acl/grants")
                                        .header("X-Test-User-Id", selfActor)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header(
                                                "Idempotency-Key",
                                                "grant-self-" + UUID.randomUUID())
                                        .contentType("application/json")
                                        .content(
                                                grantBody(
                                                        "USER",
                                                        otherUser,
                                                        datasourceId,
                                                        "READ",
                                                        "ALLOW")))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andReturn();
        long grantId = readLong(created.getResponse().getContentAsString(), "id");

        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", selfActor)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(
                                        grantBody(
                                                "USER", otherUser, datasourceId, "READ", "ALLOW")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_GRANT"));

        mockMvc.perform(
                        delete("/api/v1/admin/acl/grants/" + grantId)
                                .header("X-Test-User-Id", selfActor)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(
                        delete("/api/v1/admin/acl/grants/" + grantId)
                                .header("X-Test-User-Id", selfActor)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(
                        get("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", selfActor)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("REVOKED"));
    }

    @Test
    void nonPlatformAdminIsDeniedAndAnonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/admin/departments").header("X-Test-User-Id", 42L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        mockMvc.perform(get("/api/v1/admin/departments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void genericUserUpdateRequiresUpdateCapability() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:move"));
        assertGenericEditDenied(fixture, true, "[" + fixture.narrowRoleId() + "]");
    }

    @Test
    void genericUserUpdateCannotChangeStatusWithoutCapability() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:update", "ptmcp:user:move"));
        assertGenericEditDenied(fixture, false, "[" + fixture.narrowRoleId() + "]");
    }

    @Test
    void genericUserUpdateCannotReplaceRolesWithoutCapability() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:update", "ptmcp:user:move"));
        assertGenericEditDenied(fixture, true, "[]");
    }

    @Test
    void genericUserUpdateAllowsUnchangedRestrictedFields() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:update"));
        OrgStore.UserRow before = orgStore.getUser(fixture.targetId());
        mockMvc.perform(
                        put("/api/v1/admin/users/" + fixture.targetId())
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", before.version())
                                .contentType("application/json")
                                .content(
                                        userEditBody(
                                                fixture, true, "[" + fixture.narrowRoleId() + "]")))
                .andExpect(status().isOk());
        OrgStore.UserRow after = orgStore.getUser(fixture.targetId());
        assertEquals("已编辑", after.displayName());
        assertEquals(before.version() + 1, after.version());
        assertEquals(before.enabled(), after.enabled());
        assertEquals(before.deptId(), after.deptId());
        assertEquals(before.roleIds(), after.roleIds());
    }

    void genericUserUpdateAllowsAuthorizedStatusAndRoleChanges() throws Exception {
        UserEditFixture fixture =
                userEditFixture(
                        List.of(
                                "ptmcp:user:update",
                                "ptmcp:user:status",
                                "ptmcp:user:assign-role",
                                "ptmcp:user:move"));
        OrgStore.UserRow before = orgStore.getUser(fixture.targetId());
        mockMvc.perform(
                        put("/api/v1/admin/users/" + fixture.targetId())
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", before.version())
                                .contentType("application/json")
                                .content(userEditBody(fixture, false, "[]")))
                .andExpect(status().isOk());
        OrgStore.UserRow after = orgStore.getUser(fixture.targetId());
        assertFalse(after.enabled());
        assertTrue(after.roleIds().isEmpty());
        assertEquals(before.version() + 1, after.version());
    }

    @Test
    void genericUserUpdatePreservesMissingRolesAndIgnoresOrderOnlyChanges() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:update"));
        long secondRole = orgStore.createRole("edit_second", "另一角色", true, ADMIN_ID).id();
        OrgStore.UserRow initial = orgStore.getUser(fixture.targetId());
        OrgStore.UserRow before =
                orgStore.updateUser(
                        initial.id(),
                        initial.displayName(),
                        initial.deptId(),
                        null,
                        null,
                        null,
                        null,
                        List.of(fixture.narrowRoleId(), secondRole),
                        initial.version(),
                        ADMIN_ID);
        for (String roles :
                new String[] {null, "[" + secondRole + "," + fixture.narrowRoleId() + "]"}) {
            OrgStore.UserRow current = orgStore.getUser(fixture.targetId());
            mockMvc.perform(
                            put("/api/v1/admin/users/" + fixture.targetId())
                                    .header("X-Test-User-Id", ADMIN_ID)
                                    .header("X-Test-Platform-Admin", "true")
                                    .header("If-Match", current.version())
                                    .contentType("application/json")
                                    .content(userEditBody(fixture, true, roles)))
                    .andExpect(status().isOk());
            assertEquals(before.roleIds(), orgStore.getUser(fixture.targetId()).roleIds());
        }
    }

    @Test
    void genericUserUpdateRejectsDuplicateRoleIdsWithoutWriting() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:update"));
        OrgStore.UserRow before = orgStore.getUser(fixture.targetId());
        mockMvc.perform(
                        put("/api/v1/admin/users/" + fixture.targetId())
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", before.version())
                                .contentType("application/json")
                                .content(
                                        userEditBody(
                                                fixture,
                                                true,
                                                "["
                                                        + fixture.narrowRoleId()
                                                        + ","
                                                        + fixture.narrowRoleId()
                                                        + "]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(before, orgStore.getUser(fixture.targetId()));
    }

    @Test
    void genericUserUpdateRejectsInvalidRoleIdsWithoutWriting() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:update"));
        OrgStore.UserRow before = orgStore.getUser(fixture.targetId());
        for (String roles : List.of("[null]", "[0]", "[-1]")) {
            mockMvc.perform(
                            put("/api/v1/admin/users/" + fixture.targetId())
                                    .header("X-Test-User-Id", ADMIN_ID)
                                    .header("X-Test-Platform-Admin", "true")
                                    .header("If-Match", before.version())
                                    .contentType("application/json")
                                    .content(userEditBody(fixture, true, roles)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
            assertEquals(before, orgStore.getUser(fixture.targetId()));
        }
    }

    @Test
    void genericUserUpdateDoesNotRetryAnOutdatedSnapshot() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:update"));
        OrgStore.UserRow original = orgStore.getUser(fixture.targetId());
        OrgStore.UserRow current =
                orgStore.updateUser(
                        original.id(),
                        "并发更新资料",
                        original.deptId(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        original.version(),
                        ADMIN_ID);
        mockMvc.perform(
                        put("/api/v1/admin/users/" + fixture.targetId())
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", original.version())
                                .contentType("application/json")
                                .content(userEditBody(fixture, true, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertEquals(current, orgStore.getUser(fixture.targetId()));
    }

    @Test
    void genericUserUpdateCannotAuthorizeAgainstADifferentFutureVersion() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:update"));
        OrgStore.UserRow original = orgStore.getUser(fixture.targetId());
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch concurrentUpdateCommitted = new CountDownLatch(1);
        AtomicBoolean intercepted = new AtomicBoolean();
        OrgStore observedStore = spy(orgStore);
        doAnswer(
                        invocation -> {
                            OrgStore.UserRow snapshot =
                                    (OrgStore.UserRow) invocation.callRealMethod();
                            if (intercepted.compareAndSet(false, true)) {
                                snapshotRead.countDown();
                                assertTrue(
                                        concurrentUpdateCommitted.await(15, TimeUnit.SECONDS),
                                        "concurrent status update must commit before authorization continues");
                            }
                            return snapshot;
                        })
                .when(observedStore)
                .getUser(fixture.targetId());
        MockMvc isolatedMvc =
                MockMvcBuilders.standaloneSetup(
                                new UserAdminController(observedStore, idempotencyRecorder))
                        .setControllerAdvice(new ApiExceptionAdvice())
                        .build();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var request =
                    executor.submit(
                            () ->
                                    isolatedMvc
                                            .perform(
                                                    put("/api/v1/admin/users/" + fixture.targetId())
                                                            .requestAttr(
                                                                    AdminPrincipal.ATTRIBUTE,
                                                                    new AdminPrincipal(
                                                                            fixture.delegateId(),
                                                                            false))
                                                            .header(
                                                                    "If-Match",
                                                                    original.version() + 1)
                                                            .contentType("application/json")
                                                            .content(
                                                                    userEditBody(
                                                                            fixture, true, null)))
                                            .andReturn());
            try {
                assertTrue(
                        snapshotRead.await(15, TimeUnit.SECONDS),
                        "controller must capture the original authorization snapshot");
                OrgStore.UserRow disabled =
                        orgStore.updateUser(
                                original.id(),
                                original.displayName(),
                                original.deptId(),
                                null,
                                null,
                                null,
                                false,
                                null,
                                original.version(),
                                ADMIN_ID);
                assertEquals(original.version() + 1, disabled.version());
                concurrentUpdateCommitted.countDown();
                MvcResult result = request.get(15, TimeUnit.SECONDS);
                assertEquals(409, result.getResponse().getStatus());
                assertEquals(disabled, orgStore.getUser(fixture.targetId()));
            } finally {
                concurrentUpdateCommitted.countDown();
            }
        }
    }

    @Test
    void specializedUserStatusRequiresActorCapability() throws Exception {
        assertSpecializedActorCapability(
                "status", "ptmcp:user:status", "{\"status\":\"DISABLED\"}");
    }

    @Test
    void specializedUserRolesRequiresActorCapability() throws Exception {
        assertSpecializedActorCapability("roles", "ptmcp:user:assign-role", "{\"roleIds\":[]}");
    }

    @Test
    void specializedUserDepartmentRequiresActorCapability() throws Exception {
        assertSpecializedActorCapability("department", "ptmcp:user:move", null);
    }

    private void assertSpecializedActorCapability(String endpoint, String action, String body)
            throws Exception {
        UserEditFixture fixture = userEditFixture(List.of(action));
        if (body == null) {
            long destination =
                    orgStore.createDepartment(fixture.departmentId(), "迁入子部门", true, ADMIN_ID).id();
            body = "{\"departmentId\":" + destination + "}";
        }
        assertSpecializedEditDenied(fixture, endpoint, body);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void genericUserUpdatePreservesUnchangedProfileFields(boolean enabled) throws Exception {
        UserEditFixture fixture =
                userEditFixture(List.of("ptmcp:user:update", "ptmcp:user:status"));
        OrgStore.UserRow original = orgStore.getUser(fixture.targetId());
        OrgStore.UserRow before =
                orgStore.updateUser(
                        original.id(),
                        original.displayName(),
                        original.deptId(),
                        "profile-test@example.invalid",
                        "12345678901",
                        "通用编辑保留的合成备注",
                        null,
                        null,
                        original.version(),
                        ADMIN_ID);
        mockMvc.perform(
                        put("/api/v1/admin/users/" + fixture.targetId())
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", before.version())
                                .contentType("application/json")
                                .content(userEditBody(fixture, enabled, null)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + (before.version() + 1) + "\""));
        assertEquals(
                new OrgStore.UserRow(
                        before.id(),
                        before.username(),
                        "已编辑",
                        before.deptId(),
                        before.email(),
                        before.phoneNumber(),
                        before.remark(),
                        enabled,
                        before.roleIds(),
                        before.version() + 1),
                orgStore.getUser(fixture.targetId()));
    }

    @Test
    void specializedUserStatusPreservesUnchangedProfileFields() throws Exception {
        UserEditFixture fixture = userEditFixture(List.of("ptmcp:user:status"));
        OrgStore.UserRow original = orgStore.getUser(fixture.targetId());
        OrgStore.UserRow before =
                orgStore.updateUser(
                        original.id(),
                        original.displayName(),
                        original.deptId(),
                        "status-test@example.invalid",
                        "12345678901",
                        "启停保留的合成备注",
                        null,
                        null,
                        original.version(),
                        ADMIN_ID);
        performSpecializedEdit(fixture, "status", "{\"status\":\"DISABLED\"}")
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"" + (before.version() + 1) + "\""));
        assertEquals(
                new OrgStore.UserRow(
                        before.id(),
                        before.username(),
                        before.displayName(),
                        before.deptId(),
                        before.email(),
                        before.phoneNumber(),
                        before.remark(),
                        false,
                        before.roleIds(),
                        before.version() + 1),
                orgStore.getUser(fixture.targetId()));
    }

    @Test
    void specializedUserCommandsServeThePlatformAdminDirectly() throws Exception {
        UserEditFixture fixture =
                userEditFixture(
                        List.of("ptmcp:user:status", "ptmcp:user:assign-role", "ptmcp:user:move"));
        performSpecializedEdit(fixture, "status", "{\"status\":\"DISABLED\"}")
                .andExpect(status().isOk());
        assertFalse(orgStore.getUser(fixture.targetId()).enabled());
        performSpecializedEdit(fixture, "roles", "{\"roleIds\":[]}").andExpect(status().isOk());
        assertTrue(orgStore.getUser(fixture.targetId()).roleIds().isEmpty());
        long destination =
                orgStore.createDepartment(fixture.departmentId(), "合法迁入部门", true, ADMIN_ID).id();
        performSpecializedEdit(fixture, "department", "{\"departmentId\":" + destination + "}")
                .andExpect(status().isOk());
        assertEquals(destination, orgStore.getUser(fixture.targetId()).deptId());
    }

    @Test
    void specializedUserStatusRejectsFutureVersionAfterConcurrentMove() throws Exception {
        assertSpecializedFutureVersionRejected("status", "{\"status\":\"DISABLED\"}");
    }

    @Test
    void specializedUserRolesRejectsFutureVersionAfterConcurrentMove() throws Exception {
        assertSpecializedFutureVersionRejected("roles", "{\"roleIds\":[]}");
    }

    @Test
    void specializedUserDepartmentRejectsFutureVersionAfterConcurrentMove() throws Exception {
        assertSpecializedFutureVersionRejected("department", null);
    }

    private void assertSpecializedFutureVersionRejected(String endpoint, String body)
            throws Exception {
        UserEditFixture fixture =
                userEditFixture(
                        List.of("ptmcp:user:status", "ptmcp:user:assign-role", "ptmcp:user:move"));
        OrgStore.UserRow original = orgStore.getUser(fixture.targetId());
        long outside = createDepartment("并发移出范围");
        String requestBody =
                body == null ? "{\"departmentId\":" + fixture.departmentId() + "}" : body;
        CountDownLatch snapshotRead = new CountDownLatch(1);
        CountDownLatch concurrentMoveCommitted = new CountDownLatch(1);
        AtomicBoolean intercepted = new AtomicBoolean();
        OrgStore observedStore = spy(orgStore);
        doAnswer(
                        invocation -> {
                            OrgStore.UserRow snapshot =
                                    (OrgStore.UserRow) invocation.callRealMethod();
                            if (intercepted.compareAndSet(false, true)) {
                                snapshotRead.countDown();
                                assertTrue(
                                        concurrentMoveCommitted.await(15, TimeUnit.SECONDS),
                                        "concurrent move must commit before authorization continues");
                            }
                            return snapshot;
                        })
                .when(observedStore)
                .getUser(fixture.targetId());
        MockMvc isolatedMvc =
                MockMvcBuilders.standaloneSetup(
                                new UserAdminController(observedStore, idempotencyRecorder))
                        .setControllerAdvice(new ApiExceptionAdvice())
                        .build();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var request =
                    executor.submit(
                            () ->
                                    isolatedMvc
                                            .perform(
                                                    put("/api/v1/admin/users/"
                                                                    + fixture.targetId()
                                                                    + "/"
                                                                    + endpoint)
                                                            .requestAttr(
                                                                    AdminPrincipal.ATTRIBUTE,
                                                                    new AdminPrincipal(
                                                                            fixture.delegateId(),
                                                                            false))
                                                            .header(
                                                                    "If-Match",
                                                                    original.version() + 1)
                                                            .contentType("application/json")
                                                            .content(requestBody))
                                            .andReturn());
            try {
                assertTrue(snapshotRead.await(15, TimeUnit.SECONDS));
                orgStore.updateUser(
                        original.id(),
                        "并发迁移后的资料",
                        outside,
                        "concurrent@example.invalid",
                        "12345678901",
                        "并发更新的合成备注",
                        null,
                        null,
                        original.version(),
                        ADMIN_ID);
                UserWriteEffects committed = userWriteEffects(fixture.targetId());
                concurrentMoveCommitted.countDown();
                MvcResult result = request.get(15, TimeUnit.SECONDS);
                assertEquals(409, result.getResponse().getStatus());
                assertTrue(result.getResponse().getContentAsString().contains("VERSION_CONFLICT"));
                assertEquals(committed, userWriteEffects(fixture.targetId()));
            } finally {
                concurrentMoveCommitted.countDown();
            }
        }
    }

    private org.springframework.test.web.servlet.ResultActions performSpecializedEdit(
            UserEditFixture fixture, String endpoint, String body) throws Exception {
        return mockMvc.perform(
                put("/api/v1/admin/users/" + fixture.targetId() + "/" + endpoint)
                        .header("X-Test-User-Id", ADMIN_ID)
                        .header("X-Test-Platform-Admin", "true")
                        .header("If-Match", orgStore.getUser(fixture.targetId()).version())
                        .contentType("application/json")
                        .content(body));
    }

    private void assertSpecializedEditDenied(UserEditFixture fixture, String endpoint, String body)
            throws Exception {
        UserWriteEffects before = userWriteEffects(fixture.targetId());
        // S2: 非平台管理员在入口即被拒绝；专用命令不再单独判权。
        mockMvc.perform(
                        put("/api/v1/admin/users/" + fixture.targetId() + "/" + endpoint)
                                .header("X-Test-User-Id", fixture.delegateId())
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", orgStore.getUser(fixture.targetId()).version())
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
        assertEquals(before, userWriteEffects(fixture.targetId()));
    }

    private record UserWriteEffects(
            OrgStore.UserRow row, long epoch, long events, long operations) {}

    private org.springframework.test.web.servlet.ResultActions performScopeTestEdit(
            UserEditFixture fixture, long department) throws Exception {
        return mockMvc.perform(
                put("/api/v1/admin/users/" + fixture.targetId())
                        .header("X-Test-User-Id", fixture.delegateId())
                        .header("X-Test-Platform-Admin", "false")
                        .header("If-Match", orgStore.getUser(fixture.targetId()).version())
                        .contentType("application/json")
                        .content(
                                userEditBody(fixture, true, null)
                                        .replace(
                                                "\"deptId\":" + fixture.departmentId(),
                                                "\"deptId\":" + department)));
    }

    private void moveScopeTestUser(long userId, long department) {
        OrgStore.UserRow user = orgStore.getUser(userId);
        orgStore.updateUser(
                user.id(),
                user.displayName(),
                department,
                user.email(),
                user.phoneNumber(),
                user.remark(),
                null,
                null,
                user.version(),
                ADMIN_ID);
    }

    private void setScopeTestDepartment(long department, String field, String value)
            throws Exception {
        String sql =
                switch (field) {
                    case "parent_id" -> "UPDATE sys_dept SET parent_id = ? WHERE dept_id = ?";
                    case "del_flag" -> "UPDATE sys_dept SET del_flag = ? WHERE dept_id = ?";
                    default -> "UPDATE sys_dept SET ancestors = ? WHERE dept_id = ?";
                };
        try (Connection connection = connect();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            statement.setLong(2, department);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private UserWriteEffects userWriteEffects(long targetId) throws Exception {
        OrgStore.UserRow row = orgStore.getUser(targetId);
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT (SELECT policy_epoch FROM sys_security_state WHERE singleton_id=1),"
                                        + " (SELECT COUNT(*) FROM sys_security_event),"
                                        + " (SELECT COUNT(*) FROM admin_operation_log)")) {
            assertTrue(result.next());
            return new UserWriteEffects(
                    row, result.getLong(1), result.getLong(2), result.getLong(3));
        }
    }

    private record UserEditFixture(
            long departmentId,
            long datasourceId,
            long delegateId,
            long targetId,
            long narrowRoleId,
            long broadRoleId) {}

    private UserEditFixture userEditFixture(List<String> actions) throws Exception {
        long department = createDepartment("组合编辑范围");
        long datasource = insertActiveDatasource();
        createUser("edit-placeholder", "占位", department);
        long target = createUser("edit-target", "原资料", department);
        long delegate = createUser("edit-delegate", "资料管理员", department);
        long narrow = orgStore.createRole("edit_narrow", "资料角色", true, ADMIN_ID).id();
        long broad = orgStore.createRole("edit_broad", "平台角色", true, ADMIN_ID).id();
        long actorRole = orgStore.createRole("edit_actor", "功能角色", true, ADMIN_ID).id();
        OrgStore.UserRow actor = orgStore.getUser(delegate);
        orgStore.updateUser(
                delegate,
                actor.displayName(),
                department,
                null,
                null,
                null,
                null,
                List.of(actorRole),
                actor.version(),
                ADMIN_ID);
        OrgStore.UserRow current = orgStore.getUser(target);
        orgStore.updateUser(
                target,
                current.displayName(),
                department,
                null,
                null,
                null,
                null,
                List.of(narrow),
                current.version(),
                ADMIN_ID);
        return new UserEditFixture(department, datasource, delegate, target, narrow, broad);
    }

    private void assertGenericEditDenied(UserEditFixture fixture, boolean enabled, String rolesJson)
            throws Exception {
        OrgStore.UserRow before = orgStore.getUser(fixture.targetId());
        mockMvc.perform(
                        put("/api/v1/admin/users/" + fixture.targetId())
                                .header("X-Test-User-Id", fixture.delegateId())
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", before.version())
                                .contentType("application/json")
                                .content(userEditBody(fixture, enabled, rolesJson)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
        assertEquals(before, orgStore.getUser(fixture.targetId()));
    }

    private static String userEditBody(UserEditFixture fixture, boolean enabled, String rolesJson) {
        return "{\"displayName\":\"已编辑\",\"deptId\":"
                + fixture.departmentId()
                + ",\"enabled\":"
                + enabled
                + (rolesJson == null ? "" : ",\"roleIds\":" + rolesJson)
                + "}";
    }

    @Test
    void userUpdateWritesOperationsTrail() throws Exception {
        long deptId = createDepartment("轨迹部");
        long userId = createUser("trail-user", "轨迹用户", deptId);
        int version =
                (int)
                        readLong(
                                mockMvc.perform(
                                                get("/api/v1/admin/users/" + userId)
                                                        .header("X-Test-User-Id", ADMIN_ID)
                                                        .header("X-Test-Platform-Admin", "true"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString(),
                                "version");

        mockMvc.perform(
                        put("/api/v1/admin/users/" + userId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", String.valueOf(version))
                                .contentType("application/json")
                                .content(
                                        "{\"displayName\":\"轨迹用户改\",\"deptId\":"
                                                + deptId
                                                + ",\"enabled\":true}"))
                .andExpect(status().isOk());

        try (Connection connection = connect();
                PreparedStatement log =
                        connection.prepareStatement(
                                "SELECT after_masked FROM admin_operation_log"
                                        + " WHERE aggregate_type = 'USER' AND aggregate_id = ?"
                                        + " AND action = 'USER_UPDATE'")) {
            log.setLong(1, userId);
            try (ResultSet result = log.executeQuery()) {
                assertTrue(result.next(), "USER_UPDATE trail row missing");
                String after = result.getString(1);
                assertTrue(after.contains("轨迹用户改"), "after payload missing new name");
            }
        }
    }

    @Test
    void departmentUpdateWritesOperationsTrail() throws Exception {
        long deptId = createDepartment("改名前部");
        int version =
                (int)
                        readLong(
                                mockMvc.perform(
                                                get("/api/v1/admin/departments/" + deptId)
                                                        .header("X-Test-User-Id", ADMIN_ID)
                                                        .header("X-Test-Platform-Admin", "true"))
                                        .andExpect(status().isOk())
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString(),
                                "version");

        mockMvc.perform(
                        put("/api/v1/admin/departments/" + deptId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", String.valueOf(version))
                                .contentType("application/json")
                                .content("{\"name\":\"改名后部\",\"enabled\":true}"))
                .andExpect(status().isOk());

        try (Connection connection = connect();
                PreparedStatement log =
                        connection.prepareStatement(
                                "SELECT after_masked FROM admin_operation_log"
                                        + " WHERE aggregate_type = 'DEPARTMENT'"
                                        + " AND aggregate_id = ? AND action = 'DEPARTMENT_UPDATE'")) {
            log.setLong(1, deptId);
            try (ResultSet result = log.executeQuery()) {
                assertTrue(result.next(), "DEPARTMENT_UPDATE trail row missing");
                String after = result.getString(1);
                assertTrue(after.contains("改名后部"), "after payload missing new name");
            }
        }
    }

    @Test
    void operationsLogIsCursorPagedFilteredAndPlatformAdminOnly() throws Exception {
        // Seed three operation rows (as the delegations/platform slices would have written).
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO admin_operation_log (actor_id, request_id, action,"
                            + " aggregate_type, aggregate_id, before_masked, after_masked,"
                            + " occurred_at, create_time) VALUES"
                            + " (1, 'op-req-1', 'DELEGATION_CREATE', 'ADMIN_DELEGATION', 1, NULL,"
                            + " '{\"a\":1}', NOW(6), NOW(6))");
            statement.execute(
                    "INSERT INTO admin_operation_log (actor_id, request_id, action,"
                            + " aggregate_type, aggregate_id, before_masked, after_masked,"
                            + " occurred_at, create_time) VALUES"
                            + " (1, 'op-req-2', 'PLATFORM_CREATE', 'PLATFORM', 2, NULL,"
                            + " '{\"b\":2}', NOW(6), NOW(6))");
            statement.execute(
                    "INSERT INTO admin_operation_log (actor_id, request_id, action,"
                            + " aggregate_type, aggregate_id, before_masked, after_masked,"
                            + " occurred_at, create_time) VALUES"
                            + " (2, 'op-req-3', 'DELEGATION_REVOKE', 'ADMIN_DELEGATION', 1,"
                            + " '{\"c\":3}', NULL, NOW(6), NOW(6))");
        }

        // Non-platform admin is denied.
        mockMvc.perform(
                        get("/api/v1/admin/operations")
                                .header("X-Test-User-Id", 42L)
                                .header("X-Test-Platform-Admin", "false"))
                .andExpect(status().isForbidden());

        // Unfiltered: all three, newest first, no overflow so no cursor.
        MvcResult pageOne =
                mockMvc.perform(
                                get("/api/v1/admin/operations")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(3))
                        .andExpect(jsonPath("$.items[0].action").value("DELEGATION_REVOKE"))
                        .andExpect(jsonPath("$.items[2].action").value("DELEGATION_CREATE"))
                        .andExpect(jsonPath("$.nextCursor").doesNotExist())
                        .andReturn();
        String cursor = jsonPathCursor(pageOne.getResponse().getContentAsString());
        // No cursor on a non-overflow page: the cursor-paged case is covered below via pageSize.
        cursor = null;

        // Action filter narrows to the delegation rows only.
        mockMvc.perform(
                        get("/api/v1/admin/operations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("action", "DELEGATION_CREATE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].action").value("DELEGATION_CREATE"))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        // Aggregate filter + actor filter combined.
        mockMvc.perform(
                        get("/api/v1/admin/operations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("aggregateType", "PLATFORM")
                                .param("actorId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].action").value("PLATFORM_CREATE"));

        // Cursor pages through: pageSize=2 overflows, page two holds the oldest row.
        String paged =
                mockMvc.perform(
                                get("/api/v1/admin/operations")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .param("pageSize", "2"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String pageTwoCursor = jsonPathCursor(paged);
        mockMvc.perform(
                        get("/api/v1/admin/operations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("cursor", pageTwoCursor)
                                .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].action").value("DELEGATION_CREATE"));

        // Invalid aggregate type is rejected.
        mockMvc.perform(
                        get("/api/v1/admin/operations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("aggregateType", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminAuditListAppliesFiltersCursorAndPlatformAdminScope() throws Exception {
        long platformId = insertPlatform("p-audit");
        long otherPlatformId = insertPlatform("p-audit-other");
        long datasourceId = insertActiveDatasource();
        String oldest = UUID.randomUUID().toString();
        String middle = UUID.randomUUID().toString();
        String newest = UUID.randomUUID().toString();
        insertAudit(
                oldest, 11L, platformId, datasourceId, "mcp.query", "SUCCESS", "ALLOW", null, 40);
        insertAudit(
                middle, 11L, platformId, datasourceId, "mcp.catalog", "SUCCESS", "ALLOW", null, 20);
        insertAudit(
                newest,
                12L,
                otherPlatformId,
                datasourceId,
                "mcp.query",
                "ERROR",
                "DENY",
                "SQL_DENIED",
                10);

        mockMvc.perform(get("/api/v1/admin/audit").header("X-Test-User-Id", ADMIN_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        mockMvc.perform(get("/api/v1/admin/audit")).andExpect(status().isUnauthorized());

        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].requestId").value(newest))
                .andExpect(jsonPath("$.items[0].userId").value("12"))
                .andExpect(jsonPath("$.items[0].datasourceId").value(String.valueOf(datasourceId)))
                .andExpect(jsonPath("$.items[0].errorCode").value("SQL_DENIED"))
                .andExpect(jsonPath("$.items[2].requestId").value(oldest))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("userId", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));

        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("tool", "mcp.catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].requestId").value(middle));

        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("result", "ERROR")
                                .param("verdict", "DENY")
                                .param("errorCode", "SQL_DENIED")
                                .param("platformId", Long.toString(otherPlatformId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].requestId").value(newest));

        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("datasourceId", Long.toString(datasourceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3));

        MvcResult firstPage =
                mockMvc.perform(
                                get("/api/v1/admin/audit")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .param("pageSize", "2"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andReturn();
        String cursor = jsonPathCursor(firstPage.getResponse().getContentAsString());
        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("pageSize", "2")
                                .param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].requestId").value(oldest))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("from", Instant.now().toString())
                                .param("to", Instant.now().minusSeconds(600).toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("from", Instant.now().minus(Duration.ofDays(40)).toString())
                                .param("to", Instant.now().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("userId", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void adminAuditDetailStaysMaskedAndScopedToPlatformAdmin() throws Exception {
        long platformId = insertPlatform("p-audit-detail");
        long datasourceId = insertActiveDatasource();
        String requestId = UUID.randomUUID().toString();
        insertAudit(
                requestId,
                11L,
                platformId,
                datasourceId,
                "mcp.query",
                "SUCCESS",
                "ALLOW",
                null,
                5,
                "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20");

        mockMvc.perform(get("/api/v1/admin/audit/" + requestId).header("X-Test-User-Id", ADMIN_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        mockMvc.perform(
                        get("/api/v1/admin/audit/" + requestId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(requestId))
                .andExpect(jsonPath("$.userId").value("11"))
                .andExpect(jsonPath("$.sqlMasked").value("SELECT * FROM orders WHERE id = ?"))
                .andExpect(jsonPath("$.sqlRawCipher").doesNotExist())
                .andExpect(jsonPath("$.sqlFinalCipher").doesNotExist())
                .andExpect(jsonPath("$.keyId").doesNotExist())
                .andExpect(
                        jsonPath("$.fingerprint")
                                .value(
                                        "0102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f20"))
                .andExpect(jsonPath("$.resourceRefs.datasourceId").value(datasourceId))
                .andExpect(jsonPath("$.durationMs").value(12))
                .andExpect(jsonPath("$.truncated").value(false));

        mockMvc.perform(
                        get("/api/v1/admin/audit/" + UUID.randomUUID())
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(
                        get("/api/v1/admin/audit/not-an-uuid")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void adminPlatformDirectoryIsReadOnlyAndCursorPaged() throws Exception {
        long first = insertPlatform("dir-1");
        long second = insertPlatform("dir-2", "DISABLED");

        mockMvc.perform(get("/api/v1/admin/platforms").header("X-Test-User-Id", ADMIN_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        mockMvc.perform(
                        get("/api/v1/admin/platforms")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value(String.valueOf(first)))
                .andExpect(jsonPath("$.items[0].code").value("dir-1"))
                .andExpect(jsonPath("$.items[0].name").value("平台"))
                .andExpect(jsonPath("$.items[0].tier").value(3))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].catalogMode").value("DISCOVER"))
                .andExpect(jsonPath("$.items[0].proofMode").value("NONE"))
                .andExpect(jsonPath("$.items[0].scopeVersion").value("1"))
                .andExpect(jsonPath("$.items[0].securityVersion").value("1"))
                .andExpect(jsonPath("$.items[0].version").value("1"))
                .andExpect(jsonPath("$.items[0].proofConfigured").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        MvcResult pageOne =
                mockMvc.perform(
                                get("/api/v1/admin/platforms")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .param("pageSize", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andReturn();
        mockMvc.perform(
                        get("/api/v1/admin/platforms")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("pageSize", "1")
                                .param(
                                        "cursor",
                                        jsonPathCursor(pageOne.getResponse().getContentAsString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(String.valueOf(second)))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        mockMvc.perform(
                        get("/api/v1/admin/platforms")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].code").value("dir-2"));

        mockMvc.perform(
                        get("/api/v1/admin/platforms")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("keyword", "dir-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].code").value("dir-1"));
    }

    @Test
    void roleCreateIdempotencyReplaysOriginalResultAndRejectsChangedIntent() throws Exception {
        String key = "r".repeat(128);
        List<Long> before = roleCreateCounts();
        MvcResult first =
                mockMvc.perform(roleCreateRequest(key, "replayed_role"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.version").value(1))
                        .andReturn();
        assertRoleCreatedOnce(before);
        long roleId = readLong(first.getResponse().getContentAsString(), "id");
        String location = "/api/v1/admin/roles/" + roleId;
        assertEquals(location, first.getResponse().getHeader("Location"));
        assertEquals("SUCCEEDED", idempotencyState("roles.create", key));
        String committed = roleCreateWriteEffects();
        mockMvc.perform(roleCreateRequest(key, "replayed_role"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(header().string("Location", location))
                .andExpect(
                        result ->
                                assertEquals(
                                        first.getResponse().getContentAsString(),
                                        result.getResponse().getContentAsString()));
        mockMvc.perform(roleCreateRequest(key, "changed_role"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(committed, roleCreateWriteEffects());

        // Replays describe the original result even after the resource has been removed.
        orgStore.deleteRole(roleId, 1, ADMIN_ID);
        String deleted = roleCreateWriteEffects();
        mockMvc.perform(roleCreateRequest(key, "replayed_role"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(header().string("Location", location))
                .andExpect(
                        result ->
                                assertEquals(
                                        first.getResponse().getContentAsString(),
                                        result.getResponse().getContentAsString()));
        assertEquals(deleted, roleCreateWriteEffects());
    }

    @ValueSource(strings = {"missing", "blank", "too-long"})
    void roleCreateIdempotencyRejectsInvalidKeyWithoutOccupying(String kind) throws Exception {
        String before = roleCreateWriteEffects();
        MockHttpServletRequestBuilder request = roleCreateRequest(null, "invalid_key_role");
        if (kind.equals("blank")) request.header("Idempotency-Key", "   ");
        if (kind.equals("too-long")) request.header("Idempotency-Key", "x".repeat(129));
        mockMvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(before, roleCreateWriteEffects());
        assertEquals(0, scalar("SELECT COUNT(*) FROM api_idempotency"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"key\":\"missing_name\",\"enabled\":true}"})
    void roleCreateIdempotencyRejectsInvalidBodyBeforeOccupying(String body) throws Exception {
        String before = roleCreateWriteEffects();
        mockMvc.perform(roleCreateRequest("bad-role-body", "unused").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(before, roleCreateWriteEffects());
        assertEquals(0, scalar("SELECT COUNT(*) FROM api_idempotency"));
    }

    @Test
    void roleCreateIdempotencyRejectsNonPlatformAdminWithoutOccupying() throws Exception {
        String before = roleCreateWriteEffects();
        idempotentRoleMvc(orgStore, idempotencyRecorder)
                .perform(
                        roleCreateRequest("role-denied", "denied_role")
                                .requestAttr(
                                        AdminPrincipal.ATTRIBUTE,
                                        new AdminPrincipal(ADMIN_ID, false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
        assertEquals(before, roleCreateWriteEffects());
        assertEquals(0, scalar("SELECT COUNT(*) FROM api_idempotency"));
    }

    @Test
    void roleCreateIdempotencyDoesNotRepeatAfterRealCommitAcknowledgementLoss() throws Exception {
        MockMvc isolatedMvc =
                idempotentRoleMvc(
                        new OrgStore(idempotencyFaultSource(true, null, null)),
                        idempotencyRecorder);
        List<Long> before = roleCreateCounts();
        isolatedMvc
                .perform(roleCreateRequest("role-commit-ack", "commit_ack_role"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"))
                .andExpect(jsonPath("$.retryable").value(false));
        assertRoleCreatedOnce(before);
        assertEquals("UNKNOWN", idempotencyState("roles.create", "role-commit-ack"));
        String committed = roleCreateWriteEffects();
        isolatedMvc
                .perform(roleCreateRequest("role-commit-ack", "commit_ack_role"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"))
                .andExpect(jsonPath("$.retryable").value(false));
        assertEquals(committed, roleCreateWriteEffects());
        assertEquals("UNKNOWN", idempotencyState("roles.create", "role-commit-ack"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void roleCreateIdempotencySuccessRecordingFailureNeverRepeatsAction(boolean updateCommitted)
            throws Exception {
        String prefix = "UPDATE api_idempotency SET state = 'SUCCEEDED'";
        IdempotencyRecorder uncertainRecorder =
                new IdempotencyRecorder(
                        idempotencyFaultSource(
                                false,
                                updateCommitted ? null : prefix,
                                updateCommitted ? prefix : null));
        MockMvc isolatedMvc = idempotentRoleMvc(orgStore, uncertainRecorder);
        List<Long> before = roleCreateCounts();
        isolatedMvc
                .perform(roleCreateRequest("role-response-ack", "response_ack_role"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"))
                .andExpect(jsonPath("$.retryable").value(false));
        assertRoleCreatedOnce(before);
        String committed = roleCreateWriteEffects();
        if (updateCommitted) {
            assertEquals("SUCCEEDED", idempotencyState("roles.create", "role-response-ack"));
            long id =
                    orgStore.listRoles().stream()
                            .filter(role -> role.key().equals("response_ack_role"))
                            .findFirst()
                            .orElseThrow()
                            .id();
            isolatedMvc
                    .perform(roleCreateRequest("role-response-ack", "response_ack_role"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replay", "true"))
                    .andExpect(header().string("Location", "/api/v1/admin/roles/" + id))
                    .andExpect(jsonPath("$.id").value(id))
                    .andExpect(jsonPath("$.key").value("response_ack_role"))
                    .andExpect(jsonPath("$.version").value(1));
        } else {
            assertEquals("UNKNOWN", idempotencyState("roles.create", "role-response-ack"));
            isolatedMvc
                    .perform(roleCreateRequest("role-response-ack", "response_ack_role"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"))
                    .andExpect(jsonPath("$.retryable").value(false));
        }
        assertEquals(committed, roleCreateWriteEffects());
        assertEquals(
                updateCommitted ? "SUCCEEDED" : "UNKNOWN",
                idempotencyState("roles.create", "role-response-ack"));
    }

    @Test
    void roleCreateIdempotencyRechecksGateBeforeReplay() throws Exception {
        mockMvc.perform(roleCreateRequest("role-revoked", "revoked_role"))
                .andExpect(status().isCreated());
        String before = roleCreateWriteEffects();
        // S2: the gate re-evaluates the caller before any idempotent replay of the recorded 201.
        mockMvc.perform(
                        post("/api/v1/admin/roles")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .requestAttr(
                                        AdminPrincipal.ATTRIBUTE,
                                        new AdminPrincipal(ADMIN_ID, false))
                                .header("Idempotency-Key", "role-revoked")
                                .contentType("application/json")
                                .content(roleCreateBody("revoked_role")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
        assertEquals(before, roleCreateWriteEffects());
        assertEquals("SUCCEEDED", idempotencyState("roles.create", "role-revoked"));
        assertEquals(1, scalar("SELECT COUNT(*) FROM api_idempotency"));
    }

    @Test
    void roleCreateIdempotencyRetainsUnknownWhenCurrentQualificationIsRevoked() throws Exception {
        long actorRole = orgStore.createRole("race_actor_key", "竞态角色", true, ADMIN_ID).id();
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch finishAction = new CountDownLatch(1);
        AtomicInteger actions = new AtomicInteger();
        OrgStore observed = spy(orgStore);
        doAnswer(
                        invocation -> {
                            actions.incrementAndGet();
                            actionStarted.countDown();
                            assertTrue(finishAction.await(15, TimeUnit.SECONDS));
                            return invocation.callRealMethod();
                        })
                .when(observed)
                .createRole("qualification_race", "角色创建", true, ADMIN_ID);
        MockMvc isolatedMvc =
                MockMvcBuilders.standaloneSetup(
                                new RoleAdminController(observed, idempotencyRecorder))
                        .addInterceptors(new AdminActionInterceptor())
                        .setControllerAdvice(new ApiExceptionAdvice())
                        .build();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var original =
                    executor.submit(
                            () ->
                                    isolatedMvc
                                            .perform(
                                                    roleCreateRequest(
                                                            "role-qualification-race",
                                                            "qualification_race"))
                                            .andReturn());
            try {
                assertTrue(actionStarted.await(15, TimeUnit.SECONDS));
                assertEquals(
                        "RUNNING", idempotencyState("roles.create", "role-qualification-race"));
                // S2: qualification is the platform_admin column alone; a concurrent role rewrite
                // changes the write effects while the column flip denies the in-flight store
                // requalification.
                orgStore.updateRole(actorRole, "撤权角色", null, 1, ADMIN_ID);
                try (Connection connection = connect();
                        PreparedStatement flip =
                                connection.prepareStatement(
                                        "UPDATE sys_user_security SET platform_admin=0"
                                                + " WHERE user_id=?")) {
                    flip.setLong(1, ADMIN_ID);
                    assertEquals(1, flip.executeUpdate());
                }
                String afterRevocation = roleCreateWriteEffects();
                finishAction.countDown();
                MvcResult denied = original.get(15, TimeUnit.SECONDS);
                assertAll(
                        () -> assertEquals(403, denied.getResponse().getStatus()),
                        () ->
                                assertTrue(
                                        denied.getResponse()
                                                .getContentAsString()
                                                .contains("MANAGEMENT_SCOPE_DENIED")),
                        () -> assertEquals(afterRevocation, roleCreateWriteEffects()),
                        () ->
                                assertEquals(
                                        "UNKNOWN",
                                        idempotencyState(
                                                "roles.create", "role-qualification-race")));
                try (Connection connection = connect();
                        PreparedStatement unflip =
                                connection.prepareStatement(
                                        "UPDATE sys_user_security SET platform_admin=1"
                                                + " WHERE user_id=?")) {
                    unflip.setLong(1, ADMIN_ID);
                    assertEquals(1, unflip.executeUpdate());
                }
                String beforeRetry = roleCreateWriteEffects();
                isolatedMvc
                        .perform(roleCreateRequest("role-qualification-race", "qualification_race"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"))
                        .andExpect(jsonPath("$.retryable").value(false));
                assertEquals(1, actions.get());
                assertEquals(beforeRetry, roleCreateWriteEffects());
            } finally {
                finishAction.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{",
                "{}",
                "{\"id\":0}",
                "{\"id\":-1}",
                "null",
                "{\"id\":1} {}",
                "{\"id\":1,\"id\":2}",
                "{\"id\":1,\"extra\":true}"
            })
    void roleCreateIdempotencyInvalidRecordedResultNeverRepeatsAction(String responseBody)
            throws Exception {
        assertCorruptRoleResultNeverRepeatsAction(responseBody.getBytes(StandardCharsets.UTF_8));
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTF-16LE", "invalid-UTF-8"})
    void roleCreateIdempotencyRejectsNonUtf8RecordedResult(String encoding) throws Exception {
        byte[] responseBody =
                "{\"id\":1,\"name\":\"X\"}"
                        .getBytes(
                                encoding.equals("UTF-16LE")
                                        ? StandardCharsets.UTF_16LE
                                        : StandardCharsets.UTF_8);
        if (encoding.equals("invalid-UTF-8")) responseBody[responseBody.length - 3] = (byte) 0xC3;
        assertCorruptRoleResultNeverRepeatsAction(responseBody);
    }

    private void assertCorruptRoleResultNeverRepeatsAction(byte[] responseBody) throws Exception {
        byte[] body = roleCreateBody("corrupt_record_role").getBytes(StandardCharsets.UTF_8);
        idempotencyRecorder.occupy(
                ADMIN_ID,
                "roles.create",
                "corrupt-role-result",
                MessageDigest.getInstance("SHA-256").digest(body));
        idempotencyRecorder.completeSucceeded(
                ADMIN_ID, "roles.create", "corrupt-role-result", responseBody, 201);
        String before = roleCreateWriteEffects();
        idempotentRoleMvc(orgStore, idempotencyRecorder)
                .perform(roleCreateRequest("corrupt-role-result", "corrupt_record_role"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"))
                .andExpect(jsonPath("$.retryable").value(false));
        assertEquals(before, roleCreateWriteEffects());
        assertEquals("SUCCEEDED", idempotencyState("roles.create", "corrupt-role-result"));
    }

    @Test
    void orgCreateEndpointsReplayWithSameIdempotencyKey() throws Exception {
        String body = "{\"name\":\"幂等部门\",\"enabled\":true}";
        MvcResult first =
                mockMvc.perform(
                                post("/api/v1/admin/departments")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "org-idem-1")
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn();

        // Same key + same body replays the recorded creation instead of inserting a sibling.
        mockMvc.perform(
                        post("/api/v1/admin/departments")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "org-idem-1")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(
                        result -> {
                            String original = first.getResponse().getContentAsString();
                            if (!original.equals(result.getResponse().getContentAsString())) {
                                throw new AssertionError("replayed department body differs");
                            }
                        });

        // Missing key is now a hard 400 (the write path is contract-gated).
        mockMvc.perform(
                        post("/api/v1/admin/departments")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest());

        try (Connection connection = connect();
                PreparedStatement count =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM sys_dept WHERE dept_name = ?")) {
            count.setString(1, "幂等部门");
            try (ResultSet result = count.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
    }

    @Test
    void departmentCreateDoesNotRepeatAfterRealCommitAcknowledgementLoss() throws Exception {
        OrgStore uncertainStore = new OrgStore(idempotencyFaultSource(true, null, null));
        MockMvc isolatedMvc = idempotentDepartmentMvc(uncertainStore, idempotencyRecorder);
        for (int attempt = 0; attempt < 2; attempt++) {
            isolatedMvc
                    .perform(idempotentDepartmentRequest("lost-department-ack", "lost-ack"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"));
        }
        assertEquals(1, departmentCount("lost-ack"));
        assertEquals("UNKNOWN", idempotencyState("lost-department-ack"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void departmentSuccessRecordingFailureNeverRepeatsTheAction(boolean updateCommitted)
            throws Exception {
        String prefix = "UPDATE api_idempotency SET state = 'SUCCEEDED'";
        IdempotencyRecorder uncertainRecorder =
                new IdempotencyRecorder(
                        idempotencyFaultSource(
                                false,
                                updateCommitted ? null : prefix,
                                updateCommitted ? prefix : null));
        MockMvc isolatedMvc = idempotentDepartmentMvc(orgStore, uncertainRecorder);
        isolatedMvc
                .perform(idempotentDepartmentRequest("response-ack", "response-ack"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"))
                .andExpect(jsonPath("$.retryable").value(false));
        if (updateCommitted) {
            assertEquals("SUCCEEDED", idempotencyState("response-ack"));
            isolatedMvc
                    .perform(idempotentDepartmentRequest("response-ack", "response-ack"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replay", "true"))
                    .andExpect(jsonPath("$.name").value("response-ack"));
        } else {
            assertEquals("UNKNOWN", idempotencyState("response-ack"));
            isolatedMvc
                    .perform(idempotentDepartmentRequest("response-ack", "response-ack"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"));
        }
        assertEquals(1, departmentCount("response-ack"));
    }

    @Test
    void departmentUnknownMarkerFailureDoesNotReleaseEvenAnExpiredRunningKey() throws Exception {
        IdempotencyRecorder unavailableRecorder =
                new IdempotencyRecorder(
                        idempotencyFaultSource(false, "UPDATE api_idempotency SET state =", null));
        MockMvc isolatedMvc = idempotentDepartmentMvc(orgStore, unavailableRecorder);
        isolatedMvc
                .perform(idempotentDepartmentRequest("marker-failed", "marker-failed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"));
        assertEquals("RUNNING", idempotencyState("marker-failed"));
        expireDepartmentIntent("marker-failed");
        DepartmentWriteEffects beforeRetry = departmentWriteEffects();
        isolatedMvc
                .perform(idempotentDepartmentRequest("marker-failed", "marker-failed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"));
        assertEquals(beforeRetry, departmentWriteEffects());
        assertEquals(1, departmentCount("marker-failed"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void departmentDuplicateInFlightDoesNotExecuteAndOriginalCanFinish(boolean expired)
            throws Exception {
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch finishAction = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        AtomicInteger actions = new AtomicInteger();
        OrgStore observed = spy(orgStore);
        doAnswer(
                        invocation -> {
                            actions.incrementAndGet();
                            if (first.compareAndSet(true, false)) {
                                actionStarted.countDown();
                                assertTrue(finishAction.await(15, TimeUnit.SECONDS));
                            }
                            return invocation.callRealMethod();
                        })
                .when(observed)
                .createDepartment(null, "in-flight", true, ADMIN_ID);
        MockMvc isolatedMvc = idempotentDepartmentMvc(observed, idempotencyRecorder);
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var original =
                    executor.submit(
                            () ->
                                    isolatedMvc
                                            .perform(
                                                    idempotentDepartmentRequest(
                                                            "in-flight-key", "in-flight"))
                                            .andReturn());
            try {
                assertTrue(actionStarted.await(15, TimeUnit.SECONDS));
                if (expired) expireDepartmentIntent("in-flight-key");
                MvcResult duplicate =
                        isolatedMvc
                                .perform(idempotentDepartmentRequest("in-flight-key", "in-flight"))
                                .andReturn();
                finishAction.countDown();
                MvcResult completed = original.get(15, TimeUnit.SECONDS);
                assertEquals(409, duplicate.getResponse().getStatus());
                assertTrue(
                        duplicate
                                .getResponse()
                                .getContentAsString()
                                .contains(expired ? "OPERATION_UNKNOWN" : "OPERATION_IN_PROGRESS"));
                assertEquals(201, completed.getResponse().getStatus());
                assertEquals(1, actions.get());
                assertEquals(1, departmentCount("in-flight"));
                isolatedMvc
                        .perform(idempotentDepartmentRequest("in-flight-key", "in-flight"))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Idempotent-Replay", "true"))
                        .andExpect(
                                result ->
                                        assertEquals(
                                                completed.getResponse().getContentAsString(),
                                                result.getResponse().getContentAsString()));
            } finally {
                finishAction.countDown();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNKNOWN", "EXPIRED_RUNNING"})
    void departmentUncertainIntentStillRejectsDifferentPayload(String state) throws Exception {
        String body = "{\"name\":\"uncertain-payload\",\"enabled\":true}";
        idempotencyRecorder.occupy(
                ADMIN_ID,
                "departments.create",
                "uncertain-payload",
                MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        if (state.equals("EXPIRED_RUNNING")) {
            expireDepartmentIntent("uncertain-payload");
        } else {
            try (Connection connection = connect();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE api_idempotency SET state='UNKNOWN'");
            }
        }
        mockMvc.perform(
                        idempotentDepartmentRequest("uncertain-payload", "uncertain-payload")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPERATION_UNKNOWN"));
        mockMvc.perform(
                        idempotentDepartmentRequest("uncertain-payload", "different-payload")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        assertEquals(0, departmentCount("uncertain-payload"));
        assertEquals(0, departmentCount("different-payload"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_ARGUMENT", "VERSION_CONFLICT", "DEPENDENCY_UNAVAILABLE"})
    void idempotentDomainFailureCodeDoesNotProveNoCommittedSideEffect(String errorCode)
            throws Exception {
        AtomicInteger actions = new AtomicInteger();
        Supplier<ResponseEntity<Object>> action =
                () -> {
                    actions.incrementAndGet();
                    orgStore.createDepartment(null, "before-domain-error", true, ADMIN_ID);
                    throw new DomainException(ErrorCode.valueOf(errorCode));
                };
        assertEquals(
                ErrorCode.valueOf(errorCode),
                assertThrows(
                                DomainException.class,
                                () ->
                                        IdempotentWriteTestSupport.execute(
                                                idempotencyRecorder,
                                                ADMIN_ID,
                                                "probe.domain",
                                                "domain-key",
                                                new byte[0],
                                                action))
                        .errorCode());
        assertEquals(
                ErrorCode.OPERATION_UNKNOWN,
                assertThrows(
                                DomainException.class,
                                () ->
                                        IdempotentWriteTestSupport.execute(
                                                idempotencyRecorder,
                                                ADMIN_ID,
                                                "probe.domain",
                                                "domain-key",
                                                new byte[0],
                                                action))
                        .errorCode());
        assertEquals(1, actions.get());
        assertEquals(1, departmentCount("before-domain-error"));
    }

    @Test
    void idempotentUnclassifiedFailureIsSanitizedAndCannotRepeatCommittedAction() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        Supplier<ResponseEntity<Object>> action =
                () -> {
                    actions.incrementAndGet();
                    orgStore.createDepartment(null, "before-runtime-error", true, ADMIN_ID);
                    throw new IllegalStateException("private fixture diagnostic");
                };
        for (int attempt = 0; attempt < 2; attempt++) {
            DomainException error =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    IdempotentWriteTestSupport.execute(
                                            idempotencyRecorder,
                                            ADMIN_ID,
                                            "probe.runtime",
                                            "runtime-key",
                                            new byte[0],
                                            action));
            assertEquals(ErrorCode.OPERATION_UNKNOWN, error.errorCode());
            assertEquals(null, error.getCause());
            assertFalse(error.toString().contains("private fixture"));
        }
        assertEquals(1, actions.get());
        assertEquals(1, departmentCount("before-runtime-error"));
    }

    @Test
    void idempotentSerializationFailureCannotRepeatAnAlreadySuccessfulAction() throws Exception {
        AtomicInteger actions = new AtomicInteger();
        Supplier<ResponseEntity<UnserializableBody>> action =
                () -> {
                    actions.incrementAndGet();
                    orgStore.createDepartment(null, "before-serialization", true, ADMIN_ID);
                    return ResponseEntity.status(201).body(new UnserializableBody());
                };
        for (int attempt = 0; attempt < 2; attempt++) {
            DomainException error =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    IdempotentWriteTestSupport.execute(
                                            idempotencyRecorder,
                                            ADMIN_ID,
                                            "probe.serialization",
                                            "serialization-key",
                                            new byte[0],
                                            action));
            assertEquals(ErrorCode.OPERATION_UNKNOWN, error.errorCode());
            assertEquals(null, error.getCause());
        }
        assertEquals(1, actions.get());
        assertEquals(1, departmentCount("before-serialization"));
    }

    public static final class UnserializableBody {
        public String getValue() {
            throw new IllegalStateException("private fixture diagnostic");
        }
    }

    private MockMvc idempotentRoleMvc(OrgStore store, IdempotencyRecorder recorder) {
        return MockMvcBuilders.standaloneSetup(new RoleAdminController(store, recorder))
                .setControllerAdvice(new ApiExceptionAdvice())
                .build();
    }

    private static MockHttpServletRequestBuilder roleCreateRequest(String key, String roleKey) {
        MockHttpServletRequestBuilder request =
                post("/api/v1/admin/roles")
                        .header("X-Test-User-Id", ADMIN_ID)
                        .header("X-Test-Platform-Admin", "true")
                        .requestAttr(AdminPrincipal.ATTRIBUTE, new AdminPrincipal(ADMIN_ID, true))
                        .contentType("application/json")
                        .content(roleCreateBody(roleKey));
        return key == null ? request : request.header("Idempotency-Key", key);
    }

    private static String roleCreateBody(String roleKey) {
        return "{\"key\":\"" + roleKey + "\",\"name\":\"角色创建\",\"enabled\":true}";
    }

    private List<Long> roleCreateCounts() throws SQLException {
        return List.of(
                scalar("SELECT COUNT(*) FROM sys_role"),
                scalar("SELECT COUNT(*) FROM admin_operation_log"),
                scalar("SELECT COUNT(*) FROM sys_security_event"),
                scalar("SELECT policy_epoch FROM sys_security_state WHERE singleton_id=1"));
    }

    private void assertRoleCreatedOnce(List<Long> before) throws SQLException {
        assertEquals(
                List.of(before.get(0) + 1, before.get(1) + 1, before.get(2) + 1, before.get(3) + 1),
                roleCreateCounts());
    }

    private long scalar(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private String scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private String roleCreateWriteEffects() throws SQLException {
        StringBuilder snapshot = new StringBuilder();
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            for (String sql :
                    List.of(
                            "SELECT * FROM sys_role ORDER BY role_id",
                            "SELECT * FROM admin_operation_log ORDER BY id",
                            "SELECT * FROM sys_security_state ORDER BY singleton_id",
                            "SELECT * FROM sys_security_event ORDER BY event_id")) {
                snapshot.append(sql).append('\n');
                try (ResultSet rows = statement.executeQuery(sql)) {
                    while (rows.next()) {
                        for (int column = 1;
                                column <= rows.getMetaData().getColumnCount();
                                column++) {
                            String value = rows.getString(column);
                            snapshot.append(value == null ? -1 : value.length())
                                    .append(':')
                                    .append(value)
                                    .append('|');
                        }
                        snapshot.append('\n');
                    }
                }
            }
        }
        return snapshot.toString();
    }

    private MockMvc idempotentDepartmentMvc(OrgStore store, IdempotencyRecorder recorder) {
        return MockMvcBuilders.standaloneSetup(new DepartmentAdminController(store, recorder))
                .setControllerAdvice(new ApiExceptionAdvice())
                .build();
    }

    private static MockHttpServletRequestBuilder idempotentDepartmentRequest(
            String key, String name) {
        return post("/api/v1/admin/departments")
                .requestAttr(AdminPrincipal.ATTRIBUTE, new AdminPrincipal(ADMIN_ID, true))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content("{\"name\":\"" + name + "\",\"enabled\":true}");
    }

    private int departmentCount(String name) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM sys_dept WHERE dept_name=?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getInt(1);
            }
        }
    }

    private String idempotencyState(String key) throws Exception {
        return idempotencyState("departments.create", key);
    }

    private String idempotencyState(String route, String key) throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT state FROM api_idempotency WHERE actor_id=? AND route_key=? AND key_hash=?")) {
            bindIntent(statement, route, key);
            try (ResultSet rows = statement.executeQuery()) {
                assertTrue(rows.next());
                return rows.getString(1);
            }
        }
    }

    private void expireDepartmentIntent(String key) throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE api_idempotency SET create_time=UTC_TIMESTAMP(6)-INTERVAL 2 DAY,"
                                        + " expires_at=UTC_TIMESTAMP(6)-INTERVAL 1 DAY"
                                        + " WHERE actor_id=? AND route_key=? AND key_hash=?")) {
            bindDepartmentIntent(statement, key);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private static void bindDepartmentIntent(PreparedStatement statement, String key)
            throws Exception {
        bindIntent(statement, "departments.create", key);
    }

    private static void bindIntent(PreparedStatement statement, String route, String key)
            throws Exception {
        statement.setLong(1, ADMIN_ID);
        statement.setString(2, route);
        statement.setBytes(
                3,
                MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
    }

    /** All SQL and commits reach the owned server; only the surrounding acknowledgement fails. */
    private DataSource idempotencyFaultSource(
            boolean loseCommitAck, String beforeSql, String afterSql) {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            try {
                                Object value = method.invoke(primary, args);
                                if (!(value instanceof Connection connection)) return value;
                                return Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (connectionProxy, connectionMethod, connectionArgs) -> {
                                            try {
                                                Object result =
                                                        connectionMethod.invoke(
                                                                connection, connectionArgs);
                                                if (loseCommitAck
                                                        && connectionMethod
                                                                .getName()
                                                                .equals("commit"))
                                                    throw new SQLException(
                                                            "synthetic lost commit acknowledgement");
                                                if (result instanceof PreparedStatement statement
                                                        && connectionMethod
                                                                .getName()
                                                                .equals("prepareStatement")) {
                                                    String sql = (String) connectionArgs[0];
                                                    boolean before =
                                                            beforeSql != null
                                                                    && sql.startsWith(beforeSql);
                                                    boolean after =
                                                            afterSql != null
                                                                    && sql.startsWith(afterSql);
                                                    if (before || after)
                                                        return Proxy.newProxyInstance(
                                                                PreparedStatement.class
                                                                        .getClassLoader(),
                                                                new Class<?>[] {
                                                                    PreparedStatement.class
                                                                },
                                                                (statementProxy,
                                                                        statementMethod,
                                                                        statementArgs) -> {
                                                                    try {
                                                                        boolean execute =
                                                                                statementMethod
                                                                                        .getName()
                                                                                        .equals(
                                                                                                "executeUpdate");
                                                                        if (before && execute)
                                                                            throw new SQLException(
                                                                                    "synthetic write failure");
                                                                        Object written =
                                                                                statementMethod
                                                                                        .invoke(
                                                                                                statement,
                                                                                                statementArgs);
                                                                        if (after && execute)
                                                                            throw new SQLException(
                                                                                    "synthetic lost write acknowledgement");
                                                                        return written;
                                                                    } catch (
                                                                            InvocationTargetException
                                                                                    failure) {
                                                                        throw failure.getCause();
                                                                    }
                                                                });
                                                }
                                                return result;
                                            } catch (InvocationTargetException failure) {
                                                throw failure.getCause();
                                            }
                                        });
                            } catch (InvocationTargetException failure) {
                                throw failure.getCause();
                            }
                        });
    }

    @Test
    void previewJudgesWithoutTouchingBusinessData() throws Exception {
        long platformId = insertPlatform("p-admin-preview");
        long datasourceId = insertActiveDatasource();
        long deptId = createDepartment("预览部");
        long subject = createUser("preview-user", "预览用户", deptId);
        long encodedDs = ResourceIds.encode(ResourceIds.Type.DATASOURCE, datasourceId);

        mockMvc.perform(
                        post("/api/v1/admin/acl/preview")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(
                                        "{\"subjectUserId\":"
                                                + subject
                                                + ",\"platformId\":"
                                                + platformId
                                                + ",\"resourceIds\":["
                                                + encodedDs
                                                + ", 987654321]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decisions.length()").value(2))
                .andExpect(jsonPath("$.decisions[0].meta").value(false))
                .andExpect(jsonPath("$.decisions[0].read").value(false))
                .andExpect(jsonPath("$.decisions[1].meta").value(false))
                .andExpect(jsonPath("$.decisions[1].read").value(false))
                .andExpect(jsonPath("$.evaluatedAt").isNotEmpty());
    }

    @Test
    void effectiveListsTreePagesAndPlatformBoundedDecisions() throws Exception {
        long platformId = insertPlatform("p-admin-effective");
        long datasourceId = insertActiveDatasource();
        long namespaceId = insertActiveNamespace(datasourceId);
        long firstObject = insertActiveObject(namespaceId, "orders");
        long secondObject = insertActiveObject(namespaceId, "payments");
        long deptId = createDepartment("有效部");
        // Reset empties the user table, so the first created user would collide with the shim
        // admin's id 1 and any grant for it is (correctly) treated as self-escalation.
        createUser("effective-throwaway", "占位用户", deptId);
        long subject = createUser("effective-user", "有效用户", deptId);

        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO platform_scope (platform_id, resource_type, resource_id,"
                            + " resource_generation, action, include_descendants, create_time,"
                            + " update_time, created_by, updated_by) VALUES ("
                            + platformId
                            + ", 'DATASOURCE', "
                            + datasourceId
                            + ", 1, 'META', 1, NOW(6), NOW(6), 1, 1)");
            statement.execute(
                    "INSERT INTO platform_scope (platform_id, resource_type, resource_id,"
                            + " resource_generation, action, include_descendants, create_time,"
                            + " update_time, created_by, updated_by) VALUES ("
                            + platformId
                            + ", 'DATASOURCE', "
                            + datasourceId
                            + ", 1, 'READ', 1, NOW(6), NOW(6), 1, 1)");
        }
        // PAGE_SIZE cap and invalid filter rejections first; the subject holds no grants yet.
        mockMvc.perform(
                        get("/api/v1/admin/acl/effective")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("userId", String.valueOf(subject))
                                .param("platformId", String.valueOf(platformId))
                                .param("pageSize", "101"))
                .andExpect(status().isBadRequest());

        MvcResult firstPage =
                mockMvc.perform(
                                get("/api/v1/admin/acl/effective")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .param("userId", String.valueOf(subject))
                                        .param("platformId", String.valueOf(platformId))
                                        .param("datasourceId", String.valueOf(datasourceId))
                                        .param("pageSize", "2"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andExpect(jsonPath("$.items[0].resourceType").value("DATASOURCE"))
                        .andExpect(jsonPath("$.items[0].meta").value(false))
                        .andExpect(jsonPath("$.items[0].read").value(false))
                        .andExpect(
                                jsonPath("$.items[0].reasonCodes[0]").value("NO_EFFECTIVE_GRANT"))
                        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                        .andReturn();
        String cursor = jsonPathCursor(firstPage.getResponse().getContentAsString());

        MvcResult secondPage =
                mockMvc.perform(
                                get("/api/v1/admin/acl/effective")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .param("userId", String.valueOf(subject))
                                        .param("platformId", String.valueOf(platformId))
                                        .param("datasourceId", String.valueOf(datasourceId))
                                        .param("cursor", cursor)
                                        .param("pageSize", "2"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andReturn();
        String secondCursor =
                com.jayway.jsonpath.JsonPath.read(
                        secondPage.getResponse().getContentAsString(), "$.nextCursor");

        mockMvc.perform(
                        get("/api/v1/admin/acl/effective")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("userId", String.valueOf(subject))
                                .param("platformId", String.valueOf(platformId))
                                .param("datasourceId", String.valueOf(datasourceId))
                                .param("cursor", secondCursor)
                                .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        // Grant READ on the datasource subtree; the decisions must flip for the whole subtree.
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "grant-" + UUID.randomUUID())
                                .contentType("application/json")
                                .content(grantBody("USER", subject, datasourceId, "READ", "ALLOW")))
                .andExpect(status().isCreated());

        mockMvc.perform(
                        get("/api/v1/admin/acl/effective")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("userId", String.valueOf(subject))
                                .param("platformId", String.valueOf(platformId))
                                .param("pageSize", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(4))
                // READ Allow implies META Allow; the inherited READ reaches every subtree node.
                .andExpect(jsonPath("$.items[0].meta").value(true))
                .andExpect(jsonPath("$.items[0].read").value(true))
                .andExpect(jsonPath("$.items[0].reasonCodes[0]").value("GRANT_META"))
                .andExpect(jsonPath("$.items[3].read").value(true))
                .andExpect(jsonPath("$.items[3].resourceType").value("OBJECT"));
    }

    @Test
    void platformCrudSealsProofConfigAndKeepsItUnreadable() throws Exception {
        String createBody =
                "{\"code\":\"p-console\",\"name\":\"控制台平台\",\"tier\":2,"
                        + "\"catalogMode\":\"HIDE\",\"proofMode\":\"PROXY_SECRET\","
                        + "\"status\":\"ACTIVE\",\"proofConfig\":{"
                        + "\"proxyNetwork\":\"10.0.0.0/8\",\"secret\":\"write-only-secret\"}}";

        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/platforms")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "platform-create-1")
                                        .contentType("application/json")
                                        .content(createBody))
                        .andExpect(
                                result -> {
                                    if (result.getResponse().getStatus() != 201) {
                                        System.out.println(
                                                "PLATFORM-DEBUG: "
                                                        + result.getResponse()
                                                                .getContentAsString());
                                    }
                                })
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.code").value("p-console"))
                        .andExpect(jsonPath("$.proofConfigured").value(true))
                        .andExpect(jsonPath("$.version").value("1"))
                        .andExpect(
                                result -> {
                                    String body = result.getResponse().getContentAsString();
                                    if (body.contains("write-only-secret")
                                            || body.contains("10.0.0.0/8")) {
                                        throw new AssertionError("proof material leaked: " + body);
                                    }
                                })
                        .andExpect(
                                result -> {
                                    String etag = result.getResponse().getHeader("ETag");
                                    if (!"\"1\"".equals(etag)) {
                                        throw new AssertionError("unexpected ETag: " + etag);
                                    }
                                })
                        .andReturn();
        // Platform views carry string ids (long-id safety), so parse the quoted value.
        long platformId = readQuotedLong(created.getResponse().getContentAsString(), "id");

        // Same key replays the recorded creation instead of duplicating the row.
        mockMvc.perform(
                        post("/api/v1/admin/platforms")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "platform-create-1")
                                .contentType("application/json")
                                .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"));

        // Duplicate codes are refused without writing a second registry row.
        mockMvc.perform(
                        post("/api/v1/admin/platforms")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "platform-create-dupe")
                                .contentType("application/json")
                                .content(createBody))
                .andExpect(status().isBadRequest());

        // Tier 1 without proof stays impossible at the API level too.
        mockMvc.perform(
                        post("/api/v1/admin/platforms")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "platform-tier1")
                                .contentType("application/json")
                                .content(
                                        "{\"code\":\"p-tier1\",\"name\":\"受信\",\"tier\":1,"
                                                + "\"catalogMode\":\"HIDE\",\"proofMode\":\"NONE\","
                                                + "\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());

        // Proof-mode change without fresh material is rejected; the code is immutable.
        mockMvc.perform(
                        put("/api/v1/admin/platforms/" + platformId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1")
                                .contentType("application/json")
                                .content(
                                        "{\"code\":\"p-console\",\"name\":\"改名\",\"tier\":2,"
                                                + "\"catalogMode\":\"HIDE\",\"proofMode\":\"MTLS\","
                                                + "\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(
                        put("/api/v1/admin/platforms/" + platformId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "9")
                                .contentType("application/json")
                                .content(
                                        "{\"code\":\"p-other\",\"name\":\"改名\",\"tier\":2,"
                                                + "\"catalogMode\":\"HIDE\",\"proofMode\":\"PROXY_SECRET\","
                                                + "\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());

        MvcResult renamed =
                mockMvc.perform(
                                put("/api/v1/admin/platforms/" + platformId)
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("If-Match", "1")
                                        .contentType("application/json")
                                        .content(
                                                "{\"code\":\"p-console\",\"name\":\"改名平台\",\"tier\":2,"
                                                        + "\"catalogMode\":\"HIDE\",\"proofMode\":\"PROXY_SECRET\","
                                                        + "\"status\":\"ACTIVE\"}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.name").value("改名平台"))
                        .andExpect(jsonPath("$.version").value("2"))
                        .andExpect(jsonPath("$.proofConfigured").value(true))
                        .andReturn();
        long updatedVersion = readQuotedLong(renamed.getResponse().getContentAsString(), "version");

        // Detail reads stay masked; storage keeps ciphertext, never the plaintext.
        mockMvc.perform(
                        get("/api/v1/admin/platforms/" + platformId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proofConfigured").value(true))
                .andExpect(
                        result -> {
                            String body = result.getResponse().getContentAsString();
                            if (body.contains("secret")) {
                                throw new AssertionError("detail leaked proof material: " + body);
                            }
                        });

        try (Connection connection = connect();
                PreparedStatement cipher =
                        connection.prepareStatement(
                                "SELECT proof_config_cipher, proof_key_id FROM platform_registry"
                                        + " WHERE id = ?")) {
            cipher.setLong(1, platformId);
            try (ResultSet result = cipher.executeQuery()) {
                assertTrue(result.next());
                byte[] stored = result.getBytes(1);
                assertNotNull(stored);
                String plaintext = new String(stored, java.nio.charset.StandardCharsets.UTF_8);
                if (plaintext.contains("write-only-secret")) {
                    throw new AssertionError("storage kept plaintext proof material");
                }
                assertNotNull(result.getString(2));
            }
        }

        // Logical delete bumps the version and leaves the row auditable as DELETED.
        mockMvc.perform(
                        delete("/api/v1/admin/platforms/" + platformId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", String.valueOf(updatedVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETED"))
                .andExpect(jsonPath("$.version").value(String.valueOf(updatedVersion + 1)));

        mockMvc.perform(
                        get("/api/v1/admin/platforms/" + platformId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isNotFound());

        try (Connection connection = connect();
                PreparedStatement log =
                        connection.prepareStatement(
                                "SELECT action FROM admin_operation_log"
                                        + " WHERE aggregate_type = 'PLATFORM' AND aggregate_id = ?"
                                        + " ORDER BY id")) {
            log.setLong(1, platformId);
            try (ResultSet result = log.executeQuery()) {
                assertTrue(result.next());
                assertEquals("PLATFORM_CREATE", result.getString(1));
                assertTrue(result.next());
                assertEquals("PLATFORM_UPDATE", result.getString(1));
                assertTrue(result.next());
                assertEquals("PLATFORM_DELETE", result.getString(1));
            }
        }
    }

    @Test
    void platformScopeFullSetReplacementIsOptimisticAndLogged() throws Exception {
        long platformId = insertPlatform("p-scope");
        long datasourceId = insertActiveDatasource();
        long namespaceId = insertActiveNamespace(datasourceId);
        long objectId = insertActiveObject(namespaceId, "orders");

        // Empty at first; the read carries the scope and security versions.
        MvcResult initial =
                mockMvc.perform(
                                get("/api/v1/admin/platforms/" + platformId + "/scopes")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(0))
                        .andExpect(jsonPath("$.scopeVersion").value("1"))
                        .andExpect(jsonPath("$.version").value("1"))
                        .andReturn();
        String version =
                String.valueOf(
                        readQuotedLong(initial.getResponse().getContentAsString(), "version"));

        // Unknown targets are rejected before any write.
        mockMvc.perform(
                        put("/api/v1/admin/platforms/" + platformId + "/scopes")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", version)
                                .contentType("application/json")
                                .content(
                                        "{\"items\":[{\"resourceType\":\"DATASOURCE\","
                                                + "\"resourceId\":987654,\"resourceGeneration\":1,"
                                                + "\"action\":\"META\",\"includeDescendants\":true}]}"))
                .andExpect(
                        result -> {
                            if (result.getResponse().getStatus() != 404) {
                                System.out.println(
                                        "SCOPE-DEBUG: "
                                                + result.getResponse().getContentAsString());
                            }
                        })
                .andExpect(status().isNotFound());

        MvcResult saved =
                mockMvc.perform(
                                put("/api/v1/admin/platforms/" + platformId + "/scopes")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("If-Match", version)
                                        .contentType("application/json")
                                        .content(
                                                "{\"items\":["
                                                        + "{\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                                                        + datasourceId
                                                        + ",\"resourceGeneration\":1,\"action\":\"META\","
                                                        + "\"includeDescendants\":true},"
                                                        + "{\"resourceType\":\"OBJECT\",\"resourceId\":"
                                                        + objectId
                                                        + ",\"resourceGeneration\":1,\"action\":\"READ\","
                                                        + "\"includeDescendants\":true}]}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(2))
                        .andExpect(jsonPath("$.scopeVersion").value("2"))
                        .andExpect(jsonPath("$.version").value("2"))
                        // OBJECT rows normalize the descendants flag away.
                        .andExpect(jsonPath("$.items[1].includeDescendants").value(false))
                        .andReturn();

        // A stale If-Match cannot replace the set again.
        mockMvc.perform(
                        put("/api/v1/admin/platforms/" + platformId + "/scopes")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", version)
                                .contentType("application/json")
                                .content("{\"items\":[]}"))
                .andExpect(status().isConflict());

        // Full replacement really is full: an empty set with the fresh version wipes the scope.
        String newVersion =
                String.valueOf(readQuotedLong(saved.getResponse().getContentAsString(), "version"));
        mockMvc.perform(
                        put("/api/v1/admin/platforms/" + platformId + "/scopes")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", newVersion)
                                .contentType("application/json")
                                .content("{\"items\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.scopeVersion").value("3"));

        try (Connection connection = connect();
                PreparedStatement log =
                        connection.prepareStatement(
                                "SELECT action, after_masked FROM admin_operation_log"
                                        + " WHERE aggregate_type = 'PLATFORM' AND aggregate_id = ?"
                                        + " AND action = 'PLATFORM_SCOPE_SAVE' ORDER BY id")) {
            log.setLong(1, platformId);
            try (ResultSet result = log.executeQuery()) {
                assertTrue(result.next());
                assertEquals("PLATFORM_SCOPE_SAVE", result.getString(1));
                String after = result.getString(2);
                // MySQL normalizes JSON on storage (spacing), so assert on values only.
                assertTrue(after.contains("DATASOURCE"));
                assertTrue(after.contains("OBJECT"));
            }
        }
    }

    @Test
    void clientLifecycleShowsSecretOnceAndRevokesFamiliesOnChange() throws Exception {
        long platformId = insertPlatform("p-clients");
        String callbacks = "[\"https://app.example/callback\",\"http://127.0.0.1:3000/cb\"]";

        // PUBLIC client: no secret material anywhere.
        MvcResult publicClient =
                mockMvc.perform(
                                post("/api/v1/admin/platforms/" + platformId + "/clients")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "client-public-1")
                                        .contentType("application/json")
                                        .content(
                                                "{\"name\":\"公开端\",\"type\":\"PUBLIC\","
                                                        + "\"authMethods\":[\"none\"],\"redirectUris\":"
                                                        + callbacks
                                                        + ",\"allowedScopes\":[\"mcp:catalog\"],"
                                                        + "\"status\":\"ACTIVE\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.client.type").value("PUBLIC"))
                        .andExpect(jsonPath("$.clientSecret").doesNotExist())
                        .andExpect(jsonPath("$.client.secretConfigured").value(false))
                        .andReturn();
        String publicId =
                String.valueOf(
                                readQuotedLong(
                                                        publicClient
                                                                .getResponse()
                                                                .getContentAsString(),
                                                        "platformId")
                                                >= 0
                                        ? 0
                                        : 0)
                        + readStringField(publicClient.getResponse().getContentAsString(), "id");

        // CONFIDENTIAL client: the raw secret appears exactly once, never in the detail read.
        MvcResult confidential =
                mockMvc.perform(
                                post("/api/v1/admin/platforms/" + platformId + "/clients")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "client-conf-1")
                                        .contentType("application/json")
                                        .content(
                                                "{\"name\":\"机密端\",\"type\":\"CONFIDENTIAL\","
                                                        + "\"authMethods\":[\"client_secret_basic\"],"
                                                        + "\"redirectUris\":["
                                                        + "\"https://app.example/callback\"],"
                                                        + "\"allowedScopes\":[\"mcp:catalog\",\"mcp:query\"],"
                                                        + "\"status\":\"ACTIVE\"}"))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.client.secretConfigured").value(true))
                        .andExpect(
                                result -> {
                                    String body = result.getResponse().getContentAsString();
                                    int first = body.indexOf("clientSecret");
                                    int second = body.indexOf("clientSecret", first + 1);
                                    if (first < 0 || second >= 0) {
                                        throw new AssertionError(
                                                "secret field shape unexpected: " + body);
                                    }
                                })
                        .andReturn();
        String confidentialBody = confidential.getResponse().getContentAsString();
        String clientId = readStringField(confidentialBody, "id");
        String rawSecret = readStringField(confidentialBody, "clientSecret");

        // Storage keeps bcrypt + digest, never the raw value.
        try (Connection connection = connect();
                PreparedStatement secret =
                        connection.prepareStatement(
                                "SELECT client_secret, client_secret_hash FROM oauth2_registered_client"
                                        + " WHERE id = ?")) {
            secret.setString(1, clientId);
            try (ResultSet result = secret.executeQuery()) {
                assertTrue(result.next());
                String stored = result.getString(1);
                assertNotNull(stored);
                assertTrue(stored.startsWith("{bcrypt}"));
                if (stored.contains(rawSecret)) {
                    throw new AssertionError("raw secret stored");
                }
                String expectedDigest;
                try {
                    expectedDigest =
                            java.util.HexFormat.of()
                                    .formatHex(
                                            java.security.MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            rawSecret.getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8)));
                } catch (java.security.NoSuchAlgorithmException failure) {
                    throw new AssertionError(failure);
                }
                assertEquals(
                        expectedDigest, java.util.HexFormat.of().formatHex(result.getBytes(2)));
            }
        }

        mockMvc.perform(
                        get("/api/v1/admin/clients/" + clientId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secretConfigured").value(true))
                .andExpect(
                        result -> {
                            if (result.getResponse().getContentAsString().contains(rawSecret)) {
                                throw new AssertionError("detail leaked the raw secret");
                            }
                        });

        // Unsafe callbacks are rejected with the same rules as DCR.
        mockMvc.perform(
                        post("/api/v1/admin/platforms/" + platformId + "/clients")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "client-unsafe-cb")
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"坏回调\",\"type\":\"PUBLIC\","
                                                + "\"authMethods\":[\"none\"],\"redirectUris\":[\"https://app.example/*\"],"
                                                + "\"allowedScopes\":[\"mcp:catalog\"],\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest());

        // Seed one ACTIVE refresh family, then update the client: the family must be revoked.
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name,"
                            + " authorization_grant_type, status, create_time, update_time,"
                            + " created_by, updated_by) VALUES ('auth-1', '"
                            + clientId
                            + "', 'delegate-user', 'authorization_code', 'ACTIVE',"
                            + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 1)");
            statement.execute(
                    "INSERT INTO oauth_refresh_family (id, authorization_id, user_id,"
                            + " registered_client_id, platform_id, status, auth_version,"
                            + " absolute_expires_at, idle_expires_at, last_rotated_at, version,"
                            + " create_time, update_time) VALUES ('fam-1', 'auth-1', 1, '"
                            + clientId
                            + "', "
                            + platformId
                            + ", 'ACTIVE', 1, UTC_TIMESTAMP(6) + INTERVAL 7 DAY,"
                            + " UTC_TIMESTAMP(6) + INTERVAL 1 DAY, UTC_TIMESTAMP(6), 1,"
                            + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        }

        mockMvc.perform(
                        put("/api/v1/admin/clients/" + clientId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1")
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"机密端改名\",\"type\":\"CONFIDENTIAL\","
                                                + "\"authMethods\":[\"client_secret_basic\"],"
                                                + "\"redirectUris\":[\"https://app.example/callback\"],"
                                                + "\"allowedScopes\":[\"mcp:catalog\"],\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("机密端改名"))
                .andExpect(jsonPath("$.version").value("2"))
                .andExpect(jsonPath("$.secretConfigured").value(true));

        try (Connection connection = connect();
                PreparedStatement families =
                        connection.prepareStatement(
                                "SELECT status, revoke_reason FROM oauth_refresh_family WHERE id = 'fam-1'")) {
            try (ResultSet result = families.executeQuery()) {
                assertTrue(result.next());
                assertEquals("REVOKED", result.getString(1));
                assertNotNull(result.getString(2));
            }
        }

        // Delete disables the client and revokes any remaining family.
        mockMvc.perform(
                        delete("/api/v1/admin/clients/" + clientId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(
                        get("/api/v1/admin/clients/" + clientId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // The public client shows up in the platform list.
        mockMvc.perform(
                        get("/api/v1/admin/platforms/" + platformId + "/clients")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void auditDecryptRequiresReasonRecordsAccessAndReturnsPlaintext() throws Exception {
        String requestId = "11111111-2222-3333-4444-555555555555";
        local.ptmcp.admin.datasource.AuditCipher cipher =
                new local.ptmcp.admin.datasource.AuditCipher(
                        "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=");
        byte[] rawSealed =
                cipher.encryptForTesting(
                        "SELECT card_number FROM payment WHERE id = 1", requestId, "sqlRaw");
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO audit_log (request_id, user_id, platform_id, tool,"
                                        + " datasource_id, started_at, result, verdict, sql_masked,"
                                        + " sql_raw_cipher, key_id, create_time)"
                                        + " VALUES (?, 7, 1, 'execute_readonly_sql', 1,"
                                        + " UTC_TIMESTAMP(6), 'SUCCESS', 'ALLOW', 'SELECT *** FILTERED',"
                                        + " ?, ?, UTC_TIMESTAMP(6))")) {
            statement.setString(1, requestId);
            statement.setBytes(2, rawSealed);
            statement.setString(3, cipher.keyIdOrNull());
            statement.executeUpdate();
        }

        // Short reasons are refused without any sensitive-access row.
        mockMvc.perform(
                        post("/api/v1/admin/audit/" + requestId + "/decrypt")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content("{\"reason\":\"太短\",\"fields\":[\"sqlRaw\"]}"))
                .andExpect(status().isBadRequest());

        // Unknown requests are 404.
        mockMvc.perform(
                        post("/api/v1/admin/audit/99999999-9999-9999-9999-999999999999/decrypt")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content("{\"reason\":\"安全事件复查需要原始 SQL\",\"fields\":[\"sqlRaw\"]}"))
                .andExpect(status().isNotFound());

        // The valid reveal returns plaintext plus an expiry, and stores one ALLOW row.
        MvcResult revealed =
                mockMvc.perform(
                                post("/api/v1/admin/audit/" + requestId + "/decrypt")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                "{\"reason\":\"安全事件复查需要原始 SQL\","
                                                        + "\"fields\":[\"sqlRaw\",\"sqlFinal\"]}"))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.sqlRaw")
                                        .value("SELECT card_number FROM payment WHERE id = 1"))
                        .andExpect(jsonPath("$.sqlFinal").doesNotExist())
                        .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                        .andReturn();
        if (!revealed.getResponse().getContentAsString().contains("expiresAt")) {
            throw new AssertionError("missing expiry");
        }

        try (Connection connection = connect();
                PreparedStatement access =
                        connection.prepareStatement(
                                "SELECT operator_id, reason, fields, decision FROM audit_sensitive_access"
                                        + " WHERE request_id = ?")) {
            access.setString(1, requestId);
            try (ResultSet result = access.executeQuery()) {
                assertTrue(result.next());
                assertEquals(ADMIN_ID, result.getLong(1));
                assertTrue(result.getString(2).contains("安全事件复查"));
                assertTrue(result.getString(3).contains("sqlRaw"));
                assertEquals("ALLOW", result.getString(4));
                assertFalse(result.next());
            }
        }

        // The AC audit-trail read returns the disclosure record to the console.
        mockMvc.perform(
                        get("/api/v1/admin/audit/" + requestId + "/sensitive-access")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].operatorId").value(ADMIN_ID))
                .andExpect(jsonPath("$[0].reason").value("安全事件复查需要原始 SQL"))
                .andExpect(jsonPath("$[0].fields[0]").value("sqlRaw"))
                .andExpect(jsonPath("$[0].createdAt").isNotEmpty());

        // A request that was never decrypted has an empty trail.
        mockMvc.perform(
                        get("/api/v1/admin/audit/99999999-9999-9999-9999-999999999999/sensitive-access")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // ASVS 4.2.1: plaintext reveal throughput is bounded per operator (10 per minute). The
        // first reveal above already holds one slot, so nine more pass and the eleventh overall
        // call is refused.
        for (int attempt = 0; attempt < 9; attempt++) {
            mockMvc.perform(
                            post("/api/v1/admin/audit/" + requestId + "/decrypt")
                                    .header("X-Test-User-Id", ADMIN_ID)
                                    .header("X-Test-Platform-Admin", "true")
                                    .contentType("application/json")
                                    .content(
                                            "{\"reason\":\"频控边界验证：连续明文申请\""
                                                    + ",\"fields\":[\"sqlRaw\"]}"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(
                        post("/api/v1/admin/audit/" + requestId + "/decrypt")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(
                                        "{\"reason\":\"频控边界验证：连续明文申请\""
                                                + ",\"fields\":[\"sqlRaw\"]}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("SENSITIVE_REVEAL_LIMIT"));

        // A different operator still has their own budget.
        mockMvc.perform(
                        post("/api/v1/admin/audit/" + requestId + "/decrypt")
                                .header("X-Test-User-Id", 2L)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(
                                        "{\"reason\":\"另一操作员的独立预算验证\""
                                                + ",\"fields\":[\"sqlRaw\"]}"))
                .andExpect(status().isOk());
    }

    @Test
    void passwordResetRevokesFamiliesWritesHashAndLogs() throws Exception {
        long deptId = createDepartment("密码重置部");
        createUser("pwd-throwaway", "占位用户", deptId);
        long target = createUser("pwd-target", "密码用户", deptId);

        // The identity table is owned by the host store; tests create the dev-shaped row.
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS sys_user ("
                            + "user_id BIGINT NOT NULL AUTO_INCREMENT, user_name VARCHAR(30) NOT NULL,"
                            + " nick_name VARCHAR(30) NOT NULL, password VARCHAR(100) NOT NULL,"
                            + " status CHAR(1) NOT NULL, del_flag CHAR(1) NOT NULL DEFAULT '0',"
                            + " PRIMARY KEY (user_id), UNIQUE KEY uk_sys_user_user_name (user_name))");
            statement.execute(
                    "INSERT INTO sys_user (user_id, user_name, nick_name, password, status, del_flag)"
                            + " VALUES ("
                            + target
                            + ", 'pwd-target', '密码用户', '$2a$10$oldoldoldoldoldoldoldoldoldoldoldoldoldoldoldoldoldold',"
                            + " '0', '0') ON DUPLICATE KEY UPDATE password = VALUES(password)");
            // Trusted synthetic import; metadata creation alone intentionally remains UNBOUND.
            statement.execute(
                    "UPDATE sys_user_security SET identity_state='READY' WHERE user_id=" + target);
            // One ACTIVE refresh family so the revocation has a real target.
            statement.execute(
                    "INSERT INTO oauth2_registered_client (id, client_id, client_name,"
                            + " client_authentication_methods, authorization_grant_types, scopes,"
                            + " client_settings, token_settings, client_type, status,"
                            + " registration_source, version, create_time, update_time, created_by,"
                            + " updated_by) VALUES ('client-pwd', 'client-pwd', 'pwd client',"
                            + " 'none', 'authorization_code', 'mcp:catalog', '{}', '{}', 'PUBLIC',"
                            + " 'ACTIVE', 'ADMIN', 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 1)");
            statement.execute(
                    "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name,"
                            + " authorization_grant_type, status, create_time, update_time, created_by,"
                            + " updated_by) VALUES ('auth-pwd', 'client-pwd', 'pwd-target',"
                            + " 'authorization_code', 'ACTIVE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 1)");
            statement.execute(
                    "INSERT INTO web_session_index (session_hash, kind, user_id, auth_version,"
                            + " issued_at, expires_at, version, create_time, update_time)"
                            + " VALUES (X'11', 'ADMIN', "
                            + target
                            + ", 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6) + INTERVAL 1 DAY, 1,"
                            + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
            statement.execute(
                    "INSERT INTO personal_token (user_id, token_prefix, token_hash, name, scopes,"
                            + " auth_version, expires_at, status, version, create_time, update_time,"
                            + " created_by, updated_by)"
                            + " VALUES ("
                            + target
                            + ", 'e2e', X'22', 'e2e-token', '[\"mcp:catalog\"]', 1,"
                            + " UTC_TIMESTAMP(6) + INTERVAL 7 DAY, 'ACTIVE', 1, UTC_TIMESTAMP(6),"
                            + " UTC_TIMESTAMP(6), 1, 1)");
            statement.execute(
                    "INSERT INTO oauth_refresh_family (id, authorization_id, user_id,"
                            + " registered_client_id, platform_id, status, auth_version,"
                            + " absolute_expires_at, idle_expires_at, last_rotated_at, version,"
                            + " create_time, update_time) VALUES ('fam-pwd', 'auth-pwd', "
                            + target
                            + ", 'client-pwd', NULL, 'ACTIVE', 1, UTC_TIMESTAMP(6) + INTERVAL 7 DAY,"
                            + " UTC_TIMESTAMP(6) + INTERVAL 1 DAY, UTC_TIMESTAMP(6), 1,"
                            + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        }

        // Short reasons are rejected before any write.
        mockMvc.perform(
                        put("/api/v1/admin/users/" + target + "/password")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1")
                                .header("Idempotency-Key", "pwd-short")
                                .contentType("application/json")
                                .content("{\"newPassword\":\"short\",\"reason\":\"太短\"}"))
                .andExpect(status().isBadRequest());

        // Stale If-Match is a conflict, nothing written.
        mockMvc.perform(
                        put("/api/v1/admin/users/" + target + "/password")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "9")
                                .header("Idempotency-Key", "pwd-stale")
                                .contentType("application/json")
                                .content(
                                        "{\"newPassword\":\"correct-horse-battery\","
                                                + "\"reason\":\"用户报告凭证泄露需要立即重置\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(
                        put("/api/v1/admin/users/" + target + "/password")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1")
                                .header("Idempotency-Key", "pwd-reset-1")
                                .contentType("application/json")
                                .content(
                                        "{\"newPassword\":\"correct-horse-battery\","
                                                + "\"reason\":\"用户报告凭证泄露需要立即重置\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.identityState").value("READY"))
                .andExpect(header().doesNotExist("X-Security-Epoch"));
        mockMvc.perform(
                        get("/api/v1/admin/users/" + target + "/identity")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.recoveryRequired").value(false))
                .andExpect(jsonPath("$.targetHash").doesNotExist());
        mockMvc.perform(
                        get("/api/v1/admin/users/" + target + "/identity")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "false"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/v1/admin/users/" + target + "/password/retry")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2")
                                .header("Idempotency-Key", "pwd-retry-1")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        mockMvc.perform(
                        post("/api/v1/admin/users/" + target + "/password/retry")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2")
                                .header("Idempotency-Key", "pwd-retry-1")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));
        mockMvc.perform(
                        post("/api/v1/admin/users/" + target + "/password/retry")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "3")
                                .header("Idempotency-Key", "pwd-retry-1")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        // Identity store: a real bcrypt hash replaced the fake one; the plaintext is nowhere.
        try (Connection connection = connect();
                PreparedStatement hash =
                        connection.prepareStatement(
                                "SELECT password FROM sys_user WHERE user_id = ?")) {
            hash.setLong(1, target);
            try (ResultSet result = hash.executeQuery()) {
                assertTrue(result.next());
                String stored = result.getString(1);
                assertTrue(stored.startsWith("$2"), "expected bcrypt hash: " + stored);
                assertTrue(stored.length() >= 59);
                if (stored.contains("correct-horse")) {
                    throw new AssertionError("plaintext stored");
                }
            }
        }

        // Metadata side: family revoked, aggregate version bumped, one masked log row.
        try (Connection connection = connect()) {
            try (PreparedStatement family =
                    connection.prepareStatement(
                            "SELECT status FROM oauth_refresh_family WHERE id = 'fam-pwd'")) {
                try (ResultSet result = family.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("REVOKED", result.getString(1));
                }
            }
            try (PreparedStatement profile =
                    connection.prepareStatement(
                            "SELECT version, password_hash FROM sys_user_profile WHERE user_id = ?")) {
                profile.setLong(1, target);
                try (ResultSet result = profile.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(2, result.getLong(1));
                    // The console login source shares the same bcrypt hash.
                    assertEquals("external", result.getString(2));
                }
            }
            try (PreparedStatement session =
                    connection.prepareStatement(
                            "SELECT revoked_at IS NOT NULL FROM web_session_index"
                                    + " WHERE user_id = ? AND kind = 'ADMIN'")) {
                session.setLong(1, target);
                try (ResultSet result = session.executeQuery()) {
                    assertTrue(result.next());
                    assertTrue(result.getBoolean(1), "admin session not revoked");
                }
            }
            try (PreparedStatement token =
                    connection.prepareStatement(
                            "SELECT status FROM personal_token WHERE user_id = ?")) {
                token.setLong(1, target);
                try (ResultSet result = token.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("REVOKED", result.getString(1));
                }
            }
            try (PreparedStatement log =
                    connection.prepareStatement(
                            "SELECT action, before_masked FROM admin_operation_log"
                                    + " WHERE aggregate_type = 'USER' AND aggregate_id = ?"
                                    + " AND action = 'USER_PASSWORD_CHANGED'")) {
                log.setLong(1, target);
                try (ResultSet result = log.executeQuery()) {
                    assertTrue(result.next());
                    String before = result.getString(2);
                    if (before.contains("correct-horse")) {
                        throw new AssertionError("log leaked plaintext");
                    }
                }
            }
        }
    }

    @Test
    void adminSetsTheFirstPasswordForANewUser() throws Exception {
        long deptId = createDepartment("First credential department");
        long target = createUser("first-credential", "First Credential", deptId);
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS sys_user ("
                            + "user_id BIGINT NOT NULL AUTO_INCREMENT,"
                            + " user_name VARCHAR(30) NOT NULL,"
                            + " nick_name VARCHAR(30) NOT NULL,"
                            + " password VARCHAR(100) NOT NULL,"
                            + " status CHAR(1) NOT NULL,"
                            + " del_flag CHAR(1) NOT NULL DEFAULT '0',"
                            + " PRIMARY KEY (user_id),"
                            + " UNIQUE KEY uk_sys_user_user_name (user_name))");
        }
        String secret = "first-" + UUID.randomUUID();
        mockMvc.perform(
                        put("/api/v1/admin/users/" + target + "/password")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1")
                                .header("Idempotency-Key", "first-credential-" + target)
                                .contentType("application/json")
                                .content(
                                        "{\"newPassword\":\""
                                                + secret
                                                + "\",\"reason\":\"管理员为新建账户设置初始登录密码\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityState").value("READY"))
                .andExpect(jsonPath("$.version").value(2));

        try (Connection connection = connect();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT user_name,password FROM sys_user WHERE user_id=?")) {
            query.setLong(1, target);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals("first-credential", result.getString(1));
                assertTrue(
                        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                                .matches(secret, result.getString(2)));
            }
        }
        try (Connection connection = connect();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT status,target_hash FROM identity_provision WHERE user_id=?")) {
            query.setLong(1, target);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals("COMPLETED", result.getString(1));
                assertNull(result.getString(2));
            }
        }
    }

    @Test
    void adminFirstCredentialNeverAdoptsACaseVariantHostAccount() throws Exception {
        long deptId = createDepartment("Credential collision dept");
        long target = createUser("credential-collision", "Credential Collision", deptId);
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS sys_user ("
                            + "user_id BIGINT NOT NULL AUTO_INCREMENT,"
                            + " user_name VARCHAR(30) COLLATE utf8mb4_0900_bin NOT NULL,"
                            + " nick_name VARCHAR(30) NOT NULL,"
                            + " password VARCHAR(100) NOT NULL,"
                            + " status CHAR(1) NOT NULL,"
                            + " del_flag CHAR(1) NOT NULL DEFAULT '0',"
                            + " PRIMARY KEY (user_id),"
                            + " UNIQUE KEY uk_sys_user_user_name (user_name))");
            statement.execute(
                    "INSERT INTO sys_user"
                            + " (user_id,user_name,nick_name,password,status,del_flag)"
                            + " VALUES ("
                            + (target + 1000)
                            + ",' CREDENTIAL-COLLISION ','Existing','"
                            + new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                                    .encode(UUID.randomUUID().toString())
                            + "','0','0')");
        }
        mockMvc.perform(
                        put("/api/v1/admin/users/" + target + "/password")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1")
                                .header("Idempotency-Key", "first-credential-collision-" + target)
                                .contentType("application/json")
                                .content(
                                        "{\"newPassword\":\"first-"
                                                + UUID.randomUUID()
                                                + "\",\"reason\":\"管理员不得接管同名宿主账号\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RESOURCE_AMBIGUOUS"));
        try (Connection connection = connect();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM identity_provision WHERE user_id=?")) {
            query.setLong(1, target);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test
    void passwordRecoveryCompletesPersistedPendingWithoutNewCredentials() throws Exception {
        long deptId = createDepartment("Recovery synthetic department");
        long target = createUser("recover-synthetic", "Synthetic", deptId);
        String operation = UUID.randomUUID().toString();
        String oldSecret = "old-" + UUID.randomUUID();
        String newSecret = "new-" + UUID.randomUUID();
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        String oldHash = encoder.encode(oldSecret), targetHash = encoder.encode(newSecret);
        try (Connection connection = connect();
                Statement sql = connection.createStatement()) {
            sql.execute(
                    "CREATE TABLE IF NOT EXISTS sys_user (user_id BIGINT PRIMARY KEY,user_name VARCHAR(30) NOT NULL UNIQUE,nick_name VARCHAR(30) NOT NULL,password VARCHAR(100) NOT NULL,status CHAR(1) NOT NULL,del_flag CHAR(1) NOT NULL DEFAULT '0')");
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO sys_user (user_id,user_name,nick_name,password,status,del_flag) VALUES (?,'recover-synthetic','Synthetic',?,'0','0')")) {
                insert.setLong(1, target);
                insert.setString(2, oldHash);
                insert.executeUpdate();
            }
            sql.execute(
                    "UPDATE sys_user_security SET auth_version=2,identity_version=2,identity_state='CHANGE_PENDING',identity_change_id='"
                            + operation
                            + "',version=2 WHERE user_id="
                            + target);
            sql.execute("UPDATE sys_user_profile SET version=2 WHERE user_id=" + target);
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO identity_password_change (operation_id,user_id,auth_version,profile_version,expected_hash,target_hash,status,requested_by,create_time,update_time) VALUES (?,?,2,2,?,?,'PENDING',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
                insert.setString(1, operation);
                insert.setLong(2, target);
                insert.setBytes(
                        3, local.ptmcp.persistence.security.HostIdentityStore.fingerprint(oldHash));
                insert.setString(4, targetHash);
                insert.executeUpdate();
            }
        }
        String uri = "/api/v1/admin/users/" + target;
        mockMvc.perform(
                        put(uri).header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2")
                                .contentType("application/json")
                                .content(
                                        "{\"displayName\":\"Changed\",\"deptId\":"
                                                + deptId
                                                + ",\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(
                        get(uri + "/identity")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityState").value("CHANGE_PENDING"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.recoveryRequired").value(true));
        mockMvc.perform(
                        post(uri + "/password/retry")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "2")
                                .header("Idempotency-Key", "recovery-denied")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post(uri + "/password/retry")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2")
                                .header("Idempotency-Key", "recovery-extra")
                                .contentType("application/json")
                                .content("{\"newPassword\":\"cannot replace persisted target\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post(uri + "/password/retry")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2")
                                .header("Idempotency-Key", "recovery-complete")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identityState").value("READY"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.target").doesNotExist());
        try (Connection connection = connect();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT password FROM sys_user WHERE user_id=?")) {
            query.setLong(1, target);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertTrue(encoder.matches(newSecret, result.getString(1)));
            }
        }
    }

    private static String readStringField(String json, String field) {
        int at = json.indexOf("\"" + field + "\":\"");
        int start = at + field.length() + 4;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private long insertActiveNamespace(long datasourceId) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO resource_namespace (datasource_id, catalog_raw, catalog_norm,"
                            + " schema_raw, schema_norm, generation, status, create_time, update_time)"
                            + " VALUES ("
                            + datasourceId
                            + ", 'biz', 'biz', 'app', 'app', 1, 'ACTIVE', NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertActiveObject(long namespaceId, String name) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO resource_object (namespace_id, generation, object_raw, object_norm,"
                            + " quoted, kind, status, definition_hash, first_seen_at, last_seen_at,"
                            + " create_time, update_time) VALUES ("
                            + namespaceId
                            + ", 1, '"
                            + name
                            + "', '"
                            + name
                            + "', 0, 'TABLE', 'ACTIVE', X'00', NOW(6), NOW(6), NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    // ---- helpers -----------------------------------------------------------

    private static String grantBody(
            String subjectType, long subjectId, long datasourceId, String action, String effect) {
        return "{\"subjectType\":\""
                + subjectType
                + "\",\"subjectId\":"
                + subjectId
                + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                + datasourceId
                + ",\"resourceGeneration\":1,\"action\":\""
                + action
                + "\",\"effect\":\""
                + effect
                + "\",\"includeDescendants\":true,\"validFrom\":\""
                + Instant.now()
                + "\",\"reason\":\"测试授权\"}";
    }

    private long createDepartment(String name) throws Exception {
        return readLong(
                mockMvc.perform(
                                post("/api/v1/admin/departments")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "dept-" + UUID.randomUUID())
                                        .contentType("application/json")
                                        .content(
                                                "{\"name\":\""
                                                        + name
                                                        + "\","
                                                        + "\"enabled\":true}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");
    }

    @Test
    void userDeleteIsLogicalAndRevokesEveryCredential() throws Exception {
        long deptId = createDepartment("删除用户部");
        long target = createUser("del-target", "待删用户", deptId);
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO oauth2_registered_client (id, client_id, client_name,"
                            + " client_authentication_methods, authorization_grant_types, scopes,"
                            + " client_settings, token_settings, platform_id, client_type, status,"
                            + " registration_source, create_time, update_time, created_by, updated_by)"
                            + " VALUES ('client-del', 'client-del', 'del fixture', 'none',"
                            + " 'authorization_code,refresh_token', '[\"mcp:catalog\"]', '{}', '{}', NULL,"
                            + " 'PUBLIC', 'ACTIVE', 'ADMIN', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 1)");
            statement.execute(
                    "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name,"
                            + " user_id, authorization_grant_type, status, create_time, update_time,"
                            + " created_by, updated_by) VALUES ('auth-del', 'client-del', 'del-target', "
                            + target
                            + ", 'authorization_code', 'ACTIVE', UTC_TIMESTAMP(6),"
                            + " UTC_TIMESTAMP(6), 1, 1)");
            statement.execute(
                    "INSERT INTO web_session_index (session_hash, kind, user_id, auth_version,"
                            + " issued_at, expires_at, version, create_time, update_time)"
                            + " VALUES (X'33', 'ADMIN', "
                            + target
                            + ", 1, UTC_TIMESTAMP(6),"
                            + " UTC_TIMESTAMP(6) + INTERVAL 1 DAY, 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
            statement.execute(
                    "INSERT INTO personal_token (user_id, token_prefix, token_hash, name, scopes,"
                            + " auth_version, expires_at, status, version, create_time, update_time,"
                            + " created_by, updated_by) VALUES ("
                            + target
                            + ", 'del', X'44', 'del-token', '[\"mcp:catalog\"]', 1,"
                            + " UTC_TIMESTAMP(6) + INTERVAL 7 DAY, 'ACTIVE', 1, UTC_TIMESTAMP(6),"
                            + " UTC_TIMESTAMP(6), 1, 1)");
            statement.execute(
                    "INSERT INTO oauth_refresh_family (id, authorization_id, user_id,"
                            + " registered_client_id, platform_id, status, auth_version,"
                            + " absolute_expires_at, idle_expires_at, last_rotated_at, version,"
                            + " create_time, update_time) VALUES ('fam-del', 'auth-del', "
                            + target
                            + ", 'client-del', NULL, 'ACTIVE', 1,"
                            + " UTC_TIMESTAMP(6) + INTERVAL 7 DAY, UTC_TIMESTAMP(6) + INTERVAL 1 DAY,"
                            + " UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        }
        mockMvc.perform(
                        delete("/api/v1/admin/users/" + target)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(header().string("ETag", "\"2\""));
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            assertAll(
                    () ->
                            assertEquals(
                                    "DELETED",
                                    scalar(
                                            statement,
                                            "SELECT status FROM sys_user_profile WHERE user_id="
                                                    + target)),
                    () ->
                            assertEquals(
                                    "2",
                                    scalar(
                                            statement,
                                            "SELECT auth_version FROM sys_user_security WHERE user_id="
                                                    + target)),
                    () ->
                            assertEquals(
                                    "REVOKED",
                                    scalar(
                                            statement,
                                            "SELECT status FROM personal_token WHERE user_id="
                                                    + target)),
                    () ->
                            assertEquals(
                                    "REVOKED",
                                    scalar(
                                            statement,
                                            "SELECT status FROM oauth_refresh_family WHERE id='fam-del'")),
                    () ->
                            assertEquals(
                                    "1",
                                    scalar(
                                            statement,
                                            "SELECT revoked_at IS NOT NULL FROM web_session_index"
                                                    + " WHERE user_id="
                                                    + target)),
                    () ->
                            assertEquals(
                                    "REVOKED",
                                    scalar(
                                            statement,
                                            "SELECT status FROM oauth2_authorization WHERE id='auth-del'")),
                    () ->
                            assertEquals(
                                    "1",
                                    scalar(
                                            statement,
                                            "SELECT COUNT(*) FROM admin_operation_log"
                                                    + " WHERE action='USER_DELETE' AND aggregate_id="
                                                    + target)),
                    () ->
                            assertEquals(
                                    "0",
                                    scalar(
                                            statement,
                                            "SELECT COUNT(*) FROM sys_user_profile p"
                                                    + " JOIN sys_user_login_name l ON l.user_id=p.user_id"
                                                    + " WHERE p.status <> 'DELETED' AND l.normalized_name='del-target'")));
        }
        mockMvc.perform(
                        get("/api/v1/admin/users")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(
                        result -> {
                            if (result.getResponse().getContentAsString().contains("del-target")) {
                                throw new AssertionError(
                                        "deleted user must be hidden from the list");
                            }
                        });
    }

    @Test
    void userDeleteRequiresFreshVersionAndIsNotRepeatable() throws Exception {
        long deptId = createDepartment("删除用户冲突部");
        long target = createUser("del-conflict", "冲突用户", deptId);
        mockMvc.perform(
                        delete("/api/v1/admin/users/" + target)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "\"7\""))
                .andExpect(status().isConflict());
        try (Connection connection = connect();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT status FROM sys_user_profile WHERE user_id = ?")) {
            query.setLong(1, target);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(
                        "ACTIVE", result.getString(1), "a rejected delete must not change status");
            }
        }
        mockMvc.perform(
                        delete("/api/v1/admin/users/" + target)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "\"1\""))
                .andExpect(status().isOk());
        mockMvc.perform(
                        delete("/api/v1/admin/users/" + target)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "\"2\""))
                .andExpect(status().isConflict());
    }

    @Test
    void lastPlatformAdminCannotBeDisabledOrDeleted() throws Exception {
        // ADMIN_ID is the only ACTIVE+READY holder of ptmcp:platform:scope in this fixture.
        try (Connection connection = connect();
                PreparedStatement name =
                        connection.prepareStatement(
                                "INSERT INTO sys_user_login_name (user_id,normalized_name,create_time,update_time)"
                                        + " VALUES (?,'last_admin_fixture',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
            name.setLong(1, ADMIN_ID);
            name.executeUpdate();
        }
        mockMvc.perform(
                        put("/api/v1/admin/users/" + ADMIN_ID + "/status")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content("{\"status\":\"DISABLED\"}")
                                .header("If-Match", currentVersion(ADMIN_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_PLATFORM_ADMIN"));
        mockMvc.perform(
                        delete("/api/v1/admin/users/" + ADMIN_ID)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", currentVersion(ADMIN_ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_PLATFORM_ADMIN"));
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            assertEquals(
                    "ACTIVE",
                    scalar(
                            statement,
                            "SELECT status FROM sys_user_profile WHERE user_id=" + ADMIN_ID));
        }

        // A second usable platform admin unlocks both operations. S1: admin status is granted by
        // the independent platform_admin column, not by any capability code.
        long deptId = createDepartment("末位管理员部");
        long second = createUser("second-admin", "第二管理员", deptId);
        try (Connection connection = connect();
                PreparedStatement ready =
                        connection.prepareStatement(
                                "UPDATE sys_user_security SET identity_state='READY',"
                                        + " platform_admin=1 WHERE user_id=?")) {
            ready.setLong(1, second);
            ready.executeUpdate();
        }
        mockMvc.perform(
                        delete("/api/v1/admin/users/" + ADMIN_ID)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", currentVersion(ADMIN_ID)))
                .andExpect(status().isOk());
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            assertEquals(
                    "DELETED",
                    scalar(
                            statement,
                            "SELECT status FROM sys_user_profile WHERE user_id=" + ADMIN_ID));
        }
    }

    private String currentVersion(long userId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT version FROM sys_user_profile WHERE user_id = ?")) {
            query.setLong(1, userId);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                return Long.toString(result.getLong(1));
            }
        }
    }

    private long createUser(String username, String displayName, long deptId) throws Exception {
        return readLong(
                mockMvc.perform(
                                post("/api/v1/admin/users")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "user-" + UUID.randomUUID())
                                        .contentType("application/json")
                                        .content(
                                                "{\"username\":\""
                                                        + username
                                                        + "\","
                                                        + "\"displayName\":\""
                                                        + displayName
                                                        + "\",\"deptId\":"
                                                        + deptId
                                                        + ",\"enabled\":true}"))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "id");
    }

    private long insertActiveDatasource() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO datasource (code, name, engine, host, port, database_name,"
                            + " username, credential_cipher, key_id, tls_mode, pool_max, status,"
                            + " create_time, update_time, created_by, updated_by) VALUES"
                            + " ('ds-admin', '数据源', 'MYSQL', 'db.example.internal', 3306, 'biz',"
                            + " 'ro', X'00', 'k1', 'VERIFY_FULL', 4, 'ACTIVE', NOW(6), NOW(6), 1, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertPlatform(String code) throws Exception {
        return insertPlatform(code, "ACTIVE");
    }

    private long insertPlatform(String code, String status) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO platform_registry (code, name, tier, status, catalog_mode,"
                            + " proof_mode, scope_version, security_version, create_time,"
                            + " update_time, created_by, updated_by) VALUES ('"
                            + code
                            + "', '平台', 3, '"
                            + status
                            + "', 'DISCOVER', 'NONE', 1, 1, NOW(6), NOW(6), 1, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private void insertAudit(
            String requestId,
            long userId,
            long platformId,
            long datasourceId,
            String tool,
            String result,
            String verdict,
            String errorCode,
            long minutesAgo)
            throws Exception {
        insertAudit(
                requestId,
                userId,
                platformId,
                datasourceId,
                tool,
                result,
                verdict,
                errorCode,
                minutesAgo,
                null);
    }

    private void insertAudit(
            String requestId,
            long userId,
            long platformId,
            long datasourceId,
            String tool,
            String result,
            String verdict,
            String errorCode,
            long minutesAgo,
            String fingerprintHex)
            throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO audit_log (request_id, user_id, platform_id, tool,"
                                        + " datasource_id, started_at, completed_at, result,"
                                        + " verdict, error_code, sql_masked, sql_fingerprint,"
                                        + " resource_refs, versions, duration_ms, queue_ms, db_ms,"
                                        + " rows_returned, response_bytes, truncated, create_time)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,"
                                        + (fingerprintHex == null ? " NULL" : " UNHEX(?)")
                                        + ", ?, ?, 12, 3, 8, 1, 256, 0, ?)")) {
            int index = 1;
            statement.setString(index++, requestId);
            statement.setLong(index++, userId);
            statement.setLong(index++, platformId);
            statement.setString(index++, tool);
            statement.setLong(index++, datasourceId);
            statement.setObject(index++, now().minusMinutes(minutesAgo));
            statement.setObject(index++, now().minusMinutes(minutesAgo).plusSeconds(1));
            statement.setString(index++, result);
            statement.setString(index++, verdict);
            statement.setString(index++, errorCode);
            statement.setString(index++, "SELECT * FROM orders WHERE id = ?");
            if (fingerprintHex != null) {
                statement.setString(index++, fingerprintHex);
            }
            statement.setString(index++, "{\"datasourceId\":" + datasourceId + "}");
            statement.setString(index++, "{\"datasource\":\"v1\"}");
            statement.setObject(index, now());
            statement.executeUpdate();
        }
    }

    private static String jsonPathCursor(String json) {
        int at = json.indexOf("\"nextCursor\":");
        int start = json.indexOf('"', at + 13) + 1;
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private static java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now(java.time.ZoneOffset.UTC);
    }

    /** Reads a quoted numeric field, e.g. {@code "id":"12"}. */
    private static long readQuotedLong(String json, String field) {
        int at = json.indexOf("\"" + field + "\":\"");
        int start = at + field.length() + 4;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }

    private static long readLong(String json, String field) {
        int at = json.indexOf("\"" + field + "\":");
        int start = at + field.length() + 3;
        int end = start;
        while (end < json.length()
                && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }
        return Long.parseLong(json.substring(start, end));
    }
}
