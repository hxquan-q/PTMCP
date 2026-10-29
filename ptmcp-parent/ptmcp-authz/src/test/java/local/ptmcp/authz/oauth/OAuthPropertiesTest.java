package local.ptmcp.authz.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OAuthPropertiesTest {
    @ParameterizedTest
    @ValueSource(
            strings = {
                "ptmcp-mcp",
                "https://gateway.example/mcp?secret=value",
                "https://gateway.example/mcp#fragment",
                "http://gateway.example/mcp",
                "https://user:password@gateway.example/mcp",
                "https://gateway.example/other",
                "https://gateway.example:0/mcp",
                "https://gateway.example:65536/mcp"
            })
    void enabledOAuthRejectsNonCanonicalAudienceWithoutEchoingConfiguration(String audience) {
        var error = assertThrows(IllegalArgumentException.class, () -> properties(audience));
        assertEquals("Invalid OAuth MCP resource URI", error.getMessage());
        assertEquals(null, error.getCause());
    }

    @Test
    void acceptsHttpsAndLoopbackMcpResources() {
        for (String resource :
                new String[] {
                    "https://gateway.example/mcp",
                    "http://127.0.0.1:8082/mcp",
                    "http://localhost:8082/mcp",
                    "http://[::1]:8082/mcp"
                }) {
            assertEquals(resource, properties(resource).audience());
        }
    }

    private static OAuthProperties properties(String audience) {
        return new OAuthProperties(
                true, "https://auth.example", audience, "test-key.pem", null, null, null, null);
    }
}
