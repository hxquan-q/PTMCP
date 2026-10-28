package local.ptmcp.gateway;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.datasource.DataSourcePoolRegistry;
import local.ptmcp.datasource.MysqlReadOnlyExecutor;
import local.ptmcp.datasource.PoolHandle;
import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.datasource.metadata.MysqlMetadataCollector;
import local.ptmcp.persistence.schema.SchemaSnapshotStore;
import local.ptmcp.persistence.security.QuotaLimiter;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Real HTTP, real metadata primary, real TLS business database and real guard/executor/audit. */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpQueryFlowTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    // Distinct from the credential key on purpose: a green run proves the terminal sealed with
    // the audit key, not with whichever key happened to be lying around in the context.
    private static final String AUDIT_KEY = base64Key();
    private static final String PAT = "ptmcp_pat_" + TlsMysqlFixture.secret();
    private static final String PAT_REVOKED = "ptmcp_pat_" + TlsMysqlFixture.secret();
    private static final String PAT_EXPIRED = "ptmcp_pat_" + TlsMysqlFixture.secret();
    private static final String RESOURCE = "https://mcp.test.invalid/mcp";
    private static final MySQLContainer META =
            new MySQLContainer(TlsMysqlFixture.IMAGE)
                    .withDatabaseName("mcp_flow_meta")
                    .withUsername("flow_user")
                    .withPassword(TlsMysqlFixture.secret())
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final TlsMysqlFixture BUSINESS;
    private static final HikariDataSource PRIMARY;
    private static final Path WAL;
    private static final HttpServer JWKS;
    private static final String ISSUER;
    private static final KeyPair SIGNING_KEY = rsaKey();
    private static final KeyPair UNKNOWN_KEY = rsaKey();
    private static final UUID FAMILY = UUID.randomUUID();
    private static final UUID FAMILY_REVOKED = UUID.randomUUID();
    private static Connection businessProbe;

    static {
        try {
            META.start();
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(META.getJdbcUrl());
            config.setUsername(META.getUsername());
            config.setPassword(META.getPassword());
            config.setInitializationFailTimeout(15000);
            PRIMARY = new HikariDataSource(config);
            Flyway.configure()
                    .dataSource(PRIMARY)
                    .locations("classpath:db/ptmcp-migration")
                    .load()
                    .migrate();
            BUSINESS = new TlsMysqlFixture();
            WAL = Files.createTempDirectory("ptmcp-flow-wal-");
            JWKS = startJwksServer();
            ISSUER = "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/";
            seed();
            businessProbe = BUSINESS.connect();
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @LocalServerPort private int port;

    @MockitoSpyBean private QuotaLimiter quotas;
    @MockitoSpyBean private MysqlReadOnlyExecutor businessExecutor;
    @MockitoSpyBean private DataSourcePoolRegistry businessPools;

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
        properties.add("ptmcp.gateway.audit-key", () -> AUDIT_KEY);
        properties.add("ptmcp.gateway.issuer", () -> ISSUER);
        properties.add("ptmcp.gateway.resource", () -> RESOURCE);
        properties.add("ptmcp.gateway.jwks-uri", () -> ISSUER + "jwks");
    }

    private static void seed() throws Exception {
        try (Connection business = BUSINESS.connect();
                var sql = business.createStatement()) {
            sql.execute(
                    "CREATE TABLE orders (id BIGINT PRIMARY KEY, amount DECIMAL(12,2), note VARCHAR(255))");
            sql.execute(
                    "INSERT INTO orders (id, amount, note) VALUES (1,12.34,'synthetic'), (2,56.78,'測試😀')");
            sql.execute("CREATE TABLE secret (id BIGINT PRIMARY KEY)");
            sql.execute("CREATE TABLE wide_rows (id BIGINT PRIMARY KEY, payload LONGTEXT)");
            StringBuilder payload = new StringBuilder();
            for (int index = 0; index < 5 * 1024; index++) {
                payload.append('x');
            }
            try (PreparedStatement insert =
                    business.prepareStatement(
                            "INSERT INTO wide_rows (id, payload) VALUES (?, ?)")) {
                for (int id = 1; id <= 300; id++) {
                    insert.setInt(1, id);
                    insert.setString(2, payload.toString());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        try (Connection meta = PRIMARY.getConnection();
                var sql = meta.createStatement()) {
            sql.execute(
                    "INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,status,del_flag,create_time,update_time) VALUES (1,0,'0','test','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,create_time,update_time) VALUES (1,1,'test','unused','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            sql.execute(
                    "INSERT INTO platform_registry (id,code,name,tier,status,catalog_mode,proof_mode,create_time,update_time,created_by,updated_by) VALUES (1,'test','test',3,'ACTIVE','DISCOVER','NONE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
        }
        CredentialCipher.Sealed credential = new CredentialCipher(KEY).encrypt(BUSINESS.password);
        try (Connection meta = PRIMARY.getConnection();
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
        SchemaSnapshotStore snapshots = new SchemaSnapshotStore(PRIMARY);
        try (Connection business = BUSINESS.connect()) {
            long id =
                    snapshots.stage(
                            1,
                            new MysqlMetadataCollector().collect(business),
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
                PreparedStatement select =
                        meta.prepareStatement(
                                "SELECT id, generation FROM resource_object WHERE object_norm ="
                                        + " 'secret'");
                ResultSet found = select.executeQuery()) {
            assertTrue(found.next());
            long secretObjectId = found.getLong(1);
            long secretGeneration = found.getLong(2);
            try (PreparedStatement deny =
                    meta.prepareStatement(
                            "INSERT INTO acl_grant (subject_type,subject_id,resource_type,"
                                    + " resource_id,resource_generation,action,effect,"
                                    + " include_descendants,valid_from,status,reason,create_time,"
                                    + " update_time,created_by,updated_by) VALUES"
                                    + " ('USER',1,'OBJECT',?,?, 'READ','DENY',0,UTC_TIMESTAMP(6),"
                                    + " 'ACTIVE','synthetic deny',UTC_TIMESTAMP(6),"
                                    + " UTC_TIMESTAMP(6),1,1)")) {
                deny.setLong(1, secretObjectId);
                deny.setLong(2, secretGeneration);
                assertEquals(1, deny.executeUpdate());
            }
        }

        insertPat(PAT, "primary", 1);
        insertPat(PAT_REVOKED, "revocation-target", 1);
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement revoke =
                        meta.prepareStatement(
                                "UPDATE personal_token SET status = 'REVOKED' WHERE token_hash ="
                                        + " ?")) {
            revoke.setBytes(1, sha256(PAT_REVOKED));
            revoke.executeUpdate();
        }
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement expired =
                        meta.prepareStatement(
                                "INSERT INTO personal_token (user_id,platform_id,token_prefix,"
                                        + " token_hash,name,scopes,auth_version,expires_at,status,"
                                        + " version,create_time,update_time,created_by,updated_by)"
                                        + " VALUES (1,1,?,?, 'expired',"
                                        + " JSON_ARRAY('mcp:query'),1,DATE_SUB(UTC_TIMESTAMP(6),"
                                        + " INTERVAL 1 DAY),'ACTIVE',1,DATE_SUB(UTC_TIMESTAMP(6),"
                                        + " INTERVAL 2 DAY),DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2"
                                        + " DAY),1,1)")) {
            expired.setString(1, PAT_EXPIRED.substring(0, 16));
            expired.setBytes(2, sha256(PAT_EXPIRED));
            expired.executeUpdate();
        }
        seedJwtFamily("client-1", "auth-1", FAMILY, "ACTIVE");
        seedJwtFamily("client-2", "auth-2", FAMILY_REVOKED, "REVOKED");
    }

    private static void seedJwtFamily(
            String clientId, String authorizationId, UUID familyId, String status)
            throws Exception {
        try (Connection meta = PRIMARY.getConnection();
                var sql = meta.createStatement()) {
            sql.execute(
                    "INSERT INTO oauth2_registered_client (id, client_id, client_name,"
                            + " client_authentication_methods, authorization_grant_types, scopes,"
                            + " client_settings, token_settings, client_type, status,"
                            + " registration_source, platform_id, create_time, update_time,"
                            + " created_by, updated_by) VALUES ('"
                            + clientId
                            + "','"
                            + clientId
                            + "','Flow Test Client','none','authorization_code,refresh_token',"
                            + " 'mcp:query,mcp:catalog,mcp:schema','{}','{}','PUBLIC','ACTIVE',"
                            + " 'ADMIN',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)");
            sql.execute(
                    "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name,"
                            + " authorization_grant_type, auth_version, status, version,"
                            + " create_time, update_time, created_by, updated_by) VALUES ('"
                            + authorizationId
                            + "','"
                            + clientId
                            + "','1','authorization_code',1,'ACTIVE',1,UTC_TIMESTAMP(6),"
                            + " UTC_TIMESTAMP(6),1,1)");
            sql.execute(
                    "INSERT INTO oauth_refresh_family (id, authorization_id, user_id,"
                            + " registered_client_id, platform_id, status, auth_version,"
                            + " absolute_expires_at, idle_expires_at, last_rotated_at, revoked_at,"
                            + " version, create_time, update_time) VALUES ('"
                            + familyId
                            + "','"
                            + authorizationId
                            + "',1,'"
                            + clientId
                            + "',1,'"
                            + status
                            + "',1,DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY),"
                            + " DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 DAY),UTC_TIMESTAMP(6),"
                            + ("REVOKED".equals(status) ? "UTC_TIMESTAMP(6)" : "NULL")
                            + ",1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        }
    }

    private static void insertPat(String token, String name, long platformId) throws Exception {
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement sql =
                        meta.prepareStatement(
                                "INSERT INTO personal_token (user_id,platform_id,token_prefix,"
                                        + " token_hash,name,scopes,auth_version,expires_at,status,"
                                        + " version,create_time,update_time,created_by,updated_by)"
                                        + " VALUES (1,?,?,?,?,JSON_ARRAY('mcp:catalog','mcp:schema',"
                                        + " 'mcp:query'),1,DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1"
                                        + " DAY),'ACTIVE',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)")) {
            sql.setLong(1, platformId);
            sql.setString(2, token.substring(0, 16));
            sql.setBytes(3, sha256(token));
            sql.setString(4, name);
            sql.executeUpdate();
        }
    }

    @Test
    void allowedQueryReturnsRowsAndDurableStartAndTerminal() throws Exception {
        HttpResponse<String> response =
                call("SELECT id, amount, note FROM orders ORDER BY id", PAT);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = JSON.readTree(response.body()).path("result");
        assertFalse(result.path("isError").asBoolean(true), response.body());
        assertEquals(
                "1",
                result.path("structuredContent").path("data").path("rows").get(0).get(0).asText());
        assertEquals(
                "12.34",
                result.path("structuredContent").path("data").path("rows").get(0).get(1).asText());
        assertEquals(
                result.path("structuredContent"),
                JSON.readTree(result.path("content").get(0).path("text").asText()));
        String requestId = result.path("structuredContent").path("requestId").asText();
        assertTerminal(requestId, "SUCCESS", "ALLOW");
    }

    /**
     * End-to-end proof of the audit-sealing slice: the success terminal stores the raw SQL as an
     * AES-GCM ciphertext sealed with the configured audit key (not the credential key), and the
     * same wire format the admin console's reader uses decrypts it back to the exact query text.
     */
    @Test
    void successfulQuerySealsRawSqlWithTheAuditKeyForConsoleReveal() throws Exception {
        String sql = "SELECT id FROM orders WHERE id = 1";
        HttpResponse<String> response = call(sql, PAT);
        assertEquals(200, response.statusCode(), response.body());
        String requestId =
                JSON.readTree(response.body())
                        .path("result")
                        .path("structuredContent")
                        .path("requestId")
                        .asText();

        byte[] rawKey = Base64.getDecoder().decode(AUDIT_KEY);
        byte[] sealed;
        byte[] sealedFinal;
        String keyId;
        String masked;
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement sqlStatement =
                        meta.prepareStatement(
                                "SELECT sql_raw_cipher, sql_final_cipher, key_id, sql_masked"
                                        + " FROM audit_log WHERE request_id = ?")) {
            sqlStatement.setString(1, requestId);
            try (ResultSet row = sqlStatement.executeQuery()) {
                assertTrue(row.next(), "missing audit row for " + requestId);
                sealed = row.getBytes(1);
                sealedFinal = row.getBytes(2);
                keyId = row.getString(3);
                masked = row.getString(4);
            }
        }
        // The masked form is plaintext-safe: literals gone, structure readable.
        assertNotNull(masked, "masked sql should accompany the sealed raw sql");
        assertFalse(masked.contains("1"), "numeric literal survived masking: " + masked);
        assertTrue(masked.contains("SELECT id FROM orders WHERE id = ?"), masked);
        // The final form (post-rewrite SQL that actually reached JDBC) is sealed with the same
        // audit key; decrypting it must produce the executed statement.
        assertNotNull(sealedFinal, "final sql ciphertext missing");
        javax.crypto.Cipher finalCipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        finalCipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                new javax.crypto.spec.SecretKeySpec(rawKey, "AES"),
                new javax.crypto.spec.GCMParameterSpec(128, sealedFinal, 0, 12));
        String finalRevealed =
                new String(
                        finalCipher.doFinal(sealedFinal, 12, sealedFinal.length - 12),
                        java.nio.charset.StandardCharsets.UTF_8);
        // The final SQL is the guard-rewritten statement that actually reached JDBC — schema
        // qualified, alias renamed, safety LIMIT injected. Assert the rewrite fingerprints
        // instead of the raw text.
        assertTrue(finalRevealed.contains("synthetic_business.orders"), finalRevealed);
        assertTrue(finalRevealed.contains("__ptmcp_r1"), finalRevealed);
        assertTrue(finalRevealed.toUpperCase().contains("LIMIT"), finalRevealed);
        assertFalse(finalRevealed.equals(sql), "final form must differ from the raw query");
        assertNotNull(sealed, "success terminal must carry the sealed raw SQL");
        assertTrue(sealed.length > 12 + 16, "ciphertext shorter than iv + tag");
        String expectedKeyId =
                "local:"
                        + java.util.HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(rawKey))
                                .substring(0, 16);
        assertEquals(expectedKeyId, keyId, "terminal key id must derive from the audit key");

        javax.crypto.spec.SecretKeySpec auditKey =
                new javax.crypto.spec.SecretKeySpec(rawKey, "AES");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                auditKey,
                new javax.crypto.spec.GCMParameterSpec(128, sealed, 0, 12));
        String revealed =
                new String(
                        cipher.doFinal(sealed, 12, sealed.length - 12),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(sql, revealed, "decrypted plaintext must match the executed query");
    }

    @Test
    void unicodeValuesRoundTripThroughTheResponse() throws Exception {
        HttpResponse<String> response = call("SELECT note FROM orders WHERE id = 2", PAT);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data =
                JSON.readTree(response.body())
                        .path("result")
                        .path("structuredContent")
                        .path("data");
        assertEquals("測試😀", data.path("rows").get(0).get(0).asText());
        assertFalse(data.path("truncated").asBoolean(true));
    }

    @Test
    void deniedTableIsRejectedWithoutABusinessCheckout() throws Exception {
        long before = businessConnections();
        HttpResponse<String> response = call("SELECT id FROM secret", PAT);
        long after = businessConnections();
        assertEquals(200, response.statusCode(), response.body());
        JsonNode result = JSON.readTree(response.body()).path("result");
        assertTrue(result.path("isError").asBoolean(false), response.body());
        assertEquals(
                "TABLE_NOT_AUTHORIZED",
                result.path("structuredContent").path("error").path("code").asText());
        assertEquals("denied", result.path("structuredContent").path("status").asText());
        assertEquals(before, after);
        String deniedRequestId = result.path("structuredContent").path("requestId").asText();
        assertTerminal(deniedRequestId, "ERROR", "DENY");
        // The attempted SQL is sealed even for guard denials: what a caller tried and was
        // refused is what an auditor reads behind the console's reason gate.
        String attempted = "SELECT id FROM secret";
        byte[] sealed;
        String keyId;
        String deniedMasked;
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement sqlStatement =
                        meta.prepareStatement(
                                "SELECT sql_raw_cipher, key_id, sql_masked FROM audit_log"
                                        + " WHERE request_id = ?")) {
            sqlStatement.setString(1, deniedRequestId);
            try (ResultSet row = sqlStatement.executeQuery()) {
                assertTrue(row.next(), "missing audit row for " + deniedRequestId);
                sealed = row.getBytes(1);
                keyId = row.getString(2);
                deniedMasked = row.getString(3);
            }
        }
        assertNotNull(deniedMasked, "denied terminal should carry the masked attempt");
        assertEquals("SELECT id FROM secret", deniedMasked);
        assertNotNull(sealed, "denied terminal must carry the sealed attempted SQL");
        byte[] rawKey = Base64.getDecoder().decode(AUDIT_KEY);
        String expectedKeyId =
                "local:"
                        + java.util.HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(rawKey))
                                .substring(0, 16);
        assertEquals(expectedKeyId, keyId);
        javax.crypto.spec.SecretKeySpec auditKey =
                new javax.crypto.spec.SecretKeySpec(rawKey, "AES");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                auditKey,
                new javax.crypto.spec.GCMParameterSpec(128, sealed, 0, 12));
        String revealed =
                new String(
                        cipher.doFinal(sealed, 12, sealed.length - 12),
                        java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(attempted, revealed);
    }

    @Test
    void userConcurrencyLimitQueuesThenRejectsWithoutBusinessCheckout() throws Exception {
        List<QuotaLimiter.ExecutionPermit> held = new java.util.ArrayList<>();
        for (int index = 0; index < QuotaLimiter.USER_MAX; index++) {
            QuotaLimiter.QueuePermit queued = quotas.acquireQueue(1);
            held.add(quotas.acquireExecution(1, 1, queued));
        }
        long before = businessConnections();
        try {
            long started = System.nanoTime();
            HttpResponse<String> response = call("SELECT id FROM orders", PAT);
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertEquals(200, response.statusCode(), response.body());
            JsonNode result = JSON.readTree(response.body()).path("result");
            assertTrue(result.path("isError").asBoolean(false), response.body());
            assertEquals(
                    "GATEWAY_BUSY",
                    result.path("structuredContent").path("error").path("code").asText());
            assertTrue(
                    elapsedMillis >= QuotaLimiter.QUEUE_WINDOW_NANOS / 1_000_000 - 500,
                    "expected a bounded queue wait, got " + elapsedMillis + "ms");
            assertEquals(before, businessConnections());
            assertTerminal(
                    result.path("structuredContent").path("requestId").asText(), "ERROR", "ALLOW");
        } finally {
            held.forEach(quotas::release);
        }
        HttpResponse<String> after = call("SELECT id FROM orders", PAT);
        assertEquals(200, after.statusCode(), after.body());
        assertFalse(
                JSON.readTree(after.body()).path("result").path("isError").asBoolean(true),
                after.body());
    }

    @Test
    void staleConfigurationRejectsAllToolsWithoutBusinessCheckout() throws Exception {
        long before = businessConnections();
        long executedBefore = businessExecutionCount();
        updateDatasourceConfigVersion(2);
        try {
            HttpResponse<String> query = call("SELECT id FROM orders", PAT);
            HttpResponse<String> catalog =
                    toolCall("list_databases_and_tables", Map.of("datasource_id", "1"), PAT);
            HttpResponse<String> schema =
                    toolCall(
                            "get_table_schema",
                            Map.of(
                                    "datasource_id", "1",
                                    "database_name", BUSINESS.container.getDatabaseName(),
                                    "table_names", List.of("orders")),
                            PAT);
            assertAll(
                    () -> assertToolError(query, "SCHEMA_STALE"),
                    () -> assertToolError(catalog, "SCHEMA_STALE"),
                    () -> assertToolError(schema, "SCHEMA_STALE"),
                    () -> assertEquals(executedBefore, businessExecutionCount()),
                    () -> assertEquals(before, businessConnections()));
        } finally {
            updateDatasourceConfigVersion(1);
        }
    }

    @Test
    void queuedReadRevocationIsRejectedWithoutBusinessCheckout() throws Exception {
        List<QuotaLimiter.ExecutionPermit> held = holdUserExecutionPermits();
        long before = businessConnections();
        long executedBefore = businessExecutionCount();
        try (HttpClient client = noProxyClient()) {
            clearInvocations(quotas);
            CompletableFuture<HttpResponse<String>> pending =
                    client.sendAsync(
                            request(91, "SELECT id FROM orders", PAT),
                            HttpResponse.BodyHandlers.ofString());
            try {
                verify(quotas, timeout(10_000).atLeastOnce())
                        .acquireExecution(eq(1L), eq(1L), any());
                updateDatasourceReadGrantStatus("REVOKED");
                held.forEach(quotas::release);
                held.clear();
                HttpResponse<String> response = pending.get(10, TimeUnit.SECONDS);
                assertAll(
                        () -> assertToolError(response, "TABLE_NOT_AUTHORIZED"),
                        () -> assertEquals(executedBefore, businessExecutionCount()),
                        () -> assertEquals(before, businessConnections()));
            } finally {
                held.forEach(quotas::release);
                held.clear();
                try {
                    pending.get(10, TimeUnit.SECONDS);
                } finally {
                    updateDatasourceReadGrantStatus("ACTIVE");
                }
            }
        } finally {
            held.forEach(quotas::release);
        }
    }

    @Test
    void queuedConfigurationChangeIsRejectedWithoutBusinessCheckout() throws Exception {
        List<QuotaLimiter.ExecutionPermit> held = holdUserExecutionPermits();
        long before = businessConnections();
        long executedBefore = businessExecutionCount();
        try (HttpClient client = noProxyClient()) {
            clearInvocations(quotas);
            CompletableFuture<HttpResponse<String>> pending =
                    client.sendAsync(
                            request(92, "SELECT id FROM orders", PAT),
                            HttpResponse.BodyHandlers.ofString());
            try {
                verify(quotas, timeout(10_000).atLeastOnce())
                        .acquireExecution(eq(1L), eq(1L), any());
                updateDatasourceConfigVersion(2);
                held.forEach(quotas::release);
                held.clear();
                HttpResponse<String> response = pending.get(10, TimeUnit.SECONDS);
                assertAll(
                        () -> assertToolError(response, "SCHEMA_STALE"),
                        () -> assertEquals(executedBefore, businessExecutionCount()),
                        () -> assertEquals(before, businessConnections()));
            } finally {
                held.forEach(quotas::release);
                held.clear();
                try {
                    pending.get(10, TimeUnit.SECONDS);
                } finally {
                    updateDatasourceConfigVersion(1);
                }
            }
        } finally {
            held.forEach(quotas::release);
        }
    }

    @Test
    void queuedPolicyChangeIsRetryableWithoutBusinessCheckout() throws Exception {
        List<QuotaLimiter.ExecutionPermit> held = holdUserExecutionPermits();
        long before = businessConnections();
        long executedBefore = businessExecutionCount();
        try (HttpClient client = noProxyClient()) {
            clearInvocations(quotas);
            CompletableFuture<HttpResponse<String>> pending =
                    client.sendAsync(
                            request(93, "SELECT id FROM orders", PAT),
                            HttpResponse.BodyHandlers.ofString());
            try {
                verify(quotas, timeout(10_000).atLeastOnce())
                        .acquireExecution(eq(1L), eq(1L), any());
                updateDatasourceReadGrantStatus("ACTIVE");
                held.forEach(quotas::release);
                held.clear();
                HttpResponse<String> response = pending.get(10, TimeUnit.SECONDS);
                assertToolError(response, "SECURITY_STATE_UNAVAILABLE");
                JsonNode content =
                        JSON.readTree(response.body()).path("result").path("structuredContent");
                assertTrue(content.path("error").path("retryable").asBoolean());
                assertTerminal(content.path("requestId").asText(), "ERROR", "ERROR");
                assertEquals(executedBefore, businessExecutionCount());
                assertEquals(before, businessConnections());
            } finally {
                held.forEach(quotas::release);
                held.clear();
                pending.get(10, TimeUnit.SECONDS);
            }
        } finally {
            held.forEach(quotas::release);
        }
    }

    @Test
    void readRevokedAfterPoolActivationIsRejectedBeforeCheckout() throws Exception {
        assertChangeAfterPoolActivation(false);
    }

    @Test
    void configurationChangedAfterPoolActivationIsRejectedBeforeCheckout() throws Exception {
        assertChangeAfterPoolActivation(true);
    }

    private void assertChangeAfterPoolActivation(boolean configuration) throws Exception {
        long before = businessConnections();
        long executedBefore = businessExecutionCount();
        doAnswer(
                        invocation -> {
                            PoolHandle handle = (PoolHandle) invocation.callRealMethod();
                            if (configuration) {
                                updateDatasourceConfigVersion(2);
                            } else {
                                updateDatasourceReadGrantStatus("REVOKED");
                            }
                            return handle;
                        })
                .when(businessPools)
                .activate(any(), any(), any());
        try {
            HttpResponse<String> response = call("SELECT id FROM orders", PAT);
            assertToolError(response, configuration ? "SCHEMA_STALE" : "TABLE_NOT_AUTHORIZED");
            JsonNode content =
                    JSON.readTree(response.body()).path("result").path("structuredContent");
            assertTerminal(content.path("requestId").asText(), "ERROR", "ERROR");
            assertEquals(executedBefore, businessExecutionCount());
            assertEquals(before, businessConnections());
        } finally {
            doCallRealMethod().when(businessPools).activate(any(), any(), any());
            if (configuration) {
                updateDatasourceConfigVersion(1);
            } else {
                updateDatasourceReadGrantStatus("ACTIVE");
            }
        }
    }

    @Test
    void writeSqlIsRejectedByTheGuardBeforeCheckout() throws Exception {
        long before = businessConnections();
        HttpResponse<String> response = call("UPDATE orders SET note = 'x' WHERE id = 1", PAT);
        long after = businessConnections();
        assertEquals(200, response.statusCode(), response.body());
        JsonNode error =
                JSON.readTree(response.body())
                        .path("result")
                        .path("structuredContent")
                        .path("error");
        assertEquals("SQL_NOT_READONLY", error.path("code").asText());
        assertEquals(before, after);
    }

    @Test
    void multiStatementAndUnknownSyntaxAreRejected() throws Exception {
        HttpResponse<String> multi = call("SELECT id FROM orders; SELECT id FROM orders", PAT);
        assertEquals(200, multi.statusCode(), multi.body());
        assertEquals(
                "SQL_NOT_READONLY",
                JSON.readTree(multi.body())
                        .path("result")
                        .path("structuredContent")
                        .path("error")
                        .path("code")
                        .asText(),
                multi.body());

        HttpResponse<String> unknown = call("DESCRIBE orders", PAT);
        assertEquals(200, unknown.statusCode(), unknown.body());
        String unknownCode =
                JSON.readTree(unknown.body())
                        .path("result")
                        .path("structuredContent")
                        .path("error")
                        .path("code")
                        .asText();
        assertTrue(
                unknownCode.equals("SQL_UNSUPPORTED")
                        || unknownCode.equals("SQL_SYNTAX_INVALID")
                        || unknownCode.equals("SQL_NOT_READONLY"),
                unknown.body());
    }

    @Test
    void unknownTableAndUnknownDatabaseAreRejected() throws Exception {
        HttpResponse<String> missingTable = call("SELECT * FROM missing_table", PAT);
        assertEquals(200, missingTable.statusCode(), missingTable.body());
        assertEquals(
                "RESOURCE_NOT_FOUND",
                JSON.readTree(missingTable.body())
                        .path("result")
                        .path("structuredContent")
                        .path("error")
                        .path("code")
                        .asText());

        HttpResponse<String> wrongDatabase =
                call("SELECT * FROM orders", PAT, Map.of("database_name", "other_database"));
        assertEquals(200, wrongDatabase.statusCode(), wrongDatabase.body());
        assertEquals(
                "RESOURCE_NOT_FOUND",
                JSON.readTree(wrongDatabase.body())
                        .path("result")
                        .path("structuredContent")
                        .path("error")
                        .path("code")
                        .asText());
    }

    @Test
    void bearerAndPlatformBoundariesAreEnforced() throws Exception {
        HttpResponse<String> unknownToken =
                call("SELECT 1", "ptmcp_pat_" + TlsMysqlFixture.secret());
        assertEquals(401, unknownToken.statusCode(), unknownToken.body());

        HttpResponse<String> revoked = call("SELECT 1", PAT_REVOKED);
        assertEquals(401, revoked.statusCode(), revoked.body());
        assertEquals(
                "TOKEN_REVOKED", JSON.readTree(revoked.body()).path("error").path("code").asText());

        HttpResponse<String> expired = call("SELECT 1", PAT_EXPIRED);
        assertEquals(401, expired.statusCode(), expired.body());
        assertEquals(
                "TOKEN_INVALID", JSON.readTree(expired.body()).path("error").path("code").asText());

        HttpResponse<String> mismatch =
                rawCall(request(1, "SELECT 1", PAT), Map.of("X-Platform-ID", "999"));
        assertEquals(403, mismatch.statusCode(), mismatch.body());
        assertEquals(
                "PLATFORM_MISMATCH",
                JSON.readTree(mismatch.body()).path("error").path("code").asText());

        HttpResponse<String> consistent =
                rawCall(request(1, "SELECT 1", PAT), Map.of("X-Platform-ID", "1"));
        assertEquals(200, consistent.statusCode(), consistent.body());
    }

    @Test
    void jwtAllowedQuerySucceedsAndBadJwtIsRejected() throws Exception {
        HttpResponse<String> allowed = call("SELECT id FROM orders WHERE id = 1", jwt(FAMILY, 1));
        assertEquals(200, allowed.statusCode(), allowed.body());
        JsonNode result = JSON.readTree(allowed.body()).path("result");
        assertFalse(result.path("isError").asBoolean(true), allowed.body());
        assertTerminal(
                result.path("structuredContent").path("requestId").asText(), "SUCCESS", "ALLOW");

        HttpResponse<String> wrongKey =
                call("SELECT id FROM orders", jwtSignedWith(UNKNOWN_KEY, FAMILY, 1, null, null));
        assertEquals(401, wrongKey.statusCode(), wrongKey.body());
        assertEquals(
                "TOKEN_INVALID",
                JSON.readTree(wrongKey.body()).path("error").path("code").asText());

        HttpResponse<String> wrongAudience =
                call(
                        "SELECT id FROM orders",
                        jwtSignedWith(SIGNING_KEY, FAMILY, 1, null, "https://other.invalid/mcp"));
        assertEquals(401, wrongAudience.statusCode(), wrongAudience.body());
        assertEquals(
                "TOKEN_INVALID",
                JSON.readTree(wrongAudience.body()).path("error").path("code").asText());

        HttpResponse<String> wrongIssuer =
                call(
                        "SELECT id FROM orders",
                        jwtSignedWith(SIGNING_KEY, FAMILY, 1, "https://evil.invalid/", null));
        assertEquals(401, wrongIssuer.statusCode(), wrongIssuer.body());
        assertEquals(
                "TOKEN_INVALID",
                JSON.readTree(wrongIssuer.body()).path("error").path("code").asText());

        HttpResponse<String> revokedFamily = call("SELECT id FROM orders", jwt(FAMILY_REVOKED, 1));
        assertEquals(401, revokedFamily.statusCode(), revokedFamily.body());
        assertEquals(
                "TOKEN_REVOKED",
                JSON.readTree(revokedFamily.body()).path("error").path("code").asText());

        HttpResponse<String> versionMismatch = call("SELECT id FROM orders", jwt(FAMILY, 99));
        assertEquals(401, versionMismatch.statusCode(), versionMismatch.body());
    }

    @Test
    void cellBudgetTruncatesWideCellsOnCodepointBoundaries() throws Exception {
        HttpResponse<String> response =
                call("SELECT id, payload FROM wide_rows ORDER BY id LIMIT 5", PAT);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data =
                JSON.readTree(response.body())
                        .path("result")
                        .path("structuredContent")
                        .path("data");
        assertTrue(data.path("truncated").asBoolean(false), response.body());
        assertTrue(
                data.path("truncationReasons").toString().contains("CELL_LIMIT"), response.body());
        JsonNode truncations = data.path("cellTruncations");
        assertEquals(5, truncations.size());
        for (JsonNode truncation : truncations) {
            assertEquals("CELL_LIMIT", truncation.path("reason").asText());
            assertEquals(1, truncation.path("columnIndex").asInt());
            assertTrue(truncation.path("originalBytes").asInt() > 4096);
            assertTrue(truncation.path("returnedBytes").asInt() <= 4096);
        }
        String payload = data.path("rows").get(0).get(1).asText();
        assertTrue(payload.getBytes(StandardCharsets.UTF_8).length <= 4096);
    }

    @Test
    void responseBudgetDropsWholeRows() throws Exception {
        HttpResponse<String> response = call("SELECT payload FROM wide_rows", PAT);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data =
                JSON.readTree(response.body())
                        .path("result")
                        .path("structuredContent")
                        .path("data");
        assertTrue(data.path("truncated").asBoolean(false), response.body());
        assertTrue(
                data.path("truncationReasons").toString().contains("RESPONSE_LIMIT"),
                response.body());
        int rowsReturned = data.path("rowsReturned").asInt();
        assertTrue(rowsReturned > 0 && rowsReturned < 300, response.body());
        assertEquals(rowsReturned, data.path("rows").size());
        assertTrue(response.body().getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024);
    }

    @Test
    void rowLimitMarksPossibleTruncation() throws Exception {
        HttpResponse<String> response = call("SELECT id FROM orders", PAT, Map.of("max_rows", 1));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data =
                JSON.readTree(response.body())
                        .path("result")
                        .path("structuredContent")
                        .path("data");
        assertEquals(1, data.path("rowsReturned").asInt());
        assertTrue(data.path("truncated").asBoolean(false));
        assertTrue(data.path("truncationReasons").toString().contains("ROW_LIMIT"));
    }

    @Test
    void toolsListExposesExactlyTheThreeClosedTools() throws Exception {
        HttpResponse<String> response =
                rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}", PAT);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode tools = JSON.readTree(response.body()).path("result").path("tools");
        assertEquals(3, tools.size());
        assertEquals("list_databases_and_tables", tools.get(0).path("name").asText());
        assertEquals("get_table_schema", tools.get(1).path("name").asText());
        assertEquals("execute_readonly_sql", tools.get(2).path("name").asText());
    }

    @Test
    void catalogListsTablesWithAuthorizationFlags() throws Exception {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("datasource_id", "1");
        HttpResponse<String> response = toolCall("list_databases_and_tables", arguments, PAT);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode data =
                JSON.readTree(response.body())
                        .path("result")
                        .path("structuredContent")
                        .path("data");
        JsonNode items = data.path("items");
        assertEquals(3, items.size(), response.body());
        Map<String, Boolean> authorized = new HashMap<>();
        for (JsonNode item : items) {
            authorized.put(item.path("tableName").asText(), item.path("authorized").asBoolean());
        }
        assertEquals(Boolean.TRUE, authorized.get("orders"), response.body());
        assertEquals(Boolean.FALSE, authorized.get("secret"), response.body());
        assertEquals(Boolean.TRUE, authorized.get("wide_rows"), response.body());
        assertEquals("DISCOVER", data.path("catalogMode").asText());
        assertTrue(
                data.path("nextCursor").isNull() || data.path("nextCursor").asText("").isEmpty(),
                "one page of three objects needs no cursor");
    }

    @Test
    void tableSchemaReturnsColumnsAndRejectsUnauthorizedBatches() throws Exception {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("datasource_id", "1");
        arguments.put("database_name", BUSINESS.container.getDatabaseName());
        arguments.put("table_names", List.of("orders"));
        HttpResponse<String> response = toolCall("get_table_schema", arguments, PAT);
        assertEquals(200, response.statusCode(), response.body());
        JsonNode table =
                JSON.readTree(response.body())
                        .path("result")
                        .path("structuredContent")
                        .path("data")
                        .path("tables")
                        .get(0);
        assertEquals("orders", table.path("tableName").asText());
        assertEquals(3, table.path("columns").size());
        assertEquals("id", table.path("primaryKey").get(0).asText());
        assertFalse(table.path("foreignKeysRestricted").asBoolean(true));

        Map<String, Object> mixed = new HashMap<>(arguments);
        mixed.put("table_names", List.of("orders", "secret"));
        HttpResponse<String> rejected = toolCall("get_table_schema", mixed, PAT);
        assertEquals(200, rejected.statusCode(), rejected.body());
        JsonNode error =
                JSON.readTree(rejected.body())
                        .path("result")
                        .path("structuredContent")
                        .path("error");
        assertEquals("TABLE_NOT_AUTHORIZED", error.path("code").asText(), rejected.body());
    }

    // ---------------------------------------------------------------- helpers

    private long businessExecutionCount() {
        return mockingDetails(businessExecutor).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .count();
    }

    private List<QuotaLimiter.ExecutionPermit> holdUserExecutionPermits() {
        List<QuotaLimiter.ExecutionPermit> held = new java.util.ArrayList<>();
        for (int index = 0; index < QuotaLimiter.USER_MAX; index++) {
            QuotaLimiter.QueuePermit queued = quotas.acquireQueue(1);
            held.add(quotas.acquireExecution(1, 1, queued));
        }
        return held;
    }

    private static void updateDatasourceConfigVersion(long version) throws SQLException {
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement update =
                        meta.prepareStatement(
                                "UPDATE datasource SET config_version = ? WHERE id = 1")) {
            update.setLong(1, version);
            assertEquals(1, update.executeUpdate());
        }
    }

    private static void updateDatasourceReadGrantStatus(String status) throws SQLException {
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement update =
                        meta.prepareStatement(
                                "UPDATE acl_grant SET status = ? WHERE subject_type = 'USER'"
                                        + " AND subject_id = 1 AND resource_type = 'DATASOURCE'"
                                        + " AND resource_id = 1 AND action = 'READ' AND effect = 'ALLOW'")) {
            meta.setAutoCommit(false);
            try (Statement epoch = meta.createStatement()) {
                assertEquals(
                        1,
                        epoch.executeUpdate(
                                "UPDATE sys_security_state SET policy_epoch = policy_epoch + 1"
                                        + " WHERE singleton_id = 1"));
            }
            update.setString(1, status);
            assertEquals(1, update.executeUpdate());
            meta.commit();
        }
    }

    private static void assertToolError(HttpResponse<String> response, String errorCode)
            throws Exception {
        assertEquals(200, response.statusCode());
        JsonNode result = JSON.readTree(response.body()).path("result");
        assertAll(
                "expected MCP error " + errorCode,
                () -> assertTrue(result.path("isError").asBoolean(false)),
                () ->
                        assertEquals(
                                errorCode,
                                result.path("structuredContent")
                                        .path("error")
                                        .path("code")
                                        .asText()));
    }

    private HttpResponse<String> call(String query, String token) throws Exception {
        return call(query, token, Map.of());
    }

    private HttpResponse<String> call(String query, String token, Map<String, Object> overrides)
            throws Exception {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("datasource_id", "1");
        arguments.put("database_name", BUSINESS.container.getDatabaseName());
        arguments.put("sql_query", query);
        for (Map.Entry<String, Object> override : overrides.entrySet()) {
            arguments.put(override.getKey(), override.getValue());
        }
        return toolCall("execute_readonly_sql", arguments, token);
    }

    private HttpResponse<String> toolCall(String tool, Map<String, Object> arguments, String token)
            throws Exception {
        Map<String, Object> request =
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        1,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", tool, "arguments", arguments));
        return rpc(JSON.writeValueAsString(request), token);
    }

    private HttpRequest request(int id, String query, String token) {
        Map<String, Object> request =
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        id,
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
        return baseRequest(JSON.writeValueAsString(request))
                .header("Authorization", "Bearer " + token)
                .build();
    }

    private HttpResponse<String> rawCall(HttpRequest request, Map<String, String> headers)
            throws Exception {
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(request, (name, value) -> true).uri(request.uri());
        headers.forEach(builder::header);
        try (HttpClient client = noProxyClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpResponse<String> rpc(String body, String token) throws Exception {
        HttpRequest request = baseRequest(body).header("Authorization", "Bearer " + token).build();
        try (HttpClient client = noProxyClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private HttpRequest.Builder baseRequest(String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
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

    private String jwt(UUID familyId, long authVersion) throws Exception {
        return jwtSignedWith(SIGNING_KEY, familyId, authVersion, null, null);
    }

    private String jwtSignedWith(
            KeyPair key, UUID familyId, long authVersion, String issuerOverride, String audience)
            throws Exception {
        JWTClaimsSet.Builder claims =
                new JWTClaimsSet.Builder()
                        .issuer(issuerOverride == null ? ISSUER : issuerOverride)
                        .subject("1")
                        .audience(audience == null ? List.of(RESOURCE) : List.of(audience))
                        .expirationTime(
                                java.util.Date.from(Instant.now().plus(Duration.ofMinutes(10))))
                        .issueTime(java.util.Date.from(Instant.now()))
                        .jwtID(UUID.randomUUID().toString())
                        .claim("client_id", "client-1")
                        .claim("platform_id", 1L)
                        .claim("sid", familyId.toString())
                        .claim("auth_version", authVersion)
                        .claim("scope", "mcp:query mcp:catalog mcp:schema");
        var jws =
                new com.nimbusds.jose.JWSObject(
                        new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("flow-test").build(),
                        new com.nimbusds.jose.Payload(claims.build().toJSONObject()));
        jws.sign(new RSASSASigner(key.getPrivate()));
        return jws.serialize();
    }

    private static HttpServer startJwksServer() throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) SIGNING_KEY.getPublic();
        String jwks =
                "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"flow-test\",\"use\":\"sig\",\"alg\":"
                        + "\"RS256\",\"n\":\""
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(publicKey.getModulus().toByteArray())
                        + "\",\"e\":\""
                        + Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(publicKey.getPublicExponent().toByteArray())
                        + "\"}]}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/jwks",
                exchange -> {
                    byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
        return server;
    }

    private static KeyPair rsaKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
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

    private void assertTerminal(String requestId, String result, String verdict) throws Exception {
        try (Connection meta = PRIMARY.getConnection();
                PreparedStatement sql =
                        meta.prepareStatement(
                                "SELECT result, verdict FROM audit_log WHERE request_id = ?")) {
            sql.setString(1, requestId);
            try (ResultSet row = sql.executeQuery()) {
                assertTrue(row.next(), "missing audit row for " + requestId);
                assertEquals(result, row.getString(1));
                assertEquals(verdict, row.getString(2));
            }
        }
    }

    private static String base64Key() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (businessProbe != null) {
            businessProbe.close();
        }
        PRIMARY.close();
        BUSINESS.close();
        META.stop();
        JWKS.stop(0);
    }
}
