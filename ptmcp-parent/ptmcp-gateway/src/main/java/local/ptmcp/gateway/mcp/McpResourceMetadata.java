package local.ptmcp.gateway.mcp;

import local.ptmcp.common.oauth.McpResourceUri;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Discovery and the Bearer challenge share a deployment-controlled, never request-derived origin.
 */
@Component
public final class McpResourceMetadata {
    static final String ROOT_PATH = "/.well-known/oauth-protected-resource";
    private final String metadataUri;
    private final Map<String, Object> document;

    public McpResourceMetadata(
            @Value("${ptmcp.gateway.resource-uri:}") String resourceUri,
            @Value("${ptmcp.gateway.resource:}") String resource,
            @Value("${ptmcp.gateway.issuer:}") String issuer,
            @Value("${ptmcp.gateway.resource-metadata-uri:}") String metadataUri) {
        this.metadataUri = metadataUri;
        if (metadataUri.isEmpty()) {
            document = Map.of();
            return;
        }
        String canonicalResource = resourceUri.isBlank() ? resource : resourceUri;
        URI resourceUrl;
        try {
            resourceUrl = McpResourceUri.requireValid(canonicalResource);
        } catch (IllegalArgumentException failure) {
            throw invalidConfiguration();
        }
        URI metadataUrl = trustedUri(metadataUri);
        trustedUri(issuer);
        if (!canonicalResource.equals(resource)
                || !resourceUrl.getScheme().equalsIgnoreCase(metadataUrl.getScheme())
                || !resourceUrl.getHost().equalsIgnoreCase(metadataUrl.getHost())
                || effectivePort(resourceUrl) != effectivePort(metadataUrl)
                || !(ROOT_PATH.equals(metadataUrl.getRawPath())
                        || (ROOT_PATH + "/mcp").equals(metadataUrl.getRawPath()))) {
            throw invalidConfiguration();
        }
        document =
                Map.of(
                        "resource", canonicalResource,
                        "authorization_servers", List.of(issuer),
                        "bearer_methods_supported", List.of("header"));
    }

    Map<String, Object> document() {
        return document;
    }

    String challenge() {
        return "Bearer realm=\"PTMCP\""
                + (document.isEmpty() ? "" : ", resource_metadata=\"" + metadataUri + "\"");
    }

    private static URI trustedUri(String value) {
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
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65535
                    || !value.equals(uri.toASCIIString())
                    || value.chars().anyMatch(c -> c <= 32 || c == 127)) {
                throw invalidConfiguration();
            }
            return uri;
        } catch (IllegalArgumentException failure) {
            // Do not propagate URI parser errors: they may contain embedded credentials.
            throw invalidConfiguration();
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() == -1
                ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80)
                : uri.getPort();
    }

    private static IllegalArgumentException invalidConfiguration() {
        return new IllegalArgumentException("Invalid gateway OAuth discovery configuration");
    }
}
