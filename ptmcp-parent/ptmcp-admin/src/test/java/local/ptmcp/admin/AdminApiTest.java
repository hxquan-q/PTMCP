package local.ptmcp.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mysql.cj.jdbc.exceptions.CommunicationsException;

import local.ptmcp.persistence.org.ResourceIds;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.mysql.MySQLContainer;

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
import java.util.List;
import java.util.UUID;

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

    @BeforeEach
    void resetOnlyOwnedData() throws Exception {
        try (Connection connection = connect();
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
                            "admin_operation_log",
                            "sys_user_role",
                            "sys_user_profile",
                            "sys_user_login_name",
                            "sys_role_permission",
                            "sys_role",
                            "sys_dept",
                            "platform_registry",
                            "audit_log",
                            "audit_outbox",
                            "api_idempotency")) {
                statement.execute("DELETE FROM " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    private static final long ADMIN_ID = 1L;

    private String adminHeaders(long userId) {
        return "X-Test-User-Id: " + userId + ", X-Test-Platform-Admin: true";
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
                                                        + "\"enabled\":true,"
                                                        + "\"permissionCodes\":[\"acl:read\"]}"))
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
    }

    @Test
    void selfGrantEscalationIsBlockedWithoutAnyRowWritten() throws Exception {
        long deptId = createDepartment("自增部");
        createUser("esc-admin", "自增管理员", deptId);
        long datasourceId = insertActiveDatasource();

        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "grant-esc-" + UUID.randomUUID())
                                .contentType("application/json")
                                .content(grantBody("USER", ADMIN_ID, datasourceId, "META", "ALLOW"))
                                .header("If-Match", "0"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_PRIVILEGE_ESCALATION"));

        mockMvc.perform(
                        get("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        long otherUser = createUser("other-user", "其他用户", deptId);
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/acl/grants")
                                        .header("X-Test-User-Id", ADMIN_ID)
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
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(
                                        grantBody(
                                                "USER", otherUser, datasourceId, "READ", "ALLOW")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_GRANT"));

        mockMvc.perform(
                        delete("/api/v1/admin/acl/grants/" + grantId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(
                        delete("/api/v1/admin/acl/grants/" + grantId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        mockMvc.perform(
                        get("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", ADMIN_ID)
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
    void delegatedAdminManagesOnlyTheirSubtree() throws Exception {
        seedAdminDelegationCodes();
        // The delegable set is the caller's own capability codes; add the department ones.
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO sys_role (role_key, role_name, status, del_flag, create_time,"
                            + " update_time) VALUES ('dept-scope-admin', '部门作用域管理员', '0', '0',"
                            + " NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            long roleId;
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                roleId = keys.getLong(1);
            }
            for (String code : List.of("ptmcp:department:update", "ptmcp:department:delete")) {
                statement.execute(
                        "INSERT INTO sys_role_permission (role_id, permission_code, create_time,"
                                + " update_time) VALUES ("
                                + roleId
                                + ", '"
                                + code
                                + "', NOW(6), NOW(6))");
            }
            statement.execute(
                    "INSERT INTO sys_user_role (user_id, role_id, create_time, update_time)"
                            + " VALUES ("
                            + ADMIN_ID
                            + ", "
                            + roleId
                            + ", NOW(6), NOW(6))");
        }
        // A datasource must exist for the delegation's resource pin.
        long datasourceId = insertActiveDatasource();
        long rootId = createDepartment("委派作用域根");
        long childId = createDepartment("委派作用域子部");
        // Move child under root via update; simpler: create with parentId through API.
        long outsideId = createDepartment("委派作用域外部");

        // Make child a real child of root (API create has no parentId field; move it).
        long childVersion =
                readLong(
                        mockMvc.perform(
                                        get("/api/v1/admin/departments/" + childId)
                                                .header("X-Test-User-Id", ADMIN_ID)
                                                .header("X-Test-Platform-Admin", "true"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "version");
        mockMvc.perform(
                        put("/api/v1/admin/departments/" + childId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", String.valueOf(childVersion))
                                .contentType("application/json")
                                .content(
                                        "{\"parentId\":"
                                                + rootId
                                                + ",\"name\":\"委派作用域子部\",\"enabled\":true}"))
                .andExpect(status().isOk());

        // The delegated user sits inside the subtree and holds matching capability codes.
        createUser("scope-throwaway", "占位用户", childId);
        long delegate = createUser("delegated-admin", "受控管理员", childId);
        mockMvc.perform(
                        post("/api/v1/admin/roles")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "scope-role-" + UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        "{\"key\":\"dept_admin\",\"name\":\"部门管理员\","
                                                + "\"enabled\":true,"
                                                + "\"permissionCodes\":[\"ptmcp:department:update\","
                                                + "\"ptmcp:department:delete\"]}"))
                .andExpect(status().isCreated());
        // Grant the role via SQL (roles API list unused here); assign to delegate.
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            var roleKeys =
                    statement.executeQuery(
                            "SELECT role_id FROM sys_role WHERE role_key = 'dept_admin'");
            assertTrue(roleKeys.next());
            long roleId = roleKeys.getLong(1);
            statement.execute(
                    "INSERT INTO sys_user_role (user_id, role_id, create_time, update_time)"
                            + " VALUES ("
                            + delegate
                            + ", "
                            + roleId
                            + ", NOW(6), NOW(6))");
        }

        // Delegate the subtree with matching actions.
        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "scope-delegation-1")
                                .contentType("application/json")
                                .content(
                                        "{\"userId\":"
                                                + delegate
                                                + ",\"departmentRootId\":"
                                                + rootId
                                                + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                                                + datasourceId
                                                + ","
                                                + "\"allowedActions\":[\"ptmcp:department:update\",\"ptmcp:department:delete\"]"
                                                + ",\"validFrom\":\"2020-01-01T00:00:00Z\""
                                                + ",\"validTo\":\"2030-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated());

        // List: delegated admin sees only root + child, not the outside department.
        MvcResult listed =
                mockMvc.perform(
                                get("/api/v1/admin/departments")
                                        .header("X-Test-User-Id", delegate)
                                        .header("X-Test-Platform-Admin", "false"))
                        .andExpect(status().isOk())
                        .andReturn();
        String listBody = listed.getResponse().getContentAsString();
        if (!listBody.contains("委派作用域根") || !listBody.contains("委派作用域子部")) {
            throw new AssertionError("subtree rows missing: " + listBody);
        }
        if (listBody.contains("委派作用域外部")) {
            throw new AssertionError("outside department leaked into subtree view");
        }

        // Update inside the subtree succeeds; re-read the live version after the move.
        long liveChildVersion =
                readLong(
                        mockMvc.perform(
                                        get("/api/v1/admin/departments/" + childId)
                                                .header("X-Test-User-Id", delegate)
                                                .header("X-Test-Platform-Admin", "false"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "version");
        mockMvc.perform(
                        put("/api/v1/admin/departments/" + childId)
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", String.valueOf(liveChildVersion))
                                .contentType("application/json")
                                .content(
                                        "{\"parentId\":"
                                                + rootId
                                                + ",\"name\":\"委派作用域子部A\",\"enabled\":true}"))
                // moveDepartment already bumped once (parent change), so If-Match is +1 and the
                // rename lands at +2.
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("委派作用域子部A"));

        // Update outside the subtree is denied.
        mockMvc.perform(
                        put("/api/v1/admin/departments/" + outsideId)
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "1")
                                .contentType("application/json")
                                .content("{\"parentId\":null,\"name\":\"越权改名\",\"enabled\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        // Delete inside the subtree succeeds once its members have been moved out (the store
        // refuses to delete a department that still has user profiles in it).
        mockMvc.perform(
                        put("/api/v1/admin/users/" + (delegate - 1) + "/department")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1")
                                .contentType("application/json")
                                .content("{\"departmentId\":" + outsideId + "}"))
                .andExpect(status().isOk());
        mockMvc.perform(
                        put("/api/v1/admin/users/" + delegate + "/department")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1")
                                .contentType("application/json")
                                .content("{\"departmentId\":" + outsideId + "}"))
                .andExpect(status().isOk());
        long deletableVersion =
                readLong(
                        mockMvc.perform(
                                        get("/api/v1/admin/departments/" + childId)
                                                .header("X-Test-User-Id", delegate)
                                                .header("X-Test-Platform-Admin", "false"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "version");
        mockMvc.perform(
                        delete("/api/v1/admin/departments/" + childId)
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", String.valueOf(deletableVersion)))
                .andExpect(status().isNoContent());
        mockMvc.perform(
                        delete("/api/v1/admin/departments/" + outsideId)
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "1"))
                .andExpect(status().isForbidden());

        // Creating a root (no parent) stays platform-only.
        mockMvc.perform(
                        post("/api/v1/admin/departments")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("Idempotency-Key", "scope-root-" + UUID.randomUUID())
                                .contentType("application/json")
                                .content("{\"name\":\"越权根\",\"enabled\":true}"))
                .andExpect(status().isForbidden());

        // Platform admin is unaffected end to end.
        mockMvc.perform(
                        get("/api/v1/admin/departments")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isOk())
                .andExpect(
                        result -> {
                            if (!result.getResponse().getContentAsString().contains("委派作用域外部")) {
                                throw new AssertionError("platform admin lost tree visibility");
                            }
                        });
    }

    @Test
    void delegatedAdminManagesOnlySubtreeUsers() throws Exception {
        seedAdminDelegationCodes();
        // Delegable set gains the user codes for this slice.
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO sys_role (role_key, role_name, status, del_flag, create_time,"
                            + " update_time) VALUES ('user-scope-admin', '用户作用域管理员', '0', '0',"
                            + " NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            long roleId;
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                roleId = keys.getLong(1);
            }
            for (String code :
                    List.of(
                            "ptmcp:user:update",
                            "ptmcp:user:status",
                            "ptmcp:user:assign-role",
                            "ptmcp:user:move")) {
                statement.execute(
                        "INSERT INTO sys_role_permission (role_id, permission_code, create_time,"
                                + " update_time) VALUES ("
                                + roleId
                                + ", '"
                                + code
                                + "', NOW(6), NOW(6))");
            }
            statement.execute(
                    "INSERT INTO sys_user_role (user_id, role_id, create_time, update_time)"
                            + " VALUES ("
                            + ADMIN_ID
                            + ", "
                            + roleId
                            + ", NOW(6), NOW(6))");
        }
        long datasourceId = insertActiveDatasource();

        long rootId = createDepartment("用户作用域根");
        long insideId = createDepartment("用户作用域内");
        long outsideId = createDepartment("用户作用域外");
        // Re-parent "inside" under root (API has no create-time parent).
        long insideVersion =
                readLong(
                        mockMvc.perform(
                                        get("/api/v1/admin/departments/" + insideId)
                                                .header("X-Test-User-Id", ADMIN_ID)
                                                .header("X-Test-Platform-Admin", "true"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString(),
                        "version");
        mockMvc.perform(
                        put("/api/v1/admin/departments/" + insideId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", String.valueOf(insideVersion))
                                .contentType("application/json")
                                .content(
                                        "{\"parentId\":"
                                                + rootId
                                                + ",\"name\":\"用户作用域内\",\"enabled\":true}"))
                .andExpect(status().isOk());

        // Target users: one inside the subtree, one outside.
        createUser("inside-throwaway", "内部占位", insideId);
        long insideUser = createUser("inside-user", "内部用户", insideId);
        long outsideUser = createUser("outside-user", "外部用户", outsideId);

        // Delegate the subtree with user codes to a (distinct) delegated admin.
        createUser("userscope-throwaway", "占位二", insideId);
        long delegate = createUser("userscope-admin", "受控用户管理员", insideId);
        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "userscope-delegation-1")
                                .contentType("application/json")
                                .content(
                                        "{\"userId\":"
                                                + delegate
                                                + ",\"departmentRootId\":"
                                                + rootId
                                                + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                                                + datasourceId
                                                + ",\"allowedActions\":[\"ptmcp:user:update\",\"ptmcp:user:status\",\"ptmcp:user:assign-role\",\"ptmcp:user:move\"]"
                                                + ",\"validFrom\":\"2020-01-01T00:00:00Z\""
                                                + ",\"validTo\":\"2030-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated());

        // List: the delegated admin sees the inside user but not the outside one.
        MvcResult listed =
                mockMvc.perform(
                                get("/api/v1/admin/users")
                                        .header("X-Test-User-Id", delegate)
                                        .header("X-Test-Platform-Admin", "false"))
                        .andExpect(status().isOk())
                        .andReturn();
        String listBody = listed.getResponse().getContentAsString();
        if (!listBody.contains("inside-user")) {
            throw new AssertionError("inside user missing from subtree view");
        }
        if (listBody.contains("outside-user")) {
            throw new AssertionError("outside user leaked into subtree view");
        }

        // Status flip inside the subtree succeeds.
        mockMvc.perform(
                        put("/api/v1/admin/users/" + insideUser + "/status")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "1")
                                .contentType("application/json")
                                .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());

        // Any write on the outside user is denied.
        mockMvc.perform(
                        put("/api/v1/admin/users/" + outsideUser + "/status")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "1")
                                .contentType("application/json")
                                .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        // Role assignment inside the subtree succeeds (PRD cross-scope role checks are their own
        // slice; the delegation gate itself is what is under test here).
        mockMvc.perform(
                        put("/api/v1/admin/users/" + insideUser + "/roles")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "2")
                                .contentType("application/json")
                                .content("{\"roleIds\":[]}"))
                .andExpect(status().isOk());

        // AC-02 cross-scope role guard: a role carrying codes beyond the delegation is refused,
        // while a role whose codes all sit inside the delegation passes.
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO sys_role (role_key, role_name, status, del_flag, create_time,"
                            + " update_time) VALUES ('broad-role', '越权角色', '0', '0', NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            long broad;
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                broad = keys.getLong(1);
            }
            statement.execute(
                    "INSERT INTO sys_role_permission (role_id, permission_code, create_time,"
                            + " update_time) VALUES ("
                            + broad
                            + ", 'ptmcp:audit:decrypt', NOW(6), NOW(6))");
            statement.execute(
                    "INSERT INTO sys_role (role_key, role_name, status, del_flag, create_time,"
                            + " update_time) VALUES ('narrow-role', '委派内角色', '0', '0', NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            long narrow;
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                narrow = keys.getLong(1);
            }
            statement.execute(
                    "INSERT INTO sys_role_permission (role_id, permission_code, create_time,"
                            + " update_time) VALUES ("
                            + narrow
                            + ", 'ptmcp:user:update', NOW(6), NOW(6))");
        }
        // Look up both role ids directly.
        long broadRoleId;
        long narrowRoleId;
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            var rs =
                    statement.executeQuery(
                            "SELECT role_id, role_key FROM sys_role WHERE role_key IN ('broad-role','narrow-role')");
            broadRoleId = -1;
            narrowRoleId = -1;
            while (rs.next()) {
                if ("broad-role".equals(rs.getString(2))) broadRoleId = rs.getLong(1);
                else narrowRoleId = rs.getLong(1);
            }
        }

        mockMvc.perform(
                        put("/api/v1/admin/users/" + insideUser + "/roles")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "3")
                                .contentType("application/json")
                                .content("{\"roleIds\":[" + broadRoleId + "]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        mockMvc.perform(
                        put("/api/v1/admin/users/" + insideUser + "/roles")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "3")
                                .contentType("application/json")
                                .content("{\"roleIds\":[" + narrowRoleId + "]}"))
                .andExpect(status().isOk());

        // Move across the subtree boundary is denied on the target side.
        mockMvc.perform(
                        put("/api/v1/admin/users/" + insideUser + "/department")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "3")
                                .contentType("application/json")
                                .content("{\"departmentId\":" + outsideId + "}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void delegatedAdminReadsRoleCatalogButCannotWriteIt() throws Exception {
        seedAdminDelegationCodes();
        long datasourceId = insertActiveDatasource();
        long rootId = createDepartment("角色目录部");
        createUser("roletest-throwaway", "占位用户", rootId);
        long delegate = createUser("roletest-delegate", "角色目录受控员", rootId);

        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "role-catalog-delegation-1")
                                .contentType("application/json")
                                .content(
                                        "{\"userId\":"
                                                + delegate
                                                + ",\"departmentRootId\":"
                                                + rootId
                                                + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                                                + datasourceId
                                                + ",\"allowedActions\":[\"ptmcp:user:assign-role\"]"
                                                + ",\"validFrom\":\"2020-01-01T00:00:00Z\""
                                                + ",\"validTo\":\"2030-01-01T00:00:00Z\"}"))
                .andExpect(status().isCreated());

        // Catalog read succeeds for the delegated admin.
        mockMvc.perform(
                        get("/api/v1/admin/roles")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false"))
                .andExpect(status().isOk());

        // Writes stay platform-only: roles are shared across management scopes.
        mockMvc.perform(
                        post("/api/v1/admin/roles")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("Idempotency-Key", "role-write-" + UUID.randomUUID())
                                .contentType("application/json")
                                .content(
                                        "{\"key\":\"rogue\",\"name\":\"越权角色\",\"enabled\":true,"
                                                + "\"permissionCodes\":[]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        // A caller with no delegation at all is denied even the catalog.
        mockMvc.perform(
                        get("/api/v1/admin/roles")
                                .header("X-Test-User-Id", 42L)
                                .header("X-Test-Platform-Admin", "false"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
    }

    @Test
    void delegatedAclGrantsStayInsideEveryDelegationDimension() throws Exception {
        seedAdminDelegationCodes();
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO sys_role (role_key, role_name, status, del_flag, create_time,"
                            + " update_time) VALUES ('acl-scope-admin', 'ACL作用域管理员', '0', '0',"
                            + " NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            long roleId;
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                roleId = keys.getLong(1);
            }
            for (String code : List.of("ptmcp:acl:read", "ptmcp:acl:list")) {
                statement.execute(
                        "INSERT INTO sys_role_permission (role_id, permission_code, create_time,"
                                + " update_time) VALUES ("
                                + roleId
                                + ", '"
                                + code
                                + "', NOW(6), NOW(6))");
            }
            statement.execute(
                    "INSERT INTO sys_user_role (user_id, role_id, create_time, update_time)"
                            + " VALUES ("
                            + ADMIN_ID
                            + ", "
                            + roleId
                            + ", NOW(6), NOW(6))");
        }
        long datasourceId = insertActiveDatasource();
        long rootId = createDepartment("ACL作用域根");
        long outsideId = createDepartment("ACL作用域外");
        createUser("acl-throwaway", "占位用户", rootId);
        long delegate = createUser("acl-delegate", "ACL受控员", rootId);
        long insideSubject = createUser("acl-inside", "子树内主体", rootId);
        long secondInsideSubject = createUser("acl-inside-two", "子树内主体二", rootId);
        long outsideSubject = createUser("acl-outside", "子树外主体", outsideId);

        // Windows are computed from now so the test never depends on the wall clock.
        java.time.Instant delegationFrom =
                java.time.Instant.now().minus(java.time.Duration.ofDays(30));
        java.time.Instant delegationTo =
                java.time.Instant.now().plus(java.time.Duration.ofDays(180));
        java.time.Instant grantFrom = java.time.Instant.now().plus(java.time.Duration.ofDays(1));
        java.time.Instant grantTo = java.time.Instant.now().plus(java.time.Duration.ofDays(90));

        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "acl-delegation-1")
                                .contentType("application/json")
                                .content(
                                        "{\"userId\":"
                                                + delegate
                                                + ",\"departmentRootId\":"
                                                + rootId
                                                + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                                                + datasourceId
                                                + ",\"allowedActions\":[\"ptmcp:acl:read\"]"
                                                + ",\"validFrom\":\""
                                                + delegationFrom
                                                + "\""
                                                + ",\"validTo\":\""
                                                + delegationTo
                                                + "\"}"))
                .andExpect(status().isCreated());

        String inWindow =
                "{\"subjectType\":\"USER\",\"subjectId\":"
                        + insideSubject
                        + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                        + datasourceId
                        + ",\"resourceGeneration\":1,\"action\":\"READ\",\"effect\":\"ALLOW\","
                        + "\"includeDescendants\":false,\"validFrom\":\""
                        + grantFrom
                        + "\","
                        + "\"validTo\":\""
                        + grantTo
                        + "\",\"reason\":\"委派内授予\"}";
        String headers = "X-Test-User-Id: " + delegate + ", X-Test-Platform-Admin: false";

        // In-dimension grant succeeds (subject inside, resource matched, READ delegated, window
        // inside the delegation window).
        MvcResult granted =
                mockMvc.perform(
                                post("/api/v1/admin/acl/grants")
                                        .header("X-Test-User-Id", delegate)
                                        .header("X-Test-Platform-Admin", "false")
                                        .header("Idempotency-Key", "acl-grant-1")
                                        .contentType("application/json")
                                        .content(inWindow))
                        .andExpect(
                                result -> {
                                    if (result.getResponse().getStatus() != 201) {
                                        System.out.println(
                                                "ACL-DEBUG: "
                                                        + result.getResponse()
                                                                .getContentAsString());
                                    }
                                })
                        .andExpect(status().isCreated())
                        .andReturn();
        long grantId = readLong(granted.getResponse().getContentAsString(), "id");

        // META is not in the delegated action set.
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("Idempotency-Key", "acl-grant-meta")
                                .contentType("application/json")
                                .content(
                                        inWindow.replace(
                                                "\"action\":\"READ\"", "\"action\":\"META\"")))
                .andExpect(status().isForbidden());

        // The grant window may not outlive the delegation (2027 > 2026-06).
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("Idempotency-Key", "acl-grant-window")
                                .contentType("application/json")
                                .content(
                                        inWindow.replace(
                                                        "\"subjectId\":" + insideSubject,
                                                        "\"subjectId\":" + secondInsideSubject)
                                                .replace(
                                                        grantTo.toString(),
                                                        java.time.Instant.now()
                                                                .plus(
                                                                        java.time.Duration.ofDays(
                                                                                270))
                                                                .toString())))
                .andExpect(status().isForbidden());

        // A subject outside the subtree is denied even for a delegated action.
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("Idempotency-Key", "acl-grant-outside")
                                .contentType("application/json")
                                .content(
                                        inWindow.replace(
                                                "\"subjectId\":" + insideSubject,
                                                "\"subjectId\":" + outsideSubject)))
                .andExpect(status().isForbidden());

        // Revoke inside the delegation succeeds; the platform admin still sees everything.
        mockMvc.perform(
                        delete("/api/v1/admin/acl/grants/" + grantId)
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "false")
                                .header("If-Match", "1"))
                .andExpect(status().isOk());
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
        try (Connection connection = connect();
                PreparedStatement dump =
                        connection.prepareStatement(
                                "SELECT action, aggregate_id FROM admin_operation_log ORDER BY id")) {
            try (ResultSet result = dump.executeQuery()) {
                while (result.next()) {
                    System.out.println(
                            "TRAIL-DUMP " + result.getString(1) + " " + result.getLong(2));
                }
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
    void delegationLifecycleResolvesGenerationAndDerivesStatus() throws Exception {
        seedAdminDelegationCodes();
        long datasourceId = insertActiveDatasource();
        long deptId = createDepartment("委派部");
        // The reset empties sys_user_login_name, so ids restart at 1 (the shim admin's id);
        // a throwaway row keeps the delegated user distinct from the acting admin.
        createUser("throwaway-one", "占位用户", deptId);
        long delegate = createUser("delegate-user", "受控管理员", deptId);
        String window =
                "{\"userId\":"
                        + delegate
                        + ",\"departmentRootId\":"
                        + deptId
                        + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                        + datasourceId
                        + ",\"allowedActions\":[\"ptmcp:acl:list\",\"ptmcp:user:list\"]"
                        + ",\"validFrom\":\"2030-01-01T00:00:00Z\""
                        + ",\"validTo\":\"2030-01-02T00:00:00Z\"}";

        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/delegations")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "delegation-create-1")
                                        .contentType("application/json")
                                        .content(window))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.resourceGeneration").value(1))
                        .andExpect(jsonPath("$.status").value("ACTIVE"))
                        .andExpect(jsonPath("$.allowedActions.length()").value(2))
                        .andExpect(jsonPath("$.version").value(1))
                        .andReturn();
        long delegationId = readLong(created.getResponse().getContentAsString(), "id");

        // The future window reads as ACTIVE; the same row is absent from the EXPIRED and
        // REVOKED projections because no write derived those states.
        mockMvc.perform(
                        get("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(delegationId))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        mockMvc.perform(
                        get("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("status", "EXPIRED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));

        mockMvc.perform(
                        delete("/api/v1/admin/delegations/" + delegationId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(
                        result -> {
                            String etag = result.getResponse().getHeader("ETag");
                            if (!"\"2\"".equals(etag)) {
                                throw new AssertionError("unexpected ETag: " + etag);
                            }
                        });

        mockMvc.perform(
                        get("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .param("status", "REVOKED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].status").value("REVOKED"));

        // The operation log recorded both transitions inside the same transaction.
        try (Connection connection = connect();
                PreparedStatement log =
                        connection.prepareStatement(
                                "SELECT action FROM admin_operation_log"
                                        + " WHERE aggregate_type = 'ADMIN_DELEGATION' AND aggregate_id = ?"
                                        + " ORDER BY id")) {
            log.setLong(1, delegationId);
            try (ResultSet result = log.executeQuery()) {
                assertTrue(result.next());
                assertEquals("DELEGATION_CREATE", result.getString(1));
                assertTrue(result.next());
                assertEquals("DELEGATION_REVOKE", result.getString(1));
                assertFalse(result.next());
            }
        }
    }

    @Test
    void delegationRejectsSelfEscalationDuplicatesAndForeignActions() throws Exception {
        seedAdminDelegationCodes();
        long datasourceId = insertActiveDatasource();
        long deptId = createDepartment("委派部二");
        createUser("throwaway-two", "占位用户二", deptId);
        long delegate = createUser("delegate-two", "受控管理员二", deptId);
        String base =
                "{\"departmentRootId\":"
                        + deptId
                        + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                        + datasourceId
                        + ",\"allowedActions\":[\"ptmcp:acl:list\"]"
                        + ",\"validFrom\":\"2030-01-01T00:00:00Z\""
                        + ",\"validTo\":\"2030-01-02T00:00:00Z\"}";

        // Self-delegation is blocked without any row written.
        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "self-delegation")
                                .contentType("application/json")
                                .content(
                                        base.replace(
                                                "\"departmentRootId\"",
                                                "\"userId\":"
                                                        + ADMIN_ID
                                                        + ",\"departmentRootId\"")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_PRIVILEGE_ESCALATION"));

        // An action the caller does not hold cannot be delegated.
        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "foreign-action")
                                .contentType("application/json")
                                .content(base.replace("ptmcp:acl:list", "ptmcp:audit:decrypt")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        MvcResult first =
                mockMvc.perform(
                                post("/api/v1/admin/delegations")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "dupe-delegation-1")
                                        .contentType("application/json")
                                        .content(
                                                base.replace(
                                                        "\"departmentRootId\"",
                                                        "\"userId\":"
                                                                + delegate
                                                                + ",\"departmentRootId\"")))
                        .andExpect(status().isCreated())
                        .andReturn();
        long delegationId = readLong(first.getResponse().getContentAsString(), "id");

        // Same subject/resource/window overlap is a duplicate grant.
        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "dupe-delegation-2")
                                .contentType("application/json")
                                .content(
                                        base.replace(
                                                "\"departmentRootId\"",
                                                "\"userId\":"
                                                        + delegate
                                                        + ",\"departmentRootId\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_GRANT"));

        // A stale If-Match cannot revoke.
        mockMvc.perform(
                        delete("/api/v1/admin/delegations/" + delegationId)
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "7"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        // The delegated admin cannot revoke the delegation held in their name.
        mockMvc.perform(
                        delete("/api/v1/admin/delegations/" + delegationId)
                                .header("X-Test-User-Id", delegate)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SELF_PRIVILEGE_ESCALATION"));
    }

    @Test
    void delegationReplayReturnsRecordedResponseAndUnknownResourceIsRejected() throws Exception {
        seedAdminDelegationCodes();
        long deptId = createDepartment("委派部三");
        createUser("throwaway-three", "占位用户三", deptId);
        long delegate = createUser("delegate-three", "受控管理员三", deptId);
        String body =
                "{\"userId\":"
                        + delegate
                        + ",\"departmentRootId\":"
                        + deptId
                        + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":987654"
                        + ",\"allowedActions\":[\"ptmcp:acl:list\"]"
                        + ",\"validFrom\":\"2030-01-01T00:00:00Z\""
                        + ",\"validTo\":\"2030-01-02T00:00:00Z\"}";

        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "unknown-resource")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        long datasourceId = insertActiveDatasource();
        String known = body.replace("\"resourceId\":987654", "\"resourceId\":" + datasourceId);
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/delegations")
                                        .header("X-Test-User-Id", ADMIN_ID)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", "replay-key-1")
                                        .contentType("application/json")
                                        .content(known))
                        .andExpect(status().isCreated())
                        .andReturn();

        // Same key + same body replays the recorded response instead of inserting again.
        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", ADMIN_ID)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", "replay-key-1")
                                .contentType("application/json")
                                .content(known))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(
                        result -> {
                            String original = created.getResponse().getContentAsString();
                            if (!original.equals(result.getResponse().getContentAsString())) {
                                throw new AssertionError("replayed body differs");
                            }
                        });

        try (Connection connection = connect();
                PreparedStatement count =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM admin_delegation WHERE user_id = ?")) {
            count.setLong(1, delegate);
            try (ResultSet result = count.executeQuery()) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
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
        byte[] rawSealed = cipher.encryptForTesting("SELECT card_number FROM payment WHERE id = 1");
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

        // Diagnostic: run the epoch machinery directly to isolate the failure.
        try (Connection probeConnection = connect()) {
            var receipt =
                    new local.ptmcp.persistence.SecurityChangeTransaction(
                                    new org.springframework.jdbc.datasource
                                            .SingleConnectionDataSource(probeConnection, true))
                            .execute(
                                    new local.ptmcp.persistence.SecurityEvent(
                                            java.util.UUID.randomUUID(),
                                            local.ptmcp.persistence.SecurityEvent.Kind
                                                    .USER_SECURITY_CHANGED,
                                            new local.ptmcp.persistence.SecurityEvent.NumericId(
                                                    target),
                                            99),
                                    connection -> {});
            System.out.println("EPOCH-PROBE epoch=" + receipt.epoch());
        } catch (RuntimeException probeError) {
            System.out.println("EPOCH-PROBE-FAIL " + probeError);
            probeError.printStackTrace();
        }

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
                .andExpect(status().isNoContent())
                .andExpect(
                        result -> {
                            String epoch = result.getResponse().getHeader("X-Security-Epoch");
                            if (epoch == null || Long.parseLong(epoch) < 1) {
                                throw new AssertionError("missing security epoch: " + epoch);
                            }
                        });

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
                    assertTrue(result.getString(2).startsWith("$2"), "profile hash missing");
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
                                    + " AND action = 'USER_PASSWORD_RESET'")) {
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

    /** Delegation tests need the acting admin to hold org/ACL capability codes. */
    private void seedAdminDelegationCodes() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO sys_role (role_key, role_name, status, del_flag,"
                            + " create_time, update_time) VALUES"
                            + " ('ptmcp-platform-admin', '平台管理员', '0', '0', NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            long roleId;
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                roleId = keys.getLong(1);
            }
            for (String code :
                    List.of("ptmcp:acl:list", "ptmcp:user:list", "ptmcp:user:assign-role")) {
                statement.execute(
                        "INSERT INTO sys_role_permission (role_id, permission_code, create_time,"
                                + " update_time) VALUES ("
                                + roleId
                                + ", '"
                                + code
                                + "', NOW(6), NOW(6))");
            }
            statement.execute(
                    "INSERT INTO sys_user_role (user_id, role_id, create_time, update_time)"
                            + " VALUES ("
                            + ADMIN_ID
                            + ", "
                            + roleId
                            + ", NOW(6), NOW(6))");
        }
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
