package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

/**
 * Builds the only supported JDBC URL shapes. Callers never submit a URL, driver class or extra
 * properties; TLS is pinned to full verification and there is no plaintext fallback.
 */
public final class DatasourceConnectionFactory {
    private static final String MYSQL_BASE =
            "sslMode=VERIFY_IDENTITY"
                    + "&allowPublicKeyRetrieval=false"
                    + "&connectTimeout=3000"
                    + "&socketTimeout=5000"
                    + "&characterEncoding=UTF-8"
                    + "&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true";
    private static final String POSTGRES_BASE =
            "sslmode=verify-full&connectTimeout=3&socketTimeout=5&ApplicationName=ptmcp-admin";

    private final DestinationPolicy destinations;
    private final CredentialCipher cipher;
    private final String truststorePath;
    private final String truststorePassword;

    public DatasourceConnectionFactory(
            DestinationPolicy destinations,
            CredentialCipher cipher,
            String truststorePath,
            String truststorePassword) {
        this.destinations = destinations;
        this.cipher = cipher;
        this.truststorePath = truststorePath == null ? "" : truststorePath.trim();
        this.truststorePassword = truststorePassword == null ? "" : truststorePassword;
    }

    public record Spec(
            String engine,
            String host,
            int port,
            String databaseName,
            String username,
            byte[] credentialCipher,
            String keyId) {}

    public Connection open(Spec spec) throws SQLException {
        destinations.validate(spec.host(), spec.port());
        String credential = cipher.decrypt(spec.credentialCipher(), spec.keyId());
        String host = spec.host().contains(":") ? "[" + spec.host() + "]" : spec.host();
        String url;
        Properties properties = new Properties();
        properties.setProperty("user", spec.username());
        properties.setProperty("password", credential);
        if ("MYSQL".equals(spec.engine())) {
            StringBuilder builder =
                    new StringBuilder("jdbc:mysql://")
                            .append(host)
                            .append(':')
                            .append(spec.port())
                            .append('/')
                            .append(spec.databaseName())
                            .append('?')
                            .append(MYSQL_BASE);
            if (!truststorePath.isEmpty()) {
                builder.append("&trustCertificateKeyStoreUrl=")
                        .append(encode("file:" + truststorePath))
                        .append("&trustCertificateKeyStorePassword=")
                        .append(encode(truststorePassword));
            }
            url = builder.toString();
        } else if ("POSTGRESQL".equals(spec.engine())) {
            StringBuilder builder =
                    new StringBuilder("jdbc:postgresql://")
                            .append(host)
                            .append(':')
                            .append(spec.port())
                            .append('/')
                            .append(spec.databaseName())
                            .append('?')
                            .append(POSTGRES_BASE);
            if (!truststorePath.isEmpty()) {
                builder.append("&sslrootcert=").append(encode(truststorePath));
            }
            url = builder.toString();
        } else {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        DriverManager.setLoginTimeout(3);
        Connection connection = DriverManager.getConnection(url, properties);
        try {
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Preserve the original failure.
            }
            throw failure;
        }
    }

    public void validateDestination(String host, int port) {
        destinations.validate(host, port);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
