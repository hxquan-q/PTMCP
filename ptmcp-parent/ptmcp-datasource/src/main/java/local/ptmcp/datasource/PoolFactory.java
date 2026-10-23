package local.ptmcp.datasource;

import javax.sql.DataSource;

/**
 * Builds a pool for one identity. The registry owns lifecycle; implementations must not retain or
 * log the supplied credential.
 */
@FunctionalInterface
public interface PoolFactory {
    DataSource create(PoolKey key, char[] credential, PoolLimits limits);
}
