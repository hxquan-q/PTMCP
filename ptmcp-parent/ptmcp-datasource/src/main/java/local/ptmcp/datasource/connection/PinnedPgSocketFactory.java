package local.ptmcp.datasource.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import javax.net.SocketFactory;

/** pgJDBC retains its original hostname for verify-full while the socket uses the approved IP. */
public final class PinnedPgSocketFactory extends SocketFactory {
    @Override
    public Socket createSocket() throws IOException {
        PinnedTarget target = PinnedTarget.require();
        return new Socket() {
            @Override
            public void connect(SocketAddress endpoint, int timeout) throws IOException {
                if (!(endpoint instanceof InetSocketAddress address)
                        || address.getPort() != target.port) {
                    throw new IOException("Destination binding mismatch");
                }
                super.connect(
                        new InetSocketAddress(target.addresses.getFirst(), target.port), timeout);
            }
        };
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        throw unsupported();
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress local, int localPort)
            throws IOException {
        throw unsupported();
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        throw unsupported();
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
            throws IOException {
        throw unsupported();
    }

    private static IOException unsupported() {
        return new IOException("Unbound connection forbidden");
    }
}
