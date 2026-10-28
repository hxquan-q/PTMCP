package local.ptmcp.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.connection.ConnectionProfile;

/**
 * Identity of one physical pool generation. Credentials are intentionally absent so the key can be
 * logged; only non-sensitive identifiers and versions are retained.
 */
public record PoolKey(
        long datasourceId,
        long configVersion,
        DatabaseEngine engine,
        String host,
        int port,
        String database,
        String username,
        long credentialVersion) {
    public PoolKey {
        if (datasourceId <= 0) {
            throw new IllegalArgumentException("datasourceId must be positive");
        }
        if (configVersion <= 0 || credentialVersion <= 0) {
            throw new IllegalArgumentException("versions must be positive");
        }
        try {
            ConnectionProfile profile =
                    new ConnectionProfile(engine, host, port, database, username);
            host = profile.host();
        } catch (DomainException invalid) {
            throw new IllegalArgumentException("Pool endpoint is malformed");
        }
    }

    String poolName() {
        return "ptmcp-ds-" + datasourceId + "-c" + configVersion + "-g" + credentialVersion;
    }
}
