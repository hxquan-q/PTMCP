package local.ptmcp.admin.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.web.servlet.HandlerExceptionResolver;

import java.io.IOException;

/**
 * api-spec: normal management request bodies are capped at 1 MiB; a larger declared body fails
 * closed as {@code REQUEST_TOO_LARGE} (413) before any parsing or dispatch happens. The cap is a
 * contract boundary, not a parser limit — undeclared chunked bodies remain bounded by the container
 * and the JSON reader's own limits. Domain failures resolve through the shared advice so the wire
 * shape matches controller errors exactly.
 */
public final class AdminRequestSizeFilter implements jakarta.servlet.Filter {
    static final long MAX_BODY_BYTES = 1024L * 1024L;

    private final HandlerExceptionResolver exceptions;

    public AdminRequestSizeFilter(HandlerExceptionResolver exceptions) {
        this.exceptions = exceptions;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http
                && response instanceof HttpServletResponse httpResponse
                && http.getContentLengthLong() > MAX_BODY_BYTES) {
            DomainException failure = new DomainException(ErrorCode.REQUEST_TOO_LARGE);
            httpResponse.setHeader("Cache-Control", "no-store");
            httpResponse.setHeader("Pragma", "no-cache");
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");
            if (exceptions.resolveException(http, httpResponse, null, failure) == null) {
                throw failure;
            }
            return;
        }
        chain.doFilter(request, response);
    }
}
