package local.ptmcp.datasource;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.resource.DatabaseEngine;

import java.util.Objects;

import javax.sql.DataSource;

/**
 * Builds the only supported MySQL URL shape. Properties that could weaken TLS or inject driver
 * behavior are not configurable; callers supply an identity, not a URL.
 */
public final class HikariPoolFactory implements PoolFactory {
    private static final String URL_PARAMETERS =
            "useCursorFetch=true"
                    + "&useServerPrepStmts=true"
                    + "&sslMode=REQUIRED"
                    + "&connectionTimeZone=UTC"
                    + "&forceConnectionTimeZoneToSession=true"
                    + "&characterEncoding=UTF-8";

    @Override
    public DataSource create(PoolKey key, char[] credential, PoolLimits limits) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(limits, "limits");
        if (key.engine() != DatabaseEngine.MYSQL) {
            throw new IllegalArgumentException("only MYSQL pools are supported");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl(key));
        config.setUsername(key.username());
        config.setPassword(new String(credential));
        config.setMaximumPoolSize(limits.maxPoolSize());
        config.setMinimumIdle(0);
        config.setConnectionTimeout(limits.checkoutTimeoutMillis());
        config.setValidationTimeout(Math.min(2_000, limits.checkoutTimeoutMillis()));
        config.setPoolName(key.poolName());
        config.setAutoCommit(true);
        config.setReadOnly(false);
        config.setRegisterMbeans(false);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    static String jdbcUrl(PoolKey key) {
        String host = key.host().contains(":") ? "[" + key.host() + "]" : key.host();
        return "jdbc:mysql://"
                + host
                + ":"
                + key.port()
                + "/"
                + key.database()
                + "?"
                + URL_PARAMETERS;
    }
}
