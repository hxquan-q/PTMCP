package local.ptmcp.admin.session;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import local.ptmcp.common.error.DomainException;

import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * Resolves the opaque ADMIN session cookie into the request identity before controllers run. It
 * permits anonymous requests for downstream endpoint guards, but fails closed when authoritative
 * session or permission state cannot be checked.
 */
public final class SessionAuthFilter implements Filter {
    private final AdminSessionService sessions;
    private final HandlerExceptionResolver exceptions;

    public SessionAuthFilter(AdminSessionService sessions, HandlerExceptionResolver exceptions) {
        this.sessions = sessions;
        this.exceptions = exceptions;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http
                && response instanceof HttpServletResponse httpResponse) {
            try {
                sessions.attachSession(http);
            } catch (DomainException failure) {
                httpResponse.setHeader("Cache-Control", "no-store");
                httpResponse.setHeader("Pragma", "no-cache");
                httpResponse.setHeader("X-Content-Type-Options", "nosniff");
                if (exceptions.resolveException(http, httpResponse, null, failure) == null) {
                    throw failure;
                }
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
