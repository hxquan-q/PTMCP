package local.ptmcp.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.datasource.metadata.MysqlMetadataCollector;
import local.ptmcp.persistence.schema.SchemaSnapshotStore;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Fail-closed audit admission: when the primary metadata database cannot accept the START write,
 * the call must be rejected with HTTP 503 AUDIT_UNAVAILABLE and no business connection may be
 * opened. The write loss is produced by revoking the gateway database user's write privileges and
 * killing its pooled sessions — MySQL snapshots a connection's privileges at connect time, so only
 * fresh connections observe the revoke; the kill forces the pool to reconnect without them.
 */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpAuditResilienceTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PAT = "ptmcp_pat_" + TlsMysqlFixture.secret();
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final MySQLContainer META =
            new MySQLContainer(TlsMysqlFixture.IMAGE)
                    .withDatabaseName("mcp_audit_meta")
                    .withUsername("audit_owner")
                    .withPassword(TlsMysqlFixture.secret())
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final TlsMysqlFixture BUSINESS;
    private static final HikariDataSource OWNER;
    private static final Path WAL;
    private static Connection businessProbe;

    static {
        try {
            META.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(META.getJdbcUrl());
            config.setUsername(META.getUsername());
            config.setPassword(META.getPassword());
            config.setInitializationFailTimeout(15000);
            OWNER = new HikariDataSource(config);
            Flyway.configure()
                    .dataSource(OWNER)
                    .locations("classpath:db/ptmcp-migration")
                    .load()
                    .migrate();
            BUSINESS = new TlsMysqlFixture();
            WAL = Files.createTempDirectory("ptmcp-audit-wal-");
            seed();
            businessProbe = BUSINESS.connect();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @LocalServerPort private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry properties) {
        properties.add("ptmcp.gateway.enabled", () -> "true");
        properties.add("ptmcp.gateway.metadata-url", META::getJdbcUrl);
        properties.add("ptmcp.gateway.metadata-username", META::getUsername);
        properties.add("ptmcp.gateway.metadata-password", META::getPassword);
        properties.add("ptmcp.gateway.credential-key", () -> KEY);
        properties.add("ptmcp.gateway.allowed-cidrs", TlsMysqlFixture::allowedCidrs);
        properties.add(
                "ptmcp.gateway.allowed-ports",
                () -> Integer.toString(BUSINESS.container.getMappedPort(3306)));
        properties.add("ptmcp.gateway.truststore-path", () -> BUSINESS.trustStore.toString());
        properties.add("ptmcp.gateway.truststore-password", () -> BUSINESS.password);
        properties.add("ptmcp.gateway.wal-directory", WAL::toString);
        properties.add("ptmcp.gateway.wal-key", () -> KEY);
        properties.add("ptmcp.gateway.issuer", () -> "https://as.test.invalid");
        properties.add("ptmcp.gateway.resource", () -> "https://mcp.test.invalid/mcp");
        properties.add("ptmcp.gateway.jwks-uri", () -> "https://as.test.invalid/oauth2/jwks");
    }

    private static void seed() throws Exception {
        try (Connection business = BUSINESS.connect();
                Statement sql = business.createStatement()) {
            sql.execute("CREATE TABLE probe (id BIGINT PRIMARY KEY, label VARCHAR(64))");
            sql.execute("INSERT INTO probe (id, label) VALUES (1,'synthetic')");
        }
        try (Connection meta = OWNER.getConnection();
                Statement sql = meta.createStatement()) {
            sql.execute(
                    "INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,status,del_flag,create_time,update_time) VALUES (1,0,'0','test','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,create_time,update_time) VALUES (1,1,'test','unused','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO platform_registry (id,code,name,tier,status,catalog_mode,proof_mode,create_time,update_time,created_by,updated_by) VALUES (1,'test','test',3,'ACTIVE','DISCOVER','NONE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
        }
        CredentialCipher.Sealed credential = new CredentialCipher(KEY).encrypt(BUSINESS.password);
        try (Connection meta = OWNER.getConnection();
                PreparedStatement sql =
                        meta.prepareStatement(
                                "INSERT INTO datasource (id,code,name,engine,host,port,database_name,username,credential_cipher,key_id,tls_mode,pool_max,status,create_time,update_time,created_by,updated_by) VALUES (1,'test','test','MYSQL',?,?,?,?,?,?,'VERIFY_FULL',2,'ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)")) {
            sql.setString(1, BUSINESS.container.getHost());
            sql.setInt(2, BUSINESS.container.getMappedPort(3306));
            sql.setString(3, BUSINESS.container.getDatabaseName());
            sql.setString(4, BUSINESS.container.getUsername());
            sql.setBytes(5, credential.ciphertext());
            sql.setString(6, credential.keyId());
            sql.executeUpdate();
        }
        SchemaSnapshotStore snapshots = new SchemaSnapshotStore(OWNER);
        try (Connection business = BUSINESS.connect()) {
            long id =
                    snapshots.stage(
                            1,
                            new MysqlMetadataCollector().collect(business),
                            LocalDateTime.now(ZoneOffset.UTC));
            snapshots.publish(id, 1);
        }
        try (Connection meta = OWNER.getConnection();
                Statement sql = meta.createStatement()) {
            sql.execute(
                    "INSERT INTO platform_scope (platform_id,resource_type,resource_id,resource_generation,action,include_descendants,create_time,update_time,created_by,updated_by) VALUES (1,'DATASOURCE',1,1,'READ',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1),(1,'DATASOURCE',1,1,'META',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
            sql.execute(
                    "INSERT INTO acl_grant (subject_type,subject_id,resource_type,resource_id,resource_generation,action,effect,include_descendants,valid_from,status,reason,create_time,update_time,created_by,updated_by) VALUES ('USER',1,'DATASOURCE',1,1,'READ','ALLOW',1,UTC_TIMESTAMP(6),'ACTIVE','synthetic',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
        }
        try (Connection meta = OWNER.getConnection();
                PreparedStatement sql =
                        meta.prepareStatement(
                                "INSERT INTO personal_token (user_id,platform_id,token_prefix,token_hash,name,scopes,auth_version,expires_at,status,version,create_time,update_time,created_by,updated_by) VALUES (1,1,?,?, 'test',JSON_ARRAY('mcp:query'),1,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),'ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)")) {
            sql.setString(1, PAT.substring(0, 16));
            sql.setBytes(
                    2,
                    MessageDigest.getInstance("SHA-256")
                            .digest(PAT.getBytes(StandardCharsets.UTF_8)));
            sql.executeUpdate();
        }
    }

    @Test
    void writablePrimarySucceedsThenRevokedWriteFailsClosedWithoutBusinessCheckout()
            throws Exception {
        HttpResponse<String> allowed = call("SELECT id, label FROM probe");
        assertEquals(200, allowed.statusCode(), allowed.body());
        JsonNode result = JSON.readTree(allowed.body()).path("result");
        assertFalse(result.path("isError").asBoolean(true), allowed.body());
        assertEquals(
                "synthetic",
                result.path("structuredContent").path("data").path("rows").get(0).get(1).asText());
        assertTrue(businessConnections() > 0);
        // One tool call is exactly one audit_log row: START inserts it, the terminal updates it.
        assertEquals(1, auditRows());

        long connectionsBefore = businessConnections();
        int auditRowsBefore = auditRows();
        Connection lockHolder = OWNER.getConnection();
        try {
            lockHolder.setAutoCommit(false);
            lockHolder.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (Statement lock = lockHolder.createStatement();
                    ResultSet rows =
                            lock.executeQuery("SELECT event_id FROM audit_outbox FOR UPDATE")) {
                while (rows.next()) {
                    // Iterate the full result so the next-key locks cover every index gap.
                }
            }
            HttpResponse<String> rejected = call("SELECT id, label FROM probe");
            assertEquals(503, rejected.statusCode(), rejected.body());
            assertEquals(
                    "AUDIT_UNAVAILABLE",
                    JSON.readTree(rejected.body()).path("error").path("code").asText(),
                    rejected.body());
            assertEquals(connectionsBefore, businessConnections());
            assertEquals(auditRowsBefore, auditRows());
        } finally {
            lockHolder.rollback();
            lockHolder.close();
        }
    }

    private int auditRows() throws Exception {
        try (Connection meta = OWNER.getConnection();
                PreparedStatement sql = meta.prepareStatement("SELECT COUNT(*) FROM audit_log");
                ResultSet result = sql.executeQuery()) {
            assertTrue(result.next());
            return result.getInt(1);
        }
    }

    private long businessConnections() throws Exception {
        try {
            return readConnectionCount();
        } catch (SQLException stale) {
            // The local port forward can reset idle probes; one reconnect keeps the counter
            // meaningful without hiding a real failure.
            businessProbe = BUSINESS.connect();
            return readConnectionCount();
        }
    }

    private long readConnectionCount() throws Exception {
        try (Statement statement = businessProbe.createStatement();
                ResultSet result =
                        statement.executeQuery("SHOW GLOBAL STATUS LIKE 'Connections'")) {
            assertTrue(result.next());
            return result.getLong(2);
        }
    }

    private HttpResponse<String> call(String query) throws Exception {
        Map<String, Object> request =
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        1,
                        "method",
                        "tools/call",
                        "params",
                        Map.of(
                                "name",
                                "execute_readonly_sql",
                                "arguments",
                                Map.of(
                                        "datasource_id",
                                        "1",
                                        "database_name",
                                        BUSINESS.container.getDatabaseName(),
                                        "sql_query",
                                        query)));
        try (HttpClient client = noProxyClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", "Bearer " + PAT)
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            JSON.writeValueAsString(request)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpClient noProxyClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(
                        new java.net.ProxySelector() {
                            @Override
                            public List<java.net.Proxy> select(URI uri) {
                                return List.of(java.net.Proxy.NO_PROXY);
                            }

                            @Override
                            public void connectFailed(
                                    URI uri,
                                    java.net.SocketAddress address,
                                    java.io.IOException failure) {}
                        })
                .build();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (businessProbe != null) {
            businessProbe.close();
        }
        OWNER.close();
        BUSINESS.close();
        META.stop();
    }
}
