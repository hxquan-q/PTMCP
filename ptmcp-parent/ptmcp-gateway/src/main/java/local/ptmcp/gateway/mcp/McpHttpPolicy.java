package local.ptmcp.gateway.mcp;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** HTTP transport restrictions only; neither an identity source nor a resource authorization. */
@Component
final class McpHttpPolicy {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Set<String> allowedOrigins;

    McpHttpPolicy(@Value("${ptmcp.gateway.allowed-origins:}") String allowedOrigins) {
        this.allowedOrigins =
                allowedOrigins.isBlank()
                        ? Set.of()
                        : Arrays.stream(allowedOrigins.split(",", -1))
                                .map(String::trim)
                                .map(McpHttpPolicy::normalizeOrigin)
                                .collect(Collectors.toUnmodifiableSet());
    }

    boolean allowsOrigin(HttpServletRequest request) {
        var origins = Collections.list(request.getHeaders("Origin"));
        if (origins.isEmpty()) {
            return true;
        }
        if (origins.size() != 1) {
            return false;
        }
        try {
            return allowedOrigins.contains(normalizeOrigin(origins.getFirst()));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    boolean allowsProtocol(HttpServletRequest request, String body) {
        var versions = Collections.list(request.getHeaders("MCP-Protocol-Version"));
        if (!versions.isEmpty()) {
            return versions.size() == 1
                    && McpToolService.SUPPORTED_PROTOCOL_VERSIONS.contains(versions.getFirst());
        }
        // Stateless transport has no negotiated session to recover. The missing-header default
        // 2025-03-26 is unsupported; only the initial negotiation may omit the header.
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException malformed) {
            return true; // Preserve the service's JSON-RPC parse error, without running a tool.
        }
        if (root == null
                || !root.isObject()
                || !"2.0".equals(root.path("jsonrpc").asText(null))
                || !root.path("method").isTextual()
                || root.path("method").asText().isBlank()) {
            return true; // Preserve existing JSON-RPC invalid-request handling.
        }
        JsonNode id = root.path("id");
        if (!id.isMissingNode() && !id.isNull() && !id.isNumber() && !id.isTextual()) {
            return true; // Preserve the service's invalid-request error for malformed identifiers.
        }
        return "initialize".equals(root.path("method").asText());
    }

    private static String normalizeOrigin(String value) {
        if (value == null || value.length() > 2048) {
            throw invalidOrigin();
        }
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw invalidOrigin();
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if ((!"http".equals(scheme) && !"https".equals(scheme))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || uri.getRawAuthority().endsWith(":")) {
            throw invalidOrigin();
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535 || port < -1) {
            throw invalidOrigin();
        }
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static IllegalArgumentException invalidOrigin() {
        return new IllegalArgumentException("MCP origins must be explicit HTTP(S) origins");
    }
}
