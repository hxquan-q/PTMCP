package local.ptmcp.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.datasource.connection.CredentialCipher;

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
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/** Tier1 proof is enforced before any tool dispatch; proxy material only works from its CIDR. */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpTier1ProofTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MYSQL_IMAGE =
            System.getProperty(
                    "ptmcp.test.mysqlImage",
                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69");
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String MTLS_FINGERPRINT = "aa".repeat(32);
    private static final String WRONG_FINGERPRINT = "bb".repeat(32);
    private static final byte[] PROXY_SECRET = secret().getBytes(StandardCharsets.UTF_8);
    private static final String MTLS_PAT = "ptmcp_pat_" + secret();
    private static final String PROXY_PAT = "ptmcp_pat_" + secret();
    private static final String UNTRUSTED_PAT = "ptmcp_pat_" + secret();

    private static final MySQLContainer META =
            new MySQLContainer(MYSQL_IMAGE)
                    .withDatabaseName("tier1_meta")
                    .withUsername("tier1_user")
                    .withPassword(secret())
                    .withUrlParam("sslMode", "DISABLED")
                    .withUrlParam("allowPublicKeyRetrieval", "true");
    private static final HikariDataSource PRIMARY;
    private static final Path WAL;

    static {
        try {
            META.start();
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
            WAL = Files.createTempDirectory("ptmcp-tier1-wal-");
            seed();
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
        properties.add("ptmcp.gateway.allowed-cidrs", () -> "127.0.0.0/8");
        properties.add("ptmcp.gateway.wal-directory", WAL::toString);
        properties.add("ptmcp.gateway.wal-key", () -> KEY);
        properties.add("ptmcp.gateway.issuer", () -> "https://as.test.invalid");
        properties.add("ptmcp.gateway.resource", () -> "https://mcp.test.invalid/mcp");
    }

    @AfterAll
    static void stopOwnedDatabase() {
        PRIMARY.close();
        META.stop();
    }

    @Test
    void mtlsPlatformRequiresPinnedFingerprintFromTrustedProxy() throws Exception {
        HttpResponse<String> missing = toolsList(MTLS_PAT, Map.of());
        assertEquals(403, missing.statusCode(), missing.body());
        assertEquals("PLATFORM_PROOF_REQUIRED", error(missing).path("code").asText());

        HttpResponse<String> correct =
                toolsList(MTLS_PAT, Map.of("X-PTMCP-Client-Cert-Fingerprint", MTLS_FINGERPRINT));
        assertEquals(200, correct.statusCode(), correct.body());
        assertFalse(
                JSON.readTree(correct.body()).path("result").path("tools").isEmpty(),
                correct.body());

        HttpResponse<String> wrong =
                toolsList(MTLS_PAT, Map.of("X-PTMCP-Client-Cert-Fingerprint", WRONG_FINGERPRINT));
        assertEquals(403, wrong.statusCode(), wrong.body());
        assertEquals("PLATFORM_MISMATCH", error(wrong).path("code").asText());

        HttpResponse<String> untrustedSource =
                toolsList(
                        UNTRUSTED_PAT, Map.of("X-PTMCP-Client-Cert-Fingerprint", MTLS_FINGERPRINT));
        assertEquals(403, untrustedSource.statusCode(), untrustedSource.body());
        assertEquals("PLATFORM_PROOF_REQUIRED", error(untrustedSource).path("code").asText());
    }

    @Test
    void proxySecretPlatformRequiresTheRegisteredSecret() throws Exception {
        HttpResponse<String> missing = toolsList(PROXY_PAT, Map.of());
        assertEquals(403, missing.statusCode(), missing.body());
        assertEquals("PLATFORM_PROOF_REQUIRED", error(missing).path("code").asText());

        HttpResponse<String> wrong =
                toolsList(
                        PROXY_PAT,
                        Map.of(
                                "X-PTMCP-Proxy-Secret",
                                Base64.getEncoder()
                                        .encodeToString("wrong".getBytes(StandardCharsets.UTF_8))));
        assertEquals(403, wrong.statusCode(), wrong.body());
        assertEquals("PLATFORM_MISMATCH", error(wrong).path("code").asText());

        HttpResponse<String> correct =
                toolsList(
                        PROXY_PAT,
                        Map.of(
                                "X-PTMCP-Proxy-Secret",
                                Base64.getEncoder().encodeToString(PROXY_SECRET)));
        assertEquals(200, correct.statusCode(), correct.body());
        assertFalse(
                JSON.readTree(correct.body()).path("result").path("tools").isEmpty(),
                correct.body());
    }

    private static void seed() throws Exception {
        try (Connection connection = PRIMARY.getConnection();
                var statement = connection.createStatement()) {
            statement.execute(
                    "INSERT INTO sys_dept (dept_id,parent_id,ancestors,dept_name,status,del_flag,create_time,update_time) VALUES (1,0,'0','test','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            statement.execute(
                    "INSERT INTO sys_user_profile (user_id,dept_id,display_name,password_hash,status,create_time,update_time) VALUES (1,1,'test','unused','ACTIVE',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
            statement.execute(
                    "INSERT INTO sys_user_security (user_id,auth_version,identity_version,identity_state,version,create_time,update_time) VALUES (1,1,1,'READY',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
        }
        insertPlatform(
                1,
                "tier1-mtls",
                "MTLS",
                "{\"mode\":\"MTLS\",\"fingerprints\":[\""
                        + MTLS_FINGERPRINT
                        + "\"],\"proxyCidrs\":[\"127.0.0.1/32\"]}",
                MTLS_PAT);
        insertPlatform(
                2,
                "tier1-proxy",
                "PROXY_SECRET",
                "{\"mode\":\"PROXY_SECRET\",\"secret\":\""
                        + Base64.getEncoder().encodeToString(PROXY_SECRET)
                        + "\",\"proxyCidrs\":[\"127.0.0.1/32\"]}",
                PROXY_PAT);
        insertPlatform(
                3,
                "tier1-untrusted",
                "MTLS",
                "{\"mode\":\"MTLS\",\"fingerprints\":[\""
                        + MTLS_FINGERPRINT
                        + "\"],\"proxyCidrs\":[\"10.0.0.0/8\"]}",
                UNTRUSTED_PAT);
    }

    private static void insertPlatform(
            long id, String code, String mode, String configJson, String token) throws Exception {
        CredentialCipher.Sealed sealed = new CredentialCipher(KEY).encrypt(configJson);
        try (Connection connection = PRIMARY.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO platform_registry (id,code,name,tier,status,"
                                        + "catalog_mode,proof_mode,proof_config_cipher,"
                                        + "proof_key_id,scope_version,security_version,create_time,"
                                        + "update_time,created_by,updated_by) VALUES"
                                        + " (?,?,?,1,'ACTIVE','DISCOVER',?,?,?,1,1,"
                                        + " UTC_TIMESTAMP(6),UTC_TIMESTAMP(6),1,1)")) {
            statement.setLong(1, id);
            statement.setString(2, code);
            statement.setString(3, code);
            statement.setString(4, mode);
            statement.setBytes(5, sealed.ciphertext());
            statement.setString(6, sealed.keyId());
            statement.executeUpdate();
        }
        try (Connection connection = PRIMARY.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO personal_token (user_id,platform_id,token_prefix,"
                                        + "token_hash,name,scopes,auth_version,expires_at,status,"
                                        + "version,create_time,update_time,created_by,updated_by)"
                                        + " VALUES (1,?,?,?,?,JSON_ARRAY('mcp:catalog',"
                                        + " 'mcp:schema','mcp:query'),1,DATE_ADD(UTC_TIMESTAMP(6),"
                                        + " INTERVAL 1 DAY),'ACTIVE',1,UTC_TIMESTAMP(6),"
                                        + " UTC_TIMESTAMP(6),1,1)")) {
            statement.setLong(1, id);
            statement.setString(2, token.substring(0, 16));
            statement.setBytes(3, sha256(token));
            statement.setString(4, "tier1-" + id);
            statement.executeUpdate();
        }
    }

    private HttpResponse<String> toolsList(String token, Map<String, String> headers)
            throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        HttpRequest.Builder builder =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        try (HttpClient client = noProxyClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpClient noProxyClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .proxy(java.net.ProxySelector.of(null))
                .build();
    }

    private static JsonNode error(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).path("error");
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
}
