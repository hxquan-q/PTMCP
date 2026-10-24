package local.ptmcp.admin.api;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.io.IOException;

/**
 * Test-profile identity shim only: resolves the acting admin from fixed headers. It is not a login
 * mechanism and never accepts session credentials; the /session slice replaces it.
 */
public final class TestPrincipalFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (request instanceof HttpServletRequest http) {
            String userId = http.getHeader("X-Test-User-Id");
            if (userId != null && !userId.isBlank()) {
                long parsed;
                try {
                    parsed = Long.parseLong(userId.trim());
                } catch (NumberFormatException failure) {
                    throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                }
                boolean platformAdmin =
                        "true".equalsIgnoreCase(http.getHeader("X-Test-Platform-Admin"));
                request =
                        new HttpServletRequestWrapper(http) {
                            @Override
                            public Object getAttribute(String name) {
                                return AdminPrincipal.ATTRIBUTE.equals(name)
                                        ? new AdminPrincipal(parsed, platformAdmin)
                                        : super.getAttribute(name);
                            }
                        };
            }
        }
        chain.doFilter(request, response);
    }
}
