package local.ptmcp.datasource.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.PoolKey;
import local.ptmcp.datasource.PoolLimits;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;

import javax.sql.DataSource;

/**
 * Shared physical boundary for TEST/SYNC/QUERY: full TLS verification and DNS pinning on every
 * open.
 */
public final class PhysicalConnectionFactory {
    private final DestinationPolicy destinations;
    private final String mysqlTruststore;
    private final String mysqlTruststorePassword;
    private final String mysqlTruststoreType;
    private final String pgRootCert;

    public PhysicalConnectionFactory(
            DestinationPolicy destinations,
            String mysqlTruststore,
            String mysqlTruststorePassword,
            String mysqlTruststoreType,
            String pgRootCert) {
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.mysqlTruststore = mysqlTruststore == null ? "" : mysqlTruststore.trim();
        this.mysqlTruststorePassword =
                mysqlTruststorePassword == null ? "" : mysqlTruststorePassword;
        this.mysqlTruststoreType =
                mysqlTruststoreType == null || mysqlTruststoreType.isBlank()
                        ? "PKCS12"
                        : mysqlTruststoreType;
        this.pgRootCert = pgRootCert == null ? "" : pgRootCert.trim();
    }

    public Connection openManagement(ConnectionProfile profile, char[] credential)
            throws SQLException {
        Connection connection = physicalSource(profile, credential, 5).getConnection();
        try {
            connection.setReadOnly(true);
            return connection;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                /* Preserve the original failure. */
            }
            throw failure;
        }
    }

    public DataSource createPool(PoolKey key, char[] credential, PoolLimits limits) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(limits, "limits");
        ConnectionProfile profile =
                new ConnectionProfile(
                        key.engine(), key.host(), key.port(), key.database(), key.username());
        destinations.validate(profile.host(), profile.port());
        HikariConfig config = new HikariConfig();
        config.setDataSource(physicalSource(profile, credential, 25));
        config.setMaximumPoolSize(limits.maxPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(limits.checkoutTimeoutMillis());
        config.setValidationTimeout(Math.min(2_000, limits.checkoutTimeoutMillis()));
        config.setPoolName("ptmcp-ds-" + key.datasourceId() + "-c" + key.configVersion());
        config.setAutoCommit(true);
        config.setReadOnly(false);
        config.setRegisterMbeans(false);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private DataSource physicalSource(
            ConnectionProfile profile, char[] credential, int socketSeconds) {
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(credential, "credential");
        Properties options = new Properties();
        String host = profile.host().contains(":") ? "[" + profile.host() + "]" : profile.host();
        String url;
        if (profile.engine() == DatabaseEngine.MYSQL) {
            if (mysqlTruststore.isBlank()) {
                throw new IllegalStateException("MySQL truststore is required");
            }
            options.setProperty("sslMode", "VERIFY_IDENTITY");
            options.setProperty("allowPublicKeyRetrieval", "false");
            options.setProperty("connectTimeout", "3000");
            options.setProperty("socketTimeout", Integer.toString(socketSeconds * 1000));
            options.setProperty("connectionTimeZone", "UTC");
            options.setProperty("forceConnectionTimeZoneToSession", "true");
            options.setProperty("characterEncoding", "UTF-8");
            options.setProperty("useCursorFetch", "true");
            options.setProperty("useServerPrepStmts", "true");
            options.setProperty("socketFactory", PinnedMysqlSocketFactory.class.getName());
            options.setProperty(
                    "trustCertificateKeyStoreUrl", Path.of(mysqlTruststore).toUri().toString());
            options.setProperty("trustCertificateKeyStorePassword", mysqlTruststorePassword);
            options.setProperty("trustCertificateKeyStoreType", mysqlTruststoreType);
            options.setProperty("fallbackToSystemTrustStore", "false");
            url = "jdbc:mysql://" + host + ":" + profile.port() + "/" + profile.database();
        } else {
            if (pgRootCert.isBlank()) {
                throw new IllegalStateException("PostgreSQL root certificate is required");
            }
            options.setProperty("sslmode", "verify-full");
            options.setProperty("sslrootcert", pgRootCert);
            options.setProperty("socketFactory", PinnedPgSocketFactory.class.getName());
            options.setProperty("connectTimeout", "3");
            options.setProperty("socketTimeout", Integer.toString(socketSeconds));
            options.setProperty("ApplicationName", "ptmcp-datasource");
            url =
                    "jdbc:postgresql://"
                            + host
                            + ":"
                            + profile.port()
                            + "/"
                            + URLEncoder.encode(profile.database(), StandardCharsets.UTF_8);
        }
        return new PinnedDriverDataSource(
                destinations,
                profile.host(),
                profile.port(),
                url,
                profile.username(),
                credential,
                options);
    }
}
