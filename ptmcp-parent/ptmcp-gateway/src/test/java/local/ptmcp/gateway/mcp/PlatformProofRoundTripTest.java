package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.admin.platform.PlatformAdminService;
import local.ptmcp.admin.platform.PlatformAdminService.PlatformView;
import local.ptmcp.admin.platform.PlatformAdminService.ProofConfig;
import local.ptmcp.admin.platform.PlatformAdminService.Write;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.persistence.org.AuthorizationReader;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

/** Real admin writer, MySQL migrations, deployment envelope and gateway policy consumer. */
class PlatformProofRoundTripTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final MySQLContainer META =
            new MySQLContainer(
                            System.getProperty(
                                    "ptmcp.test.mysqlImage",
                                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                    .withDatabaseName("proof_roundtrip")
                    .withUsername("proof_test")
                    .withPassword(UUID.randomUUID().toString());
    private static final HikariDataSource PRIMARY;
    private static final PlatformAdminService ADMIN;
    private static final Tier1ProofVerifier VERIFIER;

    static {
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
        ADMIN =
                new PlatformAdminService(
                        PRIMARY, new local.ptmcp.admin.datasource.CredentialCipher(KEY));
        VERIFIER =
                new Tier1ProofVerifier(new AuthorizationReader(PRIMARY), new CredentialCipher(KEY));
    }

    @AfterAll
    static void close() {
        PRIMARY.close();
        META.stop();
    }

    @Test
    void adminMtlsWriteIsConsumedAndRotationRejectsOldCertificate() throws Exception {
        byte[] oldCertificate = "synthetic-certificate-one".getBytes(StandardCharsets.UTF_8);
        byte[] newCertificate = "synthetic-certificate-two".getBytes(StandardCharsets.UTF_8);
        String oldFingerprint = fingerprint(oldCertificate);
        String colonUpper =
                HexFormat.ofDelimiter(":")
                        .withUpperCase()
                        .formatHex(HexFormat.of().parseHex(oldFingerprint));
        PlatformView platform = create("MTLS", new ProofConfig(colonUpper, null, null));
        assertDoesNotThrow(() -> verify(platform, certificate(oldCertificate)));
        JsonNode policy = policy(platform);
        assertEquals("MTLS", policy.path("mode").asText());
        assertEquals(oldFingerprint, policy.path("fingerprints").get(0).asText());
        assertTrue(policy.path("proxyCidrs").isEmpty());
        expect(ErrorCode.PLATFORM_MISMATCH, () -> verify(platform, certificate(newCertificate)));
        MockHttpServletRequest forged = request("127.0.0.1");
        forged.addHeader(Tier1ProofVerifier.CLIENT_CERT_FINGERPRINT_HEADER, oldFingerprint);
        expect(ErrorCode.PLATFORM_PROOF_REQUIRED, () -> verify(platform, forged));
        expect(ErrorCode.PLATFORM_PROOF_REQUIRED, () -> verify(platform, request("127.0.0.1")));

        PlatformView rotated =
                update(platform, "MTLS", new ProofConfig(fingerprint(newCertificate), null, null));
        assertDoesNotThrow(() -> verify(rotated, certificate(newCertificate)));
        expect(ErrorCode.PLATFORM_MISMATCH, () -> verify(rotated, certificate(oldCertificate)));
        PlatformView retained = update(rotated, "MTLS", null);
        assertDoesNotThrow(() -> verify(retained, certificate(newCertificate)));
        assertSafeReadsAndLogs(retained, oldFingerprint);
    }

    @Test
    void adminProxyWritePreservesUtf8BytesAndRotationRejectsOldProof() throws Exception {
        String oldSecret = " synthetic-only-代理-secret ";
        String newSecret = "synthetic-only-rotated-proof";
        PlatformView platform =
                create("PROXY_SECRET", new ProofConfig(null, "127.0.0.0/8", oldSecret));
        assertEquals(
                oldSecret,
                new String(
                        Base64.getDecoder().decode(policy(platform).path("secret").asText()),
                        StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> verify(platform, proxy("127.0.0.1", oldSecret)));
        expect(ErrorCode.PLATFORM_MISMATCH, () -> verify(platform, proxy("192.0.2.1", oldSecret)));
        expect(ErrorCode.PLATFORM_PROOF_REQUIRED, () -> verify(platform, request("127.0.0.1")));
        expect(
                ErrorCode.PLATFORM_MISMATCH,
                () -> verify(platform, proxy("127.0.0.1", oldSecret.trim())));
        expect(ErrorCode.PLATFORM_MISMATCH, () -> verify(platform, proxy("127.0.0.1", newSecret)));

        PlatformView rotated =
                update(platform, "PROXY_SECRET", new ProofConfig(null, "127.0.0.0/8", newSecret));
        assertDoesNotThrow(() -> verify(rotated, proxy("127.0.0.1", newSecret)));
        expect(ErrorCode.PLATFORM_MISMATCH, () -> verify(rotated, proxy("127.0.0.1", oldSecret)));
        PlatformView retained = update(rotated, "PROXY_SECRET", null);
        assertDoesNotThrow(() -> verify(retained, proxy("127.0.0.1", newSecret)));
        assertSafeReadsAndLogs(retained, oldSecret);
        assertSafeReadsAndLogs(retained, newSecret);
    }

    @Test
    void invalidMaterialIsRejectedBeforeCreatingAPlatform() {
        for (String cidr :
                new String[] {"example.test/24", "127.0.0.1/33", "127.0.0.1", "", "::1/129"}) {
            expect(
                    ErrorCode.INVALID_ARGUMENT,
                    () -> create("PROXY_SECRET", new ProofConfig(null, cidr, "synthetic-only")));
        }
        for (String secret : new String[] {"", " ", "a".repeat(257), "界".repeat(86)}) {
            expect(
                    ErrorCode.INVALID_ARGUMENT,
                    () -> create("PROXY_SECRET", new ProofConfig(null, "127.0.0.0/8", secret)));
        }
        for (String pin : new String[] {"zz".repeat(32), "ab".repeat(31), "ab".repeat(33)}) {
            expect(
                    ErrorCode.INVALID_ARGUMENT,
                    () -> create("MTLS", new ProofConfig(pin, null, null)));
        }
        expect(ErrorCode.INVALID_ARGUMENT, () -> create("NONE", new ProofConfig(null, null, null)));
    }

    @Test
    void ipv6AndMaximumUtf8SecretAreConsumed() throws Exception {
        String secret = "界".repeat(85) + "a";
        assertEquals(256, secret.getBytes(StandardCharsets.UTF_8).length);
        PlatformView platform = create("PROXY_SECRET", new ProofConfig(null, "::1/128", secret));
        assertDoesNotThrow(() -> verify(platform, proxy("::1", secret)));
        expect(ErrorCode.PLATFORM_MISMATCH, () -> verify(platform, proxy("::2", secret)));
        assertSafeReadsAndLogs(platform, secret);
        assertSafeReadsAndLogs(
                platform,
                Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void partialAndWrongModeMaterialCannotSilentlyRetainExistingProof() throws Exception {
        String pin = fingerprint("synthetic-partial-certificate".getBytes(StandardCharsets.UTF_8));
        PlatformView platform =
                create("PROXY_SECRET", new ProofConfig(null, "127.0.0.0/8", "synthetic-only"));
        for (ProofConfig invalid :
                new ProofConfig[] {
                    new ProofConfig(null, "127.0.0.0/8", null),
                    new ProofConfig(null, null, "synthetic-only"),
                    new ProofConfig(null, "", " "),
                    new ProofConfig(pin, "127.0.0.0/8", "synthetic-only"),
                    new ProofConfig(pin, null, null)
                }) {
            expect(ErrorCode.INVALID_ARGUMENT, () -> create("PROXY_SECRET", invalid));
            expect(ErrorCode.INVALID_ARGUMENT, () -> update(platform, "PROXY_SECRET", invalid));
        }
        for (ProofConfig invalid :
                new ProofConfig[] {
                    new ProofConfig(pin, "127.0.0.0/8", null),
                    new ProofConfig(pin, null, "synthetic-only")
                }) {
            expect(ErrorCode.INVALID_ARGUMENT, () -> create("MTLS", invalid));
            expect(ErrorCode.INVALID_ARGUMENT, () -> update(platform, "MTLS", invalid));
        }
        expect(ErrorCode.INVALID_ARGUMENT, () -> update(platform, "MTLS", null));
        PlatformView retained = update(platform, "PROXY_SECRET", new ProofConfig("", "", ""));
        assertDoesNotThrow(() -> verify(retained, proxy("127.0.0.1", "synthetic-only")));
    }

    @Test
    void noneClearsProofOnlyAfterAnExplicitTierChange() throws Exception {
        PlatformView platform =
                create("PROXY_SECRET", new ProofConfig(null, "127.0.0.0/8", "synthetic-only"));
        expect(ErrorCode.INVALID_ARGUMENT, () -> update(platform, "NONE", null));
        expect(
                ErrorCode.INVALID_ARGUMENT,
                () ->
                        ADMIN.update(
                                Long.parseLong(platform.id()),
                                Long.parseLong(platform.version()),
                                new Write(
                                        platform.code(),
                                        platform.name(),
                                        2,
                                        "HIDE",
                                        "NONE",
                                        "ACTIVE",
                                        new ProofConfig(null, "127.0.0.0/8", null)),
                                1,
                                UUID.randomUUID().toString()));
        PlatformView cleared =
                ADMIN.update(
                        Long.parseLong(platform.id()),
                        Long.parseLong(platform.version()),
                        new Write(
                                platform.code(),
                                platform.name(),
                                2,
                                "HIDE",
                                "NONE",
                                "ACTIVE",
                                null),
                        1,
                        UUID.randomUUID().toString());
        assertFalse(cleared.proofConfigured());
        try (Connection connection = PRIMARY.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT proof_config_cipher, proof_key_id FROM platform_registry WHERE id = ?")) {
            statement.setLong(1, Long.parseLong(platform.id()));
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertTrue(result.getBytes(1) == null && result.getString(2) == null);
            }
        }
    }

    @Test
    void legacyPrefixRemainsFailClosedButAdminReplacementRepairsIt() throws Exception {
        PlatformView platform =
                create("PROXY_SECRET", new ProofConfig(null, "127.0.0.0/8", "synthetic-only"));
        var legacy =
                new local.ptmcp.admin.datasource.CredentialCipher(KEY)
                        .encrypt("proxy:127.0.0.0/8\u0000synthetic-only");
        try (Connection connection = PRIMARY.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE platform_registry SET proof_config_cipher = ?, proof_key_id = ? WHERE id = ?")) {
            statement.setBytes(1, legacy.ciphertext());
            statement.setString(2, legacy.keyId());
            statement.setLong(3, Long.parseLong(platform.id()));
            statement.executeUpdate();
        }
        expect(
                ErrorCode.SECURITY_STATE_UNAVAILABLE,
                () -> verify(platform, proxy("127.0.0.1", "synthetic-only")));
        PlatformView repaired =
                update(
                        platform,
                        "PROXY_SECRET",
                        new ProofConfig(null, "127.0.0.0/8", "synthetic-only-new"));
        assertDoesNotThrow(() -> verify(repaired, proxy("127.0.0.1", "synthetic-only-new")));
        expect(
                ErrorCode.PLATFORM_MISMATCH,
                () -> verify(repaired, proxy("127.0.0.1", "synthetic-only")));
    }

    private static PlatformView create(String mode, ProofConfig proof) {
        return ADMIN.create(
                new Write("p-" + UUID.randomUUID(), "合成证明平台", 1, "HIDE", mode, "ACTIVE", proof),
                1,
                UUID.randomUUID().toString());
    }

    private static PlatformView update(PlatformView platform, String mode, ProofConfig proof) {
        return ADMIN.update(
                Long.parseLong(platform.id()),
                Long.parseLong(platform.version()),
                new Write(platform.code(), platform.name(), 1, "HIDE", mode, "ACTIVE", proof),
                1,
                UUID.randomUUID().toString());
    }

    private static void verify(PlatformView platform, MockHttpServletRequest request) {
        Instant now = Instant.now();
        McpIdentity identity =
                new McpIdentity(
                        1,
                        Long.parseLong(platform.id()),
                        1,
                        "synthetic",
                        null,
                        Set.of("mcp:catalog"),
                        now.plusSeconds(60),
                        1,
                        now);
        VERIFIER.verify(identity, request);
    }

    private static MockHttpServletRequest request(String source) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(source);
        return request;
    }

    private static MockHttpServletRequest certificate(byte[] encoded) throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getEncoded()).thenReturn(encoded);
        MockHttpServletRequest request = request("127.0.0.1");
        request.setAttribute(
                "jakarta.servlet.request.X509Certificate", new X509Certificate[] {certificate});
        return request;
    }

    private static MockHttpServletRequest proxy(String source, String secret) {
        MockHttpServletRequest request = request(source);
        request.addHeader(
                Tier1ProofVerifier.PROXY_SECRET_HEADER,
                Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
        return request;
    }

    private static String fingerprint(byte[] encoded) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
    }

    private static JsonNode policy(PlatformView platform) throws Exception {
        try (Connection connection = PRIMARY.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT proof_config_cipher, proof_key_id FROM platform_registry WHERE id = ?")) {
            statement.setLong(1, Long.parseLong(platform.id()));
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return JSON.readTree(
                        new CredentialCipher(KEY).decrypt(result.getBytes(1), result.getString(2)));
            }
        }
    }

    private static void assertSafeReadsAndLogs(PlatformView platform, String material)
            throws Exception {
        String read = JSON.writeValueAsString(ADMIN.get(Long.parseLong(platform.id())));
        assertFalse(read.contains(material));
        assertFalse(read.contains("\"proofConfig\""));
        try (Connection connection = PRIMARY.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT before_masked, after_masked FROM admin_operation_log WHERE aggregate_type = 'PLATFORM' AND aggregate_id = ?")) {
            statement.setLong(1, Long.parseLong(platform.id()));
            try (ResultSet result = statement.executeQuery()) {
                int rows = 0;
                while (result.next()) {
                    rows++;
                    assertFalse(String.valueOf(result.getString(1)).contains(material));
                    assertFalse(String.valueOf(result.getString(2)).contains(material));
                }
                assertTrue(rows > 0, "platform mutations must write operation logs");
            }
        }
    }

    private static void expect(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(DomainException.class, action).errorCode());
    }
}
