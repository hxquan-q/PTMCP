package local.ptmcp.gateway.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Loads the three business tools from the mcp-tools.json contract. The gateway only advertises and
 * dispatches these tools; an unknown tool name is rejected before authentication.
 */
public final class McpToolRegistry {
    private final Map<String, McpToolDefinition> tools;

    public McpToolRegistry(JsonNode toolsArray) {
        Objects.requireNonNull(toolsArray, "toolsArray");
        if (!toolsArray.isArray() || toolsArray.isEmpty()) {
            throw new IllegalArgumentException("tools array must not be empty");
        }
        this.tools =
                Objects.requireNonNull(
                        toolsArray
                                .valueStream()
                                .map(McpToolDefinition::fromNode)
                                .collect(
                                        Collectors.toMap(
                                                McpToolDefinition::name, Function.identity())),
                        "tools");
    }

    public static McpToolRegistry fromClasspath(ObjectMapper mapper, String classpathLocation) {
        try (var stream =
                McpToolRegistry.class.getClassLoader().getResourceAsStream(classpathLocation)) {
            if (stream == null) {
                throw new IllegalStateException("mcp-tools.json not found at " + classpathLocation);
            }
            JsonNode root = mapper.readTree(stream);
            return new McpToolRegistry(root.get("tools"));
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to load MCP tool registry", failure);
        }
    }

    public McpToolDefinition get(String toolName) {
        return tools.get(toolName);
    }

    public boolean contains(String toolName) {
        return tools.containsKey(toolName);
    }

    public List<McpToolDefinition> all() {
        return List.copyOf(tools.values());
    }

    public int size() {
        return tools.size();
    }
}
