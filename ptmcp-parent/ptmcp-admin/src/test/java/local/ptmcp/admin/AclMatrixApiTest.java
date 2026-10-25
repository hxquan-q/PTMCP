package local.ptmcp.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.junit.jupiter.api.AfterEach;
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
import java.util.Base64;

/**
 * The ACL grant matrix (api-spec §5): persistent-layer filtering, effect/window/version/generation
 * display, delete-and-recreate staleness, inheritance, Deny override, subject changes and
 * delegated-admin scoping (IDOR).
 */
@SpringBootTest(
        classes = local.ptmcp.admin.AdminApplication.class,
        properties = "ptmcp.admin.test-principal=true")
@AutoConfigureMockMvc
class AclMatrixApiTest {
    private static final String MYSQL_IMAGE =
            "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69";
    private static final long PLATFORM_ADMIN = 1L;

    private static final MySQLContainer MYSQL = startMysql();

    @Autowired private MockMvc mockMvc;

    private long rootDept;
    private long childDept;
    private long outsideDept;
    private long delegateUser;
    private long targetUser;
    private long datasourceA;
    private long datasourceB;
    private long namespaceId;
    private long objectId;
    private long platformId;

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
                        .withDatabaseName("acl_matrix_test")
                        .withUsername("matrix_test")
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

    @BeforeEach
    void seedFixtures() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table :
                    new String[] {
                        "acl_grant",
                        "admin_delegation",
                        "admin_operation_log",
                        "sys_user_role",
                        "sys_user_profile",
                        "sys_user_login_name",
                        "sys_role_permission",
                        "sys_role",
                        "sys_dept",
                        "resource_object",
                        "resource_namespace",
                        "schema_snapshot",
                        "datasource",
                        "platform_scope",
                        "platform_registry",
                        "api_idempotency"
                    }) {
                statement.execute("DELETE FROM " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
        rootDept = createDepartment("矩阵根");
        createUser("matrix-operator", "矩阵操作员", rootDept);
        childDept = createChildDepartment(rootDept, "矩阵子部门");
        outsideDept = createDepartment("矩阵外部");
        delegateUser = createUser("matrix-delegate", "矩阵委派员", childDept);
        targetUser = createUser("matrix-target", "矩阵目标", childDept);
        datasourceA = insertDatasource("ds-matrix-a");
        datasourceB = insertDatasource("ds-matrix-b");
        platformId = insertPlatform("matrix-plat");
        namespaceId = insertNamespace(datasourceA, "ns-a");
        objectId = insertObject(namespaceId, "obj-a");
        seedPlatformScope(platformId, datasourceA);
    }

    @AfterEach
    void cleanGrants() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM acl_grant");
            statement.execute("DELETE FROM admin_operation_log");
            statement.execute("DELETE FROM admin_delegation");
        }
    }

    @Test
    void matrixDisplaysEffectWindowVersionGenerationAndFiltersInSql() throws Exception {
        createGrant("USER", targetUser, datasourceA, "META", "ALLOW");
        createGrant("USER", targetUser, datasourceA, "READ", "DENY");

        MvcResult all = matrix(request -> request);
        String body = all.getResponse().getContentAsString();
        assertTrue(body.contains("\"effect\":\"ALLOW\""), body);
        assertTrue(body.contains("\"effect\":\"DENY\""), body);
        assertTrue(body.contains("\"stale\":false"), body);
        assertTrue(body.contains("\"liveGeneration\":1"), body);
        assertTrue(body.contains("\"resourceGeneration\":1"), body);
        assertTrue(body.contains("validFrom"));
        assertEquals(2, countRows(body));

        // Effect filter runs in the query: ALLOW-only answer has no DENY rows.
        MvcResult allowOnly = matrix(request -> request.param("effect", "ALLOW"));
        String allowBody = allowOnly.getResponse().getContentAsString();
        assertTrue(allowBody.contains("\"effect\":\"ALLOW\""));
        assertFalse(allowBody.contains("\"effect\":\"DENY\""));

        // Status filter: REVOKED is empty right now.
        MvcResult revoked = matrix(request -> request.param("status", "REVOKED"));
        assertEquals("[]", revoked.getResponse().getContentAsString());

        // Subject filter pins rows to one subject.
        MvcResult subjectRows =
                matrix(request -> request.param("subjectId", String.valueOf(targetUser)));
        assertTrue(
                subjectRows
                        .getResponse()
                        .getContentAsString()
                        .contains("\"subjectId\":" + targetUser));

        // Resource filter pins rows to one resource.
        MvcResult resourceRows =
                matrix(request -> request.param("resourceId", String.valueOf(datasourceB)));
        assertEquals("[]", resourceRows.getResponse().getContentAsString());
    }

    @Test
    void denyOverridesAllowAndSubjectChangesFlipDecisions() throws Exception {
        createGrant("USER", targetUser, datasourceA, "READ", "ALLOW");

        String before = previewBody();
        assertTrue(before.contains("\"read\":true"), before);

        // Subject change #1: a DENY flips the decision for the same subject/resource/action.
        createGrant("USER", targetUser, datasourceA, "READ", "DENY");
        String after = previewBody();
        assertFalse(after.contains("\"read\":true"), after);
        assertTrue(after.contains("\"read\":false"), after);

        // Subject change #2: a role-based grant reaches the subject through the role.
        long roleId = createRole("matrix_role");
        grantToRole(roleId, datasourceA, "META");
        MvcResult result =
                mockMvc.perform(
                                put("/api/v1/admin/users/" + targetUser + "/roles")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("If-Match", "1")
                                        .contentType("application/json")
                                        .content("{\"roleIds\":[" + roleId + "]}"))
                        .andExpect(status().isOk())
                        .andReturn();
        assertTrue(result.getResponse().getStatus() == 200);

        // READ is still denied by the USER deny; META arrives through the role grant — the
        // subject change flipped META while the READ deny keeps holding.
        String withRole = previewBody();
        assertTrue(withRole.contains("\"meta\":true"), withRole);
        assertTrue(withRole.contains("\"read\":false"), withRole);
    }

    @Test
    void descendantInheritanceCoversChildObjects() throws Exception {
        // Namespace-scoped grant with include_descendants covers the child object.
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO acl_grant (subject_type, subject_id, resource_type, resource_id,"
                            + " resource_generation, action, effect, include_descendants,"
                            + " valid_from, valid_to, status, reason, version, create_time,"
                            + " update_time, created_by, updated_by) VALUES ('USER',"
                            + targetUser
                            + ", 'NAMESPACE', "
                            + namespaceId
                            + ", 1, 'READ', 'ALLOW', 1, UTC_TIMESTAMP(6) - INTERVAL 1 MINUTE,"
                            + " NULL, 'ACTIVE', 'inheritance probe', 1, NOW(6), NOW(6), 1, 1)");
        }
        String body = previewObjectBody();
        assertTrue(body.contains("\"read\":true"), "inherited read must apply: " + body);
    }

    @Test
    void deleteAndRecreateMarksStaleAndDeniesPreview() throws Exception {
        createGrant("USER", targetUser, datasourceA, "READ", "ALLOW");
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("UPDATE datasource SET config_version = 2 WHERE id = " + datasourceA);
        }

        MvcResult stale =
                matrix(
                        request ->
                                request.param("resourceType", "DATASOURCE")
                                        .param("resourceId", String.valueOf(datasourceA))
                                        .param("stale", "true"));
        String staleBody = stale.getResponse().getContentAsString();
        assertTrue(staleBody.contains("\"stale\":true"), staleBody);
        assertTrue(staleBody.contains("\"liveGeneration\":2"), staleBody);
        assertTrue(staleBody.contains("\"resourceGeneration\":1"), staleBody);

        MvcResult fresh =
                matrix(
                        request ->
                                request.param("resourceType", "DATASOURCE")
                                        .param("resourceId", String.valueOf(datasourceA))
                                        .param("stale", "false"));
        assertEquals("[]", fresh.getResponse().getContentAsString());

        String preview = previewBody();
        assertFalse(preview.contains("\"read\":true"), "stale grant must not apply: " + preview);
    }

    @Test
    void delegatedAdminMatrixIsScopedAndIdorIsDenied() throws Exception {
        createGrant("USER", targetUser, datasourceA, "READ", "ALLOW");
        createGrant("USER", targetUser, datasourceB, "READ", "ALLOW");
        createDelegation(delegateUser, childDept, datasourceA);

        // Unscoped matrix for a delegated admin: denied (no cross-scope leak).
        mockMvc.perform(get("/api/v1/admin/acl/matrix").header("X-Test-User-Id", delegateUser))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        // Pinned to the covered resource: only that resource's rows.
        MvcResult scoped =
                mockMvc.perform(
                                get("/api/v1/admin/acl/matrix")
                                        .header("X-Test-User-Id", delegateUser)
                                        .param("resourceType", "DATASOURCE")
                                        .param("resourceId", String.valueOf(datasourceA)))
                        .andExpect(status().isOk())
                        .andReturn();
        String scopedBody = scoped.getResponse().getContentAsString();
        assertTrue(scopedBody.contains("\"resourceId\":" + datasourceA), scopedBody);
        assertFalse(scopedBody.contains("\"resourceId\":" + datasourceB), "IDOR leak");

        // Pinned to an uncovered resource: denied without rows.
        mockMvc.perform(
                        get("/api/v1/admin/acl/matrix")
                                .header("X-Test-User-Id", delegateUser)
                                .param("resourceType", "DATASOURCE")
                                .param("resourceId", String.valueOf(datasourceB)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        // Platform admin sees everything.
        MvcResult all = matrix(request -> request);
        String allBody = all.getResponse().getContentAsString();
        assertTrue(allBody.contains("\"resourceId\":" + datasourceA));
        assertTrue(allBody.contains("\"resourceId\":" + datasourceB));
    }

    @Test
    void crossDepartmentGrantByDelegatedAdminIsDenied() throws Exception {
        long outsideTarget = createUser("outside-target", "外部目标", outsideDept);
        createDelegation(delegateUser, childDept, datasourceA);

        // A delegated admin cannot grant to a subject outside the delegated subtree.
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", delegateUser)
                                .contentType("application/json")
                                .content(
                                        grantBody(
                                                "USER",
                                                outsideTarget,
                                                datasourceA,
                                                "READ",
                                                "ALLOW")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult matrix(
            java.util.function.UnaryOperator<
                            org.springframework.test.web.servlet.request
                                    .MockHttpServletRequestBuilder>
                    customizer)
            throws Exception {
        return mockMvc.perform(
                        customizer.apply(
                                get("/api/v1/admin/acl/matrix")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")))
                .andReturn();
    }

    private String previewBody() throws Exception {
        return mockMvc.perform(
                        post("/api/v1/admin/acl/preview")
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(
                                        "{\"subjectUserId\":"
                                                + targetUser
                                                + ",\"platformId\":"
                                                + platformId
                                                + ",\"resourceIds\":["
                                                + ResourceIds.encode(
                                                        ResourceIds.Type.DATASOURCE, datasourceA)
                                                + "],\"at\":\""
                                                + Instant.now()
                                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private String previewObjectBody() throws Exception {
        return mockMvc.perform(
                        post("/api/v1/admin/acl/preview")
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(
                                        "{\"subjectUserId\":"
                                                + targetUser
                                                + ",\"platformId\":"
                                                + platformId
                                                + ",\"resourceIds\":["
                                                + ResourceIds.encode(
                                                        ResourceIds.Type.OBJECT, objectId)
                                                + "],\"at\":\""
                                                + Instant.now()
                                                + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private long createGrant(
            String subjectType, long subjectId, long datasource, String action, String effect)
            throws Exception {
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/acl/grants")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                grantBody(
                                                        subjectType,
                                                        subjectId,
                                                        datasource,
                                                        action,
                                                        effect)))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readLong(created.getResponse().getContentAsString(), "id");
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
                + "\",\"includeDescendants\":false,\"validFrom\":\""
                + Instant.now()
                + "\",\"reason\":\"矩阵测试\"}";
    }

    private void grantToRole(long roleId, long datasource, String action) throws Exception {
        mockMvc.perform(
                        post("/api/v1/admin/acl/grants")
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(
                                        "{\"subjectType\":\"ROLE\",\"subjectId\":"
                                                + roleId
                                                + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                                                + datasource
                                                + ",\"resourceGeneration\":1,\"action\":\""
                                                + action
                                                + "\",\"effect\":\"ALLOW\",\"includeDescendants\":false,"
                                                + "\"validFrom\":\""
                                                + Instant.now()
                                                + "\",\"reason\":\"角色授权\"}"))
                .andExpect(status().isCreated());
    }

    private long createRole(String key) throws Exception {
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/roles")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                "{\"key\":\""
                                                        + key
                                                        + "\",\"name\":\"矩阵角色\",\"enabled\":true,"
                                                        + "\"permissionCodes\":[\"acl:grant\"]}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readLong(created.getResponse().getContentAsString(), "id");
    }

    private long createDelegation(long delegate, long departmentRoot, long datasource)
            throws Exception {
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
                                                        + ",\"resourceGeneration\":1,"
                                                        + "\"allowedActions\":[\"acl:list\",\"acl:grant\",\"acl:revoke\"],"
                                                        + "\"validFrom\":\""
                                                        + Instant.now().minusSeconds(60)
                                                        + "\",\"validTo\":null}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return readLong(created.getResponse().getContentAsString(), "id");
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
        MvcResult created =
                mockMvc.perform(
                                post("/api/v1/admin/users")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                "{\"username\":\""
                                                        + username
                                                        + "\",\"displayName\":\""
                                                        + displayName
                                                        + "\",\"deptId\":"
                                                        + deptId
                                                        + ",\"enabled\":true}"))
                        .andExpect(status().isCreated())
                        .andReturn();
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

    private long insertPlatform(String code) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO platform_registry (code, name, tier, status, catalog_mode,"
                            + " proof_mode, scope_version, security_version, create_time,"
                            + " update_time, created_by, updated_by) VALUES ('"
                            + code
                            + "', '平台', 3, 'ACTIVE', 'DISCOVER', 'NONE', 1, 1, NOW(6), NOW(6),"
                            + " 1, 1)",
                    Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** Platform ceiling over the datasource subtree: META+READ with descendants. */
    private void seedPlatformScope(long platform, long datasource) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO platform_scope (platform_id, resource_type, resource_id,"
                            + " resource_generation, action, include_descendants, create_time,"
                            + " update_time, created_by, updated_by) VALUES ("
                            + platform
                            + ", 'DATASOURCE', "
                            + datasource
                            + ", 1, 'META', 1, NOW(6), NOW(6), 1, 1)");
            statement.execute(
                    "INSERT INTO platform_scope (platform_id, resource_type, resource_id,"
                            + " resource_generation, action, include_descendants, create_time,"
                            + " update_time, created_by, updated_by) VALUES ("
                            + platform
                            + ", 'DATASOURCE', "
                            + datasource
                            + ", 1, 'READ', 1, NOW(6), NOW(6), 1, 1)");
        }
    }

    private long insertNamespace(long datasourceId, String name) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO resource_namespace (datasource_id, catalog_raw, catalog_norm,"
                            + " schema_raw, schema_norm, generation, status, create_time,"
                            + " update_time) VALUES ("
                            + datasourceId
                            + ", '"
                            + name
                            + "-raw', '"
                            + name
                            + "', '"
                            + name
                            + "-schema', '"
                            + name
                            + "', 1, 'ACTIVE', NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private long insertObject(long namespaceId, String name) throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            // definition_hash is BINARY(32): UNHEX the SHA-2 hex digest.
            statement.execute(
                    "INSERT INTO resource_object (namespace_id, object_raw, object_norm,"
                            + " generation, kind, status, definition_hash, first_seen_at,"
                            + " last_seen_at, create_time, update_time) VALUES ("
                            + namespaceId
                            + ", '"
                            + name
                            + "', '"
                            + name
                            + "', 1, 'TABLE', 'ACTIVE', UNHEX(SHA2('"
                            + name
                            + "', 256)), NOW(6), NOW(6), NOW(6), NOW(6))",
                    Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    private static int countRows(String json) {
        return (json.split("\\\"id\\\":").length - 1);
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
