package local.ptmcp.datasource.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

/** A physical connection's validated target, scoped to the synchronous JDBC connection attempt. */
final class PinnedTarget {
    static final ThreadLocal<PinnedTarget> CURRENT = new ThreadLocal<>();
    final String host;
    final int port;
    final List<InetAddress> addresses;

    PinnedTarget(String host, int port, List<InetAddress> addresses) {
        this.host = host;
        this.port = port;
        this.addresses = List.copyOf(addresses);
    }

    static PinnedTarget require() throws IOException {
        PinnedTarget target = CURRENT.get();
        if (target == null || target.addresses.isEmpty()) {
            throw new IOException("Verified destination required");
        }
        return target;
    }
}
