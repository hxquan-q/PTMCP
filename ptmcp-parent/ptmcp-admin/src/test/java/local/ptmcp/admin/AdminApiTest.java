package local.ptmcp.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Admin org/ACL APIs against the real MySQL schema; identity uses the documented test shim. */
@SpringBootTest(classes = AdminApplication.class, properties = "ptmcp.admin.test-principal=true")
@AutoConfigureMockMvc
class AdminApiTest {
    private static final String MYSQL_IMAGE =
            "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69";
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
