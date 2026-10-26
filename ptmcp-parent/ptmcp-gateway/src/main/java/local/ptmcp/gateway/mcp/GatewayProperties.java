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
        String truststorePath,
        String truststorePassword,
        String walDirectory,
        String walKey,
        String issuer,
        String resource,
        String jwksUri,
        String resourceMetadataUri) {

    public GatewayProperties {
        metadataUrl = orEmpty(metadataUrl);
        metadataUsername = orEmpty(metadataUsername);
        metadataPassword = orEmpty(metadataPassword);
        credentialKey = orEmpty(credentialKey);
        allowedCidrs = orEmpty(allowedCidrs);
        truststorePath = orEmpty(truststorePath);
        truststorePassword = orEmpty(truststorePassword);
        walDirectory = orEmpty(walDirectory);
        walKey = orEmpty(walKey);
        issuer = orEmpty(issuer);
        resource = orEmpty(resource);
        jwksUri = orEmpty(jwksUri);
        resourceMetadataUri = orEmpty(resourceMetadataUri);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
