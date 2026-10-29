package local.ptmcp.common.oauth;

import java.net.URI;

/** Deployment-controlled resource identifier shared by the AS and the MCP resource server. */
public final class McpResourceUri {
    private McpResourceUri() {}

    public static URI requireValid(String value) {
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            boolean https = "https".equalsIgnoreCase(uri.getScheme());
            boolean loopback =
                    "http".equalsIgnoreCase(uri.getScheme())
                            && ("127.0.0.1".equals(host)
                                    || "[::1]".equals(host)
                                    || "localhost".equalsIgnoreCase(host));
            if ((!https && !loopback)
                    || host == null
                    || !"/mcp".equals(uri.getRawPath())
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || !value.equals(uri.toASCIIString())
                    || value.chars().anyMatch(c -> c <= 32 || c == 127)) {
                throw invalidUri();
            }
            return uri;
        } catch (IllegalArgumentException | NullPointerException failure) {
            // Parser messages can include credentials. Configuration errors never echo the URI.
            throw invalidUri();
        }
    }

    private static IllegalArgumentException invalidUri() {
        return new IllegalArgumentException("Invalid OAuth MCP resource URI");
    }
}
