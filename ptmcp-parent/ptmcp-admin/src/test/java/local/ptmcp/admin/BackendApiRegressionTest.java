package local.ptmcp.admin;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import local.ptmcp.admin.platform.ClientAdminService;
import local.ptmcp.persistence.org.AclDirectory;
import local.ptmcp.persistence.org.AclGrantStore;
import local.ptmcp.persistence.org.OrgStore;
import local.ptmcp.persistence.org.ResourceIds;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

/** Regression cases over isolated MySQL and the real management filter/controller chain. */
@SpringBootTest(classes = AdminApplication.class, properties = "ptmcp.admin.test-principal=true")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class BackendApiRegressionTest {
    private static final long ADMIN = 9_000_001L;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired private MockMvc mvc;

    @Autowired
    @Qualifier("adminDataSource")
    private DataSource dataSource;

    @Test
    void denyRevocationMustNotRaiseDelegatedAdminsOwnRead() throws Exception {
        long dept = department("review-dept");
        long actor = user("review-delegate", dept);
        long datasource = datasource();
        long platform = platform("review-platform");
        delegation(actor, dept, datasource);
        ceiling(platform, datasource);
        seedGrant(actor, datasource, "ALLOW", "review allow");
        AclGrantStore.AclGrantRecord denied = seedGrant(actor, datasource, "DENY", "review deny");
        assertFalse(read(actor, platform, datasource, Instant.now()));

        MvcResult response =
                mvc.perform(
                                delete("/api/v1/admin/acl/grants/" + denied.id())
                                        .header("X-Test-User-Id", actor)
                                        .header("X-Test-Platform-Admin", "false")
                                        .header("If-Match", "1"))
                        .andReturn();

        boolean after = read(actor, platform, datasource, Instant.now());
        assertAll(
                () -> assertEquals(403, response.getResponse().getStatus()),
                () -> assertFalse(after, "revoking own DENY must not create effective READ"));
    }

    @Test
    void invalidReplacementMustLeaveOriginalGrantActive() throws Exception {
        long dept = department("review-dept");
        long target = user("review-target", dept);
        long datasource = datasource();
        AclGrantStore.AclGrantRecord original =
                seedGrant(target, datasource, "DENY", "review original");
        String replacement = grantBody(target, datasource, Instant.now().minusSeconds(30), "");

        MvcResult response =
                mvc.perform(
                                put("/api/v1/admin/acl/grants/" + original.id())
                                        .header("X-Test-User-Id", ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("If-Match", "1")
                                        .contentType("application/json")
                                        .content(replacement))
                        .andReturn();
        assertEquals(400, response.getResponse().getStatus());
        assertEquals(
                "ACTIVE",
                new AclGrantStore(dataSource).get(original.id()).status(),
                "a rejected replacement must not revoke the original rule");
    }

    @Test
    void confidentialCreationMustNotPersistOrReplaySecret() throws Exception {
        long platform = platform("review-platform");
        String key = UUID.randomUUID().toString();
        String body =
                "{\"name\":\"review client\",\"type\":\"CONFIDENTIAL\","
                        + "\"authMethods\":[\"client_secret_basic\"],"
                        + "\"redirectUris\":[\"https://review.example/callback\"],"
                        + "\"allowedScopes\":[\"mcp:catalog\"],\"status\":\"ACTIVE\"}";
        String route = "/api/v1/admin/platforms/" + platform + "/clients";
        MvcResult first =
                mvc.perform(
                                post(route)
                                        .header("X-Test-User-Id", ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body))
                        .andReturn();
        assertEquals(201, first.getResponse().getStatus());
        String secret = tree(first).path("clientSecret").asText();
        assertFalse(secret.isBlank());
        boolean storedPlaintext;
        try (Connection connection = dataSource.getConnection();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT response_cipher FROM api_idempotency"
                                        + " WHERE actor_id=? AND route_key=?")) {
            query.setLong(1, ADMIN);
            query.setString(2, "clients.create:" + platform);
            try (var result = query.executeQuery()) {
                assertTrue(result.next());
                storedPlaintext =
                        new String(result.getBytes(1), StandardCharsets.UTF_8).contains(secret);
            }
        }
        MvcResult replay =
                mvc.perform(
                                post(route)
                                        .header("X-Test-User-Id", ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body))
                        .andReturn();
        assertEquals(201, replay.getResponse().getStatus());
        boolean returnedAgain = replay.getResponse().getContentAsString().contains(secret);
        assertAll(
                () ->
                        assertFalse(
                                storedPlaintext, "credential appears in plaintext replay storage"),
                () ->
                        assertFalse(
                                returnedAgain,
                                "credential is returned again on idempotent replay"));
    }

    @Test
    void futureSelfGrantMustBeRejected() throws Exception {
        long dept = department("review-dept");
        long actor = user("review-delegate", dept);
        long datasource = datasource();
        long platform = platform("review-platform");
        delegation(actor, dept, datasource);
        ceiling(platform, datasource);
        Instant from = Instant.now().plusSeconds(300);
        MvcResult response =
                mvc.perform(
                                post("/api/v1/admin/acl/grants")
                                        .header("X-Test-User-Id", actor)
                                        .header("X-Test-Platform-Admin", "false")
                                        .contentType("application/json")
                                        .content(
                                                grantBody(actor, datasource, from, "future grant")))
                        .andReturn();
        boolean futureRead = read(actor, platform, datasource, from.plusSeconds(1));
        assertAll(
                () -> assertEquals(403, response.getResponse().getStatus()),
                () -> assertFalse(futureRead, "future activation must not bypass self-escalation"));
    }

    @Test
    void clientListMustProvideBoundedPageAndContinuation() throws Exception {
        long platform = platform("review-platform");
        ClientAdminService clients = new ClientAdminService(dataSource);
        for (int index = 0; index < 3; index++) {
            clients.create(
                    platform,
                    new ClientAdminService.ClientWrite(
                            "review-client-" + index,
                            "PUBLIC",
                            List.of("none"),
                            List.of("https://review.example/callback"),
                            List.of("mcp:catalog"),
                            null,
                            "ACTIVE"),
                    ADMIN,
                    UUID.randomUUID().toString());
        }
        MvcResult response =
                mvc.perform(
                                get("/api/v1/admin/platforms/" + platform + "/clients")
                                        .header("X-Test-User-Id", ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .param("pageSize", "1"))
                        .andReturn();
        assertEquals(200, response.getResponse().getStatus());
        JsonNode result = tree(response);
        assertAll(
                () -> assertTrue(result.path("items").size() <= 1, "pageSize must be honored"),
                () ->
                        assertTrue(
                                result.hasNonNull("nextCursor"),
                                "the remaining client must have a usable continuation"));
    }

    @Test
    void publicClientReplayMustNotFabricateSecretMarker() throws Exception {
        long platform = platform("review-platform");
        String key = UUID.randomUUID().toString();
        String body =
                "{\"name\":\"review public\",\"type\":\"PUBLIC\","
                        + "\"authMethods\":[\"none\"],"
                        + "\"redirectUris\":[\"https://review.example/callback\"],"
                        + "\"allowedScopes\":[\"mcp:catalog\"],\"status\":\"ACTIVE\"}";
        String route = "/api/v1/admin/platforms/" + platform + "/clients";
        MvcResult first =
                mvc.perform(
                                post(route)
                                        .header("X-Test-User-Id", ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body))
                        .andReturn();
        MvcResult replay =
                mvc.perform(
                                post(route)
                                        .header("X-Test-User-Id", ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", key)
                                        .contentType("application/json")
                                        .content(body))
                        .andReturn();
        assertAll(
                () -> assertEquals(201, first.getResponse().getStatus()),
                () -> assertEquals(201, replay.getResponse().getStatus()),
                () ->
                        assertTrue(
                                tree(first).path("clientSecret").isNull()
                                        || tree(first).path("clientSecret").isMissingNode(),
                                "public clients must not present a secret value"),
                () ->
                        assertTrue(
                                tree(replay).path("clientSecret").isNull()
                                        || tree(replay).path("clientSecret").isMissingNode(),
                                "public replays must not present a secret value"),
                () ->
                        assertFalse(
                                replay.getResponse()
                                        .getContentAsString()
                                        .contains(ClientAdminService.SECRET_ALREADY_CREATED),
                                "replay must not fabricate a secret marker for public clients"));
    }

    @Test
    void clientColumnsPersistCanonicalJsonArrays() throws Exception {
        long platform = platform("review-platform");
        mvc.perform(
                        post("/api/v1/admin/platforms/" + platform + "/clients")
                                .header("X-Test-User-Id", ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType("application/json")
                                .content(
                                        "{\"name\":\"review client\",\"type\":\"CONFIDENTIAL\","
                                                + "\"authMethods\":[\"client_secret_basic\"],"
                                                + "\"redirectUris\":[\"https://review.example/cb?allow=a,b\"],"
                                                + "\"allowedScopes\":[\"mcp:catalog\",\"mcp:query\"],"
                                                + "\"status\":\"ACTIVE\"}"))
                .andReturn();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT redirect_uris, scopes FROM oauth2_registered_client"
                                        + " WHERE platform_id = ?")) {
            query.setLong(1, platform);
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                String uris = row.getString(1);
                String scopes = row.getString(2);
                assertAll(
                        () -> assertTrue(uris.startsWith("["), "uris persist as a JSON array"),
                        () -> assertTrue(scopes.startsWith("["), "scopes persist as a JSON array"),
                        () ->
                                assertTrue(
                                        uris.contains("https://review.example/cb?allow=a,b"),
                                        "commas inside a redirect URI survive storage"),
                        () -> assertTrue(scopes.contains("mcp:query")));
            }
        }
    }

    private static final String MYSQL_IMAGE =
            System.getProperty(
                    "ptmcp.test.mysqlImage",
                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69");
    private static final MySQLContainer MYSQL = startMysql();
    private static final String DEPLOYMENT_KEY = randomSecret();

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static MySQLContainer startMysql() {
        MySQLContainer mysql =
                new MySQLContainer(MYSQL_IMAGE)
                        .withDatabaseName("backend_api_regression")
                        .withUsername("regression_user")
                        .withPassword(randomSecret())
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
                .load()
                .migrate();
        return mysql;
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ptmcp.admin.datasource.url", MYSQL::getJdbcUrl);
        registry.add("ptmcp.admin.datasource.username", MYSQL::getUsername);
        registry.add("ptmcp.admin.datasource.password", MYSQL::getPassword);
        registry.add("ptmcp.admin.credential-key", () -> DEPLOYMENT_KEY);
        registry.add("ptmcp.admin.audit-key", () -> DEPLOYMENT_KEY);
    }

    @AfterAll
    static void stopOwnedContainer() {
        MYSQL.stop();
    }

    @BeforeEach
    void resetOwnedData() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS=0");
            try {
                for (String table :
                        List.of(
                                "acl_grant",
                                "platform_scope",
                                "admin_delegation",
                                "admin_operation_log",
                                "api_idempotency",
                                "oauth2_registered_client",
                                "oauth_refresh_family",
                                "oauth2_authorization",
                                "sys_user_role",
                                "sys_user_profile",
                                "sys_user_login_name",
                                "sys_user_security",
                                "sys_role",
                                "sys_dept",
                                "datasource",
                                "platform_registry")) {
                    statement.execute("DELETE FROM " + table);
                }
            } finally {
                statement.execute("SET FOREIGN_KEY_CHECKS=1");
            }
            statement.execute(
                    "INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,status,del_flag,create_time,update_time) VALUES (9000001,0,'0','fixture admin','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            statement.execute(
                    "INSERT INTO sys_user_login_name (user_id,normalized_name,create_time,update_time) VALUES (9000001,'fixture_admin',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            statement.execute(
                    "INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,create_time,update_time) VALUES (9000001,9000001,'fixture admin','test-external','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            statement.execute(
                    "INSERT INTO sys_user_security (user_id,auth_version,identity_version,identity_state,platform_admin,create_time,update_time) VALUES (9000001,1,1,'READY',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        }
    }

    private long department(String name) {
        return new OrgStore(dataSource).createDepartment(null, name, true, ADMIN).id();
    }

    private long user(String name, long dept) throws Exception {
        long id =
                new OrgStore(dataSource)
                        .createUser(name, name, dept, "test-external", List.of(), true, ADMIN)
                        .id();
        // A valid authoritative actor fixture, independent of the action-role helper.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ready =
                        connection.prepareStatement(
                                "UPDATE sys_user_security SET identity_state='READY' WHERE user_id=?")) {
            ready.setLong(1, id);
            ready.executeUpdate();
        }
        return id;
    }

    private long datasource() throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO datasource (code,name,engine,host,port,database_name,username,credential_cipher,key_id,tls_mode,pool_max,status,create_time,update_time,created_by,updated_by) VALUES ('fixture-ds','fixture','MYSQL','fixture.example',3306,'fixture_db','fixture_ro',X'00','fixture-key','VERIFY_FULL',4,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),9000001,9000001)",
                                Statement.RETURN_GENERATED_KEYS)) {
            insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private long platform(String code) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO platform_registry (code,name,tier,status,catalog_mode,proof_mode,scope_version,security_version,create_time,update_time,created_by,updated_by) VALUES (?,'fixture',3,'ACTIVE','DISCOVER','NONE',1,1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),9000001,9000001)",
                                Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, code);
            insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private void delegation(long actor, long dept, long datasource) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO admin_delegation (user_id,department_root_id,resource_type,"
                                        + "resource_id,resource_generation,allowed_actions,valid_from,valid_to,"
                                        + "status,version,create_time,update_time,created_by,updated_by)"
                                        + " VALUES (?,?,'DATASOURCE',?,1,'[\"ptmcp:acl:read\"]',"
                                        + "UTC_TIMESTAMP(6)-INTERVAL 1 DAY,UTC_TIMESTAMP(6)+INTERVAL 1 DAY,"
                                        + "'ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?)")) {
            insert.setLong(1, actor);
            insert.setLong(2, dept);
            insert.setLong(3, datasource);
            insert.setLong(4, ADMIN);
            insert.setLong(5, ADMIN);
            insert.executeUpdate();
        }
    }

    private void ceiling(long platform, long datasource) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO platform_scope (platform_id,resource_type,resource_id,"
                                        + "resource_generation,action,include_descendants,create_time,"
                                        + "update_time,created_by,updated_by) VALUES (?,'DATASOURCE',?,1,"
                                        + "'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),?,?)")) {
            insert.setLong(1, platform);
            insert.setLong(2, datasource);
            insert.setLong(3, ADMIN);
            insert.setLong(4, ADMIN);
            insert.executeUpdate();
        }
    }

    private AclGrantStore.AclGrantRecord seedGrant(
            long subject, long datasource, String effect, String reason) {
        return new AclGrantStore(dataSource)
                .create(
                        new AclGrantStore.AclGrantRecord(
                                0,
                                "USER",
                                subject,
                                "DATASOURCE",
                                datasource,
                                1,
                                "READ",
                                effect,
                                true,
                                Instant.now().minusSeconds(60),
                                null,
                                "ACTIVE",
                                reason,
                                1),
                        ADMIN);
    }

    private boolean read(long user, long platform, long datasource, Instant at) {
        return new AclDirectory(dataSource)
                .preview(
                        user,
                        platform,
                        List.of(ResourceIds.encode(ResourceIds.Type.DATASOURCE, datasource)),
                        at)
                .getFirst()
                .read();
    }

    private long extraDatasource(long original) throws Exception {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert =
                        connection.prepareStatement(
                                "INSERT INTO datasource (code,name,engine,host,port,database_name,username,"
                                        + "credential_cipher,key_id,tls_mode,pool_max,status,create_time,"
                                        + "update_time,created_by,updated_by) SELECT 'ds-extra','extra',engine,"
                                        + "host,port,database_name,username,credential_cipher,key_id,tls_mode,"
                                        + "pool_max,status,create_time,update_time,created_by,updated_by"
                                        + " FROM datasource WHERE id=?",
                                PreparedStatement.RETURN_GENERATED_KEYS)) {
            insert.setLong(1, original);
            insert.executeUpdate();
            try (var keys = insert.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    @Test
    void privateKeyJwtRegistrationFailsClosedBeforeCreatingClient() throws Exception {
        long platform = platform("review-platform");
        String body =
                "{\"name\":\"review client\",\"type\":\"CONFIDENTIAL\",\"authMethods\":[\"private_key_jwt\"],\"jwksUri\":\"https://review.example/jwks\",\"redirectUris\":[\"https://review.example/callback\"],\"allowedScopes\":[\"mcp:catalog\"],\"status\":\"ACTIVE\"}";
        MvcResult result =
                mvc.perform(
                                post("/api/v1/admin/platforms/" + platform + "/clients")
                                        .header("X-Test-User-Id", ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType("application/json")
                                        .content(body))
                        .andReturn();
        assertEquals(400, result.getResponse().getStatus());
        try (Connection connection = dataSource.getConnection();
                Statement query = connection.createStatement();
                var rows = query.executeQuery("SELECT COUNT(*) FROM oauth2_registered_client")) {
            assertTrue(rows.next());
            assertEquals(0, rows.getInt(1));
        }
    }

    @Test
    void successfulGrantUpdateKeepsIdentityCreatorAndAdvancesVersion() throws Exception {
        long dept = department("review-dept");
        long subject = user("review-subject", dept);
        long editor = user("review-editor", dept);
        // S2: 管理资格来自独立布尔列，不再需要能力码。
        try (Connection connection = dataSource.getConnection();
                PreparedStatement flip =
                        connection.prepareStatement(
                                "UPDATE sys_user_security SET platform_admin=1 WHERE user_id=?")) {
            flip.setLong(1, editor);
            assertEquals(1, flip.executeUpdate());
        }
        long datasource = datasource();
        var original = seedGrant(subject, datasource, "DENY", "original rule");
        MvcResult result =
                mvc.perform(
                                put("/api/v1/admin/acl/grants/" + original.id())
                                        .header("X-Test-User-Id", editor)
                                        .header("X-Test-Platform-Admin", "true")
                                        .header("If-Match", "1")
                                        .contentType("application/json")
                                        .content(
                                                grantBody(
                                                        subject,
                                                        datasource,
                                                        Instant.now().minusSeconds(30),
                                                        "updated rule")))
                        .andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertAll(
                () -> assertEquals(original.id(), tree(result).path("id").asLong()),
                () -> assertEquals(2, tree(result).path("version").asLong()));
        try (Connection connection = dataSource.getConnection();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT created_by FROM acl_grant WHERE id=?")) {
            query.setLong(1, original.id());
            try (var row = query.executeQuery()) {
                assertTrue(row.next());
                assertEquals(ADMIN, row.getLong(1));
            }
        }
    }

    @Test
    void anAuditWriteFailureRollsBackGrantAndPolicyEpoch() throws Exception {
        long dept = department("review-dept");
        long subject = user("review-subject", dept);
        long datasource = datasource();
        var original = seedGrant(subject, datasource, "DENY", "original rule");
        long epoch;
        try (Connection connection = dataSource.getConnection();
                Statement query = connection.createStatement();
                var row =
                        query.executeQuery(
                                "SELECT policy_epoch FROM sys_security_state WHERE singleton_id=1")) {
            assertTrue(row.next());
            epoch = row.getLong(1);
        }
        try (Connection connection = dataSource.getConnection();
                Statement constraint = connection.createStatement()) {
            constraint.execute(
                    "ALTER TABLE admin_operation_log ADD CONSTRAINT reject_acl_update CHECK (action <> 'ACL_UPDATE')");
            try {
                MvcResult result =
                        mvc.perform(
                                        put("/api/v1/admin/acl/grants/" + original.id())
                                                .header("X-Test-User-Id", ADMIN)
                                                .header("X-Test-Platform-Admin", "true")
                                                .header("If-Match", "1")
                                                .contentType("application/json")
                                                .content(
                                                        grantBody(
                                                                subject,
                                                                datasource,
                                                                Instant.now().minusSeconds(30),
                                                                "updated rule")))
                                .andReturn();
                assertEquals(503, result.getResponse().getStatus());
                var unchanged = new AclGrantStore(dataSource).get(original.id());
                assertAll(
                        () -> assertEquals("ACTIVE", unchanged.status()),
                        () -> assertEquals("DENY", unchanged.effect()),
                        () -> assertEquals(1, unchanged.version()));
                try (var row =
                        constraint.executeQuery(
                                "SELECT policy_epoch FROM sys_security_state WHERE singleton_id=1")) {
                    assertTrue(row.next());
                    assertEquals(epoch, row.getLong(1));
                }
            } finally {
                constraint.execute("ALTER TABLE admin_operation_log DROP CHECK reject_acl_update");
            }
        }
    }

    private static String grantBody(long subject, long datasource, Instant from, String reason) {
        return "{\"subjectType\":\"USER\",\"subjectId\":"
                + subject
                + ",\"resourceType\":\"DATASOURCE\",\"resourceId\":"
                + datasource
                + ",\"resourceGeneration\":1,\"action\":\"READ\",\"effect\":\"ALLOW\","
                + "\"includeDescendants\":true,\"validFrom\":\""
                + from
                + "\",\"validTo\":\""
                + from.plusSeconds(600)
                + "\",\"reason\":\""
                + reason
                + "\"}";
    }

    private static JsonNode tree(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
