package local.ptmcp.datasource.connection;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.PoolFactory;
import local.ptmcp.datasource.PoolKey;
import local.ptmcp.datasource.PoolLimits;

import javax.sql.DataSource;

/** Engine-specific facade over the shared, fully verified physical connection boundary. */
public final class PinnedTlsPgPoolFactory implements PoolFactory {
    private final PhysicalConnectionFactory physical;

    public PinnedTlsPgPoolFactory(DestinationPolicy destinations, String sslRootCert) {
        physical = new PhysicalConnectionFactory(destinations, "", "", "PKCS12", sslRootCert);
    }

    @Override
    public DataSource create(PoolKey key, char[] credential, PoolLimits limits) {
        if (key.engine() != DatabaseEngine.POSTGRESQL) {
            throw new IllegalArgumentException("Unexpected database engine");
        }
        return physical.createPool(key, credential, limits);
    }
}
