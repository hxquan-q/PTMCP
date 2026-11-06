package local.ptmcp.gateway.mcp;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JSON-RPC envelopes and MCP result payloads for {@link McpToolService}; no SQL, credential or
 * driver text.
 */
final class McpToolResults {
    private static final int LIST_TTL_MS = 300_000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private McpToolResults() {}

    static void requireWithinBudget(String body) {
        if (body.getBytes(StandardCharsets.UTF_8).length > McpToolService.RESPONSE_BUDGET_BYTES) {
            throw new DomainException(ErrorCode.RESULT_METADATA_TOO_LARGE);
        }
    }

    static Map<String, Object> successContent(UUID requestId, Map<String, Object> data) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("requestId", requestId.toString());
        structured.put("status", "success");
        structured.put("data", data);
        return structured;
    }

    static Map<String, Object> errorContent(
            UUID requestId, ErrorCode code, McpToolService.Stage stage) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", McpErrorCatalog.message(code));
        error.put("retryable", McpErrorCatalog.retryable(code));
        error.put("details", Map.of());
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("requestId", requestId.toString());
        structured.put(
                "status",
                stage == McpToolService.Stage.GUARD || code == ErrorCode.TABLE_NOT_AUTHORIZED
                        ? "denied"
                        : "error");
        structured.put("error", error);
        return structured;
    }

    static Map<String, Object> callToolResult(boolean isError, Map<String, Object> structured) {
        String text = JSON.writeValueAsString(structured);
        return Map.of(
                "isError", isError,
                "content", List.of(Map.of("type", "text", "text", text)),
                "structuredContent", structured);
    }

    static Map<String, Object> initializeResult(JsonNode params) {
        String requested = params.path("protocolVersion").asText(McpToolService.PROTOCOL_VERSION);
        String version =
                McpToolService.SUPPORTED_PROTOCOL_VERSIONS.contains(requested)
                        ? requested
                        : McpToolService.PROTOCOL_VERSION;
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "ptmcp-gateway");
        serverInfo.put("version", "0.1.0");
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", version);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    /** Wraps a list result with the MCP 2026-07-28 cacheable-result hints. */
    static Map<String, Object> cacheableList(Map<String, Object> inner) {
        Map<String, Object> result = new LinkedHashMap<>(inner);
        result.put("resultType", "complete");
        result.put("ttlMs", LIST_TTL_MS);
        result.put("cacheScope", "private");
        return result;
    }

    static Map<String, Object> discoverResult() {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "ptmcp-gateway");
        serverInfo.put("version", "0.1.0");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("supportedVersions", List.copyOf(McpToolService.SUPPORTED_PROTOCOL_VERSIONS));
        result.put("capabilities", capabilities);
        result.put("io.modelcontextprotocol/serverInfo", serverInfo);
        return result;
    }

    static Map<String, Object> listResult() {
        return Map.of("tools", McpToolCatalog.toolDescriptors());
    }

    static String envelope(JsonNode id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return JSON.writeValueAsString(response);
    }

    static String rpcError(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return JSON.writeValueAsString(response);
    }
}
