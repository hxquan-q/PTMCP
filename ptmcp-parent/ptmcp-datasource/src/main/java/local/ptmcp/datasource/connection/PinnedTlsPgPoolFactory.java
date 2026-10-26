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
 * Gateway-side PostgreSQL pool factory. It never accepts a JDBC URL from a request: the URL is
 * rebuilt from the datasource identity, always uses full certificate verification against the
 * operator-supplied root certificate, and pins every physical socket through {@link
 * PinnedPgSocketFactory}. If the deployment has not supplied a PG root certificate, the first
 * PostgreSQL checkout fails closed instead of falling back to the JVM's public trust store.
 */
public final class PinnedTlsPgPoolFactory implements PoolFactory {
    private static final String URL_PARAMETERS =
            "sslmode=verify-full"
                    + "&sslfactory="
                    + PinnedPgSocketFactory.class.getName()
                    + "&connectTimeout=3"
                    + "&socketTimeout=25"
                    + "&ApplicationName=ptmcp-datasource";

    private final DestinationPolicy destinations;
    private final String sslRootCert;

    public PinnedTlsPgPoolFactory(DestinationPolicy destinations, String sslRootCert) {
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.sslRootCert = sslRootCert == null ? "" : sslRootCert.trim();
    }

    @Override
    public DataSource create(PoolKey key, char[] credential, PoolLimits limits) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(limits, "limits");
        if (key.engine() != DatabaseEngine.POSTGRESQL) {
            throw new IllegalArgumentException("only POSTGRESQL pools are supported");
        }
        if (sslRootCert.isEmpty()) {
            throw new IllegalStateException("ptmcp.gateway.pg-ssl-root-cert is required");
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
        return "jdbc:postgresql://"
                + host
                + ":"
                + key.port()
                + "/"
                + encode(key.database())
                + "?"
                + URL_PARAMETERS
                + "&sslrootcert="
                + encode(sslRootCert);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
