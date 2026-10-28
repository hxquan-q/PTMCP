package local.ptmcp.admin.web;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns a server-generated request id and echoes it in {@code X-Request-Id}.
 *
 * <p>An inbound header is never read, so callers cannot choose the id used in logs or error
 * responses.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestIdFilter implements Filter {
    public static final String HEADER = "X-Request-Id";
    private static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, requestId);
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader(HEADER, requestId);
        }
        chain.doFilter(request, response);
    }

    /** Returns the server-generated id, or {@code null} outside this filter chain. */
    public static String requestId(ServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String id ? id : null;
    }
}
