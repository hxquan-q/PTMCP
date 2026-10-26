package local.ptmcp.datasource.connection;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.PoolFactory;
import local.ptmcp.datasource.PoolKey;
import local.ptmcp.datasource.PoolLimits;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Gateway-side MySQL pool factory: full TLS verification against the deployment truststore, the
 * pinned socket factory for anti-rebinding, and the same bounded execution parameters as the base
 * factory. Callers supply an identity and the operator truststore, never a URL; properties that
 * could weaken verification are not configurable.
 */
public final class PinnedTlsMysqlPoolFactory implements PoolFactory {
    private static final String URL_PARAMETERS =
            "useCursorFetch=true"
                    + "&useServerPrepStmts=true"
                    + "&sslMode=VERIFY_IDENTITY"
                    + "&allowPublicKeyRetrieval=false"
                    + "&connectTimeout=3000"
                    + "&socketTimeout=25000"
                    + "&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true"
                    + "&characterEncoding=UTF-8"
                    + "&socketFactory="
                    + PinnedMysqlSocketFactory.class.getName();

    private final DestinationPolicy destinations;
    private final String truststorePath;
    private final String truststorePassword;
    private final String truststoreType;

    public PinnedTlsMysqlPoolFactory(
            DestinationPolicy destinations,
            String truststorePath,
            String truststorePassword,
            String truststoreType) {
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.truststorePath = truststorePath == null ? "" : truststorePath.trim();
        this.truststorePassword = truststorePassword == null ? "" : truststorePassword;
        this.truststoreType =
                truststoreType == null || truststoreType.isBlank() ? "PKCS12" : truststoreType;
    }

    @Override
    public DataSource create(PoolKey key, char[] credential, PoolLimits limits) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(limits, "limits");
        if (key.engine() != DatabaseEngine.MYSQL) {
            throw new IllegalArgumentException("only MYSQL pools are supported");
        }
        if (truststorePath.isEmpty()) {
            throw new IllegalStateException("ptmcp.gateway.truststore-path is required");
        }
        destinations.validate(key.host(), key.port());
        HikariConfig config = new HikariConfig();
        config.setDataSource(
                new PinnedDriverDataSource(
                        destinations,
                        key.host(),
                        key.port(),
                        jdbcUrl(key),
                        key.username(),
                        credential));
        config.setMaximumPoolSize(limits.maxPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(limits.checkoutTimeoutMillis());
        config.setValidationTimeout(Math.min(2_000, limits.checkoutTimeoutMillis()));
        config.setPoolName("ptmcp-ds-" + key.datasourceId());
        config.setAutoCommit(true);
        config.setReadOnly(false);
        config.setRegisterMbeans(false);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private String jdbcUrl(PoolKey key) {
        String host = key.host().contains(":") ? "[" + key.host() + "]" : key.host();
        return "jdbc:mysql://"
                + host
                + ":"
                + key.port()
                + "/"
                + key.database()
                + "?"
                + URL_PARAMETERS
                + "&trustCertificateKeyStoreUrl="
                + encode("file:" + truststorePath)
                + "&trustCertificateKeyStorePassword="
                + encode(truststorePassword)
                + "&trustCertificateKeyStoreType="
                + encode(truststoreType);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
