package local.ptmcp.admin.session;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * Resolves the opaque ADMIN session cookie into the request identity before controllers run. It
 * never rejects a request: anonymous endpoints stay anonymous and protected endpoints raise the
 * documented 401 through their own guards.
 */
public final class SessionAuthFilter implements Filter {
    private final AdminSessionService sessions;

    public SessionAuthFilter(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            sessions.attachSession(http);
        }
        chain.doFilter(request, response);
    }
}
