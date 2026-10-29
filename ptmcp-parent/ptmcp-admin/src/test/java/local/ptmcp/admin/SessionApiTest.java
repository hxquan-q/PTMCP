package local.ptmcp.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

import jakarta.servlet.http.Cookie;

import local.ptmcp.admin.session.AdminSessionService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * C6 session and personal-portal APIs against real MySQL. The login/session boundary is exercised
 * through MockMvc exactly as the browser would use it: pre-session CSRF, opaque cookies, rotation
 * and revocation. Personal data is seeded per test and cleaned without touching any other schema.
 */
@SpringBootTest(
        classes = AdminApplication.class,
        properties = {
            "ptmcp.admin.cookie-secure=true",
            "ptmcp.admin.identity-datasource.url=${ptmcp.admin.datasource.url}",
            "ptmcp.admin.identity-datasource.username=${ptmcp.admin.datasource.username}",
            "ptmcp.admin.identity-datasource.password=${ptmcp.admin.datasource.password}"
        })
@AutoConfigureMockMvc
class SessionApiTest {
    private static final String MYSQL_IMAGE =
            System.getProperty(
                    "ptmcp.test.mysqlImage",
                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69");
    private static final MySQLContainer MYSQL = startMysql();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final String PASSWORD = syntheticSecret();
    private static final String NEW_PASSWORD = syntheticSecret();
    private static final String WRONG_PASSWORD = "wrong-" + syntheticSecret();
    private static final long ALICE = 1001L;
    private static final long BOB = 1002L;

    private static String syntheticSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static MySQLContainer startMysql() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        MySQLContainer container =
                new MySQLContainer(MYSQL_IMAGE)
                        .withDatabaseName("session_api_test")
                        .withUsername("session_test")
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

    /** Transport-only retry for the known WSL2 Docker port-forward flake; assertions unchanged. */
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
        registry.add("ptmcp.mcp.url", () -> "https://mcp.example.test/mcp");
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
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS sys_user (user_id BIGINT PRIMARY KEY,user_name VARCHAR(30) NOT NULL UNIQUE,nick_name VARCHAR(30) NOT NULL,password VARCHAR(100) NOT NULL,status CHAR(1) NOT NULL,del_flag CHAR(1) NOT NULL DEFAULT '0')");
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table :
                    List.of(
                            "identity_password_change",
                            "sys_user_security",
                            "sys_user",
                            "api_idempotency",
                            "admin_operation_log",
                            "audit_log",
                            "personal_token",
                            "web_session_index",
                            "oauth_refresh_token",
                            "oauth_refresh_family",
                            "oauth2_authorization",
                            "oauth2_registered_client",
                            "resource_column",
                            "snapshot_object",
                            "business_dictionary",
                            "resource_object",
                            "resource_namespace",
                            "schema_snapshot",
                            "acl_grant",
                            "platform_scope",
                            "admin_delegation",
                            "sys_user_role",
                            "sys_user_profile",
                            "sys_user_login_name",
                            "sys_role_permission",
                            "sys_role",
                            "sys_dept",
                            "datasource",
                            "platform_registry")) {
                statement.execute("DELETE FROM " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    // ------------------------------------------------------------------ session

    @Test
    void sessionLifecycleIssuesHardenedCookiesAndRotatesCsrf() throws Exception {
        Fixture fixture = seedPortal();

        MvcResult csrf =
                mockMvc.perform(get("/api/v1/session/csrf"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.csrfToken").isNotEmpty())
                        .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                        .andReturn();
        String presessionHeader = cookieHeader(csrf, AdminSessionService.PRESESSION_COOKIE);
        assertNotNull(presessionHeader);
        for (String attribute : List.of("HttpOnly", "Secure", "SameSite=Lax", "Max-Age=")) {
            assertTrue(
                    presessionHeader.contains(attribute),
                    "pre-session cookie missing " + attribute + ": " + presessionHeader);
        }

        String presession = presessionHeader.split(";", 2)[0].split("=", 2)[1];
        String preToken = json(csrf).get("csrfToken").asString();
        String loginBody = "{\"username\":\"alice\",\"password\":\"" + PASSWORD + "\"}";

        mockMvc.perform(
                        post("/api/v1/session/login")
                                .cookie(
                                        new Cookie(
                                                AdminSessionService.PRESESSION_COOKIE, presession))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(loginBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        MvcResult login =
                mockMvc.perform(
                                post("/api/v1/session/login")
                                        .cookie(
                                                new Cookie(
                                                        AdminSessionService.PRESESSION_COOKIE,
                                                        presession))
                                        .header("X-CSRF-Token", preToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(loginBody))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.userId").value(ALICE))
                        .andExpect(jsonPath("$.displayName").value("爱丽丝"))
                        .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                        .andReturn();
        String sessionHeader = cookieHeader(login, AdminSessionService.SESSION_COOKIE);
        assertNotNull(sessionHeader);
        for (String attribute : List.of("HttpOnly", "Secure", "SameSite=Lax", "Max-Age=28800")) {
            assertTrue(
                    sessionHeader.contains(attribute),
                    "session cookie missing " + attribute + ": " + sessionHeader);
        }
        assertTrue(
                cookieHeader(login, AdminSessionService.PRESESSION_COOKIE).contains("Max-Age=0"));
        String session = sessionHeader.split(";", 2)[0].split("=", 2)[1];
        assertFalse(session.equals(presession));
        assertEquals(
                "1",
                scalar(
                        "SELECT COUNT(*) FROM web_session_index WHERE session_hash = UNHEX('"
                                + hex(sha256(session))
                                + "') AND kind = 'ADMIN' AND revoked_at IS NULL"
                                + " AND expires_at > UTC_TIMESTAMP(6)"));

        MvcResult rotated =
                mockMvc.perform(
                                get("/api/v1/session/csrf")
                                        .cookie(
                                                new Cookie(
                                                        AdminSessionService.SESSION_COOKIE,
                                                        session)))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andReturn();
        String sessionToken = json(rotated).get("csrfToken").asString();
        assertNotEquals(preToken, sessionToken);

        mockMvc.perform(
                        get("/api/v1/session/me")
                                .cookie(new Cookie(AdminSessionService.SESSION_COOKIE, session)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.userId").value(ALICE))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.department.name").value("门户部"))
                .andExpect(jsonPath("$.roles[0].name").value("门户用户"))
                .andExpect(jsonPath("$.permissions[0]").value("mcp:query"))
                .andExpect(
                        jsonPath("$.managedScopes[0]")
                                .value("DATASOURCE:" + fixture.datasourceId()));

        mockMvc.perform(
                        post("/api/v1/session/logout")
                                .cookie(new Cookie(AdminSessionService.SESSION_COOKIE, session))
                                .header("X-CSRF-Token", sessionToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loggedOut").value(true));

        mockMvc.perform(
                        get("/api/v1/session/me")
                                .cookie(new Cookie(AdminSessionService.SESSION_COOKIE, session)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        mockMvc.perform(get("/api/v1/session/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/v1/session/csrf").header("Sec-Fetch-Site", "cross-site"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void platformAdminSessionLosesScopeAfterMembershipRemoval() throws Exception {
        assertScopeRevokedBy("DELETE FROM sys_user_role WHERE user_id = " + ALICE);
    }

    @Test
    void platformAdminSessionLosesScopeAfterRoleDisabled() throws Exception {
        assertScopeRevokedBy("UPDATE sys_role SET status = '1' WHERE role_key = 'portal-user'");
    }

    @Test
    void platformAdminSessionLosesScopeAfterRoleSoftDeleted() throws Exception {
        assertScopeRevokedBy("UPDATE sys_role SET del_flag = '1' WHERE role_key = 'portal-user'");
    }

    @Test
    void platformAdminSessionLosesScopeAfterPermissionRemoval() throws Exception {
        assertScopeRevokedBy(
                "DELETE FROM sys_role_permission WHERE permission_code = 'ptmcp:platform:scope'");
    }

    @Test
    void platformAdminScopeGrantedAfterLoginAppliesWithoutRenewingSession() throws Exception {
        seedPortal();
        Session session = login("bob", PASSWORD);
        String expiresAt = assertSessionScope(session, false, null);
        String index = sessionIndexSnapshot(BOB);
        long roleId =
                Long.parseLong(
                        scalar("SELECT role_id FROM sys_role WHERE role_key = 'portal-user'"));
        addUserRole(BOB, roleId);
        assertSessionScope(session, true, expiresAt);
        assertEquals(index, sessionIndexSnapshot(BOB));
        mockMvc.perform(
                        post("/api/v1/session/logout")
                                .cookie(session.cookie())
                                .header("X-CSRF-Token", session.csrf()))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminScopeLookupFailureRejectsBeforeManagementWrite() throws Exception {
        seedPortal();
        Session session = login("alice", PASSWORD);
        String index = sessionIndexSnapshot(ALICE);
        String operations = scalar("SELECT COUNT(*) FROM admin_operation_log");
        executeSql("RENAME TABLE sys_role_permission TO session_test_unavailable_permission");
        try {
            MvcResult result =
                    mockMvc.perform(
                                    post("/api/v1/admin/departments")
                                            .cookie(session.cookie())
                                            .header("X-CSRF-Token", session.csrf())
                                            .header("Idempotency-Key", UUID.randomUUID().toString())
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    "{\"name\":\"scope_failure_probe\",\"enabled\":true}"))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code").value("SECURITY_STATE_UNAVAILABLE"))
                            .andExpect(jsonPath("$.retryable").value(true))
                            .andExpect(header().string("Cache-Control", "no-store"))
                            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                            .andExpect(header().exists("X-Request-Id"))
                            .andReturn();
            assertFalse(result.getResponse().getContentAsString().contains("sys_role_permission"));
            assertFalse(result.getResponse().getContentAsString().contains("SQLException"));
            assertEquals(
                    "0",
                    scalar(
                            "SELECT COUNT(*) FROM sys_dept WHERE dept_name = 'scope_failure_probe'"));
            assertEquals(operations, scalar("SELECT COUNT(*) FROM admin_operation_log"));
            assertEquals(index, sessionIndexSnapshot(ALICE));
        } finally {
            executeSql("RENAME TABLE session_test_unavailable_permission TO sys_role_permission");
        }
        assertSessionScope(session, true, null);
    }

    private void assertScopeRevokedBy(String mutation) throws Exception {
        seedPortal();
        Session session = login("alice", PASSWORD);
        String expiresAt = assertSessionScope(session, true, null);
        String index = sessionIndexSnapshot(ALICE);
        executeSql(mutation);
        assertSessionScope(session, false, expiresAt);
        assertEquals(index, sessionIndexSnapshot(ALICE));
        mockMvc.perform(
                        post("/api/v1/session/logout")
                                .cookie(session.cookie())
                                .header("X-CSRF-Token", session.csrf()))
                .andExpect(status().isOk());
    }

    private String assertSessionScope(Session session, boolean expected, String originalExpiresAt)
            throws Exception {
        MvcResult me =
                mockMvc.perform(get("/api/v1/session/me").cookie(session.cookie()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.platformAdmin").value(expected))
                        .andExpect(header().doesNotExist("Set-Cookie"))
                        .andReturn();
        JsonNode data = json(me);
        assertEquals(
                expected, data.get("permissions").toString().contains("\"ptmcp:platform:scope\""));
        String expiresAt = data.get("expiresAt").asString();
        if (originalExpiresAt != null) {
            assertEquals(originalExpiresAt, expiresAt);
        }
        MvcResult management =
                mockMvc.perform(get("/api/v1/admin/roles").cookie(session.cookie()))
                        .andExpect(expected ? status().isOk() : status().isForbidden())
                        .andReturn();
        if (!expected) {
            assertEquals("MANAGEMENT_SCOPE_DENIED", json(management).get("code").asString());
        }
        return expiresAt;
    }

    private static String sessionIndexSnapshot(long userId) throws SQLException {
        return scalar(
                "SELECT CONCAT_WS('|', HEX(session_hash), auth_version, version,"
                        + " issued_at, expires_at, revoked_at) FROM web_session_index"
                        + " WHERE kind = 'ADMIN' AND user_id = "
                        + userId);
    }

    private static void executeSql(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void aHostIdCollisionCannotAuthenticateAnotherLoginName() throws Exception {
        seedPortal();
        try (Connection connection = connect();
                PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE sys_user SET user_name='eve' WHERE user_id=?")) {
            update.setLong(1, BOB);
            assertEquals(1, update.executeUpdate());
        }
        assertBobLoginRejectedByIdentityBinding();
    }

    @Test
    void anUnboundIdentityCannotUseAnIdenticallyNamedHostAccount() throws Exception {
        seedPortal();
        try (Connection connection = connect();
                PreparedStatement update =
                        connection.prepareStatement(
                                "UPDATE sys_user_security SET identity_state='UNBOUND' WHERE user_id=?")) {
            update.setLong(1, BOB);
            assertEquals(1, update.executeUpdate());
        }
        assertBobLoginRejectedByIdentityBinding();
    }

    private void assertBobLoginRejectedByIdentityBinding() throws Exception {
        MvcResult csrf =
                mockMvc.perform(get("/api/v1/session/csrf")).andExpect(status().isOk()).andReturn();
        String presession =
                cookieHeader(csrf, AdminSessionService.PRESESSION_COOKIE)
                        .split(";", 2)[0]
                        .split("=", 2)[1];
        mockMvc.perform(
                        post("/api/v1/session/login")
                                .cookie(
                                        new Cookie(
                                                AdminSessionService.PRESESSION_COOKIE, presession))
                                .header("X-CSRF-Token", json(csrf).get("csrfToken").asString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"bob\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));
        try (Connection connection = connect();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM web_session_index WHERE user_id=?")) {
            query.setLong(1, BOB);
            try (ResultSet result = query.executeQuery()) {
                assertTrue(result.next());
                assertEquals(0, result.getInt(1));
            }
        }
    }

    @Test
    void loginFailuresAreUniformAndTheFifthFailureLocksTheAccount() throws Exception {
        seedPortal();
        MvcResult csrf =
                mockMvc.perform(get("/api/v1/session/csrf")).andExpect(status().isOk()).andReturn();
        String presession =
                cookieHeader(csrf, AdminSessionService.PRESESSION_COOKIE)
                        .split(";", 2)[0]
                        .split("=", 2)[1];
        String token = json(csrf).get("csrfToken").asString();
        Cookie cookie = new Cookie(AdminSessionService.PRESESSION_COOKIE, presession);
        String unknown = "{\"username\":\"ghost\",\"password\":\"" + PASSWORD + "\"}";
        String wrong = "{\"username\":\"alice\",\"password\":\"" + WRONG_PASSWORD + "\"}";

        mockMvc.perform(
                        post("/api/v1/session/login")
                                .cookie(cookie)
                                .header("X-CSRF-Token", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(unknown))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));

        for (int attempt = 1; attempt <= 5; attempt++) {
            mockMvc.perform(
                            post("/api/v1/session/login")
                                    .cookie(cookie)
                                    .header("X-CSRF-Token", token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(wrong))
                    .andExpect(
                            attempt == 5 ? status().isTooManyRequests() : status().isUnauthorized())
                    .andExpect(
                            jsonPath("$.code")
                                    .value(
                                            attempt == 5
                                                    ? "LOGIN_LOCKED"
                                                    : "AUTHENTICATION_FAILED"));
        }

        mockMvc.perform(
                        post("/api/v1/session/login")
                                .cookie(cookie)
                                .header("X-CSRF-Token", token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"alice\",\"password\":\""
                                                + PASSWORD
                                                + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("LOGIN_LOCKED"));
    }

    @Test
    void passwordChangeRevokesSessionTokensFamiliesAndAuthorizations() throws Exception {
        Fixture fixture = seedPortal();
        Session session = login("alice", PASSWORD);

        String tokenId =
                createToken(session, fixture.platformId(), "laptop", "[\"mcp:query\"]")
                        .get("metadata")
                        .get("id")
                        .asString();

        mockMvc.perform(
                        put("/api/v1/session/password")
                                .cookie(session.cookie())
                                .header("X-CSRF-Token", session.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"oldPassword\":\""
                                                + PASSWORD
                                                + "\",\"newPassword\":\""
                                                + NEW_PASSWORD
                                                + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reauthenticationRequired").value(true));

        mockMvc.perform(
                        get("/api/v1/session/me")
                                .cookie(
                                        new Cookie(
                                                AdminSessionService.SESSION_COOKIE,
                                                session.cookie().getValue())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        mockMvc.perform(
                        post("/api/v1/session/login")
                                .cookie(new Cookie(AdminSessionService.PRESESSION_COOKIE, "stale"))
                                .header("X-CSRF-Token", "stale")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"username\":\"alice\",\"password\":\""
                                                + PASSWORD
                                                + "\"}"))
                .andExpect(status().isForbidden());

        assertEquals("REVOKED", scalar("SELECT status FROM personal_token WHERE id = " + tokenId));
        assertEquals(
                "REVOKED",
                scalar(
                        "SELECT status FROM oauth_refresh_family WHERE id = '"
                                + fixture.familyId()
                                + "'"));
        assertEquals(
                "REVOKED",
                scalar(
                        "SELECT status FROM oauth2_authorization WHERE id = '"
                                + fixture.authorizationId()
                                + "'"));
        assertEquals(
                "0",
                scalar(
                        "SELECT COUNT(*) FROM web_session_index WHERE user_id = "
                                + ALICE
                                + " AND revoked_at IS NULL"));

        Session relogin = login("alice", NEW_PASSWORD);
        mockMvc.perform(get("/api/v1/session/me").cookie(relogin.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(ALICE));
    }

    @Test
    void aHostRenameCannotBeginSelfServicePasswordChange() throws Exception {
        seedPortal();
        Session session = login("alice", PASSWORD);
        String security =
                scalar(
                        "SELECT CONCAT(auth_version, ':', identity_version, ':', identity_state, ':', version) FROM sys_user_security WHERE user_id="
                                + ALICE);
        String profileVersion =
                scalar("SELECT version FROM sys_user_profile WHERE user_id=" + ALICE);
        String epoch = scalar("SELECT policy_epoch FROM sys_security_state WHERE singleton_id=1");
        String operations = scalar("SELECT COUNT(*) FROM identity_password_change");
        String activeSessions =
                scalar(
                        "SELECT COUNT(*) FROM web_session_index WHERE user_id="
                                + ALICE
                                + " AND revoked_at IS NULL");
        try (Connection connection = connect();
                PreparedStatement rename =
                        connection.prepareStatement(
                                "UPDATE sys_user SET user_name='renamed-host' WHERE user_id=?")) {
            rename.setLong(1, ALICE);
            assertEquals(1, rename.executeUpdate());
        }

        mockMvc.perform(
                        put("/api/v1/session/password")
                                .cookie(session.cookie())
                                .header("X-CSRF-Token", session.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        JSON.writeValueAsString(
                                                java.util.Map.of(
                                                        "oldPassword",
                                                        PASSWORD,
                                                        "newPassword",
                                                        NEW_PASSWORD))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_FAILED"));

        assertEquals(
                security,
                scalar(
                        "SELECT CONCAT(auth_version, ':', identity_version, ':', identity_state, ':', version) FROM sys_user_security WHERE user_id="
                                + ALICE));
        assertEquals(
                profileVersion,
                scalar("SELECT version FROM sys_user_profile WHERE user_id=" + ALICE));
        assertEquals(
                epoch, scalar("SELECT policy_epoch FROM sys_security_state WHERE singleton_id=1"));
        assertEquals(operations, scalar("SELECT COUNT(*) FROM identity_password_change"));
        assertEquals(
                activeSessions,
                scalar(
                        "SELECT COUNT(*) FROM web_session_index WHERE user_id="
                                + ALICE
                                + " AND revoked_at IS NULL"));
    }

    @Test
    void identityActionsRequireCsrfAndRejectCrossOrigin() throws Exception {
        seedPortal();
        Session admin = login("alice", PASSWORD);
        String uri = "/api/v1/admin/users/" + BOB + "/password";
        String body =
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "newPassword",
                                NEW_PASSWORD,
                                "reason",
                                "Synthetic recovery boundary"));
        mockMvc.perform(
                        put(uri).cookie(admin.cookie())
                                .header("If-Match", "1")
                                .header("Idempotency-Key", "reset-no-csrf")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(
                        put(uri).cookie(admin.cookie())
                                .header("If-Match", "1")
                                .header("X-CSRF-Token", admin.csrf())
                                .header("Origin", "https://attacker.example.test")
                                .header("Idempotency-Key", "reset-cross-origin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(
                        post(uri + "/retry")
                                .cookie(admin.cookie())
                                .header("If-Match", "1")
                                .header("X-CSRF-Token", admin.csrf())
                                .header("Sec-Fetch-Site", "cross-site")
                                .header("Idempotency-Key", "retry-cross-origin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        for (String invalidOrigin :
                java.util.List.of(
                        "null",
                        "https://localhost",
                        "http://localhost:8080",
                        "http://localhost/",
                        "http://user@localhost",
                        "http://localhost?query",
                        "http://localhost/#fragment")) {
            mockMvc.perform(
                            put(uri).cookie(admin.cookie())
                                    .header("If-Match", "1")
                                    .header("X-CSRF-Token", admin.csrf())
                                    .header("Origin", invalidOrigin)
                                    .header("Idempotency-Key", "reset-invalid-origin")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        }
        mockMvc.perform(
                        put(uri).cookie(admin.cookie())
                                .header("If-Match", "1")
                                .header("X-CSRF-Token", admin.csrf())
                                .header("Sec-Fetch-Site", "same-site")
                                .header("Origin", "http://localhost")
                                .header("Idempotency-Key", "reset-sibling-site")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        mockMvc.perform(
                        put(uri).cookie(admin.cookie())
                                .header("If-Match", "1")
                                .header("X-CSRF-Token", admin.csrf())
                                .header("Referer", "https://attacker.example.test/page")
                                .header("Idempotency-Key", "reset-other-referer")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        assertEquals(
                "1", scalar("SELECT auth_version FROM sys_user_security WHERE user_id=" + BOB));
        mockMvc.perform(
                        put(uri).cookie(admin.cookie())
                                .header("If-Match", "1")
                                .header("X-CSRF-Token", admin.csrf())
                                .header("Sec-Fetch-Site", "same-origin")
                                .header("Origin", "http://localhost")
                                .header("Idempotency-Key", "reset-same-origin")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
    }

    // ------------------------------------------------------------------ personal tokens

    @Test
    void personalTokensReturnPlaintextOnceAndStayOwnerScoped() throws Exception {
        Fixture fixture = seedPortal();
        Session alice = login("alice", PASSWORD);

        mockMvc.perform(
                        post("/api/v1/me/tokens")
                                .cookie(alice.cookie())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"no-csrf\",\"platformId\":"
                                                + fixture.platformId()
                                                + ",\"scopes\":[\"mcp:query\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        JsonNode created =
                createToken(
                        alice, fixture.platformId(), "laptop", "[\"mcp:catalog\",\"mcp:query\"]");
        String plaintext = created.get("token").asString();
        long tokenId = created.get("metadata").get("id").asLong();
        String prefix = created.get("metadata").get("prefix").asString();
        assertTrue(plaintext.startsWith("ptmcp_pat_"));
        assertTrue(plaintext.length() >= 53);
        assertEquals(plaintext.substring(0, 16), prefix);
        assertFalse(plaintext.equals(prefix));
        assertEquals("ACTIVE", created.get("metadata").get("status").asString());

        assertEquals(
                plaintext.substring(0, 16),
                scalar("SELECT token_prefix FROM personal_token WHERE id = " + tokenId));
        assertEquals(
                "1",
                scalar(
                        "SELECT COUNT(*) FROM personal_token WHERE id = "
                                + tokenId
                                + " AND token_hash = UNHEX('"
                                + hex(sha256(plaintext))
                                + "')"));
        assertFalse(
                scalar("SELECT HEX(token_hash) FROM personal_token WHERE id = " + tokenId)
                        .contains(plaintext));

        mockMvc.perform(get("/api/v1/me/tokens").param("status", "ACTIVE").cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(tokenId))
                .andExpect(jsonPath("$.items[0].prefix").value(prefix))
                .andExpect(jsonPath("$.items[0].token").doesNotExist());

        Session bob = login("bob", PASSWORD);
        mockMvc.perform(
                        delete("/api/v1/me/tokens/" + tokenId)
                                .cookie(bob.cookie())
                                .header("X-CSRF-Token", bob.csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(
                        delete("/api/v1/me/tokens/" + tokenId)
                                .cookie(alice.cookie())
                                .header("X-CSRF-Token", alice.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));
        mockMvc.perform(
                        delete("/api/v1/me/tokens/" + tokenId)
                                .cookie(alice.cookie())
                                .header("X-CSRF-Token", alice.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"));

        long tierOne = addPlatform("tier-one", 1, "DISCOVER", "MTLS");
        mockMvc.perform(
                        post("/api/v1/me/tokens")
                                .cookie(alice.cookie())
                                .header("X-CSRF-Token", alice.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"tier\",\"platformId\":"
                                                + tierOne
                                                + ",\"scopes\":[\"mcp:query\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));

        mockMvc.perform(
                        post("/api/v1/me/tokens")
                                .cookie(alice.cookie())
                                .header("X-CSRF-Token", alice.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"bad\",\"platformId\":"
                                                + fixture.platformId()
                                                + ",\"scopes\":[\"admin:all\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        post("/api/v1/me/tokens")
                                .cookie(alice.cookie())
                                .header("X-CSRF-Token", alice.csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"long\",\"platformId\":"
                                                + fixture.platformId()
                                                + ",\"scopes\":[\"mcp:query\"],"
                                                + "\"expiresAt\":\""
                                                + Instant.now().plusSeconds(200L * 24 * 3600)
                                                + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    // ------------------------------------------------------------------ catalog and history

    @Test
    void dictionaryHidesDeniedObjectsAndRequiresReadForDetail() throws Exception {
        Fixture fixture = seedPortal();
        Session alice = login("alice", PASSWORD);

        mockMvc.perform(
                        get("/api/v1/me/dictionary")
                                .param("platformId", Long.toString(fixture.platformId()))
                                .param("datasourceId", Long.toString(fixture.datasourceId()))
                                .cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].objectId").value(fixture.ordersId()))
                .andExpect(jsonPath("$.items[0].tableName").value("orders"))
                .andExpect(jsonPath("$.items[0].authorized").value(true))
                .andExpect(jsonPath("$.items[1].objectId").value(fixture.paymentsId()))
                .andExpect(jsonPath("$.items[1].authorized").value(false))
                .andExpect(jsonPath("$.items[1].metadataVisible").value(true));

        mockMvc.perform(
                        get("/api/v1/me/dictionary/" + fixture.ordersId())
                                .param("platformId", Long.toString(fixture.platformId()))
                                .cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tableName").value("orders"))
                .andExpect(jsonPath("$.tableComment").value("订单表"))
                .andExpect(jsonPath("$.dictionaryTitle").value("订单"))
                .andExpect(jsonPath("$.snapshotVersion").value(1))
                .andExpect(jsonPath("$.columns.length()").value(2))
                .andExpect(jsonPath("$.columns[0].name").value("id"))
                .andExpect(jsonPath("$.columns[0].primaryKey").value(true))
                .andExpect(jsonPath("$.columns[0].title").value("主键"))
                .andExpect(jsonPath("$.foreignKeysRestricted").value(true));

        mockMvc.perform(
                        get("/api/v1/me/dictionary/" + fixture.paymentsId())
                                .param("platformId", Long.toString(fixture.platformId()))
                                .cookie(alice.cookie()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("TABLE_NOT_AUTHORIZED"));

        mockMvc.perform(
                        get("/api/v1/me/dictionary/" + fixture.secretId())
                                .param("platformId", Long.toString(fixture.platformId()))
                                .cookie(alice.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        setCatalogMode(fixture.platformId(), "HIDE");
        mockMvc.perform(
                        get("/api/v1/me/dictionary")
                                .param("platformId", Long.toString(fixture.platformId()))
                                .param("datasourceId", Long.toString(fixture.datasourceId()))
                                .cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].objectId").value(fixture.ordersId()));

        mockMvc.perform(
                        get("/api/v1/me/access-context")
                                .param("level", "PLATFORM")
                                .cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(fixture.platformId()))
                .andExpect(jsonPath("$.items[0].catalogMode").value("HIDE"))
                .andExpect(
                        jsonPath("$.versions['platform:" + fixture.platformId() + "']")
                                .value("1/1"));

        mockMvc.perform(
                        get("/api/v1/me/access-context")
                                .param("level", "DATASOURCE")
                                .param("platformId", Long.toString(fixture.platformId()))
                                .cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(fixture.datasourceId()))
                .andExpect(jsonPath("$.items[0].engine").value("MYSQL"));

        mockMvc.perform(
                        get("/api/v1/me/access-context")
                                .param("level", "NAMESPACE")
                                .param("platformId", Long.toString(fixture.platformId()))
                                .param("datasourceId", Long.toString(fixture.datasourceId()))
                                .cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].displayName").value("biz"))
                .andExpect(jsonPath("$.items[0].schemaName").isEmpty());
    }

    @Test
    void historyIsOwnerScopedAndNeverReturnsRawSql() throws Exception {
        Fixture fixture = seedPortal();
        Session alice = login("alice", PASSWORD);

        mockMvc.perform(get("/api/v1/me/history").cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].requestId").value(fixture.requestId()))
                .andExpect(
                        jsonPath("$.items[0].sqlMasked").value("SELECT * FROM orders WHERE id = ?"))
                .andExpect(jsonPath("$.items[0].sqlRawCipher").doesNotExist())
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        String cursor =
                json(mockMvc.perform(
                                        get("/api/v1/me/history")
                                                .param("pageSize", "1")
                                                .cookie(alice.cookie()))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.items.length()").value(1))
                                .andReturn())
                        .get("nextCursor")
                        .asString();
        mockMvc.perform(
                        get("/api/v1/me/history")
                                .param("pageSize", "1")
                                .param("cursor", cursor)
                                .cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        mockMvc.perform(get("/api/v1/me/history/" + fixture.requestId()).cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(fixture.requestId()))
                .andExpect(jsonPath("$.sqlMasked").value("SELECT * FROM orders WHERE id = ?"))
                .andExpect(jsonPath("$.sqlRawCipher").doesNotExist())
                .andExpect(jsonPath("$.resourceRefs.datasourceId").value(fixture.datasourceId()));

        mockMvc.perform(
                        get("/api/v1/me/history/" + fixture.otherRequestId())
                                .cookie(alice.cookie()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(
                        get("/api/v1/me/history/" + fixture.requestId())
                                .cookie(alice.cookie())
                                .param("userId", Long.toString(BOB)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        get("/api/v1/me/history")
                                .param("from", Instant.now().plusSeconds(600).toString())
                                .cookie(alice.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void mcpConfigIsVersionUnverifiedAndAuthorizationRevocationIsIdempotent() throws Exception {
        Fixture fixture = seedPortal();
        Session alice = login("alice", PASSWORD);

        mockMvc.perform(
                        get("/api/v1/me/mcp-config")
                                .param("platformId", Long.toString(fixture.platformId()))
                                .param("clientType", "VSCODE")
                                .cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transport").value("streamable-http"))
                .andExpect(jsonPath("$.url").value("https://mcp.example.test/mcp"))
                .andExpect(jsonPath("$.authentication[0]").value("oauth"))
                .andExpect(jsonPath("$.validatedVersion").isEmpty())
                .andExpect(jsonPath("$.compatibilityStatus").value("UNVERIFIED"))
                .andExpect(jsonPath("$.configTemplate").isNotEmpty());

        mockMvc.perform(
                        get("/api/v1/me/mcp-config")
                                .param("platformId", Long.toString(fixture.platformId()))
                                .param("clientType", "UNKNOWN")
                                .cookie(alice.cookie()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(get("/api/v1/me/authorizations").cookie(alice.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(fixture.familyId()))
                .andExpect(jsonPath("$.items[0].clientName").value("VS Code"))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));

        mockMvc.perform(
                        delete("/api/v1/me/authorizations/" + fixture.otherFamilyId())
                                .cookie(alice.cookie())
                                .header("X-CSRF-Token", alice.csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(
                        delete("/api/v1/me/authorizations/" + fixture.familyId())
                                .cookie(alice.cookie())
                                .header("X-CSRF-Token", alice.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revoked").value(true));
        mockMvc.perform(
                        delete("/api/v1/me/authorizations/" + fixture.familyId())
                                .cookie(alice.cookie())
                                .header("X-CSRF-Token", alice.csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revoked").value(true));

        assertEquals(
                "REVOKED",
                scalar(
                        "SELECT status FROM oauth2_authorization WHERE family_id = '"
                                + fixture.familyId()
                                + "'"));
        assertEquals(
                "ACTIVE",
                scalar(
                        "SELECT status FROM oauth_refresh_family WHERE id = '"
                                + fixture.otherFamilyId()
                                + "'"));
    }

    // ------------------------------------------------------------------ seed

    private record Fixture(
            long platformId,
            long datasourceId,
            long namespaceId,
            long ordersId,
            long paymentsId,
            long secretId,
            String requestId,
            String otherRequestId,
            String familyId,
            String otherFamilyId,
            String authorizationId) {}

    private Fixture seedPortal() throws Exception {
        long deptId = addDepartment("门户部");
        long roleId = addRole("portal-user", "门户用户");
        addRolePermission(roleId, "mcp:query");
        addRolePermission(roleId, "ptmcp:platform:scope");
        addUser(ALICE, "alice", "爱丽丝", PASSWORD, deptId);
        addUser(BOB, "bob", "鲍勃", PASSWORD, deptId);
        addUserRole(ALICE, roleId);

        long platformId = addPlatform("portal", 3, "DISCOVER", "NONE");
        long datasourceId = addDatasource("ds-portal");
        long namespaceId = addNamespace(datasourceId, "biz", "", 1);
        long ordersId = addObject(namespaceId, "orders", "TABLE", 1);
        long paymentsId = addObject(namespaceId, "payments", "TABLE", 1);
        long secretId = addObject(namespaceId, "secret", "TABLE", 1);
        long snapshotId = addSnapshot(datasourceId, 1);
        addSnapshotObject(snapshotId, ordersId, "订单表");
        addSnapshotObject(snapshotId, paymentsId, "支付表");
        addSnapshotObject(snapshotId, secretId, "机密表");
        addColumn(snapshotId, ordersId, 0, "id", "BIGINT", -5, false, null, null, "主键", true, null);
        addColumn(
                snapshotId,
                ordersId,
                1,
                "total",
                "DECIMAL",
                3,
                true,
                12,
                2,
                null,
                false,
                "{\"name\":\"fk_total\",\"columns\":[\"total\"]}");
        addColumn(
                snapshotId, paymentsId, 0, "id", "BIGINT", -5, false, null, null, null, true, null);
        addColumn(snapshotId, secretId, 0, "id", "BIGINT", -5, false, null, null, null, true, null);
        addDictionary(ordersId, "", "订单", "已支付订单");
        addDictionary(ordersId, "id", "主键", "订单主键");
        setActiveSnapshot(datasourceId, snapshotId);

        addPlatformScope(platformId, "DATASOURCE", datasourceId, "META", true);
        addPlatformScope(platformId, "DATASOURCE", datasourceId, "READ", true);
        addGrant(ALICE, "DATASOURCE", datasourceId, "READ", "ALLOW", true);
        addGrant(ALICE, "OBJECT", paymentsId, "READ", "DENY", false);
        addGrant(ALICE, "OBJECT", secretId, "META", "DENY", false);

        String requestId = UUID.randomUUID().toString();
        String otherRequestId = UUID.randomUUID().toString();
        addAudit(
                requestId,
                ALICE,
                platformId,
                datasourceId,
                "execute_readonly_sql",
                "SELECT * FROM orders WHERE id = ?",
                1);
        addAudit(
                UUID.randomUUID().toString(),
                ALICE,
                platformId,
                datasourceId,
                "list_databases_and_tables",
                "SELECT 1",
                2);
        addAudit(
                otherRequestId,
                BOB,
                platformId,
                datasourceId,
                "execute_readonly_sql",
                "SELECT * FROM secret WHERE id = ?",
                1);

        addOauthClient();
        String familyId = UUID.randomUUID().toString();
        String authorizationId = "auth-" + familyId.substring(0, 8);
        addAuthorization(authorizationId, ALICE, familyId);
        addFamily(familyId, authorizationId, ALICE, platformId);
        String otherFamilyId = UUID.randomUUID().toString();
        String otherAuthorizationId = "auth-" + otherFamilyId.substring(0, 8);
        addAuthorization(otherAuthorizationId, BOB, otherFamilyId);
        addFamily(otherFamilyId, otherAuthorizationId, BOB, platformId);

        return new Fixture(
                platformId,
                datasourceId,
                namespaceId,
                ordersId,
                paymentsId,
                secretId,
                requestId,
                otherRequestId,
                familyId,
                otherFamilyId,
                authorizationId);
    }

    // ------------------------------------------------------------------ helpers

    private record Session(Cookie cookie, String csrf) {}

    private Session login(String username, String password) throws Exception {
        MvcResult csrf =
                mockMvc.perform(get("/api/v1/session/csrf")).andExpect(status().isOk()).andReturn();
        String presession =
                cookieHeader(csrf, AdminSessionService.PRESESSION_COOKIE)
                        .split(";", 2)[0]
                        .split("=", 2)[1];
        String preToken = json(csrf).get("csrfToken").asString();
        MvcResult login =
                mockMvc.perform(
                                post("/api/v1/session/login")
                                        .cookie(
                                                new Cookie(
                                                        AdminSessionService.PRESESSION_COOKIE,
                                                        presession))
                                        .header("X-CSRF-Token", preToken)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"username\":\""
                                                        + username
                                                        + "\",\"password\":\""
                                                        + password
                                                        + "\"}"))
                        .andExpect(status().isOk())
                        .andReturn();
        String sessionValue =
                cookieHeader(login, AdminSessionService.SESSION_COOKIE)
                        .split(";", 2)[0]
                        .split("=", 2)[1];
        Cookie cookie = new Cookie(AdminSessionService.SESSION_COOKIE, sessionValue);
        MvcResult rotated =
                mockMvc.perform(get("/api/v1/session/csrf").cookie(cookie))
                        .andExpect(status().isOk())
                        .andReturn();
        return new Session(cookie, json(rotated).get("csrfToken").asString());
    }

    private JsonNode createToken(Session session, long platformId, String name, String scopes)
            throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/me/tokens")
                                        .cookie(session.cookie())
                                        .header("X-CSRF-Token", session.csrf())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\""
                                                        + name
                                                        + "\",\"platformId\":"
                                                        + platformId
                                                        + ",\"scopes\":"
                                                        + scopes
                                                        + "}"))
                        .andExpect(status().isCreated())
                        .andReturn();
        return json(result);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String cookieHeader(MvcResult result, String name) {
        List<String> headers = new ArrayList<>(result.getResponse().getHeaders("Set-Cookie"));
        for (String header : headers) {
            if (header.startsWith(name + "=")) {
                return header;
            }
        }
        return null;
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String scalar(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }

    private static void setCatalogMode(long platformId, String mode) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE platform_registry SET catalog_mode = ? WHERE id = ?")) {
            statement.setString(1, mode);
            statement.setLong(2, platformId);
            statement.executeUpdate();
        }
    }

    private static long addDepartment(String name) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO sys_dept (dept_name, ancestors, create_time,"
                                        + " update_time) VALUES (?, '', ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, name);
            statement.setObject(2, now());
            statement.setObject(3, now());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long addRole(String key, String name) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO sys_role (role_key, role_name, create_time,"
                                        + " update_time) VALUES (?, ?, ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, key);
            statement.setString(2, name);
            statement.setObject(3, now());
            statement.setObject(4, now());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static void addRolePermission(long roleId, String code) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO sys_role_permission (role_id, permission_code,"
                                        + " create_time, update_time) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, roleId);
            statement.setString(2, code);
            statement.setObject(3, now());
            statement.setObject(4, now());
            statement.executeUpdate();
        }
    }

    private static void addUser(
            long userId, String username, String displayName, String password, long deptId)
            throws SQLException {
        String hash = new BCryptPasswordEncoder().encode(password);
        try (Connection connection = connect()) {
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO sys_user (user_id,user_name,nick_name,password,status,del_flag) VALUES (?,?,?,?,'0','0')")) {
                insert.setLong(1, userId);
                insert.setString(2, username);
                insert.setString(3, displayName);
                insert.setString(4, hash);
                insert.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO sys_user_profile (user_id, dept_id, display_name,"
                                    + " password_hash, status, version, create_time, update_time)"
                                    + " VALUES (?, ?, ?, ?, 'ACTIVE', 1, ?, ?)")) {
                statement.setLong(1, userId);
                statement.setLong(2, deptId);
                statement.setString(3, displayName);
                statement.setString(4, hash);
                statement.setObject(5, now());
                statement.setObject(6, now());
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO sys_user_login_name (user_id, normalized_name,"
                                    + " create_time, update_time) VALUES (?, ?, ?, ?)")) {
                statement.setLong(1, userId);
                statement.setString(2, username);
                statement.setObject(3, now());
                statement.setObject(4, now());
                statement.executeUpdate();
            }
            try (PreparedStatement insert =
                    connection.prepareStatement(
                            "INSERT INTO sys_user_security (user_id,auth_version,identity_version,identity_state,version,create_time,update_time) VALUES (?,1,1,'READY',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
                insert.setLong(1, userId);
                insert.executeUpdate();
            }
        }
    }

    private static void addUserRole(long userId, long roleId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO sys_user_role (user_id, role_id, create_time,"
                                        + " update_time) VALUES (?, ?, ?, ?)")) {
            statement.setLong(1, userId);
            statement.setLong(2, roleId);
            statement.setObject(3, now());
            statement.setObject(4, now());
            statement.executeUpdate();
        }
    }

    private static long addPlatform(String code, int tier, String catalogMode, String proofMode)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO platform_registry (code, name, tier, status,"
                                        + " catalog_mode, proof_mode, scope_version,"
                                        + " security_version, create_time, update_time,"
                                        + " created_by, updated_by) VALUES (?, ?, ?, 'ACTIVE',"
                                        + " ?, ?, 1, 1, ?, ?, 1, 1)",
                                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, code);
            statement.setString(2, code);
            statement.setInt(3, tier);
            statement.setString(4, catalogMode);
            statement.setString(5, proofMode);
            statement.setObject(6, now());
            statement.setObject(7, now());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long addDatasource(String code) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO datasource (code, name, engine, host, port,"
                                        + " database_name, username, credential_cipher, key_id,"
                                        + " tls_mode, pool_max, status, create_time, update_time,"
                                        + " created_by, updated_by) VALUES (?, ?, 'MYSQL',"
                                        + " 'db.example.internal', 3306, 'biz', 'ro', X'00', 'k1',"
                                        + " 'VERIFY_FULL', 4, 'ACTIVE', ?, ?, 1, 1)",
                                Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, code);
            statement.setString(2, code);
            statement.setObject(3, now());
            statement.setObject(4, now());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long addNamespace(
            long datasourceId, String catalog, String schema, long generation) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO resource_namespace (datasource_id, catalog_raw,"
                                        + " catalog_norm, schema_raw, schema_norm, generation,"
                                        + " status, create_time, update_time) VALUES (?, ?, ?,"
                                        + " ?, ?, ?, 'ACTIVE', ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, datasourceId);
            statement.setString(2, catalog);
            statement.setString(3, catalog);
            statement.setString(4, schema);
            statement.setString(5, schema);
            statement.setLong(6, generation);
            statement.setObject(7, now());
            statement.setObject(8, now());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long addObject(long namespaceId, String name, String kind, long generation)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO resource_object (namespace_id, generation,"
                                        + " object_raw, object_norm, kind, status,"
                                        + " definition_hash, first_seen_at, last_seen_at,"
                                        + " create_time, update_time) VALUES (?, ?, ?, ?, ?,"
                                        + " 'ACTIVE', UNHEX('"
                                        + "00".repeat(32)
                                        + "'), ?, ?, ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, namespaceId);
            statement.setLong(2, generation);
            statement.setString(3, name);
            statement.setString(4, name);
            statement.setString(5, kind);
            statement.setObject(6, now());
            statement.setObject(7, now());
            statement.setObject(8, now());
            statement.setObject(9, now());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static long addSnapshot(long datasourceId, long resourceVersion) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO schema_snapshot (datasource_id, config_version,"
                                        + " resource_version, status, captured_at, expires_at,"
                                        + " object_count, checksum, create_time, update_time)"
                                        + " VALUES (?, 1, ?, 'PUBLISHED', ?, ?, 3, UNHEX('"
                                        + "00".repeat(32)
                                        + "'), ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, datasourceId);
            statement.setLong(2, resourceVersion);
            statement.setObject(3, now());
            statement.setObject(4, now().plusHours(2));
            statement.setObject(5, now());
            statement.setObject(6, now());
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private static void setActiveSnapshot(long datasourceId, long snapshotId) throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE datasource SET active_snapshot_id = ?, last_sync_at = ?"
                                        + " WHERE id = ?")) {
            statement.setLong(1, snapshotId);
            statement.setObject(2, now());
            statement.setLong(3, datasourceId);
            statement.executeUpdate();
        }
    }

    private static void addSnapshotObject(long snapshotId, long objectId, String comment)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO snapshot_object (snapshot_id, object_id,"
                                        + " object_version, table_comment, column_signature,"
                                        + " create_time, update_time) VALUES (?, ?, 1, ?,"
                                        + " UNHEX('"
                                        + "00".repeat(32)
                                        + "'), ?, ?)")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            statement.setString(3, comment);
            statement.setObject(4, now());
            statement.setObject(5, now());
            statement.executeUpdate();
        }
    }

    private static void addColumn(
            long snapshotId,
            long objectId,
            int ordinal,
            String name,
            String dataType,
            int jdbcType,
            boolean nullable,
            Integer precision,
            Integer scale,
            String comment,
            boolean primaryKey,
            String foreignKey)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO resource_column (snapshot_id, object_id, ordinal,"
                                        + " column_raw, column_norm, data_type, jdbc_type,"
                                        + " nullable, precision_value, scale_value, comment,"
                                        + " is_primary_key, foreign_key, create_time, update_time)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            statement.setInt(3, ordinal);
            statement.setString(4, name);
            statement.setString(5, name);
            statement.setString(6, dataType);
            statement.setInt(7, jdbcType);
            statement.setInt(8, nullable ? 1 : 0);
            statement.setObject(9, precision);
            statement.setObject(10, scale);
            statement.setString(11, comment);
            statement.setInt(12, primaryKey ? 1 : 0);
            statement.setString(13, foreignKey);
            statement.setObject(14, now());
            statement.setObject(15, now());
            statement.executeUpdate();
        }
    }

    private static void addDictionary(long objectId, String columnNorm, String title, String desc)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO business_dictionary (object_id, column_norm, title,"
                                        + " description, version, create_time, update_time,"
                                        + " created_by, updated_by) VALUES (?, ?, ?, ?, 1, ?, ?,"
                                        + " 1, 1)")) {
            statement.setLong(1, objectId);
            statement.setString(2, columnNorm);
            statement.setString(3, title);
            statement.setString(4, desc);
            statement.setObject(5, now());
            statement.setObject(6, now());
            statement.executeUpdate();
        }
    }

    private static void addPlatformScope(
            long platformId,
            String resourceType,
            long resourceId,
            String action,
            boolean descendants)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO platform_scope (platform_id, resource_type,"
                                        + " resource_id, resource_generation, action,"
                                        + " include_descendants, create_time, update_time,"
                                        + " created_by, updated_by) VALUES (?, ?, ?, 1, ?, ?, ?, ?,"
                                        + " 1, 1)")) {
            statement.setLong(1, platformId);
            statement.setString(2, resourceType);
            statement.setLong(3, resourceId);
            statement.setString(4, action);
            statement.setInt(5, descendants ? 1 : 0);
            statement.setObject(6, now());
            statement.setObject(7, now());
            statement.executeUpdate();
        }
    }

    private static void addGrant(
            long userId,
            String resourceType,
            long resourceId,
            String action,
            String effect,
            boolean descendants)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO acl_grant (subject_type, subject_id, resource_type,"
                                        + " resource_id, resource_generation, action, effect,"
                                        + " include_descendants, valid_from, status, reason,"
                                        + " create_time, update_time, created_by, updated_by)"
                                        + " VALUES ('USER', ?, ?, ?, 1, ?, ?, ?, ?, 'ACTIVE',"
                                        + " 'test', ?, ?, 1, 1)")) {
            statement.setLong(1, userId);
            statement.setString(2, resourceType);
            statement.setLong(3, resourceId);
            statement.setString(4, action);
            statement.setString(5, effect);
            statement.setInt(6, descendants ? 1 : 0);
            statement.setObject(7, now().minusMinutes(5));
            statement.setObject(8, now());
            statement.setObject(9, now());
            statement.executeUpdate();
        }
    }

    private static void addAudit(
            String requestId,
            long userId,
            long platformId,
            long datasourceId,
            String tool,
            String sqlMasked,
            long minutesAgo)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO audit_log (request_id, user_id, platform_id, tool,"
                                        + " datasource_id, started_at, completed_at, result,"
                                        + " verdict, sql_masked, resource_refs, duration_ms,"
                                        + " rows_returned, truncated, create_time) VALUES (?, ?, ?,"
                                        + " ?, ?, ?, ?, 'SUCCESS', 'ALLOW', ?, ?, 12, 1, 0, ?)")) {
            statement.setString(1, requestId);
            statement.setLong(2, userId);
            statement.setLong(3, platformId);
            statement.setString(4, tool);
            statement.setLong(5, datasourceId);
            statement.setObject(6, now().minusMinutes(minutesAgo));
            statement.setObject(7, now().minusMinutes(minutesAgo).plusSeconds(1));
            statement.setString(8, sqlMasked);
            statement.setString(9, "{\"datasourceId\":" + datasourceId + "}");
            statement.setObject(10, now());
            statement.executeUpdate();
        }
    }

    private static void addOauthClient() throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO oauth2_registered_client (id, client_id,"
                                        + " client_name, client_authentication_methods,"
                                        + " authorization_grant_types, scopes, client_settings,"
                                        + " token_settings, client_type, status,"
                                        + " registration_source, create_time, update_time,"
                                        + " created_by, updated_by) VALUES ('client-1', 'client-1',"
                                        + " 'VS Code', 'none', 'authorization_code,refresh_token',"
                                        + " 'mcp:catalog,mcp:schema,mcp:query', '{}', '{}',"
                                        + " 'PUBLIC', 'ACTIVE', 'DCR', ?, ?, 1, 1)")) {
            statement.setObject(1, now());
            statement.setObject(2, now());
            statement.executeUpdate();
        }
    }

    private static void addAuthorization(String id, long userId, String familyId)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO oauth2_authorization (id, registered_client_id,"
                                        + " principal_name, authorization_grant_type, user_id,"
                                        + " family_id, status, create_time, update_time,"
                                        + " created_by, updated_by) VALUES (?, 'client-1', ?,"
                                        + " 'authorization_code', ?, ?, 'ACTIVE', ?, ?, 1, 1)")) {
            statement.setString(1, id);
            statement.setString(2, "user-" + userId);
            statement.setLong(3, userId);
            statement.setString(4, familyId);
            statement.setObject(5, now());
            statement.setObject(6, now());
            statement.executeUpdate();
        }
    }

    private static void addFamily(
            String familyId, String authorizationId, long userId, long platformId)
            throws SQLException {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO oauth_refresh_family (id, authorization_id, user_id,"
                                        + " registered_client_id, platform_id, status,"
                                        + " auth_version, absolute_expires_at, idle_expires_at,"
                                        + " last_rotated_at, create_time, update_time) VALUES (?,"
                                        + " ?, ?, 'client-1', ?, 'ACTIVE', 1, ?, ?, ?, ?, ?)")) {
            statement.setString(1, familyId);
            statement.setString(2, authorizationId);
            statement.setLong(3, userId);
            statement.setLong(4, platformId);
            statement.setObject(5, now().plusSeconds(30L * 24 * 3600));
            statement.setObject(6, now().plusSeconds(7L * 24 * 3600));
            statement.setObject(7, now());
            statement.setObject(8, now());
            statement.setObject(9, now());
            statement.executeUpdate();
        }
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("no generated key");
            }
            return keys.getLong(1);
        }
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(UTC);
    }
}
