package local.ptmcp.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * C7 datasource, job and metadata admin APIs against real MySQL. Probes and syncs must fail closed
 * when the target certificate cannot be verified; the suite asserts that behaviour instead of
 * faking a successful TLS connection.
 */
@SpringBootTest(
        classes = AdminApplication.class,
        properties = {
            "ptmcp.admin.test-principal=true",
            "ptmcp.admin.credential-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
            "ptmcp.admin.datasource.allowed-cidrs=127.0.0.1/32,::1/128"
        })
@AutoConfigureMockMvc
class DatasourceApiTest {
    private static final String MYSQL_IMAGE =
            "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69";
    private static final MySQLContainer MYSQL = startMysql();
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final String CREDENTIAL = syntheticSecret();
    private static final String DRAFT_CREDENTIAL = CREDENTIAL + "-draft";
    private static final String ADMIN = "9001";
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

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
                        .withDatabaseName("datasource_api_test")
                        .withUsername("datasource_test")
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
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table :
                    List.of(
                            "api_idempotency",
                            "metadata_sync_job",
                            "business_dictionary",
                            "resource_column",
                            "snapshot_object",
                            "resource_object",
                            "resource_namespace",
                            "schema_snapshot",
                            "acl_grant",
                            "datasource")) {
                statement.execute("DELETE FROM " + table);
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }

    // ------------------------------------------------------------------ datasource

    @Test
    void createValidatesDestinationAndNeverReturnsCredential() throws Exception {
        String secret = CREDENTIAL;
        String unknownField =
                body("orders", "127.0.0.1")
                        .replaceFirst("}$", ",\"jdbcUrl\":\"jdbc:mysql://evil\"}");
        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(unknownField))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("metadata", "169.254.169.254")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("private", "10.1.2.3")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        MvcResult created =
                mockMvc.perform(
                                admin(post("/api/v1/admin/datasources"))
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body("orders", "127.0.0.1")))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("ETag"))
                        .andExpect(header().string("ETag", "\"1\""))
                        .andExpect(jsonPath("$.id").isString())
                        .andExpect(jsonPath("$.version").value("1"))
                        .andExpect(jsonPath("$.configVersion").value("1"))
                        .andExpect(jsonPath("$.status").value("DRAFT"))
                        .andExpect(jsonPath("$.credentialConfigured").value(true))
                        .andExpect(jsonPath("$.health").value("UNKNOWN"))
                        .andExpect(jsonPath("$.createdAt").isNotEmpty())
                        .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                        .andReturn();
        String raw = created.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(raw.contains(secret), "credential leaked into create response");
        assertFalse(raw.contains("credentialCipher"));
        assertTrue(
                created.getResponse()
                        .getHeader("Location")
                        .endsWith(
                                "/api/v1/admin/datasources/" + json(created).get("id").asString()));

        long id = Long.parseLong(json(created).get("id").asString());
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT credential_cipher, key_id FROM datasource WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                byte[] cipher = result.getBytes(1);
                assertFalse(Arrays.equals(cipher, secret.getBytes(StandardCharsets.UTF_8)));
                assertFalse(new String(cipher, StandardCharsets.UTF_8).contains(secret));
                assertTrue(result.getString(2).startsWith("local:"));
            }
        }

        mockMvc.perform(admin(get("/api/v1/admin/datasources/" + id)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").value(Long.toString(id)));
        mockMvc.perform(admin(get("/api/v1/admin/datasources/999999")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void idempotentCreateReplaysAndUpdatesUseIfMatch() throws Exception {
        String key = UUID.randomUUID().toString();
        String createBody = body("orders", "127.0.0.1");
        MvcResult first =
                mockMvc.perform(
                                admin(post("/api/v1/admin/datasources"))
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createBody))
                        .andExpect(status().isCreated())
                        .andReturn();
        String id = json(first).get("id").asString();
        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources"))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(id));
        assertEquals(1, count("SELECT COUNT(*) FROM datasource"));

        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources"))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("renamed", "127.0.0.1")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources"))
                                .header("Content-Type", "application/json")
                                .content(createBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        admin(put("/api/v1/admin/datasources/" + id))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("orders", "127.0.0.1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        mockMvc.perform(
                        admin(put("/api/v1/admin/datasources/" + id))
                                .header("If-Match", "\"99\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body("orders", "127.0.0.1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        Map<String, Object> disabled = write("orders", "127.0.0.1");
        disabled.put("status", "DISABLED");
        disabled.remove("credential");
        mockMvc.perform(
                        admin(put("/api/v1/admin/datasources/" + id))
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.version").value("2"))
                .andExpect(jsonPath("$.resourceVersion").value("2"))
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(
                        admin(put("/api/v1/admin/datasources/" + id))
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(disabled)))
                .andExpect(status().isConflict());
    }

    @Test
    void deleteConflictsWithActiveGrantThenIsIdempotent() throws Exception {
        MvcResult created =
                mockMvc.perform(
                                admin(post("/api/v1/admin/datasources"))
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body("orders", "127.0.0.1")))
                        .andExpect(status().isCreated())
                        .andReturn();
        long id = Long.parseLong(json(created).get("id").asString());
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO acl_grant (subject_type, subject_id, resource_type, "
                                        + "resource_id, resource_generation, action, effect, "
                                        + "include_descendants, valid_from, valid_to, status, reason, "
                                        + "version, create_time, update_time, created_by, updated_by) "
                                        + "VALUES ('USER', 9001, 'DATASOURCE', ?, 1, 'READ', 'ALLOW', 0, "
                                        + "UTC_TIMESTAMP(6), NULL, 'ACTIVE', 'test', 1, "
                                        + "UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 9001, 9001)")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
        mockMvc.perform(
                        admin(delete("/api/v1/admin/datasources/" + id))
                                .header("If-Match", "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFERENCES_EXIST"));

        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE acl_grant SET status = 'REVOKED'");
        }
        mockMvc.perform(
                        admin(delete("/api/v1/admin/datasources/" + id))
                                .header("If-Match", "\"1\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.id").value(Long.toString(id)))
                .andExpect(jsonPath("$.version").value("2"));

        mockMvc.perform(
                        admin(delete("/api/v1/admin/datasources/" + id))
                                .header("If-Match", "\"999\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.version").value("2"));

        mockMvc.perform(admin(get("/api/v1/admin/datasources/" + id)))
                .andExpect(status().isNotFound());
    }

    @Test
    void probeFailsClosedOnUnverifiedTlsAndNeverPersistsDraftCredential() throws Exception {
        MvcResult created =
                mockMvc.perform(
                                admin(post("/api/v1/admin/datasources"))
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body("orders", "127.0.0.1")))
                        .andExpect(status().isCreated())
                        .andReturn();
        long id = Long.parseLong(json(created).get("id").asString());
        byte[] before = credentialCipher(id);

        Map<String, Object> draft = write("orders", "127.0.0.1");
        draft.put("credential", DRAFT_CREDENTIAL);
        MvcResult probe =
                mockMvc.perform(
                                admin(post("/api/v1/admin/datasources/" + id + "/test"))
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(JSON.writeValueAsString(Map.of("draft", draft))))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.id").isString())
                        .andExpect(jsonPath("$.type").value("TEST"))
                        .andExpect(jsonPath("$.datasourceId").value(Long.toString(id)))
                        .andExpect(jsonPath("$.state").value("FAILED"))
                        .andExpect(jsonPath("$.errorCode").value("DRIVER_ERROR"))
                        .andExpect(jsonPath("$.progress.processed").value(0))
                        .andExpect(jsonPath("$.progress.total").isEmpty())
                        .andExpect(jsonPath("$.startedAt").isNotEmpty())
                        .andExpect(jsonPath("$.finishedAt").isNotEmpty())
                        .andReturn();
        String jobId = json(probe).get("id").asString();
        mockMvc.perform(admin(get("/api/v1/admin/jobs/" + jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"));

        assertTrue(Arrays.equals(before, credentialCipher(id)), "draft credential persisted");
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT username, credential_cipher FROM datasource WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(MYSQL.getUsername(), result.getString(1));
                assertFalse(
                        new String(result.getBytes(2), StandardCharsets.UTF_8)
                                .contains(DRAFT_CREDENTIAL));
            }
        }

        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources/" + id + "/test"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        Map<String, Object> wrongEngine = write("orders", "127.0.0.1");
        wrongEngine.put("engine", "POSTGRESQL");
        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources/" + id + "/test"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(Map.of("draft", wrongEngine))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void syncEnqueuesOnceAndFailsClosedWithoutPublishingSnapshot() throws Exception {
        MvcResult created =
                mockMvc.perform(
                                admin(post("/api/v1/admin/datasources"))
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body("orders", "127.0.0.1")))
                        .andExpect(status().isCreated())
                        .andReturn();
        long id = Long.parseLong(json(created).get("id").asString());
        Map<String, Object> active = write("orders", "127.0.0.1");
        active.put("status", "ACTIVE");
        active.remove("credential");
        mockMvc.perform(
                        admin(put("/api/v1/admin/datasources/" + id))
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(JSON.writeValueAsString(active)))
                .andExpect(status().isOk());

        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO metadata_sync_job (datasource_id, job_type, requested_by, "
                                        + "config_version, state, create_time, update_time) "
                                        + "VALUES (?, 'SYNC', 9001, 2, 'QUEUED', UTC_TIMESTAMP(6), "
                                        + "UTC_TIMESTAMP(6))")) {
            statement.setLong(1, id);
            statement.executeUpdate();
        }
        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources/" + id + "/sync"))
                                .header("Idempotency-Key", UUID.randomUUID().toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"mode\":\"FULL\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SYNC_IN_PROGRESS"));
        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM metadata_sync_job");
        }

        String key = UUID.randomUUID().toString();
        MvcResult sync =
                mockMvc.perform(
                                admin(post("/api/v1/admin/datasources/" + id + "/sync"))
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"mode\":\"FULL\"}"))
                        .andExpect(status().isAccepted())
                        .andExpect(jsonPath("$.type").value("SYNC"))
                        .andExpect(jsonPath("$.datasourceId").value(Long.toString(id)))
                        .andExpect(jsonPath("$.progress.total").isEmpty())
                        .andReturn();
        String jobId = json(sync).get("id").asString();
        JsonNode terminal = waitForTerminal(jobId);
        assertEquals("FAILED", terminal.get("state").asString());
        assertEquals("DRIVER_ERROR", terminal.get("errorCode").asString());
        assertEquals(0, terminal.get("progress").get("processed").asInt());

        mockMvc.perform(
                        admin(post("/api/v1/admin/datasources/" + id + "/sync"))
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"mode\":\"FULL\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(jobId));

        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT active_snapshot_id FROM datasource WHERE id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                result.getLong(1);
                assertTrue(result.wasNull(), "failed sync must not publish a snapshot");
            }
        }
    }

    // ------------------------------------------------------------------ metadata

    @Test
    void metadataListAndDetailUseTheActiveSnapshot() throws Exception {
        Seed seed = seedSnapshot(false);

        mockMvc.perform(admin(get("/api/v1/admin/metadata/objects")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        MvcResult first =
                mockMvc.perform(
                                admin(
                                        get("/api/v1/admin/metadata/objects")
                                                .param(
                                                        "datasourceId",
                                                        Long.toString(seed.datasourceId()))
                                                .param("pageSize", "1")))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.items.length()").value(1))
                        .andExpect(jsonPath("$.items[0].objectName").value("orders"))
                        .andExpect(jsonPath("$.items[0].objectType").value("TABLE"))
                        .andExpect(jsonPath("$.items[0].snapshotVersion").value("7"))
                        .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                        .andReturn();
        assertFalse(json(first).get("nextCursor").isNull());

        String cursor = json(first).get("nextCursor").asString();
        mockMvc.perform(
                        admin(
                                get("/api/v1/admin/metadata/objects")
                                        .param("datasourceId", Long.toString(seed.datasourceId()))
                                        .param("pageSize", "1")
                                        .param("cursor", cursor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].objectName").value("payments"))
                .andExpect(jsonPath("$.nextCursor").isEmpty());

        mockMvc.perform(
                        admin(
                                get("/api/v1/admin/metadata/objects")
                                        .param("datasourceId", Long.toString(seed.datasourceId()))
                                        .param("kind", "VIEW")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].objectName").value("payments"));

        mockMvc.perform(
                        admin(
                                get("/api/v1/admin/metadata/objects")
                                        .param("datasourceId", Long.toString(seed.datasourceId()))
                                        .param("keyword", "ord")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].objectName").value("orders"));

        mockMvc.perform(
                        admin(
                                get("/api/v1/admin/metadata/objects")
                                        .param("datasourceId", Long.toString(seed.datasourceId()))
                                        .param("cursor", "not-a-cursor")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(admin(get("/api/v1/admin/metadata/objects/" + seed.ordersId())))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.objectName").value("orders"))
                .andExpect(jsonPath("$.tableComment").value("订单表"))
                .andExpect(jsonPath("$.physicalIdentity").value("orders@7"))
                .andExpect(jsonPath("$.columns.length()").value(2))
                .andExpect(jsonPath("$.columns[0].name").value("id"))
                .andExpect(jsonPath("$.columns[0].primaryKey").value(true))
                .andExpect(jsonPath("$.columns[1].name").value("amount"))
                .andExpect(jsonPath("$.dictionary.version").value("1"))
                .andExpect(jsonPath("$.snapshotId").value(Long.toString(seed.snapshotId())));

        mockMvc.perform(
                        get("/api/v1/admin/metadata/objects")
                                .header("X-Test-User-Id", ADMIN)
                                .header("X-Test-Platform-Admin", "false")
                                .param("datasourceId", Long.toString(seed.datasourceId())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_SCOPE_DENIED"));
    }

    @Test
    void expiredOrMissingSnapshotReturnsSchemaStale() throws Exception {
        Seed seed = seedSnapshot(true);
        mockMvc.perform(
                        admin(
                                get("/api/v1/admin/metadata/objects")
                                        .param("datasourceId", Long.toString(seed.datasourceId()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SCHEMA_STALE"));
        mockMvc.perform(admin(get("/api/v1/admin/metadata/objects/" + seed.ordersId())))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SCHEMA_STALE"));

        try (Connection connection = connect();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE datasource SET active_snapshot_id = NULL");
        }
        mockMvc.perform(
                        admin(
                                get("/api/v1/admin/metadata/objects")
                                        .param("datasourceId", Long.toString(seed.datasourceId()))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SCHEMA_STALE"));
    }

    @Test
    void dictionaryWritesRequireVersionsAndRejectUnknownColumnsOrRichText() throws Exception {
        Seed seed = seedSnapshot(false);
        String path = "/api/v1/admin/metadata/objects/" + seed.ordersId() + "/dictionary";

        mockMvc.perform(
                        admin(put(path))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(dictionary("订单", "orders", "id")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));

        mockMvc.perform(
                        admin(put(path))
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(dictionary("订单", "orders", "missing")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertEquals(0, count("SELECT COUNT(*) FROM business_dictionary"));

        mockMvc.perform(
                        admin(put(path))
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        JSON.writeValueAsString(
                                                Map.of(
                                                        "title", "订单",
                                                        "description", "<b>rich</b>",
                                                        "columns", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        mockMvc.perform(
                        admin(put(path))
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(dictionary("订单", "订单信息", "id")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.dictionary.title").value("订单"))
                .andExpect(jsonPath("$.dictionary.version").value("2"))
                .andExpect(jsonPath("$.dictionary.columns.length()").value(1))
                .andExpect(jsonPath("$.dictionary.columns[0].columnName").value("id"))
                .andExpect(jsonPath("$.dictionary.columns[0].title").value("主键"));

        mockMvc.perform(
                        admin(put(path))
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(dictionary("订单", "再次", "id")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));

        mockMvc.perform(
                        admin(put(path))
                                .header("If-Match", "\"2\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(dictionary("订单", "再次", "ID")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dictionary.version").value("3"))
                .andExpect(jsonPath("$.dictionary.columns[0].columnName").value("id"));
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder builder) {
        return builder.header("X-Test-User-Id", ADMIN).header("X-Test-Platform-Admin", "true");
    }

    private static Map<String, Object> write(String code, String host) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Orders " + code);
        body.put("engine", "MYSQL");
        body.put("host", host);
        body.put("port", MYSQL.getMappedPort(3306));
        body.put("databaseName", MYSQL.getDatabaseName());
        body.put("username", MYSQL.getUsername());
        body.put("credential", CREDENTIAL);
        body.put("tlsMode", "VERIFY_FULL");
        body.put("poolMax", 2);
        body.put("status", "DRAFT");
        return body;
    }

    private static String body(String code, String host) {
        return JSON.writeValueAsString(write(code, host));
    }

    private static String dictionary(String title, String description, String columnName) {
        return JSON.writeValueAsString(
                Map.of(
                        "title",
                        title,
                        "description",
                        description,
                        "ownerUserId",
                        9001,
                        "columns",
                        List.of(
                                Map.of(
                                        "columnName", columnName,
                                        "title", "主键",
                                        "description", "订单主键"))));
    }

    private JsonNode waitForTerminal(String jobId) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            MvcResult result =
                    mockMvc.perform(admin(get("/api/v1/admin/jobs/" + jobId)))
                            .andExpect(status().isOk())
                            .andReturn();
            JsonNode body = json(result);
            if (TERMINAL.contains(body.get("state").asString())) {
                return body;
            }
            Thread.sleep(200L);
        }
        fail("job did not reach a terminal state");
        return null;
    }

    private byte[] credentialCipher(long datasourceId) throws Exception {
        try (Connection connection = connect();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT credential_cipher FROM datasource WHERE id = ?")) {
            statement.setLong(1, datasourceId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBytes(1);
            }
        }
    }

    private static int count(String sql) throws SQLException {
        try (Connection connection = connect();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private record Seed(long datasourceId, long snapshotId, long ordersId, long paymentsId) {}

    /**
     * Seeds a published snapshot directly: sync publication itself is covered by T15-a against a
     * real target, while these tests need deterministic object metadata without a TLS truststore.
     */
    private static Seed seedSnapshot(boolean expired) throws SQLException {
        LocalDateTime now = LocalDateTime.now(UTC);
        try (Connection connection = connect()) {
            long datasourceId;
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO datasource (code, name, engine, host, port, database_name, "
                                    + "username, credential_cipher, key_id, tls_mode, pool_max, status, "
                                    + "config_version, resource_version, create_time, update_time, "
                                    + "created_by, updated_by) VALUES (?, 'Seed', 'MYSQL', '127.0.0.1', "
                                    + "3306, 'seed', 'seed', ?, 'local:seed', 'VERIFY_FULL', 2, 'ACTIVE', "
                                    + "7, 7, ?, ?, 9001, 9001)",
                            Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, "seed_" + UUID.randomUUID().toString().substring(0, 8));
                statement.setBytes(2, new byte[] {1, 2, 3});
                statement.setObject(3, now);
                statement.setObject(4, now);
                statement.executeUpdate();
                datasourceId = generatedId(statement);
            }
            long snapshotId;
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO schema_snapshot (datasource_id, config_version, "
                                    + "resource_version, status, captured_at, expires_at, object_count, "
                                    + "checksum, create_time, update_time) "
                                    + "VALUES (?, 7, 7, 'PUBLISHED', ?, ?, 2, ?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, datasourceId);
                statement.setObject(2, now.minusMinutes(10));
                statement.setObject(3, expired ? now.minusMinutes(5) : now.plusHours(1));
                statement.setBytes(4, new byte[32]);
                statement.setObject(5, now);
                statement.setObject(6, now);
                statement.executeUpdate();
                snapshotId = generatedId(statement);
            }
            long namespaceId;
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "INSERT INTO resource_namespace (datasource_id, catalog_raw, catalog_norm, "
                                    + "schema_raw, schema_norm, generation, status, create_time, update_time) "
                                    + "VALUES (?, 'seed', 'seed', '', '', 1, 'ACTIVE', ?, ?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                statement.setLong(1, datasourceId);
                statement.setObject(2, now);
                statement.setObject(3, now);
                statement.executeUpdate();
                namespaceId = generatedId(statement);
            }
            long ordersId = insertObject(connection, namespaceId, "orders", "TABLE", "订单表", now);
            long paymentsId =
                    insertObject(connection, namespaceId, "payments", "VIEW", "支付视图", now);
            insertSnapshotObject(connection, snapshotId, ordersId, "订单表", now);
            insertSnapshotObject(connection, snapshotId, paymentsId, "支付视图", now);
            insertColumn(connection, snapshotId, ordersId, 0, "id", "BIGINT", true, true, now);
            insertColumn(
                    connection, snapshotId, ordersId, 1, "amount", "DECIMAL", false, false, now);
            insertColumn(
                    connection,
                    snapshotId,
                    paymentsId,
                    0,
                    "payment_id",
                    "BIGINT",
                    true,
                    false,
                    now);
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "UPDATE datasource SET active_snapshot_id = ?, last_sync_at = ? WHERE id = ?")) {
                statement.setLong(1, snapshotId);
                statement.setObject(2, now);
                statement.setLong(3, datasourceId);
                statement.executeUpdate();
            }
            return new Seed(datasourceId, snapshotId, ordersId, paymentsId);
        }
    }

    private static long insertObject(
            Connection connection,
            long namespaceId,
            String name,
            String kind,
            String comment,
            LocalDateTime now)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO resource_object (namespace_id, generation, object_raw, "
                                + "object_norm, quoted, kind, status, definition_hash, physical_identity, "
                                + "first_seen_at, last_seen_at, create_time, update_time) "
                                + "VALUES (?, 1, ?, ?, 0, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, namespaceId);
            statement.setString(2, name);
            statement.setString(3, name);
            statement.setString(4, kind);
            statement.setBytes(5, new byte[32]);
            statement.setString(6, name + "@7");
            statement.setObject(7, now);
            statement.setObject(8, now);
            statement.setObject(9, now);
            statement.setObject(10, now);
            statement.executeUpdate();
            return generatedId(statement);
        }
    }

    private static void insertSnapshotObject(
            Connection connection,
            long snapshotId,
            long objectId,
            String comment,
            LocalDateTime now)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO snapshot_object (snapshot_id, object_id, object_version, "
                                + "table_comment, column_signature, create_time, update_time) "
                                + "VALUES (?, ?, 1, ?, ?, ?, ?)")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            statement.setString(3, comment);
            statement.setBytes(4, new byte[32]);
            statement.setObject(5, now);
            statement.setObject(6, now);
            statement.executeUpdate();
        }
    }

    private static void insertColumn(
            Connection connection,
            long snapshotId,
            long objectId,
            int ordinal,
            String name,
            String type,
            boolean primaryKey,
            boolean nullable,
            LocalDateTime now)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO resource_column (snapshot_id, object_id, ordinal, column_raw, "
                                + "column_norm, quoted, data_type, jdbc_type, nullable, precision_value, "
                                + "scale_value, max_length, comment, is_primary_key, foreign_key, "
                                + "create_time, update_time) "
                                + "VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, NULL, NULL, NULL, NULL, ?, ?, ?, ?)")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            statement.setInt(3, ordinal);
            statement.setString(4, name);
            statement.setString(5, name);
            statement.setString(6, type);
            statement.setInt(7, "BIGINT".equals(type) ? -5 : 3);
            statement.setBoolean(8, nullable);
            statement.setBoolean(9, primaryKey);
            statement.setNull(10, java.sql.Types.VARCHAR);
            statement.setObject(11, now);
            statement.setObject(12, now);
            statement.executeUpdate();
        }
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            assertTrue(keys.next());
            long id = keys.getLong(1);
            assertNotEquals(0, id);
            return id;
        }
    }
}
