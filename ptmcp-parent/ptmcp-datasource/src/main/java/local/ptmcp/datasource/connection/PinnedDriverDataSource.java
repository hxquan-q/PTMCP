package local.ptmcp.datasource.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

/**
 * Opens each physical connection through the destination policy: every DNS answer is re-validated
 * against the operator allowlist at connection time and the socket is pinned to the approved
 * address, so a later DNS change cannot redirect an existing pool. Hikari may call this from its
 * own connection adder threads; the pin is therefore installed and cleared inside this call on
 * whichever thread performs it.
 */
final class PinnedDriverDataSource implements DataSource {
    private final DestinationPolicy destinations;
    private final String host;
    private final int port;
    private final String url;
    private final Properties credentials;

    PinnedDriverDataSource(
            DestinationPolicy destinations,
            String host,
            int port,
            String url,
            String username,
            char[] password) {
        this.destinations = destinations;
        this.host = host;
        this.port = port;
        this.url = url;
        this.credentials = new Properties();
        credentials.setProperty("user", username);
        credentials.setProperty("password", new String(password));
    }

    @Override
    public Connection getConnection() throws SQLException {
        List<java.net.InetAddress> addresses = destinations.resolve(host, port);
        PinnedTarget.CURRENT.set(new PinnedTarget(host, port, addresses));
        try {
            return java.sql.DriverManager.getConnection(url, credentials);
        } finally {
            PinnedTarget.CURRENT.remove();
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLException("Credentials are owned by the pool");
    }

    @Override
    public <T> T unwrap(Class<T> interfaceType) throws SQLException {
        if (interfaceType.isInstance(this)) {
            return interfaceType.cast(this);
        }
        throw new SQLException("Not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> interfaceType) {
        return interfaceType.isInstance(this);
    }

    @Override
    public java.io.PrintWriter getLogWriter() {
        return null;
    }

    @Override
    public void setLogWriter(java.io.PrintWriter out) {
        // Driver logging stays off; SQL and credentials must never reach a writer.
    }

    @Override
    public void setLoginTimeout(int seconds) {
        // The URL carries connectTimeout; no global driver timeout is accepted here.
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public java.util.logging.Logger getParentLogger() {
        return java.util.logging.Logger.getGlobal();
    }
}
