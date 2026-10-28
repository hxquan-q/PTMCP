package local.ptmcp.datasource.connection;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;

/**
 * Validated endpoint data, never caller-supplied JDBC URLs, driver classes or connection options.
 */
public record ConnectionProfile(
        DatabaseEngine engine, String host, int port, String database, String username) {
    public ConnectionProfile {
        if (engine == null
                || port < 1
                || port > 65535
                || database == null
                || !database.matches("[A-Za-z0-9_$.-]{1,128}")
                || username == null
                || !username.matches("[^\\s\\u0000]{1,128}")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        host = DestinationPolicy.normalizeHost(host);
    }

    @Override
    public String toString() {
        return "ConnectionProfile[engine=" + engine + "]";
    }
}
