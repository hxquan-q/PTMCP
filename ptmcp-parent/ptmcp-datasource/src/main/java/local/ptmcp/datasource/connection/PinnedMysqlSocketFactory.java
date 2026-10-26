package local.ptmcp.datasource.connection;

import com.mysql.cj.conf.PropertyKey;
import com.mysql.cj.conf.PropertySet;
import com.mysql.cj.protocol.StandardSocketFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Connector/J connects only to the validated IP; inherited TLS still verifies the URL hostname. */
public final class PinnedMysqlSocketFactory extends StandardSocketFactory {
    @Override
    @SuppressWarnings("unchecked")
    public <T extends Closeable> T connect(
            String hostname, int targetPort, PropertySet properties, int loginTimeout)
            throws IOException {
        PinnedTarget target = PinnedTarget.require();
        if (!target.host.equals(hostname) || target.port != targetPort) {
            throw new IOException("Destination binding mismatch");
        }
        host = hostname;
        port = targetPort;
        loginTimeoutCountdown = loginTimeout;
        rawSocket = new Socket();
        try {
            configureSocket(rawSocket, properties);
            int timeout = properties.getIntegerProperty(PropertyKey.connectTimeout).getValue();
            rawSocket.connect(
                    new InetSocketAddress(target.addresses.getFirst(), port),
                    getRealTimeout(timeout));
            resetLoginTimeCountdown();
            sslSocket = rawSocket;
            return (T) rawSocket;
        } catch (IOException | RuntimeException failure) {
            rawSocket.close();
            throw failure;
        }
    }
}
