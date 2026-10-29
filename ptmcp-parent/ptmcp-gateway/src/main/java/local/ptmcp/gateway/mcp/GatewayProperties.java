package local.ptmcp.gateway.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Deployment-supplied gateway configuration. Every value comes from server configuration; none is
 * derived from request input, and the tool path never accepts overrides from headers or tool
 * arguments.
 *
 * @param enabled false keeps the /mcp boundary mounted but every tool call fails closed
 * @param metadataUrl primary metadata JDBC URL
 * @param walKey base64 AES-256 key for local terminal WAL frames
 */
@ConfigurationProperties(prefix = "ptmcp.gateway")
public record GatewayProperties(
        boolean enabled,
        String metadataUrl,
        String metadataUsername,
        String metadataPassword,
        String credentialKey,
        String allowedCidrs,
        String allowedPorts,
        String truststorePath,
        String truststorePassword,
        String pgSslRootCert,
        String walDirectory,
        String walKey,
        String auditKey,
        String issuer,
        String resource,
        String jwksUri,
        String resourceMetadataUri,
        long walMaxBytes,
        long walMinFreeBytes) {

    public GatewayProperties {
        metadataUrl = orEmpty(metadataUrl);
        metadataUsername = orEmpty(metadataUsername);
        metadataPassword = orEmpty(metadataPassword);
        credentialKey = orEmpty(credentialKey);
        allowedCidrs = orEmpty(allowedCidrs);
        allowedPorts = allowedPorts == null ? "3306,5432" : allowedPorts.trim();
        truststorePath = orEmpty(truststorePath);
        truststorePassword = orEmpty(truststorePassword);
        pgSslRootCert = orEmpty(pgSslRootCert);
        walDirectory = orEmpty(walDirectory);
        walKey = orEmpty(walKey);
        auditKey = orEmpty(auditKey);
        issuer = orEmpty(issuer);
        resource = orEmpty(resource);
        jwksUri = orEmpty(jwksUri);
        resourceMetadataUri = orEmpty(resourceMetadataUri);
        if (walMaxBytes < 0 || walMinFreeBytes < 0) {
            throw new IllegalArgumentException("WAL capacity must be positive");
        }
        walMaxBytes = walMaxBytes == 0 ? 512L * 1024 * 1024 : walMaxBytes;
        walMinFreeBytes = walMinFreeBytes == 0 ? 128L * 1024 * 1024 : walMinFreeBytes;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
