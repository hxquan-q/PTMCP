package local.ptmcp.gateway;

import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;

/**
 * Disposable CA/trust material and a real VERIFY_IDENTITY MySQL endpoint, never shared dev data.
 */
final class TlsMysqlFixture implements AutoCloseable {
    static final String IMAGE =
            System.getProperty(
                    "ptmcp.test.mysqlImage",
                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69");
    final Path directory;
    final Path trustStore;
    final String password = secret();
    final MySQLContainer container;

    TlsMysqlFixture() throws Exception {
        directory = Files.createTempDirectory("ptmcp-tls-test-");
        trustStore = directory.resolve("trust.p12");
        Path keyStore = directory.resolve("server.p12");
        String executable =
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                "keytool"
                                        + (System.getProperty("os.name").startsWith("Windows")
                                                ? ".exe"
                                                : ""))
                        .toString();
        Process process =
                new ProcessBuilder(
                                executable,
                                "-genkeypair",
                                "-alias",
                                "server",
                                "-keyalg",
                                "RSA",
                                "-keysize",
                                "2048",
                                "-dname",
                                "CN=localhost",
                                "-validity",
                                "2",
                                "-ext",
                                certificateSan(),
                                "-storetype",
                                "PKCS12",
                                "-keystore",
                                keyStore.toString(),
                                "-storepass",
                                password,
                                "-noprompt")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("Test certificate generation failed");
        }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(keyStore)) {
            keys.load(input, password.toCharArray());
        }
        byte[] cert = keys.getCertificate("server").getEncoded();
        byte[] key = keys.getKey("server", password.toCharArray()).getEncoded();
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, password.toCharArray());
        trust.setCertificateEntry("test-server", keys.getCertificate("server"));
        try (var output = Files.newOutputStream(trustStore)) {
            trust.store(output, password.toCharArray());
        }
        container =
                new MySQLContainer(IMAGE)
                        .withDatabaseName("synthetic_business")
                        .withUsername("synthetic_user")
                        .withPassword(password)
                        .withCopyToContainer(
                                Transferable.of(pem("CERTIFICATE", cert), 0644),
                                "/etc/test-tls/server.pem")
                        .withCopyToContainer(
                                Transferable.of(pem("PRIVATE KEY", key), 0644),
                                "/etc/test-tls/server-key.pem")
                        .withCommand(
                                "--ssl-ca=/etc/test-tls/server.pem",
                                "--ssl-cert=/etc/test-tls/server.pem",
                                "--ssl-key=/etc/test-tls/server-key.pem",
                                "--require-secure-transport=ON",
                                "--default-time-zone=+00:00")
                        .withUrlParam("sslMode", "VERIFY_IDENTITY")
                        .withUrlParam("trustCertificateKeyStoreUrl", trustStore.toUri().toString())
                        .withUrlParam("trustCertificateKeyStorePassword", password)
                        .withUrlParam("trustCertificateKeyStoreType", "PKCS12")
                        .withUrlParam("connectionTimeZone", "UTC");
        container.start();
    }

    Connection connect() throws Exception {
        return DriverManager.getConnection(
                container.getJdbcUrl(), container.getUsername(), password);
    }

    static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * The container is normally reached through Testcontainers' host override on this Windows/WSL
     * setup. Include that numeric host in the disposable test certificate so VERIFY_IDENTITY keeps
     * its hostname check instead of being disabled. Production trust material is never affected.
     */
    static String allowedCidrs() {
        String override = System.getenv("TESTCONTAINERS_HOST_OVERRIDE");
        if (override != null && override.trim().matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            return "127.0.0.0/8," + override.trim() + "/32";
        }
        return "127.0.0.0/8";
    }

    private static String certificateSan() {
        String san = "SAN=dns:localhost,ip:127.0.0.1";
        String override = System.getenv("TESTCONTAINERS_HOST_OVERRIDE");
        if (override == null || override.isBlank()) {
            return san;
        }
        String host = override.trim();
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}")
                ? san + ",ip:" + host
                : san + ",dns:" + host;
    }

    private static byte[] pem(String kind, byte[] bytes) {
        return ("-----BEGIN "
                        + kind
                        + "-----\n"
                        + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(bytes)
                        + "\n-----END "
                        + kind
                        + "-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public void close() throws Exception {
        container.stop();
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                Files.delete(file);
            }
        }
        Files.delete(directory);
    }
}
