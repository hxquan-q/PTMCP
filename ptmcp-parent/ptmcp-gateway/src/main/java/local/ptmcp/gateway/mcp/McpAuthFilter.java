package local.ptmcp.gateway.mcp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Objects;

/**
 * Per-request authentication for the MCP endpoint. Every request must independently present a valid
 * Bearer token; there is no session, no cookie and no Mcp-Session-Id reliance. An unauthenticated
 * request receives 401 with a {@code WWW-Authenticate} header pointing to the protected-resource
 * metadata discovery location (RFC 9728). Authentication failure always returns before tool
 * execution.
 */
public final class McpAuthFilter implements Filter {
    static final String BEARER_PREFIX = "Bearer ";
    static final String WWW_AUTHENTICATE =
            "Bearer realm=\"ptmcp\", resource_metadata=\"/.well-known/oauth-protected-resource\"";

    private final McpTokenValidator validator;

    @FunctionalInterface
    public interface McpTokenValidator {
        /** Returns the principal identity if the token is valid, null otherwise. */
        String validate(String token);
    }

    public McpAuthFilter(McpTokenValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator");
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        if (!(req instanceof HttpServletRequest http)
                || !(res instanceof HttpServletResponse httpRes)) {
            chain.doFilter(req, res);
            return;
        }
        String path = http.getRequestURI();
        if (!path.equals("/mcp")) {
            chain.doFilter(req, res);
            return;
        }
        String authHeader = http.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            unauthorized(httpRes);
            return;
        }
        String token = authHeader.substring(BEARER_PREFIX.length());
        String principal = validator.validate(token);
        if (principal == null) {
            unauthorized(httpRes);
            return;
        }
        http.setAttribute("ptmcp.mcp.principal", principal);
        chain.doFilter(req, res);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setHeader("WWW-Authenticate", WWW_AUTHENTICATE);
        response.setContentType("application/json");
        response.getWriter()
                .write(
                        "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32001,"
                                + "\"message\":\"Unauthorized\"},\"id\":null}");
    }
}
