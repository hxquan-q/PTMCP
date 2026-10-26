package local.ptmcp.gateway.mcp;

import jakarta.servlet.http.HttpServletRequest;

/** Authentication and authoritative state revalidation, supplied only by server configuration. */
public interface McpAuthentication {
    McpIdentity authenticate(String bearer, HttpServletRequest request);

    McpIdentity revalidate(McpIdentity identity);
}
