package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

class McpHttpPolicyTest {
    @Test
    void emptyConfigurationRejectsEveryOriginButNotNonBrowserClients() {
        McpHttpPolicy policy = new McpHttpPolicy("");
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertTrue(policy.allowsOrigin(request));
        request.addHeader("Origin", "http://localhost");
        assertFalse(policy.allowsOrigin(request));
    }

    @Test
    void invalidAllowlistEntriesFailStartupRatherThanBeingIgnored() {
        for (String value :
                List.of(
                        "*",
                        "null",
                        "https://client.invalid/",
                        "https://user@client.invalid",
                        "https://client.invalid?x=1",
                        "https://client.invalid#x",
                        "https://client.invalid:",
                        "https://client.invalid:0",
                        "https://client.invalid:65536",
                        "https://client.invalid,",
                        ",https://client.invalid",
                        "file://client.invalid",
                        "not an origin")) {
            assertThrows(IllegalArgumentException.class, () -> new McpHttpPolicy(value), value);
        }
    }

    @Test
    void allowlistSupportsMultipleExplicitOriginsAndDefaultPorts() {
        McpHttpPolicy policy = new McpHttpPolicy(" http://localhost:80, https://[::1]:8443 ");
        for (String value :
                List.of("http://LOCALHOST", "http://localhost:80", "https://[::1]:8443")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("Origin", value);
            assertTrue(policy.allowsOrigin(request), value);
        }
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Origin", "https://[::1]");
        assertFalse(policy.allowsOrigin(request));
    }

    @Test
    void invalidBodiesRetainJsonRpcErrorHandlingButCannotExcuseInvalidHeaders() {
        McpHttpPolicy policy = new McpHttpPolicy("");
        for (String body :
                List.of(
                        "{",
                        "[]",
                        "null",
                        "{\"jsonrpc\":\"1.0\",\"method\":\"initialize\"}",
                        "{\"jsonrpc\":\"2.0\"}",
                        "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"tools/list\"}",
                        "{\"jsonrpc\":\"2.0\",\"id\":[],\"method\":\"tools/list\"}",
                        "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"tools/list\"}")) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            assertTrue(policy.allowsProtocol(request, body), body);
            request.addHeader("MCP-Protocol-Version", "unsupported");
            assertFalse(policy.allowsProtocol(request, body), body);
        }
    }
}
