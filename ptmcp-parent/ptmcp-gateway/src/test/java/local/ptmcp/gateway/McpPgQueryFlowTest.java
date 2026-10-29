package local.ptmcp.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.DataSourcePoolRegistry;
import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.datasource.metadata.PgMetadataCollector;
import local.ptmcp.datasource.postgres.PgApprovedQueryAdapter;
import local.ptmcp.datasource.testing.TlsDatabaseFixture;
import local.ptmcp.persistence.schema.SchemaSnapshotStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Real HTTP + CA-verified PostgreSQL using the production destination and TLS pool factory. */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpPgQueryFlowTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MYSQL_IMAGE =
            System.getProperty(
                    "ptmcp.test.mysqlImage",
                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String PAT = "ptmcp_pat_" + secret();
    private static final String RESOURCE = "https://mcp.test.invalid/mcp";
    private static final String DATABASE_NAME = TlsDatabaseFixture.DATABASE;

    private static final MySQLContainer META =
            new MySQLContainer(MYSQL_IMAGE)
                    .withDatabaseName("pg_flow_meta")
                    .withUsername("pg_flow_user")
                    .withPassword(secret())
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final TlsDatabaseFixture TLS;
    private static final PostgreSQLContainer BUSINESS;
    private static final HikariDataSource PRIMARY;
    private static final Path WAL;

    static {
        try {
            META.start();
            TLS = new TlsDatabaseFixture(DatabaseEngine.POSTGRESQL);
            BUSINESS = (PostgreSQLContainer) TLS.container;
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(META.getJdbcUrl());
            config.setUsername(META.getUsername());
            config.setPassword(META.getPassword());
            config.setInitializationFailTimeout(15_000);
            PRIMARY = new HikariDataSource(config);
            Flyway.configure()
                    .dataSource(PRIMARY)
                    .locations("classpath:db/ptmcp-migration")
                    .load()
                    .migrate();
            WAL = Files.createTempDirectory("ptmcp-pg-flow-wal-");
            seed();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @LocalServerPort private int port;
    @MockitoSpyBean private DataSourcePoolRegistry pools;
    @MockitoSpyBean private PgApprovedQueryAdapter pgAdapter;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("ptmcp.gateway.enabled", () -> "true");
        properties.add("ptmcp.gateway.metadata-url", META::getJdbcUrl);
        properties.add("ptmcp.gateway.metadata-username", META::getUsername);
        properties.add("ptmcp.gateway.metadata-password", META::getPassword);
        properties.add("ptmcp.gateway.credential-key", () -> KEY);
        properties.add("ptmcp.gateway.allowed-cidrs", () -> "127.0.0.0/8,172.16.0.0/12");
        properties.add("ptmcp.gateway.allowed-ports", () -> Integer.toString(TLS.port()));
        properties.add("ptmcp.gateway.pg-ssl-root-cert", () -> TLS.rootCert.toString());
        properties.add("ptmcp.gateway.truststore-path", () -> "target/test-only-trust.p12");
        properties.add("ptmcp.gateway.truststore-password", TlsMysqlFixture::secret);
        properties.add("ptmcp.gateway.wal-directory", WAL::toString);
        properties.add("ptmcp.gateway.wal-key", () -> KEY);
        properties.add("ptmcp.gateway.issuer", () -> "https://as.test.invalid");
        properties.add("ptmcp.gateway.resource", () -> RESOURCE);
    }

    @AfterAll
    static void stopOwnedContainers() throws Exception {
        PRIMARY.close();
        TLS.close();
        META.stop();
    }

    @Test
    void pgCatalogRequiresSchemaAndReturnsSchemaQualifiedItems() throws Exception {
        HttpResponse<String> missingSchema =
                toolCall(
                        "list_databases_and_tables",
                        Map.of("datasource_id", "1", "database_name", DATABASE_NAME),
                        PAT);
        assertEquals(200, missingSchema.statusCode(), missingSchema.body());
        assertEquals(
                "DIALECT_CONTEXT_INVALID",
                error(missingSchema).path("code").asText(),
                missingSchema.body());

        HttpResponse<String> listed =
                toolCall(
                        "list_databases_and_tables",
                        Map.of(
                                "datasource_id",
                                "1",
                                "database_name",
                                DATABASE_NAME,
                                "schema_name",
                                "public"),
                        PAT);
        assertEquals(200, listed.statusCode(), listed.body());
        JsonNode items = data(listed).path("items");
        assertFalse(items.isEmpty(), listed.body());
        assertEquals("public", items.get(0).path("schemaName").asText());
        assertEquals("orders", items.get(0).path("tableName").asText());
    }

    @Test
    void pgCatalogDiscoversNamespaceBeforeSchemaIsKnown() throws Exception {
        HttpResponse<String> listed = toolCall("list_databases_and_tables", Map.of("datasource_id", "1"), PAT);
        assertEquals(200, listed.statusCode(), listed.body());
        assertFalse(JSON.readTree(listed.body()).path("result").path("isError").asBoolean(true), listed.body());
        assertEquals("public", data(listed).path("items").get(0).path("schemaName").asText());
        HttpResponse<String> incomplete = toolCall("list_databases_and_tables",
                Map.of("datasource_id", "1", "schema_name", "public"), PAT);
        assertTrue(incomplete.body().contains("DIALECT_CONTEXT_INVALID"), incomplete.body());
    }

    @Test
    void pgSchemaAndQueryReturnTypedValues() throws Exception {
        HttpResponse<String> schema =
                toolCall(
                        "get_table_schema",
                        Map.of(
                                "datasource_id",
                                "1",
                                "database_name",
                                DATABASE_NAME,
                                "schema_name",
                                "public",
                                "table_names",
                                List.of("orders")),
                        PAT);
        assertEquals(200, schema.statusCode(), schema.body());
        assertEquals("public", data(schema).path("schemaName").asText());
        assertEquals("orders", data(schema).path("tables").get(0).path("tableName").asText());

        HttpResponse<String> response =
                toolCall(
                        "execute_readonly_sql",
                        Map.of(
                                "datasource_id",
                                "1",
                                "database_name",
                                DATABASE_NAME,
                                "schema_name",
                                "public",
                                "sql_query",
                                "SELECT id, amount, note FROM orders ORDER BY id"),
                        PAT);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = JSON.readTree(response.body()).path("result");
        assertFalse(result.path("isError").asBoolean(true), response.body());
        JsonNode queryData = result.path("structuredContent").path("data");
        assertEquals("public", queryData.path("schemaName").asText());
        assertEquals(1, queryData.path("rows").get(0).get(0).asInt());
        assertEquals("12.34", queryData.path("rows").get(0).get(1).asText());
        assertEquals("測試😀", queryData.path("rows").get(0).get(2).asText());
        assertEquals("NUMBER", queryData.path("rows").get(0).get(0).getNodeType().name());
        try (var lease = pools.current(1).orElseThrow().lease();
                Connection connection = lease.dataSource().getConnection();
                var statement = connection.createStatement();
                var ssl =
                        statement.executeQuery(
                                "SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")) {
            assertTrue(ssl.next());
            assertTrue(ssl.getBoolean(1), "the production pool must use TLS");
        }
        assertEquals(
                result.path("structuredContent"),
                JSON.readTree(result.path("content").get(0).path("text").asText()));
        assertEquals(
                "SUCCESS",
                auditColumn(result.path("structuredContent").path("requestId").asText(), "result"));
        assertEquals(
                "ALLOW",
                auditColumn(
                        result.path("structuredContent").path("requestId").asText(), "verdict"));
    }

    @Test
    void pgQueryWithoutSchemaIsRejectedBeforeExecution() throws Exception {
        HttpResponse<String> response =
                toolCall(
                        "execute_readonly_sql",
                        Map.of(
                                "datasource_id",
                                "1",
                                "database_name",
                                DATABASE_NAME,
                                "sql_query",
                                "SELECT id FROM orders"),
                        PAT);
        assertEquals(200, response.statusCode(), response.body());
        assertEquals("DIALECT_CONTEXT_INVALID", error(response).path("code").asText());
    }

    @Test
    void pgObjectDenyRejectsBeforePoolActivationOrExecution() throws Exception {
        try (Connection connection = PRIMARY.getConnection();
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO acl_grant (subject_type,subject_id,resource_type,resource_id,resource_generation,"
                            + "action,effect,valid_from,status,reason,create_time,update_time,created_by,updated_by) "
                            + "SELECT 'USER',1,'OBJECT',id,generation,'READ','DENY',UTC_TIMESTAMP(6),'ACTIVE',"
                            + "'synthetic-pg-deny',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1 FROM resource_object WHERE object_norm='orders'");
        }
        try {
            HttpResponse<String> response =
                    toolCall(
                            "execute_readonly_sql",
                            Map.of(
                                    "datasource_id",
                                    "1",
                                    "database_name",
                                    DATABASE_NAME,
                                    "schema_name",
                                    "public",
                                    "sql_query",
                                    "SELECT id FROM orders"),
                            PAT);
            assertEquals(
                    "TABLE_NOT_AUTHORIZED", error(response).path("code").asText(), response.body());
            verifyNoInteractions(pools, pgAdapter);
        } finally {
            try (Connection connection = PRIMARY.getConnection();
                    var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM acl_grant WHERE reason='synthetic-pg-deny'");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private static void seed() throws Exception {
        try (Connection meta = PRIMARY.getConnection();
                var sql = meta.createStatement()) {
            sql.execute(
                    "INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,status,del_flag,create_time,update_time) VALUES (1,0,'0','test','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,create_time,update_time) VALUES (1,1,'test','unused','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO platform_registry (id,code,name,tier,status,catalog_mode,proof_mode,create_time,update_time,created_by,updated_by) VALUES (1,'test','test',3,'ACTIVE','DISCOVER','NONE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
        }
        CredentialCipher.Sealed credential =
                new CredentialCipher(KEY).encrypt(BUSINESS.getPassword());
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement sql =
                        meta.prepareStatement(
                                "INSERT INTO datasource (id,code,name,engine,host,port,database_name,username,credential_cipher,key_id,tls_mode,pool_max,status,create_time,update_time,created_by,updated_by) VALUES (1,'pg-test','pg-test','POSTGRESQL',?,?,?,?,?,?,'VERIFY_FULL',2,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)")) {
            sql.setString(1, BUSINESS.getHost());
            sql.setInt(2, BUSINESS.getMappedPort(5432));
            sql.setString(3, BUSINESS.getDatabaseName());
            sql.setString(4, BUSINESS.getUsername());
            sql.setBytes(5, credential.ciphertext());
            sql.setString(6, credential.keyId());
            sql.executeUpdate();
        }
        try (Connection business =
                        java.sql.DriverManager.getConnection(
                                BUSINESS.getJdbcUrl(),
                                BUSINESS.getUsername(),
                                BUSINESS.getPassword());
                var sql = business.createStatement()) {
            sql.execute(
                    "CREATE TABLE orders (id integer PRIMARY KEY, amount numeric(12,2), note text)");
            sql.execute("INSERT INTO orders VALUES (1, 12.34, '測試😀'), (2, 56.78, 'second')");
        }
        SchemaSnapshotStore snapshots = new SchemaSnapshotStore(PRIMARY);
        try (Connection business =
                java.sql.DriverManager.getConnection(
                        BUSINESS.getJdbcUrl(), BUSINESS.getUsername(), BUSINESS.getPassword())) {
            long id =
                    snapshots.stage(
                            1,
                            new PgMetadataCollector().collect(business),
                            LocalDateTime.now(ZoneOffset.UTC));
            snapshots.publish(id, 1);
        }
        try (Connection meta = PRIMARY.getConnection();
                var sql = meta.createStatement()) {
            sql.execute(
                    "INSERT INTO platform_scope (platform_id,resource_type,resource_id,resource_generation,action,include_descendants,create_time,update_time,created_by,updated_by) VALUES (1,'DATASOURCE',1,1,'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),(1,'DATASOURCE',1,1,'META',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
            sql.execute(
                    "INSERT INTO acl_grant (subject_type,subject_id,resource_type,resource_id,resource_generation,action,effect,include_descendants,valid_from,status,reason,create_time,update_time,created_by,updated_by) VALUES ('USER',1,'DATASOURCE',1,1,'READ','ALLOW',1,UTC_TIMESTAMP(6),'ACTIVE','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
        }
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement sql =
                        meta.prepareStatement(
                                "INSERT INTO personal_token (user_id,platform_id,token_prefix,token_hash,name,scopes,auth_version,expires_at,status,version,create_time,update_time,created_by,updated_by) VALUES (1,1,?,?,?,JSON_ARRAY('mcp:catalog','mcp:schema','mcp:query'),1,DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY),'ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)")) {
            sql.setString(1, PAT.substring(0, 16));
            sql.setBytes(2, sha256(PAT));
            sql.setString(3, "pg-flow");
            sql.executeUpdate();
        }
    }

    private static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private HttpResponse<String> toolCall(String tool, Map<String, Object> arguments, String token)
            throws Exception {
        Map<String, Object> request = new HashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        request.put("params", Map.of("name", tool, "arguments", arguments));
        HttpRequest httpRequest =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)))
                        .build();
        try (HttpClient client = noProxyClient()) {
            return client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpClient noProxyClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(java.net.ProxySelector.of(null))
                .build();
    }

    private static JsonNode data(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("result").path("structuredContent").path("data");
    }

    private static JsonNode error(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body())
                .path("result")
                .path("structuredContent")
                .path("error");
    }

    private String auditColumn(String requestId, String column) throws Exception {
        try (Connection connection = PRIMARY.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT " + column + " FROM audit_log WHERE request_id = ?")) {
            statement.setString(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "audit row missing");
                return result.getString(1);
            }
        }
    }
}
