package local.ptmcp.gateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MCP Streamable HTTP STATELESS endpoint (api-spec §6). Every request is independently
 * authenticated by {@link McpAuthFilter}; there is no session, no cookie and no Mcp-Session-Id. The
 * gateway only advertises the three tools from mcp-tools.json and never executes SQL — actual
 * execution is wired by T19-b. Client compatibility is NOT_RUN.
 */
@RestController
@ConditionalOnProperty("ptmcp.gateway.mcp.enabled")
public class McpProtocolController {
    private final McpToolRegistry toolRegistry;
    private final ObjectMapper mapper;

    public McpProtocolController(McpToolRegistry toolRegistry, ObjectMapper mapper) {
        this.toolRegistry = toolRegistry;
        this.mapper = mapper;
    }

    @PostMapping(value = "/mcp", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handle(
            @RequestBody JsonNode body, HttpServletRequest request) {
        String method = body.has("method") ? body.get("method").asText() : null;
        Object id = body.has("id") ? body.get("id") : null;

        if ("initialize".equals(method)) {
            return jsonRpc(
                    id,
                    Map.of(
                            "protocolVersion", "2025-03-26",
                            "capabilities", Map.of("tools", Map.of()),
                            "serverInfo",
                                    Map.of(
                                            "name", "ptmcp-gateway",
                                            "version", "0.1.0-design")));
        }
        if ("tools/list".equals(method)) {
            List<Map<String, Object>> toolList =
                    toolRegistry.all().stream()
                            .map(
                                    t ->
                                            Map.<String, Object>of(
                                                    "name", t.name(),
                                                    "description", t.description(),
                                                    "inputSchema", t.inputSchema()))
                            .toList();
            return jsonRpc(id, Map.of("tools", toolList));
        }
        if ("tools/call".equals(method)) {
            JsonNode params = body.get("params");
            String toolName =
                    params != null && params.has("name") ? params.get("name").asText() : null;
            if (toolName == null || !toolRegistry.contains(toolName)) {
                return jsonRpcError(id, -32602, "Unknown tool: " + toolName);
            }
            // T19-b wires actual execution; this skeleton returns a not-implemented response.
            return jsonRpc(
                    id,
                    Map.of(
                            "content",
                            List.of(
                                    Map.of(
                                            "type",
                                            "text",
                                            "text",
                                            "Tool execution requires T19-b wiring")),
                            "isError",
                            true));
        }
        return jsonRpcError(id, -32601, "Method not found: " + method);
    }

    private static ResponseEntity<Map<String, Object>> jsonRpc(
            Object id, Map<String, Object> result) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("jsonrpc", "2.0", "result", result, "id", id == null ? "" : id));
    }

    private static ResponseEntity<Map<String, Object>> jsonRpcError(
            Object id, int code, String message) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "error",
                                Map.of("code", code, "message", message),
                                "id",
                                id == null ? "" : id));
    }
}
