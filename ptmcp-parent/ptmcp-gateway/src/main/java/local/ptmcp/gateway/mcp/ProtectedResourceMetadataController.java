package local.ptmcp.gateway.mcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * RFC 9728 protected-resource metadata discovery. The MCP client fetches this to learn which
 * authorization server to use and what scopes are needed. The authorization_servers field points to
 * the authz module's issuer (T13-a). Not authenticated — discovery is pre-auth.
 */
@RestController
@ConditionalOnProperty("ptmcp.gateway.mcp.enabled")
public class ProtectedResourceMetadataController {
    private final String issuer;

    public ProtectedResourceMetadataController(
            @org.springframework.beans.factory.annotation.Value(
                            "${ptmcp.gateway.mcp.authz-issuer:http://127.0.0.1:8081}")
                    String issuer) {
        this.issuer = issuer;
    }

    @GetMapping(
            value = "/.well-known/oauth-protected-resource",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> metadata() {
        return Map.of(
                "resource", issuer,
                "authorization_servers", List.of(issuer),
                "scopes_supported", List.of("mcp:catalog", "mcp:query"),
                "bearer_methods_supported", List.of("header"));
    }
}
