package local.ptmcp.gateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;

/**
 * One registered MCP tool definition loaded from {@code mcp-tools.json}. The gateway only
 * advertises tools listed here; unknown tool names in JSON-RPC requests are rejected before
 * authentication is even attempted.
 */
public record McpToolDefinition(String name, String description, JsonNode inputSchema) {

    public McpToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tool name must not be blank");
        }
    }

    /** Creates a definition from the mcp-tools.json tool entry. */
    public static McpToolDefinition fromNode(JsonNode tool) {
        return new McpToolDefinition(
                tool.get("name").asText(),
                tool.has("description") ? tool.get("description").asText() : "",
                tool.get("inputSchema"));
    }

    /** Validates the arguments map against this tool's JSON Schema (required + types only). */
    public boolean validateArguments(Map<String, Object> arguments) {
        if (arguments == null) {
            return inputSchema.get("required") == null || inputSchema.get("required").isEmpty();
        }
        JsonNode required = inputSchema.get("required");
        if (required != null) {
            for (JsonNode req : required) {
                if (!arguments.containsKey(req.asText())) {
                    return false;
                }
            }
        }
        return true;
    }
}
