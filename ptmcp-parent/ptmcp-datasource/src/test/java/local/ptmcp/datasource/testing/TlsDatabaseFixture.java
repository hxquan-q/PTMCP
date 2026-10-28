package local.ptmcp.datasource.testing;

import local.ptmcp.common.resource.DatabaseEngine;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Disposable CA-signed endpoints; production trust and existing databases are never used. */
public final class TlsDatabaseFixture implements AutoCloseable {
    public final String password = secret();
    public final Path directory;
    public final Path trustStore;
    public final Path rootCert;
    public final GenericContainer<?> container;
    public final DatabaseEngine engine;
    public final int databasePort;
    public static final String DATABASE = "synthetic_business";
    public static final String USER = "synthetic_user";

    public TlsDatabaseFixture(DatabaseEngine engine) throws Exception {
        this(engine, "SAN=dns:localhost,ip:127.0.0.1,dns:db.synthetic.invalid", false);
    }

    public TlsDatabaseFixture(DatabaseEngine engine, String san, boolean expired) throws Exception {
        this.engine = engine;
        directory = Files.createTempDirectory("ptmcp-physical-tls-");
        trustStore = directory.resolve("trust.p12");
        rootCert = directory.resolve("root.pem");
        Path ca = directory.resolve("ca.p12");
        Path server = directory.resolve("server.p12");
        Path request = directory.resolve("server.csr");
        Path certificate = directory.resolve("server.pem");
        keytool(
                "-genkeypair",
                "-alias",
                "root",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-dname",
                "CN=Synthetic Test Root",
                "-validity",
                "3650",
                "-ext",
                "BC=ca:true",
                "-ext",
                "KU=keyCertSign,cRLSign",
                "-keystore",
                ca.toString());
        keytool(
                "-exportcert",
                "-rfc",
                "-alias",
                "root",
                "-keystore",
                ca.toString(),
                "-file",
                rootCert.toString());
        keytool(
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
                "-keystore",
                server.toString());
        keytool(
                "-certreq",
                "-alias",
                "server",
                "-keystore",
                server.toString(),
                "-file",
                request.toString());
        List<String> sign =
                new ArrayList<>(
                        List.of(
                                "-gencert",
                                "-rfc",
                                "-alias",
                                "root",
                                "-keystore",
                                ca.toString(),
                                "-infile",
                                request.toString(),
                                "-outfile",
                                certificate.toString(),
                                "-validity",
                                "2",
                                "-ext",
                                san,
                                "-ext",
                                "EKU=serverAuth"));
        if (expired) {
            sign.addAll(List.of("-startdate", "2020/01/01 00:00:00"));
        }
        keytool(sign.toArray(String[]::new));
        keytool(
                "-importcert",
                "-alias",
                "root",
                "-keystore",
                trustStore.toString(),
                "-file",
                rootCert.toString());
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(server)) {
            keys.load(input, password.toCharArray());
        }
        byte[] key = keys.getKey("server", password.toCharArray()).getEncoded();
        byte[] certificateBytes = Files.readAllBytes(certificate);
        byte[] keyBytes = pem("PRIVATE KEY", key);
        if (engine == DatabaseEngine.MYSQL) {
            databasePort = 3306;
            container =
                    new MySQLContainer(
                            System.getProperty(
                                    "ptmcp.test.mysqlImage",
                                    "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69")) {
                        @Override
                        protected void waitUntilContainerStarted() {
                            // The final listener (not the bootstrap server) is ready. Expired
                            // certificates are tested by the production connection path below,
                            // so readiness must not try its own JDBC certificate validation.
                            Wait.forLogMessage(".*ready for connections.*port: 3306.*\\s", 1)
                                    .withStartupTimeout(Duration.ofSeconds(60))
                                    .waitUntilReady(this);
                        }
                    }.withDatabaseName(DATABASE)
                            .withUsername(USER)
                            .withPassword(password)
                            .withCopyToContainer(
                                    Transferable.of(Files.readAllBytes(rootCert), 0644),
                                    "/etc/test-tls/root.pem")
                            .withCopyToContainer(
                                    Transferable.of(certificateBytes, 0644),
                                    "/etc/test-tls/server.pem")
                            .withCopyToContainer(
                                    Transferable.of(keyBytes, 0644), "/etc/test-tls/server-key.pem")
                            .withCommand(
                                    "--ssl-ca=/etc/test-tls/root.pem",
                                    "--ssl-cert=/etc/test-tls/server.pem",
                                    "--ssl-key=/etc/test-tls/server-key.pem",
                                    "--require-secure-transport=ON",
                                    "--default-time-zone=+00:00")
                            .withUrlParam("sslMode", "REQUIRED");
        } else {
            databasePort = 5432;
            container =
                    new PostgreSQLContainer(
                                    DockerImageName.parse(
                                            System.getProperty(
                                                    "ptmcp.test.pgImage",
                                                    "postgres@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6")))
                            .withDatabaseName(DATABASE)
                            .withUsername(USER)
                            .withPassword(password)
                            .withCopyToContainer(
                                    Transferable.of(certificateBytes, 0644),
                                    "/etc/test-tls/server.pem")
                            .withCopyToContainer(
                                    Transferable.of(keyBytes, 0644), "/etc/test-tls/server-key.pem")
                            .withCreateContainerCmdModifier(
                                    command -> command.withEntrypoint("/bin/bash", "-c"))
                            .withCommand(
                                    new String[] {
                                        "cp /etc/test-tls/server-key.pem /tmp/test-server-key.pem; "
                                                + "chown postgres:postgres /tmp/test-server-key.pem; chmod 600 /tmp/test-server-key.pem; "
                                                + "exec docker-entrypoint.sh postgres -c ssl=on -c ssl_cert_file=/etc/test-tls/server.pem "
                                                + "-c ssl_key_file=/tmp/test-server-key.pem"
                                    })
                            .withUrlParam("sslmode", "require");
        }
        container.start();
    }

    public int port() {
        return container.getMappedPort(databasePort);
    }

    private void keytool(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(
                Path.of(
                                System.getProperty("java.home"),
                                "bin",
                                System.getProperty("os.name").startsWith("Windows")
                                        ? "keytool.exe"
                                        : "keytool")
                        .toString());
        command.addAll(List.of(arguments));
        command.addAll(List.of("-storepass", password, "-storetype", "PKCS12", "-noprompt"));
        Process process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Synthetic certificate generation timed out");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Synthetic certificate generation failed");
        }
    }

    static String secret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
