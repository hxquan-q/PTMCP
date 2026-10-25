package local.ptmcp.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mysql.cj.jdbc.exceptions.CommunicationsException;

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
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

/** Delegated-administration APIs: scope, window, self-escalation and enforcement on ACL grants. */
@SpringBootTest(
        classes = local.ptmcp.admin.AdminApplication.class,
        properties = "ptmcp.admin.test-principal=true")
@AutoConfigureMockMvc
class DelegationApiTest {
    private static final String MYSQL_IMAGE =
            "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69";
    private static final long PLATFORM_ADMIN = 1L;

    private static final MySQLContainer MYSQL = startMysql();

    @Autowired private MockMvc mockMvc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("ptmcp.admin.datasource.url", MYSQL::getJdbcUrl);
        registry.add("ptmcp.admin.datasource.username", MYSQL::getUsername);
        registry.add("ptmcp.admin.datasource.password", MYSQL::getPassword);
    }

    private static MySQLContainer startMysql() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        MySQLContainer container =
                new MySQLContainer(MYSQL_IMAGE)
                        .withDatabaseName("delegation_api_test")
                        .withUsername("delegation_test")
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
                try {
                    Thread.sleep(2_000L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
        return container;
    }

    private static boolean isCommunicationFailure(Exception failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof CommunicationsException
                    || current instanceof java.net.ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @AfterAll
    static void stopContainer() {
        if (MYSQL != null) {
            MYSQL.stop();
        }
    }

    private long rootDept;
    private long operatorUser;

    @BeforeEach
    void seedOperatorAndResetDelegations() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM admin_operation_log");
            statement.execute("DELETE FROM admin_delegation");
        }
        rootDept = createDepartment("委派测试根");
        // The first created user takes id 1: the platform operator identity itself. The suffix
        // keeps the operator name unique because users are never deleted between tests.
        operatorUser =
                createUser(
                        "operator-" + Long.toUnsignedString(System.nanoTime()), "平台管理员", rootDept);
    }

    @Test
    void platformAdminCreatesAndListsAndRevokesDelegations() throws Exception {
        long delegate = createUser("delegate-admin", "委派管理员", rootDept);
        long datasource = insertDatasource("ds-del-1");

        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/delegations")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                delegationBody(
                                                        delegate,
                                                        rootDept,
                                                        datasource,
                                                        Instant.now().minus(1, ChronoUnit.MINUTES),
                                                        null)))
                        .andReturn();
        assertEquals(
                201,
                created.getResponse().getStatus(),
                "body=" + created.getResponse().getContentAsString());
        long id = readLong(created.getResponse().getContentAsString(), "id");

        mockMvc.perform(
                        get("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .param("userId", String.valueOf(delegate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));

        MvcResult revoked =
                mockMvc.perform(
                                delete("/api/v1/admin/delegations/" + id)
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("If-Match", "1"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.status").value("REVOKED"))
                        .andReturn();
        long version = readLong(revoked.getResponse().getContentAsString(), "version");
        assertEquals(2, version);

        // Idempotent replay of the same revocation returns the revoked state.
        mockMvc.perform(
                        delete("/api/v1/admin/delegations/" + id)
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
    }

    @Test
    void delegationToSelfIsSelfEscalationAndNeverPersists() throws Exception {
        int before = countDelegationsFor(PLATFORM_ADMIN);
        MvcResult selfDelegation =
                mockMvc.perform(
                                post("/api/v1/admin/delegations")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                delegationBody(
                                                        PLATFORM_ADMIN,
                                                        rootDept,
                                                        insertDatasource("ds-del-self"),
                                                        Instant.now().minus(1, ChronoUnit.MINUTES),
                                                        null)))
                        .andReturn();
        assertEquals(
                403,
                selfDelegation.getResponse().getStatus(),
                "body=" + selfDelegation.getResponse().getContentAsString());
        assertTrue(
                selfDelegation
                        .getResponse()
                        .getContentAsString()
                        .contains("SELF_PRIVILEGE_ESCALATION"),
                selfDelegation.getResponse().getContentAsString());
        assertEquals((long) before, (long) countDelegationsFor(PLATFORM_ADMIN));
    }

    @Test
    void nonPlatformAdminCannotCreateOrRevokeDelegations() throws Exception {
        long delegate = createUser("escalator", "越级者", rootDept);
        mockMvc.perform(
                        post("/api/v1/admin/delegations")
                                .header("X-Test-User-Id", delegate)
                                .contentType("application/json")
                                .content(
                                        delegationBody(
                                                delegate,
                                                rootDept,
                                                insertDatasource("ds-del-2"),
                                                Instant.now().minus(1, ChronoUnit.MINUTES),
                                                null)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
        mockMvc.perform(
                        delete("/api/v1/admin/delegations/1")
                                .header("X-Test-User-Id", delegate)
                                .header("If-Match", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
    }

    @Test
    void delegationRevocationRequiresIfMatchAndRejectsStaleVersions() throws Exception {
        long delegate = createUser("lock-admin", "锁管理员", rootDept);
        long datasource = insertDatasource("ds-del-3");
        long id = createDelegation(delegate, rootDept, datasource, null);

        mockMvc.perform(
                        delete("/api/v1/admin/delegations/" + id)
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        mockMvc.perform(
                        delete("/api/v1/admin/delegations/" + id)
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "99"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
    }

    @Test
    void delegatedAdminGrantsOnlyInsideScopeAndWindow() throws Exception {
        long childDept = createChildDepartment(rootDept, "范围子");
        long outside = createDepartment("范围外部");
        long delegate = createUser("scoped-admin", "范围内管理员", childDept);
        long targetInSubtree = createUser("target-in", "目标内", childDept);
        long targetOutside = createUser("target-out", "目标外", outside);
        long inScopeDatasource = insertDatasource("ds-del-in");
        long outOfScopeDatasource = insertDatasource("ds-del-out");
        createDelegation(delegate, childDept, inScopeDatasource, null);

        // In scope: subject inside the delegated subtree and resource inside the delegation.
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", delegate)
                                .contentType("application/json")
                                .content(
                                        grantBody(
                                                "USER",
                                                targetInSubtree,
                                                inScopeDatasource,
                                                "READ",
                                                "ALLOW")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // Subject outside the delegated subtree: rejected without any row.
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", delegate)
                                .contentType("application/json")
                                .content(
                                        grantBody(
                                                "USER",
                                                targetOutside,
                                                inScopeDatasource,
                                                "READ",
                                                "ALLOW")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        // Resource outside the delegated scope.
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", delegate)
                                .contentType("application/json")
                                .content(
                                        grantBody(
                                                "USER",
                                                targetInSubtree,
                                                outOfScopeDatasource,
                                                "READ",
                                                "ALLOW")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
    }

    @Test
    void expiredAndRevokedDelegationsGrantNothing() throws Exception {
        long delegate = createUser("expired-admin", "过期管理员", rootDept);
        long target = createUser("expired-target", "过期目标", rootDept);
        long datasource = insertDatasource("ds-del-exp");

        long expired =
                createDelegation(
                        delegate,
                        rootDept,
                        datasource,
                        Instant.now().minus(2, ChronoUnit.HOURS),
                        Instant.now().minus(1, ChronoUnit.HOURS));
        assertGrantDenied(delegate, target, datasource);
        revoke(expired);

        long live = createDelegation(delegate, rootDept, datasource, null);
        assertGrantAllowed(delegate, target, datasource);
        revoke(live);
        assertGrantDenied(delegate, target, datasource);
    }

    @Test
    void actionOutsideTheDelegatedListIsRejected() throws Exception {
        long delegate = createUser("grant-only-admin", "仅授予管理员", rootDept);
        long target = createUser("grant-only-target", "仅授予目标", rootDept);
        long datasource = insertDatasource("ds-del-act");
        long id = createDelegationWithActions(delegate, rootDept, datasource, "acl:grant");

        long grantId = createGrant(delegate, target, datasource);

        // The delegation covers acl:grant but not acl:revoke.
        mockMvc.perform(
                        delete("/api/v1/admin/acl/grants/" + grantId)
                                .header("X-Test-User-Id", delegate)
                                .header("If-Match", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        revoke(id);
    }

    // ------------------------------------------------------------------ helpers

    private void assertGrantAllowed(long delegate, long target, long datasource) throws Exception {
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", delegate)
                                .contentType("application/json")
                                .content(grantBody("USER", target, datasource, "READ", "ALLOW")))
                .andExpect(status().isCreated());
    }

    private void assertGrantDenied(long delegate, long target, long datasource) throws Exception {
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", delegate)
                                .contentType("application/json")
                                .content(grantBody("USER", target, datasource, "READ", "ALLOW")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
    }

    private long createDelegation(
            long delegate, long departmentRoot, long datasource, java.time.Instant validTo)
            throws Exception {
        return createDelegationWithActions(
                delegate,
                departmentRoot,
                datasource,
                java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.MINUTES),
                validTo,
                "acl:grant",
                "acl:revoke");
    }

    private long createDelegation(
            long delegate,
            long departmentRoot,
            long datasource,
            java.time.Instant validFrom,
            java.time.Instant validTo)
            throws Exception {
        return createDelegationWithActions(
                delegate,
                departmentRoot,
                datasource,
                validFrom,
                validTo,
                "acl:grant",
                "acl:revoke");
    }

    private long createDelegation(long delegate, long departmentRoot, long datasource)
            throws Exception {
        return createDelegation(
                delegate,
                departmentRoot,
                datasource,
                java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.MINUTES),
                null);
    }

    private long createDelegationWithActions(
            long delegate, long departmentRoot, long datasource, String... actions)
            throws Exception {
        return createDelegationWithActions(
                delegate,
                departmentRoot,
                datasource,
                java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.MINUTES),
                null,
                actions);
    }

    private long createDelegationWithActions(
            long delegate,
            long departmentRoot,
            long datasource,
            Instant validFrom,
            Instant validTo,
            String... actions)
            throws Exception {
        StringBuilder actionJson = new StringBuilder("[");
        for (int index = 0; index < actions.length; index++) {
            if (index > 0) {
                actionJson.append(',');
            }
            actionJson.append('"').append(actions[index]).append('"');
        }
        actionJson.append(']');
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/delegations")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                "{\"userId\":"
                                                        + delegate
                                                        + ",\"departmentRootId\":"
                                                        + departmentRoot
                                                        + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                                                        + datasource
                                                        + ",\"resourceGeneration\":1,\"allowedActions\":"
                                                        + actionJson
                                                        + ",\"validFrom\":\""
                                                        + validFrom
                                                        + "\",\"validTo\":"
                                                        + (validTo == null
                                                                ? "null"
                                                                : "\"" + validTo + "\"")
                                                        + "}"))
                        .andReturn();
        assertEquals(
                201,
                created.getResponse().getStatus(),
                "body=" + created.getResponse().getContentAsString());
        return readLong(created.getResponse().getContentAsString(), "id");
    }

    private void revoke(long delegationId) throws Exception {
        mockMvc.perform(
                        delete("/api/v1/admin/delegations/" + delegationId)
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .header("If-Match", "1"))
                .andExpect(status().isOk());
    }

    private long createGrant(long delegate, long target, long datasource) throws Exception {
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/acl/grants")
                                        .header("X-Test-User-Id", delegate)
                                        .contentType("application/json")
                                        .content(
                                                grantBody(
                                                        "USER",
                                                        target,
                                                        datasource,
                                                        "READ",
                                                        "ALLOW")))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readLong(created.getResponse().getContentAsString(), "id");
    }

    private int countDelegationsFor(long userId) throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/admin/delegations")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .param("userId", String.valueOf(userId)))
                        .andExpect(status().isOk())
                        .andReturn();
        String body = result.getResponse().getContentAsString();
        return body.split("\\\"id\\\":").length - 1;
    }

    private String delegationBody(
            long userId, long departmentRoot, long datasource, Instant validFrom, Instant validTo) {
        return "{\"userId\":"
                + userId
                + ",\"departmentRootId\":"
                + departmentRoot
                + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                + datasource
                + ",\"resourceGeneration\":1,\"allowedActions\":[\"acl:grant\",\"acl:revoke\"],"
                + "\"validFrom\":\""
                + validFrom
                + "\",\"validTo\":"
                + (validTo == null ? "null" : "\"" + validTo + "\"")
                + "}";
    }

    private String grantBody(
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
                + "\",\"reason\":\"委派测试\"}";
    }

    private long createDepartment(String name) throws Exception {
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/departments")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content("{\"name\":\"" + name + "\",\"enabled\":true}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readLong(created.getResponse().getContentAsString(), "id");
    }

    private long createChildDepartment(long parentId, String name) throws Exception {
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/departments")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                "{\"name\":\""
                                                        + name
                                                        + "\",\"parentId\":"
                                                        + parentId
                                                        + ",\"enabled\":true}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readLong(created.getResponse().getContentAsString(), "id");
    }

    private long createUser(String username, String displayName, long deptId) throws Exception {
        String requestBody =
                "{\"username\":\""
                        + username
                        + "\",\"displayName\":\""
                        + displayName
                        + "\",\"deptId\":"
                        + deptId
                        + ",\"enabled\":true}";
        System.err.println("[CREATE-USER] request=" + requestBody);
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/users")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(requestBody))
                        .andReturn();
        assertEquals(
                201,
                created.getResponse().getStatus(),
                "createUser body=" + created.getResponse().getContentAsString());
        return readLong(created.getResponse().getContentAsString(), "id");
    }

    private long insertDatasource(String code) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO datasource (code, name, engine, host, port, database_name,"
                            + " username, credential_cipher, key_id, tls_mode, pool_max, status,"
                            + " create_time, update_time, created_by, updated_by) VALUES ('"
                            + code
                            + "', '数据源', 'MYSQL', 'db.example.internal', 3306, 'biz',"
                            + " 'ro', X'00', 'k1', 'VERIFY_FULL', 4, 'ACTIVE', NOW(6), NOW(6), 1, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
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

    private Connection connect() throws SQLException {
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
                try {
                    Thread.sleep(2_000L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
        }
        throw last;
    }
}
