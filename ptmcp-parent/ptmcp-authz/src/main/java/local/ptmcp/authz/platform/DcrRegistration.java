package local.ptmcp.authz.platform;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Dynamic client registration for the public tier. Clients cannot register Tier1 trust: any
 * confidential authentication method is treated as a Tier1 attempt and refused, a self-claimed
 * platform binding or internal-client flag is refused, and scopes are capped at the catalog scope.
 * Redirect URIs follow the RFC 8252 rules: exact https origins, or plain http only for the
 * IPv4/IPv6 loopback, never fragments, userinfo, wildcards or parent traversal.
 */
public final class DcrRegistration {
    public enum Tier {
        PUBLIC
    }

    public record DcrRequest(
            List<String> redirectUris,
            String tokenEndpointAuthMethod,
            Set<String> scopes,
            Long claimedPlatformId,
            boolean requestsInternalClient) {}

    public record RegisteredClient(
            String tokenEndpointAuthMethod,
            Set<String> scopes,
            Tier tier,
            List<String> redirectUris) {}

    static final String CATALOG_SCOPE = "mcp:catalog";
    private static final int MAX_REDIRECT_URIS = 5;

    public RegisteredClient register(DcrRequest request) {
        if (request.claimedPlatformId() != null || request.requestsInternalClient()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        List<String> redirectUris =
                request.redirectUris() == null ? List.of() : request.redirectUris();
        if (redirectUris.isEmpty() || redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        redirectUris.forEach(DcrRegistration::validateRedirectUri);
        if (!"none".equals(request.tokenEndpointAuthMethod())) {
            throw new DomainException(ErrorCode.PLATFORM_PROOF_REQUIRED);
        }
        Set<String> requested = request.scopes() == null ? Set.of() : request.scopes();
        if (!Set.of(CATALOG_SCOPE).containsAll(requested)) {
            throw new DomainException(ErrorCode.UNSUPPORTED_FIELD);
        }
        Set<String> effective = requested.isEmpty() ? Set.of(CATALOG_SCOPE) : Set.copyOf(requested);
        return new RegisteredClient("none", effective, Tier.PUBLIC, List.copyOf(redirectUris));
    }

    private static void validateRedirectUri(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (value.indexOf('*') >= 0
                || uri.getScheme() == null
                || uri.getHost() == null
                || uri.getHost().isEmpty()
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        boolean loopbackHttp = "http".equals(scheme) && isLoopbackHost(uri.getHost());
        if (!https && !loopbackHttp) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/") || path.contains("..")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static boolean isLoopbackHost(String host) {
        if ("localhost".equalsIgnoreCase(host)) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length == 4) {
            if (!"127".equals(parts[0])) {
                return false;
            }
            for (int index = 1; index < 4; index++) {
                if (!parts[index].matches("\\d{1,3}")) {
                    return false;
                }
                if (Integer.parseInt(parts[index]) > 255) {
                    return false;
                }
            }
            return true;
        }
        if (host.indexOf(':') >= 0) {
            try {
                java.net.InetAddress address = java.net.InetAddress.getByName(host);
                return address.isLoopbackAddress();
            } catch (java.net.UnknownHostException failure) {
                return false;
            }
        }
        return false;
    }
}
