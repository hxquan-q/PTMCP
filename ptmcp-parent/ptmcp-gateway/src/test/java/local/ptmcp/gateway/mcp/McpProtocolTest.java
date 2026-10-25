package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** MCP protocol handler unit tests — no HTTP layer, direct controller invocation. */
class McpProtocolTest {
    private McpToolRegistry registry;
    private McpProtocolController controller;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        registry = McpToolRegistry.fromClasspath(mapper, "mcp-tools.json");
        controller = new McpProtocolController(registry, mapper);
    }

    @Test
    void toolsListReturnsExactlyThreeTools() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\",\"id\":1}";
        var response = controller.handle(mapper.readTree(request), null);
        assertEquals(200, response.getStatusCode().value());
        JsonNode body = mapper.readTree(mapper.writeValueAsString(response.getBody()));
        assertEquals(3, body.at("/result/tools").size());
    }

    @Test
    void initializeReturnsProtocolVersion() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"initialize\",\"id\":2}";
        var response = controller.handle(mapper.readTree(request), null);
        assertEquals(200, response.getStatusCode().value());
        JsonNode body = mapper.readTree(mapper.writeValueAsString(response.getBody()));
        assertEquals("2025-03-26", body.at("/result/protocolVersion").asText());
    }

    @Test
    void unknownToolReturnsError() throws Exception {
        String request =
                "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"unknown\"},\"id\":3}";
        var response = controller.handle(mapper.readTree(request), null);
        JsonNode body = mapper.readTree(mapper.writeValueAsString(response.getBody()));
        assertEquals(-32602, body.at("/error/code").asInt());
    }

    @Test
    void unknownMethodReturnsError() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"method\":\"bad/method\",\"id\":4}";
        var response = controller.handle(mapper.readTree(request), null);
        JsonNode body = mapper.readTree(mapper.writeValueAsString(response.getBody()));
        assertEquals(-32601, body.at("/error/code").asInt());
    }
}
