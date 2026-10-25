package local.ptmcp.gateway.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MCP gateway wiring; enabled only when ptmcp.gateway.mcp.enabled=true. */
@Configuration
@ConditionalOnProperty("ptmcp.gateway.mcp.enabled")
public class McpGatewayConfiguration {

    @Bean
    public McpToolRegistry mcpToolRegistry(ObjectMapper mapper) {
        return McpToolRegistry.fromClasspath(mapper, "mcp-tools.json");
    }

    @Bean
    public FilterRegistrationBean<McpAuthFilter> mcpAuthFilter() {
        // The token validator is a stub in this slice: T19-b wires the real Bearer/PAT
        // verification against the authz module. The filter structure is complete.
        return new FilterRegistrationBean<>(
                new McpAuthFilter(
                        token -> token.equals("valid-test-token") ? "test-principal" : null));
    }
}
