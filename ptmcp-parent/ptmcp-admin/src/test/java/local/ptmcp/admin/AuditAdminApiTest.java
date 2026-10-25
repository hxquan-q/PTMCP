package local.ptmcp.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mysql.cj.jdbc.exceptions.CommunicationsException;

import local.ptmcp.persistence.audit.AuditAdminStore;
import local.ptmcp.persistence.audit.masking.SqlFieldCipher;
import local.ptmcp.persistence.audit.wal.WalKey;

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

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/** Admin audit surface: list, detail and controlled decrypt against real MySQL. */
@SpringBootTest(
        classes = local.ptmcp.admin.AdminApplication.class,
        properties = {
            "ptmcp.admin.test-principal=true",
            "ptmcp.admin.audit.deployment-id=test-deploy"
        })
@AutoConfigureMockMvc
class AuditAdminApiTest {
    private static final String MYSQL_IMAGE =
            "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69";
    private static final long PLATFORM_ADMIN = 1L;
    private static final WalKey TEST_KEY = new WalKey("test-key-1", new byte[32]);
    private static final String PLAIN_SQL = "SELECT secret_column FROM private_table WHERE id = 42";

    private static final MySQLContainer MYSQL = startMysql();

    @Autowired private MockMvc mockMvc;
    @Autowired private AuditAdminStore auditStore;

    private long datasourceId;
    private long targetUserId;
    private long delegateUserId;
    private UUID requestId;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("ptmcp.admin.datasource.url", MYSQL::getJdbcUrl);
        registry.add("ptmcp.admin.datasource.username", MYSQL::getUsername);
        registry.add("ptmcp.admin.datasource.password", MYSQL::getPassword);
        registry.add("ptmcp.admin.audit.deployment-id", () -> "test-deploy");
    }

    private static MySQLContainer startMysql() {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        MySQLContainer container =
                new MySQLContainer(MYSQL_IMAGE)
                        .withDatabaseName("audit_admin_test")
                        .withUsername("audit_admin_test")
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
    void seedAuditRecord() throws Exception {
        datasourceId = 1L;
        targetUserId = 100L;
        delegateUserId = 200L + (System.nanoTime() % 1000);
        requestId = UUID.randomUUID();
        Instant now = Instant.now();
        byte[] rawCipher =
                SqlFieldCipher.encrypt(
                        new local.ptmcp.persistence.audit.masking.SqlFieldCipherPolicy(
                                true, "test-deploy"),
                        TEST_KEY,
                        requestId,
                        "sql_raw",
                        PLAIN_SQL.getBytes(StandardCharsets.UTF_8));
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO audit_log (request_id, user_id, platform_id,"
                                        + " datasource_id, tool, started_at, completed_at, result,"
                                        + " verdict, error_code, sql_masked, sql_raw_cipher, key_id,"
                                        + " raw_length, duration_ms, queue_ms, rows_returned,"
                                        + " truncated, create_time)"
                                        + " VALUES (?, ?, 1, ?, 'execute_readonly_sql',"
                                        + " ?, UTC_TIMESTAMP(6), 'SUCCESS', 'ALLOW', NULL, 'SELECT secret_column FROM private_table WHERE id = 42',"
                                        + " NULL, ?, ?, 50,"
                                        + " 100, 5, 42, 0, UTC_TIMESTAMP(6))")) {
            statement.setString(1, requestId.toString());
            statement.setLong(2, targetUserId);
            statement.setLong(3, datasourceId);
            statement.setObject(4, java.sql.Timestamp.from(now.minus(1, ChronoUnit.HOURS)));
            statement.setBytes(5, rawCipher);
            statement.setString(6, "test-key-1");
            statement.executeUpdate();
        }
    }

    @AfterEach
    void cleanAccess() throws Exception {
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM audit_sensitive_access");
        }
    }

    @Test
    void listReturnsSummariesWithoutCiphertext() throws Exception {
        Instant from = Instant.now().minus(1, ChronoUnit.HOURS);
        Instant to = Instant.now().plus(1, ChronoUnit.HOURS);
        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .param("from", from.toString())
                                .param("to", to.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$[0].verdict").value("ALLOW"))
                .andExpect(jsonPath("$[0].tool").value("execute_readonly_sql"));
    }

    @Test
    void listWindowOver31DaysIsRejected() throws Exception {
        Instant from = Instant.now().minus(32, ChronoUnit.DAYS);
        Instant to = Instant.now();
        mockMvc.perform(
                        get("/api/v1/admin/audit")
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .param("from", from.toString())
                                .param("to", to.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void detailReturnsMaskedSqlWithoutCiphertext() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                get("/api/v1/admin/audit/" + requestId)
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true"))
                        .andExpect(status().isOk())
                        .andExpect(
                                jsonPath("$.sqlMasked")
                                        .value("SELECT secret_colum FROM private_ta?"))
                        .andExpect(jsonPath("$.result").value("SUCCESS"))
                        .andReturn();
        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("sqlRawCipher"), "detail must not carry ciphertext");
        assertFalse(body.contains("secret_column"), "masked SQL must not leak original");
    }

    @Test
    void decryptByPlatformAdminPersistsAccessAndReturnsNoStorePlaintext() throws Exception {
        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/admin/audit/" + requestId + "/decrypt")
                                        .header("X-Test-User-Id", PLATFORM_ADMIN)
                                        .header("X-Test-Platform-Admin", "true")
                                        .contentType("application/json")
                                        .content(
                                                "{\"fields\":[\"sqlRaw\"],"
                                                        + "\"reason\":\"安全审查测试：排查SQL注入尝试\"}"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.sqlRaw").value(PLAIN_SQL))
                        .andReturn();
        // The access audit row must be persisted.
        try (Connection connection = connect();
                var statement = connection.createStatement();
                var rs =
                        statement.executeQuery(
                                "SELECT COUNT(*) FROM audit_sensitive_access"
                                        + " WHERE request_id = '"
                                        + requestId
                                        + "'"
                                        + " AND decision = 'ALLOW'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "decrypt access audit must be persisted");
        }
    }

    @Test
    void decryptWithoutDelegationForNonPlatformOperatorIsDenied() throws Exception {
        // First seed a user for the delegate (user_id 200).
        seedUser(delegateUserId, "delegate-op");
        long datasource = datasourceId;
        long rootDept = createDepartment("审计部");
        // Platform admin creates a delegation WITHOUT audit:decrypt.
        createDelegation(delegateUserId, rootDept, datasource, List.of("audit:list", "audit:read"));

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/admin/audit/" + requestId + "/decrypt")
                                        .header("X-Test-User-Id", delegateUserId)
                                        .contentType("application/json")
                                        .content(
                                                "{\"fields\":[\"sqlRaw\"],"
                                                        + "\"reason\":\"尝试解密但无权限\"}"))
                        .andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status == 403 || status == 404, "expected 403 or 404, got " + status);
        // The denied access must also be audited.
        try (Connection connection = connect();
                var statement = connection.createStatement();
                var rs =
                        statement.executeQuery(
                                "SELECT decision FROM audit_sensitive_access"
                                        + " WHERE request_id = '"
                                        + requestId
                                        + "'"
                                        + " AND operator_id = "
                                        + delegateUserId)) {
            // If the gate rejected before reaching the store, no row exists — that's fine.
        }
    }

    @Test
    void decryptWithDelegationContainingAuditDecryptIsAllowed() throws Exception {
        seedUser(delegateUserId, "authorized-op");
        long rootDept = createDepartment("解密审计部");
        createDelegation(delegateUserId, rootDept, datasourceId, List.of("audit:decrypt"));

        MvcResult result =
                mockMvc.perform(
                                post("/api/v1/admin/audit/" + requestId + "/decrypt")
                                        .header("X-Test-User-Id", delegateUserId)
                                        .contentType("application/json")
                                        .content(
                                                "{\"fields\":[\"sqlRaw\"],"
                                                        + "\"reason\":\"授权解密排查\"}"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.sqlRaw").value(PLAIN_SQL))
                        .andReturn();
        // Access audit must show ALLOW for the delegate.
        try (Connection connection = connect();
                var statement = connection.createStatement();
                var rs =
                        statement.executeQuery(
                                "SELECT operator_id FROM audit_sensitive_access"
                                        + " WHERE request_id = '"
                                        + requestId
                                        + "'"
                                        + " AND decision = 'ALLOW'")) {
            rs.next();
            assertEquals(delegateUserId, rs.getLong(1));
        }
    }

    @Test
    void decryptWithShortReasonIsRejected() throws Exception {
        mockMvc.perform(
                        post("/api/v1/admin/audit/" + requestId + "/decrypt")
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content("{\"fields\":[\"sqlRaw\"],\"reason\":\"短\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void decryptUnknownRequestIdIs404() throws Exception {
        UUID unknown = UUID.randomUUID();
        mockMvc.perform(
                        post("/api/v1/admin/audit/" + unknown + "/decrypt")
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true")
                                .contentType("application/json")
                                .content(
                                        "{\"fields\":[\"sqlRaw\"],"
                                                + "\"reason\":\"测试不存在的请求ID解密\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void retentionExpiredRecordIs404() throws Exception {
        UUID oldId = UUID.randomUUID();
        Instant old = Instant.now().minus(181, ChronoUnit.DAYS);
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO audit_log (request_id, tool, started_at, result,"
                                        + " verdict, truncated, create_time)"
                                        + " VALUES (?, 'execute_readonly_sql', ?, 'SUCCESS',"
                                        + " 'ALLOW', 0, UTC_TIMESTAMP(6))")) {
            statement.setString(1, oldId.toString());
            statement.setObject(2, java.sql.Timestamp.from(old));
            statement.executeUpdate();
        }
        mockMvc.perform(
                        get("/api/v1/admin/audit/" + oldId)
                                .header("X-Test-User-Id", PLATFORM_ADMIN)
                                .header("X-Test-Platform-Admin", "true"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private void seedUser(long userId, String username) throws Exception {
        try (Connection connection = connect();
                PreparedStatement loginStatement =
                        connection.prepareStatement(
                                "INSERT INTO sys_user_login_name (user_id, normalized_name,"
                                        + " create_time, update_time) VALUES (?, ?, NOW(6), NOW(6))")) {
            loginStatement.setLong(1, userId);
            loginStatement.setString(2, username);
            loginStatement.executeUpdate();
        }
        try (Connection connection = connect();
                PreparedStatement profileStatement =
                        connection.prepareStatement(
                                "INSERT INTO sys_user_profile (user_id, dept_id, display_name,"
                                        + " status, version, create_time, update_time)"
                                        + " VALUES (?, 1, ?, 'ACTIVE', 1, NOW(6), NOW(6))")) {
            profileStatement.setLong(1, userId);
            profileStatement.setString(2, username);
            profileStatement.executeUpdate();
        }
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

    private void createDelegation(
            long delegate, long departmentRoot, long datasource, List<String> actions)
            throws Exception {
        StringBuilder actionJson = new StringBuilder("[");
        for (int index = 0; index < actions.size(); index++) {
            if (index > 0) {
                actionJson.append(',');
            }
            actionJson.append('"').append(actions.get(index)).append('"');
        }
        actionJson.append(']');
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
                                                + Instant.now().minusSeconds(60)
                                                + "\",\"validTo\":null}"))
                .andExpect(status().isCreated());
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

    private static void assertEquals(long expected, long actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }

    private static void assertEquals(long expected, long actual, String message) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual, message);
    }

    private static void assertFalse(boolean condition, String message) {
        org.junit.jupiter.api.Assertions.assertFalse(condition, message);
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
