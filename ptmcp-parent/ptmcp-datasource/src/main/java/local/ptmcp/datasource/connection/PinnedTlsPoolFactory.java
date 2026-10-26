package local.ptmcp.datasource.connection;

import local.ptmcp.datasource.PoolFactory;
import local.ptmcp.datasource.PoolKey;
import local.ptmcp.datasource.PoolLimits;

import java.util.Objects;

import javax.sql.DataSource;

/** Dispatches one pool registry to the pinned MySQL or PostgreSQL factory by engine. */
public final class PinnedTlsPoolFactory implements PoolFactory {
    private final PinnedTlsMysqlPoolFactory mysql;
    private final PinnedTlsPgPoolFactory postgresql;

    public PinnedTlsPoolFactory(
            PinnedTlsMysqlPoolFactory mysql, PinnedTlsPgPoolFactory postgresql) {
        this.mysql = Objects.requireNonNull(mysql, "mysql");
        this.postgresql = Objects.requireNonNull(postgresql, "postgresql");
    }

    @Override
    public DataSource create(PoolKey key, char[] credential, PoolLimits limits) {
        return switch (Objects.requireNonNull(key, "key").engine()) {
            case MYSQL -> mysql.create(key, credential, limits);
            case POSTGRESQL -> postgresql.create(key, credential, limits);
            default -> throw new IllegalArgumentException("engine is not supported");
        };
    }
}
