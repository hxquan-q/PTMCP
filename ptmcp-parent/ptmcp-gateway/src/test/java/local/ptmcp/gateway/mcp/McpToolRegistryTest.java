package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/** Tool registry: loads exactly the three tools from mcp-tools.json, validates arguments. */
class McpToolRegistryTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void loadsThreeToolsFromClasspath() {
        McpToolRegistry registry = McpToolRegistry.fromClasspath(mapper, "mcp-tools.json");
        assertEquals(3, registry.size());
        assertTrue(registry.contains("list_databases_and_tables"));
        assertTrue(registry.contains("get_table_schema"));
        assertTrue(registry.contains("execute_readonly_sql"));
        assertFalse(registry.contains("unknown_tool"));
        for (var tool : registry.all()) {
            assertNotNull(tool.inputSchema());
            assertNotNull(tool.description());
        }
    }

    @Test
    void validatesRequiredArguments() {
        McpToolRegistry registry = McpToolRegistry.fromClasspath(mapper, "mcp-tools.json");
        var tool = registry.get("list_databases_and_tables");
        assertTrue(tool.validateArguments(java.util.Map.of("datasource_id", "1")));
        assertFalse(tool.validateArguments(java.util.Map.of()));
        assertFalse(tool.validateArguments(null));
    }
}
