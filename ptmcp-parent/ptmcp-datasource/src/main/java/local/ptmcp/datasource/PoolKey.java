package local.ptmcp.datasource;

import local.ptmcp.common.resource.DatabaseEngine;

import java.util.Objects;
import java.util.regex.Pattern;

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
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._:-]{1,253}");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$-]{1,64}");
    private static final Pattern USERNAME = Pattern.compile("[^\\s\\u0000]{1,128}");

    public PoolKey {
        if (datasourceId <= 0) {
            throw new IllegalArgumentException("datasourceId must be positive");
        }
        if (configVersion <= 0 || credentialVersion <= 0) {
            throw new IllegalArgumentException("versions must be positive");
        }
        Objects.requireNonNull(engine, "engine");
        if (host == null || !HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("host is malformed");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port is out of range");
        }
        if (database == null || !DATABASE.matcher(database).matches()) {
            throw new IllegalArgumentException("database is malformed");
        }
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException("username is malformed");
        }
    }

    String poolName() {
        return "ptmcp-ds-" + datasourceId + "-c" + configVersion + "-g" + credentialVersion;
    }
}
